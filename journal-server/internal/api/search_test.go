package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/time/rate"

	"github.com/SushantPulipati05/journal-server/internal/embed"
	"github.com/SushantPulipati05/journal-server/internal/vault"
)

// fakeOllama stands in for the embedding model: each word adds to one of 64
// slots (with a few synonyms mapped together), so texts sharing words or
// synonyms come out close. Good enough to test the plumbing.
func fakeOllama(t *testing.T) *httptest.Server {
	synonyms := map[string]string{"stressed": "anxiety", "worried": "anxiety", "nervous": "anxiety",
		"placements": "interview", "interviews": "interview", "pasta": "food", "dinner": "food"}
	words := regexp.MustCompile(`[a-z]+`)
	vec := func(text string) []float32 {
		if i := strings.LastIndex(text, ": "); i >= 0 {
			text = text[i+2:] // drop the "title: … | text: " / "query: " prompt
		}
		v := make([]float32, 64)
		for _, w := range words.FindAllString(strings.ToLower(text), -1) {
			if s, ok := synonyms[w]; ok {
				w = s
			}
			sum := sha256.Sum256([]byte(w))
			v[sum[0]%64]++
		}
		var n float64
		for _, x := range v {
			n += float64(x * x)
		}
		for i := range v {
			v[i] /= float32(math.Sqrt(n) + 1e-9)
		}
		return v
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Input []string `json:"input"`
		}
		json.NewDecoder(r.Body).Decode(&req)
		out := make([][]float32, len(req.Input))
		for i, in := range req.Input {
			out[i] = vec(in)
		}
		json.NewEncoder(w).Encode(map[string]any{"embeddings": out})
	}))
	t.Cleanup(srv.Close)
	return srv
}

// newSecureServer is a server with encryption at rest and semantic search on.
func newSecureServer(t *testing.T) (*Server, http.Handler) {
	t.Helper()
	master := make([]byte, vault.KeySize)
	rand.Read(master)
	keyring, err := vault.NewKeyring(testPool, master)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, false).
		WithEncryption(keyring).
		WithEmbedder(embed.NewOllama(fakeOllama(t).URL, "test-model"))
	srv.authLimiter = newIPLimiter(rate.Inf, 1_000_000, false)
	return srv, srv.Routes()
}

// drainEmbedJobs runs the worker until nothing is due.
func drainEmbedJobs(t *testing.T, srv *Server) {
	t.Helper()
	for i := 0; i < 100; i++ {
		did, err := srv.embedOne(context.Background())
		if err != nil {
			t.Fatalf("embed: %v", err)
		}
		if !did {
			return
		}
	}
	t.Fatal("embedding jobs never ran out")
}

func rawEntry(t *testing.T, id uuid.UUID) (title, body string) {
	t.Helper()
	err := testPool.QueryRow(context.Background(), "SELECT title, body FROM entries WHERE id = $1", id).Scan(&title, &body)
	if err != nil {
		t.Fatal(err)
	}
	return title, body
}

func TestEntriesAreEncryptedAtRest(t *testing.T) {
	_, h := newSecureServer(t)
	user := createUser(t)
	id := uuid.New()

	c := change(id, "Secret title", time.Minute)
	c.Body = "I never told anyone this."
	expectStatuses(t, push(t, h, user, c), statusApplied)

	title, body := rawEntry(t, id)
	if !vault.IsEncrypted(title) || !vault.IsEncrypted(body) || strings.Contains(body, "anyone") {
		t.Fatalf("stored in plain text: %q / %q", title, body)
	}

	got := pull(t, h, user, 0, 0)
	if len(got.Changes) != 1 || got.Changes[0].Title != "Secret title" || got.Changes[0].Body != "I never told anyone this." {
		t.Fatalf("pull = %+v", got.Changes)
	}

	// The REST endpoints read it back too.
	rec := do(t, h, "GET", "/entries/"+id.String(), user, "")
	expectStatus(t, rec, http.StatusOK)
	if e := decode[entryResponse](t, rec); e.Body != "I never told anyone this." {
		t.Fatalf("get = %+v", e)
	}
}

func TestOldPlainEntriesGetEncrypted(t *testing.T) {
	srv, h := newSecureServer(t)
	user := createUser(t)
	id := uuid.New()
	_, err := testPool.Exec(context.Background(),
		`INSERT INTO entries (id, user_id, title, body, created_at, updated_at) VALUES ($1, $2, 'Old', 'Written before encryption', now(), now())`,
		id, user)
	if err != nil {
		t.Fatal(err)
	}

	// Readable before the backfill (plain text passes through)...
	if got := pull(t, h, user, 0, 0); len(got.Changes) != 1 || got.Changes[0].Body != "Written before encryption" {
		t.Fatalf("pull before = %+v", got.Changes)
	}
	srv.EncryptExistingEntries(context.Background())
	if title, body := rawEntry(t, id); !vault.IsEncrypted(title) || !vault.IsEncrypted(body) {
		t.Fatalf("not encrypted: %q / %q", title, body)
	}
	// ...and after.
	if got := pull(t, h, user, 0, 0); len(got.Changes) != 1 || got.Changes[0].Body != "Written before encryption" {
		t.Fatalf("pull after = %+v", got.Changes)
	}
}

