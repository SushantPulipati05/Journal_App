package api

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

// ---------- helpers ----------

// change builds a live (not deleted) change updated `age` ago.
func change(id uuid.UUID, title string, age time.Duration) syncChange {
	t := time.Now().Add(-age)
	return syncChange{ID: id, Title: title, Body: "body of " + title, CreatedAt: t, UpdatedAt: t}
}

func push(t *testing.T, h http.Handler, user uuid.UUID, changes ...syncChange) []pushResult {
	t.Helper()
	body, err := json.Marshal(pushRequest{Changes: changes})
	if err != nil {
		t.Fatal(err)
	}
	rec := do(t, h, "POST", "/sync/push", user, string(body))
	expectStatus(t, rec, http.StatusOK)
	return decode[pushResponse](t, rec).Results
}

func pull(t *testing.T, h http.Handler, user uuid.UUID, since int64, limit int) pullResponse {
	t.Helper()
	path := "/sync/pull?since=" + strconv.FormatInt(since, 10)
	if limit > 0 {
		path += "&limit=" + strconv.Itoa(limit)
	}
	rec := do(t, h, "GET", path, user, "")
	expectStatus(t, rec, http.StatusOK)
	return decode[pullResponse](t, rec)
}

func expectStatuses(t *testing.T, results []pushResult, want ...string) {
	t.Helper()
	if len(results) != len(want) {
		t.Fatalf("got %d results, want %d: %+v", len(results), len(want), results)
	}
	for i, w := range want {
		if results[i].Status != w {
			t.Errorf("result %d: status = %q, want %q (%+v)", i, results[i].Status, w, results[i])
		}
	}
}

// ---------- tests ----------

func TestPushThenPull(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	a, b, c := uuid.New(), uuid.New(), uuid.New()

	results := push(t, h, user,
		change(a, "first", 3*time.Hour),
		change(b, "second", 2*time.Hour),
		change(c, "third", time.Hour),
	)
	expectStatuses(t, results, statusApplied, statusApplied, statusApplied)
	if !(results[0].Version < results[1].Version && results[1].Version < results[2].Version) {
		t.Errorf("versions should increase: %+v", results)
	}

	got := pull(t, h, user, 0, 0)
	if len(got.Changes) != 3 || got.HasMore {
		t.Fatalf("pull = %+v", got)
	}
	for i, id := range []uuid.UUID{a, b, c} {
		if got.Changes[i].ID != id {
			t.Errorf("change %d is %s, want %s", i, got.Changes[i].ID, id)
		}
	}
	if got.NextCursor != results[2].Version {
		t.Errorf("next_cursor = %d, want %d", got.NextCursor, results[2].Version)
	}

	// Pulling again from the cursor finds nothing, and the cursor stays put.
	again := pull(t, h, user, got.NextCursor, 0)
	if len(again.Changes) != 0 || again.NextCursor != got.NextCursor {
		t.Errorf("second pull = %+v", again)
	}
	if again.Changes == nil {
		t.Error("changes must be [] not null, or clients crash")
	}
}

func TestPullPaging(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	for i := 0; i < 5; i++ {
		push(t, h, user, change(uuid.New(), "entry "+strconv.Itoa(i), time.Hour))
	}

	var seen, cursor int64
	pages := 0
	for {
		page := pull(t, h, user, cursor, 2)
		pages++
		seen += int64(len(page.Changes))
		cursor = page.NextCursor
		if !page.HasMore {
			break
		}
		if pages > 10 {
			t.Fatal("paging never finished")
		}
	}
	if seen != 5 || pages != 3 {
		t.Errorf("saw %d entries over %d pages, want 5 over 3", seen, pages)
	}
}

func TestPushLastWriteWins(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	id := uuid.New()

	first := push(t, h, user, change(id, "v1", 2*time.Hour))
	expectStatuses(t, first, statusApplied)

	// An OLDER edit (made offline earlier) loses.
	expectStatuses(t, push(t, h, user, change(id, "older", 3*time.Hour)), statusStale)
	if got := pull(t, h, user, 0, 0); got.Changes[0].Title != "v1" {
		t.Errorf("older edit overwrote the entry: %+v", got.Changes[0])
	}

	// A NEWER edit wins and gets a new, higher version.
	second := push(t, h, user, change(id, "v2", time.Hour))
	expectStatuses(t, second, statusApplied)
	if second[0].Version <= first[0].Version {
		t.Errorf("version did not increase: %d then %d", first[0].Version, second[0].Version)
	}

	// A device that already had v1 only has to download the one change.
	got := pull(t, h, user, first[0].Version, 0)
	if len(got.Changes) != 1 || got.Changes[0].Title != "v2" {
		t.Errorf("pull after v1 = %+v", got)
	}
}

func TestPushIsIdempotent(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	c := change(uuid.New(), "same change", time.Hour)

	first := push(t, h, user, c)
	expectStatuses(t, first, statusApplied)

	// A retry after a lost response must not create a new version.
	expectStatuses(t, push(t, h, user, c), statusStale)
	if got := pull(t, h, user, first[0].Version, 0); len(got.Changes) != 0 {
		t.Errorf("retry created a new change: %+v", got.Changes)
	}
}

