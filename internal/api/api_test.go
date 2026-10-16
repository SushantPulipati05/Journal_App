package api

import (
	"context"
	"database/sql"
	"encoding/json"
	"io"
	"log"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgxpool"
	_ "github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/testcontainers/testcontainers-go/modules/postgres"

	"github.com/SushantPulipati05/journal-server/migrations"
)

// testPool is shared by every test in this package.
var testPool *pgxpool.Pool

// TestMain runs once: start a throwaway Postgres, migrate it, run all tests, clean up.
func TestMain(m *testing.M) {
	ctx := context.Background()

	container, err := postgres.Run(ctx, "postgres:17",
		postgres.WithDatabase("journal_test"),
		postgres.WithUsername("test"),
		postgres.WithPassword("test"),
		postgres.BasicWaitStrategies(),
	)
	if err != nil {
		log.Fatalf("start postgres container: %v", err)
	}

	url, err := container.ConnectionString(ctx, "sslmode=disable")
	if err != nil {
		log.Fatalf("connection string: %v", err)
	}

	if err := migrate(ctx, url); err != nil {
		log.Fatalf("migrate: %v", err)
	}

	testPool, err = pgxpool.New(ctx, url)
	if err != nil {
		log.Fatalf("create pool: %v", err)
	}

	code := m.Run() // runs every TestXxx function

	testPool.Close()
	_ = container.Terminate(ctx)
	os.Exit(code)
}

func migrate(ctx context.Context, url string) error {
	db, err := sql.Open("pgx", url)
	if err != nil {
		return err
	}
	defer db.Close()
	goose.SetBaseFS(migrations.FS)
	if err := goose.SetDialect("postgres"); err != nil {
		return err
	}
	return goose.UpContext(ctx, db, ".")
}

// ---------- helpers ----------

func newTestHandler() http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil)) // silence logs in tests
	return NewServer(testPool, logger).Routes()
}

// createUser inserts a fresh user so every test gets its own isolated data.
func createUser(t *testing.T) uuid.UUID {
	t.Helper()
	var id uuid.UUID
	err := testPool.QueryRow(context.Background(),
		"INSERT INTO users (email) VALUES ($1) RETURNING id",
		uuid.NewString()+"@test.local",
	).Scan(&id)
	if err != nil {
		t.Fatalf("create user: %v", err)
	}
	return id
}

// do sends a request through the router and returns the recorded response.
func do(t *testing.T, h http.Handler, method, path string, userID uuid.UUID, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if userID != uuid.Nil {
		req.Header.Set("X-User-ID", userID.String())
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

// decode parses a JSON response body into any type T.
func decode[T any](t *testing.T, rec *httptest.ResponseRecorder) T {
	t.Helper()
	var v T
	if err := json.Unmarshal(rec.Body.Bytes(), &v); err != nil {
		t.Fatalf("decode response %q: %v", rec.Body.String(), err)
	}
	return v
}

func expectStatus(t *testing.T, rec *httptest.ResponseRecorder, want int) {
	t.Helper()
	if rec.Code != want {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, want, rec.Body.String())
	}
}

type listResponse struct {
	Entries []entryResponse `json:"entries"`
}

// ---------- tests ----------

func TestHealth(t *testing.T) {
	rec := do(t, newTestHandler(), "GET", "/healthz", uuid.Nil, "")
	expectStatus(t, rec, http.StatusOK)
}

func TestCreateAndGetEntry(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	rec := do(t, h, "POST", "/entries", user, `{"title":"Hello","body":"First entry"}`)
	expectStatus(t, rec, http.StatusCreated)
	created := decode[entryResponse](t, rec)

	if created.Title != "Hello" || created.Body != "First entry" {
		t.Errorf("created = %+v", created)
	}

	rec = do(t, h, "GET", "/entries/"+created.ID.String(), user, "")
	expectStatus(t, rec, http.StatusOK)
	got := decode[entryResponse](t, rec)
	if got.ID != created.ID {
		t.Errorf("got id %s, want %s", got.ID, created.ID)
	}
}

func TestCreateWithClientID(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	id := uuid.New()

	rec := do(t, h, "POST", "/entries", user, `{"id":"`+id.String()+`","title":"Offline entry"}`)
	expectStatus(t, rec, http.StatusCreated)
	if got := decode[entryResponse](t, rec); got.ID != id {
		t.Errorf("server ignored client id: got %s, want %s", got.ID, id)
	}

	// Sending the same id again must be rejected, not duplicated.
	rec = do(t, h, "POST", "/entries", user, `{"id":"`+id.String()+`","title":"Again"}`)
	expectStatus(t, rec, http.StatusConflict)
}

func TestCreateValidation(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	cases := []struct {
		name string
		body string
		want int
	}{
		{"empty entry", `{"title":"","body":"   "}`, http.StatusUnprocessableEntity},
		{"title too long", `{"title":"` + strings.Repeat("a", 201) + `"}`, http.StatusUnprocessableEntity},
		{"unknown field", `{"titel":"typo"}`, http.StatusBadRequest},
		{"broken JSON", `{"title":`, http.StatusBadRequest},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rec := do(t, h, "POST", "/entries", user, c.body)
			expectStatus(t, rec, c.want)
		})
	}
}