type searchBody struct {
	Results []searchResult `json:"results"`
	Pending int            `json:"pending"`
}

func search(t *testing.T, h http.Handler, user uuid.UUID, q string) searchBody {
	t.Helper()
	rec := do(t, h, "GET", "/search?q="+url.QueryEscape(q), user, "")
	expectStatus(t, rec, http.StatusOK)
	return decode[searchBody](t, rec)
}

func TestSearchFindsByMeaning(t *testing.T) {
	srv, h := newSecureServer(t)
	user := createUser(t)
	stress, food := uuid.New(), uuid.New()

	a := change(stress, "Monday", time.Minute)
	a.Body = "So stressed about placements, interviews next week.\nCould not sleep."
	b := change(food, "Friday", time.Minute)
	b.Body = "Made pasta with friends. Lovely evening."
	push(t, h, user, a, b)

	if got := search(t, h, user, "nervous about interviews"); got.Pending != 2 {
		t.Fatalf("before the worker runs, both entries should be pending: %+v", got)
	}
	drainEmbedJobs(t, srv)

	got := search(t, h, user, "nervous about interviews")
	if got.Pending != 0 || len(got.Results) != 2 || got.Results[0].EntryID != stress {
		t.Fatalf("results = %+v", got)
	}
	if !strings.Contains(got.Results[0].Snippet, "placements") {
		t.Fatalf("snippet = %q", got.Results[0].Snippet)
	}
	if got := search(t, h, user, "dinner"); got.Results[0].EntryID != food {
		t.Fatalf("results = %+v", got)
	}

	// Another user sees nothing of this.
	if got := search(t, h, createUser(t), "interviews"); len(got.Results) != 0 {
		t.Fatalf("leaked to another user: %+v", got)
	}
}

func TestSearchFollowsEditsAndDeletes(t *testing.T) {
	srv, h := newSecureServer(t)
	user := createUser(t)
	id := uuid.New()

	c := change(id, "Day", 2*time.Minute)
	c.Body = "Made pasta for dinner."
	push(t, h, user, c)
	drainEmbedJobs(t, srv)

	c.Body = "Worried about the interview."
	c.UpdatedAt = time.Now()
	push(t, h, user, c)
	drainEmbedJobs(t, srv)
	if got := search(t, h, user, "nervous"); len(got.Results) != 1 || !strings.Contains(got.Results[0].Snippet, "interview") {
		t.Fatalf("after edit = %+v", got)
	}

	now := time.Now()
	c.UpdatedAt, c.DeletedAt = now.Add(time.Second), &now
	push(t, h, user, c)
	if got := search(t, h, user, "nervous"); len(got.Results) != 0 {
		t.Fatalf("deleted entry still found: %+v", got)
	}
	var n int
	testPool.QueryRow(context.Background(), "SELECT count(*) FROM entry_chunks WHERE entry_id = $1", id).Scan(&n)
	if n != 0 {
		t.Fatalf("%d chunks left after delete", n)
	}
}

func TestSearchValidation(t *testing.T) {
	_, h := newSecureServer(t)
	user := createUser(t)
	expectStatus(t, do(t, h, "GET", "/search?q=", user, ""), http.StatusBadRequest)
	expectStatus(t, do(t, h, "GET", "/search?q="+strings.Repeat("a", 501), user, ""), http.StatusBadRequest)
	expectStatus(t, do(t, h, "GET", "/search?q=x", uuid.Nil, ""), http.StatusUnauthorized)

	// Search switched off: a clear 503, not a crash.
	expectStatus(t, do(t, newTestHandler(), "GET", "/search?q=x", user, ""), http.StatusServiceUnavailable)
}

func TestDeletingAccountDeletesKey(t *testing.T) {
	_, h := newSecureServer(t)
	user := createUser(t)
	push(t, h, user, change(uuid.New(), "x", time.Minute))
	var keys int
	testPool.QueryRow(context.Background(), "SELECT count(*) FROM user_keys WHERE user_id = $1", user).Scan(&keys)
	if keys != 1 {
		t.Fatalf("want a key, got %d", keys)
	}
	expectStatus(t, do(t, h, "DELETE", "/me", user, ""), http.StatusNoContent)
	testPool.QueryRow(context.Background(), "SELECT count(*) FROM user_keys WHERE user_id = $1", user).Scan(&keys)
	if keys != 0 {
		t.Fatal("key survived account deletion")
	}
}