func TestPushTombstone(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	id := uuid.New()

	live := push(t, h, user, change(id, "secret thoughts", 2*time.Hour))
	expectStatuses(t, live, statusApplied)

	// The delete carries content on purpose: the server must throw it away.
	del := change(id, "secret thoughts", time.Hour)
	deletedAt := del.UpdatedAt
	del.DeletedAt = &deletedAt
	expectStatuses(t, push(t, h, user, del), statusApplied)

	got := pull(t, h, user, live[0].Version, 0)
	if len(got.Changes) != 1 {
		t.Fatalf("pull = %+v", got)
	}
	tomb := got.Changes[0]
	if tomb.DeletedAt == nil || tomb.Title != "" || tomb.Body != "" {
		t.Errorf("tombstone should have deleted_at and no content: %+v", tomb)
	}

	// The normal API agrees the entry is gone.
	expectStatus(t, do(t, h, "GET", "/entries/"+id.String(), user, ""), http.StatusNotFound)

	// An older offline edit can't resurrect it.
	expectStatuses(t, push(t, h, user, change(id, "zombie", 3*time.Hour)), statusStale)
}

func TestPushCannotTouchOthersEntries(t *testing.T) {
	h := newTestHandler()
	alice, bob := createUser(t), createUser(t)
	id := uuid.New()

	expectStatuses(t, push(t, h, alice, change(id, "alice's entry", 2*time.Hour)), statusApplied)

	// Bob reuses Alice's id with a NEWER timestamp. It must be refused.
	expectStatuses(t, push(t, h, bob, change(id, "bob's takeover", time.Hour)), statusRejected)

	if got := pull(t, h, alice, 0, 0); len(got.Changes) != 1 || got.Changes[0].Title != "alice's entry" {
		t.Errorf("alice's entry was changed: %+v", got.Changes)
	}
	if got := pull(t, h, bob, 0, 0); len(got.Changes) != 0 {
		t.Errorf("bob can see data he doesn't own: %+v", got.Changes)
	}
}

func TestPushBadChangeDoesNotBlockOthers(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	empty := change(uuid.New(), "", time.Hour)
	empty.Body = "  "
	noID := change(uuid.Nil, "no id", time.Hour)
	noDates := syncChange{ID: uuid.New(), Title: "no dates"}
	good := change(uuid.New(), "good one", time.Hour)

	results := push(t, h, user, empty, noID, noDates, good)
	expectStatuses(t, results, statusInvalid, statusInvalid, statusInvalid, statusApplied)
	if results[0].Error == "" {
		t.Error("invalid result should say why")
	}
	if got := pull(t, h, user, 0, 0); len(got.Changes) != 1 {
		t.Errorf("only the good change should be stored: %+v", got.Changes)
	}
}

func TestPushClampsFutureTimestamps(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)
	id := uuid.New()

	// A phone with its clock set a day ahead.
	wrongClock := change(id, "from the future", -24*time.Hour)
	expectStatuses(t, push(t, h, user, wrongClock), statusApplied)

	// An honest edit made a moment later must still be able to win.
	honest := change(id, "honest edit", -time.Second)
	expectStatuses(t, push(t, h, user, honest), statusApplied)

	if got := pull(t, h, user, 0, 0); got.Changes[0].Title != "honest edit" {
		t.Errorf("entry is stuck on the future timestamp: %+v", got.Changes[0])
	}
}

func TestPullSeesEntriesCreatedThroughREST(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	rec := do(t, h, "POST", "/entries", user, `{"title":"made on the web"}`)
	expectStatus(t, rec, http.StatusCreated)
	created := decode[entryResponse](t, rec)

	got := pull(t, h, user, 0, 0)
	if len(got.Changes) != 1 || got.Changes[0].ID != created.ID {
		t.Errorf("pull = %+v", got)
	}
}

func TestSyncUsersAreIsolated(t *testing.T) {
	h := newTestHandler()
	alice, bob := createUser(t), createUser(t)

	push(t, h, alice, change(uuid.New(), "alice 1", time.Hour), change(uuid.New(), "alice 2", time.Hour))
	push(t, h, bob, change(uuid.New(), "bob 1", time.Hour))

	if got := pull(t, h, alice, 0, 0); len(got.Changes) != 2 {
		t.Errorf("alice sees %d changes, want 2", len(got.Changes))
	}
	if got := pull(t, h, bob, 0, 0); len(got.Changes) != 1 {
		t.Errorf("bob sees %d changes, want 1", len(got.Changes))
	}
}

func TestSyncRequiresAuth(t *testing.T) {
	h := newTestHandler()
	expectStatus(t, do(t, h, "POST", "/sync/push", uuid.Nil, `{"changes":[]}`), http.StatusUnauthorized)
	expectStatus(t, do(t, h, "GET", "/sync/pull", uuid.Nil, ""), http.StatusUnauthorized)
}

func TestSyncInputLimits(t *testing.T) {
	h := newTestHandler()
	user := createUser(t)

	// More than 100 changes in one push.
	var sb strings.Builder
	sb.WriteString(`{"changes":[`)
	for i := 0; i < maxPushChanges+1; i++ {
		if i > 0 {
			sb.WriteString(",")
		}
		sb.WriteString(`{}`)
	}
	sb.WriteString(`]}`)
	expectStatus(t, do(t, h, "POST", "/sync/push", user, sb.String()), http.StatusUnprocessableEntity)

	// Typos in field names are an error, not silently ignored.
	expectStatus(t, do(t, h, "POST", "/sync/push", user, `{"chnages":[]}`), http.StatusBadRequest)

	for _, q := range []string{"since=-1", "since=abc", "limit=0", "limit=501", "limit=abc"} {
		expectStatus(t, do(t, h, "GET", "/sync/pull?"+q, user, ""), http.StatusBadRequest)
	}
}
