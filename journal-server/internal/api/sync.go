package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/SushantPulipati05/journal-server/internal/db"
)

const (
	maxPushChanges = 100
	maxPullLimit   = 500

	// A phone with a wrong clock must not be able to stamp an entry 10 years
	// in the future, because then no honest edit could ever win again.
	maxClockSkew = 5 * time.Minute
)

// syncChange is one entry as a device sees it. A deleted entry (a tombstone)
// has DeletedAt set. Tombstones are how a deletion reaches your other devices.
type syncChange struct {
	ID        uuid.UUID  `json:"id"`
	Title     string     `json:"title"`
	Body      string     `json:"body"`
	CreatedAt time.Time  `json:"created_at"`
	UpdatedAt time.Time  `json:"updated_at"`
	DeletedAt *time.Time `json:"deleted_at"`
}

// pullChange is a syncChange plus the server's version number for it.
type pullChange struct {
	syncChange
	Version int64 `json:"version"`
}

type pushRequest struct {
	Changes []syncChange `json:"changes"`
}

// Push result statuses.
const (
	statusApplied  = "applied"  // the server took this change
	statusStale    = "stale"    // the server already has this version or a newer one
	statusRejected = "rejected" // the id belongs to someone else
	statusInvalid  = "invalid"  // the change itself is malformed
)

type pushResult struct {
	ID      uuid.UUID `json:"id"`
	Status  string    `json:"status"`
	Version int64     `json:"version,omitempty"`
	Error   string    `json:"error,omitempty"`
}

type pushResponse struct {
	Results []pushResult `json:"results"`
}

// POST /sync/push — a device uploads the changes it made while offline.
//
// Conflict rule: last write wins, judged by updated_at. A change older than
// (or equal to) what the server holds is "stale" and ignored; the device
// learns the winning version on its next pull.
func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	userID := userIDFrom(r.Context())

	var req pushRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if len(req.Changes) > maxPushChanges {
		writeError(w, http.StatusUnprocessableEntity,
			"too many changes in one push (max "+strconv.Itoa(maxPushChanges)+")")
		return
	}

	ctx := r.Context()
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	defer tx.Rollback(ctx) // no-op after a successful Commit

	// Serialize pushes per user. Version numbers come from one global
	// sequence, and a pull trusts "everything up to version N has arrived".
	// If two pushes by the same user ran at once, a higher version could
	// commit before a lower one and the lower one would be skipped forever.
	// The lock is per user and released automatically at commit/rollback.
	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))", userID.String()); err != nil {
		s.serverError(w, r, err)
		return
	}

	q := s.queries.WithTx(tx)
	now := time.Now()
	results := make([]pushResult, 0, len(req.Changes))
	var removedPhotos []uuid.UUID
	for _, c := range req.Changes {
		res, err := applyChange(ctx, q, userID, c, now)
		if err != nil {
			var pgErr *pgconn.PgError
			if errors.As(err, &pgErr) && pgErr.Code == "23503" {
				// The account was deleted while we were working.
				writeError(w, http.StatusUnauthorized, "account no longer exists")
				return
			}
			s.serverError(w, r, err)
			return
		}
		// An entry that was just deleted takes its photos with it.
		if res.Status == statusApplied && c.DeletedAt != nil {
			ids, err := q.DeleteEntryAttachments(ctx, db.DeleteEntryAttachmentsParams{EntryID: c.ID, UserID: userID})
			if err != nil {
				s.serverError(w, r, err)
				return
			}
			removedPhotos = append(removedPhotos, ids...)
		}
		results = append(results, res)
	}

	if err := tx.Commit(ctx); err != nil {
		s.serverError(w, r, err)
		return
	}
	s.removeFiles(userID, removedPhotos)
	writeJSON(w, http.StatusOK, pushResponse{Results: results})
}

