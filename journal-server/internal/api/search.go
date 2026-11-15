package api

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/SushantPulipati05/journal-server/internal/db"
	"github.com/SushantPulipati05/journal-server/internal/embed"
	"github.com/SushantPulipati05/journal-server/internal/vault"
)

/*
Encryption at rest + semantic search.

Saving:   push → title/body encrypted → stored → embed_jobs row (same transaction)
Worker:   lease a job → decrypt → split into chunks → embed changed chunks → entry_chunks
Search:   embed the question → compare with every chunk of this user → best entries,
          with the matching paragraph decrypted as a snippet
*/

const (
	maxQueryLen       = 500
	defaultSearchSize = 20
	maxSearchSize     = 50
	snippetRunes      = 240

	// A job is leased for this long; if the worker dies, another try comes later.
	embedLease = 5 * time.Minute
	// Embeddings per request to the model: small batches finish well within the timeout.
	embedBatch = 4
)

// ---------- small helpers used by the entry handlers ----------

// decryptEntry returns e with its title and body readable.
func decryptEntry(uc *vault.UserCipher, e db.Entry) (db.Entry, error) {
	id := e.ID.String()
	title, err := uc.Decrypt(e.Title, id, "title")
	if err != nil {
		return e, err
	}
	body, err := uc.Decrypt(e.Body, id, "body")
	if err != nil {
		return e, err
	}
	e.Title, e.Body = title, body
	return e, nil
}

// writeEntry decrypts an entry and sends it.
func (s *Server) writeEntry(w http.ResponseWriter, r *http.Request, status int, uc *vault.UserCipher, e db.Entry) {
	e, err := decryptEntry(uc, e)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, status, toEntryResponse(e))
}

// afterEntryChange queues re-embedding after a save outside the sync
// transaction (the REST endpoints). A failure is only logged: the entry is
// saved, and the next start-up re-queues anything missing.
func (s *Server) afterEntryChange(r *http.Request, userID, entryID uuid.UUID, deleted bool) {
	if err := s.queueEmbedding(r.Context(), s.pool, userID, entryID, deleted); err != nil {
		s.logger.Error("queue embedding", "error", err, "entry", entryID)
		return
	}
	s.wakeEmbedWorker()
}

// queueEmbedding asks the worker to (re-)embed an entry. A deleted entry's
// vectors are removed straight away instead.
func (s *Server) queueEmbedding(ctx context.Context, conn db.DBTX, userID, entryID uuid.UUID, deleted bool) error {
	if deleted {
		if _, err := conn.Exec(ctx, `DELETE FROM entry_chunks WHERE entry_id = $1 AND user_id = $2`, entryID, userID); err != nil {
			return err
		}
		_, err := conn.Exec(ctx, `DELETE FROM embed_jobs WHERE entry_id = $1 AND user_id = $2`, entryID, userID)
		return err
	}
	if s.embedder == nil {
		return nil // search is off; start-up queues everything once it's turned on
	}
	_, err := conn.Exec(ctx, `
		INSERT INTO embed_jobs (entry_id, user_id) VALUES ($1, $2)
		ON CONFLICT (entry_id) DO UPDATE
		SET attempts = 0, run_after = now(), queued_at = now(), last_error = NULL`,
		entryID, userID)
	return err
}

func (s *Server) wakeEmbedWorker() {
	select {
	case s.embedWake <- struct{}{}:
	default: // already awake
	}
}

// ---------- the embedding worker ----------