func TestRequiresUser(t *testing.T) {
	rec := do(t, newTestHandler(), "GET", "/entries", uuid.Nil, "")
	expectStatus(t, rec, http.StatusUnauthorized)
}

func TestUsersCannotSeeEachOthersEntries(t *testing.T) {
	h := newTestHandler()
	alice := createUser(t)
	bob := createUser(t)

	rec := do(t, h, "POST", "/entries", alice, `{"title":"Alice's secret"}`)
	expectStatus(t, rec, http.StatusCreated)
	entry := decode[entryResponse](t, rec)
	path := "/entries/" + entry.ID.String()

	expectStatus(t, do(t, h, "GET", path, bob, ""), http.StatusNotFound)
	expectStatus(t, do(t, h, "PUT", path, bob, `{"title":"hacked"}`), http.StatusNotFound)
	expectStatus(t, do(t, h, "DELETE", path, bob, ""), http.StatusNotFound)

	rec = do(t, h, "GET", "/entries", bob, "")
	expectStatus(t, rec, http.StatusOK)
	if n := len(decode[listResponse](t, rec).Entries); n != 0 {
		t.Errorf("bob sees %d entries, want 0", n)
	}
}

func TestUpdateEntryBumpsVersion(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	created := decode[entryResponse](t, do(t, h, "POST", "/entries", user, `{"title":"Old"}`))
	versionBefore := entryVersion(t, created.ID)

	rec := do(t, h, "PUT", "/entries/"+created.ID.String(), user, `{"title":"New","body":"Edited"}`)
	expectStatus(t, rec, http.StatusOK)
	updated := decode[entryResponse](t, rec)

	if updated.Title != "New" {
		t.Errorf("title = %q, want %q", updated.Title, "New")
	}
	if !updated.UpdatedAt.After(created.UpdatedAt) {
		t.Errorf("updated_at did not move forward")
	}
	if v := entryVersion(t, created.ID); v <= versionBefore {
		t.Errorf("version = %d, want > %d", v, versionBefore)
	}
}

func TestSoftDelete(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	created := decode[entryResponse](t, do(t, h, "POST", "/entries", user, `{"title":"Delete me"}`))
	path := "/entries/" + created.ID.String()

	expectStatus(t, do(t, h, "DELETE", path, user, ""), http.StatusNoContent)
	expectStatus(t, do(t, h, "GET", path, user, ""), http.StatusNotFound)
	expectStatus(t, do(t, h, "DELETE", path, user, ""), http.StatusNotFound)

	// The row must still exist as a tombstone for sync.
	var deleted bool
	err := testPool.QueryRow(context.Background(),
		"SELECT deleted_at IS NOT NULL FROM entries WHERE id = $1", created.ID,
	).Scan(&deleted)
	if err != nil {
		t.Fatalf("query tombstone: %v", err)
	}
	if !deleted {
		t.Errorf("entry row should still exist with deleted_at set")
	}
}

func TestListPagination(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	for _, title := range []string{"one", "two", "three"} {
		expectStatus(t, do(t, h, "POST", "/entries", user, `{"title":"`+title+`"}`), http.StatusCreated)
	}

	page1 := decode[listResponse](t, do(t, h, "GET", "/entries?limit=2", user, ""))
	page2 := decode[listResponse](t, do(t, h, "GET", "/entries?limit=2&offset=2", user, ""))

	if len(page1.Entries) != 2 || len(page2.Entries) != 1 {
		t.Fatalf("pages = %d + %d entries, want 2 + 1", len(page1.Entries), len(page2.Entries))
	}
	if page1.Entries[0].Title != "three" {
		t.Errorf("newest first: got %q, want %q", page1.Entries[0].Title, "three")
	}

	expectStatus(t, do(t, h, "GET", "/entries?limit=0", user, ""), http.StatusBadRequest)
	expectStatus(t, do(t, h, "GET", "/entries?limit=abc", user, ""), http.StatusBadRequest)
}

func entryVersion(t *testing.T, id uuid.UUID) int64 {
	t.Helper()
	var v int64
	err := testPool.QueryRow(context.Background(), "SELECT version FROM entries WHERE id = $1", id).Scan(&v)
	if err != nil {
		t.Fatalf("read version: %v", err)
	}
	return v
}