// applyChange validates one change and tries to store it. A returned error is
// a real failure (database down); a bad change is reported in the result.
func applyChange(ctx context.Context, q *db.Queries, userID uuid.UUID, c syncChange, now time.Time) (pushResult, error) {
	res := pushResult{ID: c.ID}
	invalid := func(msg string) (pushResult, error) {
		res.Status, res.Error = statusInvalid, msg
		return res, nil
	}

	if c.ID == uuid.Nil {
		return invalid("id is required")
	}
	if c.CreatedAt.IsZero() || c.UpdatedAt.IsZero() {
		return invalid("created_at and updated_at are required")
	}

	if c.DeletedAt != nil {
		// A deleted entry keeps no content. "Delete" has to mean delete.
		c.Title, c.Body = "", ""
	} else if msg := validateEntry(c.Title, c.Body); msg != "" {
		return invalid(msg)
	}

	if c.UpdatedAt.After(now.Add(maxClockSkew)) {
		c.UpdatedAt = now
	}
	if c.CreatedAt.After(c.UpdatedAt) {
		c.CreatedAt = c.UpdatedAt
	}

	entry, err := q.UpsertEntry(ctx, db.UpsertEntryParams{
		ID:        c.ID,
		UserID:    userID,
		Title:     c.Title,
		Body:      c.Body,
		CreatedAt: c.CreatedAt,
		UpdatedAt: c.UpdatedAt,
		DeletedAt: c.DeletedAt,
	})
	switch {
	case err == nil:
		res.Status, res.Version = statusApplied, entry.Version
		return res, nil

	case errors.Is(err, pgx.ErrNoRows):
		// Nothing was written. Either the server's copy is as new or newer
		// (stale), or the id belongs to another account (rejected).
		_, err := q.GetEntryIncludingDeleted(ctx, db.GetEntryIncludingDeletedParams{ID: c.ID, UserID: userID})
		switch {
		case err == nil:
			res.Status = statusStale
			return res, nil
		case errors.Is(err, pgx.ErrNoRows):
			res.Status, res.Error = statusRejected, "id already in use"
			return res, nil
		default:
			return res, err
		}

	default:
		return res, err
	}
}

type pullResponse struct {
	Changes    []pullChange `json:"changes"`
	NextCursor int64        `json:"next_cursor"`
	HasMore    bool         `json:"has_more"`
}

// GET /sync/pull?since=<cursor>&limit=<n> — a device downloads what changed.
//
// The cursor is the highest version the device has seen (start at 0). Send
// back next_cursor next time. While has_more is true, pull again right away.
func (s *Server) handlePull(w http.ResponseWriter, r *http.Request) {
	since := int64(0)
	if raw := r.URL.Query().Get("since"); raw != "" {
		n, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || n < 0 {
			writeError(w, http.StatusBadRequest, "since must be a non-negative number")
			return
		}
		since = n
	}
	limit, err := queryInt(r, "limit", 100, 1, maxPullLimit)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	// Ask for one extra row, just to learn whether another page exists.
	rows, err := s.queries.ListEntryChanges(r.Context(), db.ListEntryChangesParams{
		UserID:   userIDFrom(r.Context()),
		Since:    since,
		PageSize: int32(limit + 1),
	})
	if err != nil {
		s.serverError(w, r, err)
		return
	}

	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}

	resp := pullResponse{Changes: make([]pullChange, 0, len(rows)), NextCursor: since, HasMore: hasMore}
	for _, e := range rows {
		c := pullChange{
			syncChange: syncChange{
				ID:        e.ID,
				Title:     e.Title,
				Body:      e.Body,
				CreatedAt: e.CreatedAt,
				UpdatedAt: e.UpdatedAt,
				DeletedAt: e.DeletedAt,
			},
			Version: e.Version,
		}
		if e.DeletedAt != nil {
			c.Title, c.Body = "", "" // older tombstones may still hold content
		}
		resp.Changes = append(resp.Changes, c)
		resp.NextCursor = e.Version
	}
	writeJSON(w, http.StatusOK, resp)
}