// RunEmbedWorker embeds queued entries until ctx is cancelled. Run it in its
// own goroutine; it does nothing when search is off.
func (s *Server) RunEmbedWorker(ctx context.Context) {
	if s.embedder == nil {
		return
	}
	if err := s.queueMissingEmbeddings(ctx); err != nil {
		s.logger.Error("queue missing embeddings", "error", err)
	}
	tick := time.NewTicker(5 * time.Second)
	defer tick.Stop()
	for {
		// Work through everything that's due, then wait.
		for ctx.Err() == nil {
			did, err := s.embedOne(ctx)
			if err != nil {
				s.logger.Error("embedding worker", "error", err)
				break
			}
			if !did {
				break
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		case <-s.embedWake:
		}
	}
}

// queueMissingEmbeddings queues every live entry that has no vectors yet
// (old entries, or entries saved while search was off).
func (s *Server) queueMissingEmbeddings(ctx context.Context) error {
	tag, err := s.pool.Exec(ctx, `
		INSERT INTO embed_jobs (entry_id, user_id)
		SELECT e.id, e.user_id FROM entries e
		WHERE e.deleted_at IS NULL
		  AND NOT EXISTS (SELECT 1 FROM entry_chunks c WHERE c.entry_id = e.id AND c.model = $1)
		ON CONFLICT (entry_id) DO NOTHING`, s.embedder.Model())
	if err == nil && tag.RowsAffected() > 0 {
		s.logger.Info("queued entries for embedding", "count", tag.RowsAffected())
	}
	return err
}

// embedOne leases one due job and does it. did = false when nothing was due.
func (s *Server) embedOne(ctx context.Context) (did bool, err error) {
	var entryID, userID uuid.UUID
	var attempts int
	var queuedAt time.Time
	// FOR UPDATE SKIP LOCKED: two workers never take the same job. The lease
	// (run_after in the future) means a crashed worker's job comes back later.
	err = s.pool.QueryRow(ctx, `
		UPDATE embed_jobs SET run_after = now() + $1::int * interval '1 second', attempts = attempts + 1
		WHERE entry_id = (
			SELECT entry_id FROM embed_jobs WHERE run_after <= now()
			ORDER BY run_after LIMIT 1 FOR UPDATE SKIP LOCKED)
		RETURNING entry_id, user_id, attempts, queued_at`,
		int(embedLease.Seconds()),
	).Scan(&entryID, &userID, &attempts, &queuedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}

	if jobErr := s.embedEntry(ctx, userID, entryID); jobErr != nil {
		// Try again later: 1 min, 2, 4, 8 ... at most 6 hours.
		wait := time.Minute << min(attempts-1, 9)
		if wait > 6*time.Hour {
			wait = 6 * time.Hour
		}
		s.logger.Warn("embedding failed, will retry", "entry", entryID, "attempt", attempts, "in", wait, "error", jobErr)
		_, err = s.pool.Exec(ctx, `
			UPDATE embed_jobs SET run_after = now() + $3::int * interval '1 second', last_error = $4
			WHERE entry_id = $1 AND queued_at = $2`,
			entryID, queuedAt, int(wait.Seconds()), truncate(jobErr.Error(), 500))
		return true, err
	}
	// Done. If the entry was saved again meanwhile, queued_at changed and the
	// job stays for another round.
	_, err = s.pool.Exec(ctx, `DELETE FROM embed_jobs WHERE entry_id = $1 AND queued_at = $2`, entryID, queuedAt)
	return true, err
}

// embedEntry brings one entry's vectors up to date.
func (s *Server) embedEntry(ctx context.Context, userID, entryID uuid.UUID) error {
	var title, body string
	var deletedAt *time.Time
	err := s.pool.QueryRow(ctx,
		`SELECT title, body, deleted_at FROM entries WHERE id = $1 AND user_id = $2`, entryID, userID,
	).Scan(&title, &body, &deletedAt)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && deletedAt != nil) {
		_, err = s.pool.Exec(ctx, `DELETE FROM entry_chunks WHERE entry_id = $1`, entryID)
		return err
	}
	if err != nil {
		return err
	}

	uc, err := s.vault.ForUser(ctx, userID)
	if err != nil {
		return err
	}
	id := entryID.String()
	if title, err = uc.Decrypt(title, id, "title"); err != nil {
		return err
	}
	if body, err = uc.Decrypt(body, id, "body"); err != nil {
		return err
	}

	chunks := embed.Chunks(title, body)
	model := s.embedder.Model()
	hashes := make([][]byte, len(chunks))
	for i, c := range chunks {
		hashes[i] = uc.Hash(model + "\x00" + title + "\x00" + c)
	}

	// Which chunks changed since last time?
	existing := map[int]string{}
	rows, err := s.pool.Query(ctx, `SELECT chunk_index, text_hash FROM entry_chunks WHERE entry_id = $1 AND model = $2`, entryID, model)
	if err != nil {
		return err
	}
	for rows.Next() {
		var idx int
		var h []byte
		if err := rows.Scan(&idx, &h); err != nil {
			rows.Close()
			return err
		}
		existing[idx] = string(h)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	var todo []int
	for i := range chunks {
		if existing[i] != string(hashes[i]) {
			todo = append(todo, i)
		}
	}

	// Embed the changed ones, a batch at a time.
	vectors := make(map[int][]float32, len(todo))
	for start := 0; start < len(todo); start += embedBatch {
		end := min(start+embedBatch, len(todo))
		texts := make([]string, 0, end-start)
		for _, i := range todo[start:end] {
			texts = append(texts, chunks[i])
		}
		vecs, err := s.embedder.EmbedDocuments(ctx, title, texts)
		if err != nil {
			return err
		}
		for k, i := range todo[start:end] {
			vectors[i] = vecs[k]
		}
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	// Paragraphs that no longer exist, or vectors of another model.
	if _, err := tx.Exec(ctx, `DELETE FROM entry_chunks WHERE entry_id = $1 AND (chunk_index >= $2 OR model <> $3)`,
		entryID, len(chunks), model); err != nil {
		return err
	}
	for _, i := range todo {
		if _, err := tx.Exec(ctx, `
			INSERT INTO entry_chunks (entry_id, user_id, chunk_index, text_hash, model, embedding)
			VALUES ($1, $2, $3, $4, $5, $6)
			ON CONFLICT (entry_id, chunk_index) DO UPDATE
			SET text_hash = EXCLUDED.text_hash, model = EXCLUDED.model, embedding = EXCLUDED.embedding`,
			entryID, userID, i, hashes[i], model, vectors[i]); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// ---------- encrypting entries saved before encryption was on ----------

// EncryptExistingEntries encrypts old plain-text entries in the background.
// The version isn't bumped: devices already have the same text, nothing to sync.
func (s *Server) EncryptExistingEntries(ctx context.Context) {
	if s.vault == nil {
		return
	}
	total := 0
	for round := 0; round < 10_000 && ctx.Err() == nil; round++ {
		rows, err := s.pool.Query(ctx, `
			SELECT id, user_id, title, body FROM entries
			WHERE (title <> '' AND title NOT LIKE 'enc:%') OR (body <> '' AND body NOT LIKE 'enc:%')
			LIMIT 100`)
		if err != nil {
			s.logger.Error("encrypt existing entries", "error", err)
			return
		}
		type plain struct {
			id, userID  uuid.UUID
			title, body string
		}
		var batch []plain
		for rows.Next() {
			var p plain
			if err := rows.Scan(&p.id, &p.userID, &p.title, &p.body); err != nil {
				rows.Close()
				s.logger.Error("encrypt existing entries", "error", err)
				return
			}
			batch = append(batch, p)
		}
		rows.Close()
		if len(batch) == 0 {
			break
		}
		for _, p := range batch {
			uc, err := s.vault.ForUser(ctx, p.userID)
			if err != nil {
				s.logger.Error("encrypt existing entries", "error", err)
				return
			}
			id := p.id.String()
			title, body := p.title, p.body
			if !vault.IsEncrypted(title) {
				title = uc.Encrypt(title, id, "title")
			}
			if !vault.IsEncrypted(body) {
				body = uc.Encrypt(body, id, "body")
			}
			// Only if nobody saved the entry in the meantime (then it's already encrypted).
			if _, err := s.pool.Exec(ctx,
				`UPDATE entries SET title = $2, body = $3 WHERE id = $1 AND title = $4 AND body = $5`,
				p.id, title, body, p.title, p.body); err != nil {
				s.logger.Error("encrypt existing entries", "error", err)
				return
			}
			total++
		}
	}
	if total > 0 {
		s.logger.Info("encrypted existing entries", "count", total)
	}
}

// ---------- GET /search ----------

type searchResult struct {
	EntryID uuid.UUID `json:"entry_id"`
	Score   float32   `json:"score"`
	Snippet string    `json:"snippet"`
}

type searchResponse struct {
	Results []searchResult `json:"results"`
	// Pending: this user's entries still waiting to be embedded, so the app
	// can say "still preparing search" instead of looking broken.
	Pending int `json:"pending"`
}

// GET /search?q=<question>&limit=<n> — entries whose meaning is closest to q.
func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	if s.embedder == nil {
		writeError(w, http.StatusServiceUnavailable, "search is not available")
		return
	}
	userID := userIDFrom(r.Context())
	if !s.searchLimiter.allow(userID.String()) {
		writeError(w, http.StatusTooManyRequests, "too many searches, please slow down")
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" || utf8.RuneCountInString(q) > maxQueryLen {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("q must be 1 to %d characters", maxQueryLen))
		return
	}
	limit, err := queryInt(r, "limit", defaultSearchSize, 1, maxSearchSize)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	ctx := r.Context()

	question, err := s.embedder.EmbedQuery(ctx, q)
	if err != nil {
		s.logger.Error("search: embed question", "error", err) // never log the question itself
		writeError(w, http.StatusServiceUnavailable, "search is not available right now")
		return
	}

	// Compare with every chunk of this user's live entries. A few thousand
	// vectors take about a millisecond; pgvector is the next step at scale.
	rows, err := s.pool.Query(ctx, `
		SELECT c.entry_id, c.chunk_index, c.embedding
		FROM entry_chunks c JOIN entries e ON e.id = c.entry_id
		WHERE c.user_id = $1 AND c.model = $2 AND e.deleted_at IS NULL`,
		userID, s.embedder.Model())
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	type best struct {
		chunk int
		score float32
	}
	byEntry := map[uuid.UUID]best{}
	for rows.Next() {
		var entryID uuid.UUID
		var idx int
		var vec []float32
		if err := rows.Scan(&entryID, &idx, &vec); err != nil {
			rows.Close()
			s.serverError(w, r, err)
			return
		}
		score := embed.Dot(question, vec)
		if b, ok := byEntry[entryID]; !ok || score > b.score {
			byEntry[entryID] = best{chunk: idx, score: score}
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		s.serverError(w, r, err)
		return
	}

	ids := make([]uuid.UUID, 0, len(byEntry))
	for id := range byEntry {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return byEntry[ids[i]].score > byEntry[ids[j]].score })
	if len(ids) > limit {
		ids = ids[:limit]
	}

	// Cut the matching paragraph out of each entry, as a snippet.
	snippets := map[uuid.UUID]string{}
	if len(ids) > 0 {
		uc, err := s.vault.ForUser(ctx, userID)
		if err != nil {
			s.serverError(w, r, err)
			return
		}
		idStrings := make([]string, len(ids))
		for i, id := range ids {
			idStrings[i] = id.String()
		}
		entryRows, err := s.pool.Query(ctx,
			`SELECT id, title, body FROM entries WHERE user_id = $1 AND id = ANY($2::uuid[])`, userID, idStrings)
		if err != nil {
			s.serverError(w, r, err)
			return
		}
		for entryRows.Next() {
			var id uuid.UUID
			var title, body string
			if err := entryRows.Scan(&id, &title, &body); err != nil {
				entryRows.Close()
				s.serverError(w, r, err)
				return
			}
			key := id.String()
			if title, err = uc.Decrypt(title, key, "title"); err != nil {
				continue
			}
			if body, err = uc.Decrypt(body, key, "body"); err != nil {
				continue
			}
			if chunks := embed.Chunks(title, body); byEntry[id].chunk < len(chunks) {
				snippets[id] = embed.Snippet(chunks[byEntry[id].chunk], snippetRunes)
			}
		}
		entryRows.Close()
		if err := entryRows.Err(); err != nil {
			s.serverError(w, r, err)
			return
		}
	}

	resp := searchResponse{Results: make([]searchResult, 0, len(ids))}
	for _, id := range ids {
		resp.Results = append(resp.Results, searchResult{EntryID: id, Score: byEntry[id].score, Snippet: snippets[id]})
	}
	if err := s.pool.QueryRow(ctx, `SELECT count(*) FROM embed_jobs WHERE user_id = $1`, userID).Scan(&resp.Pending); err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
