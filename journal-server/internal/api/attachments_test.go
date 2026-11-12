package api

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/time/rate"

	"github.com/SushantPulipati05/journal-server/internal/storage"
)

// fakeStore is an in-memory bucket. "Uploading" is done by calling put directly.
type fakeStore struct {
	mu      sync.Mutex
	objects map[string]storage.ObjectInfo
}

func newFakeStore() *fakeStore { return &fakeStore{objects: map[string]storage.ObjectInfo{}} }

func (f *fakeStore) put(key string, size int64, contentType string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.objects[key] = storage.ObjectInfo{Size: size, ContentType: contentType}
}

func (f *fakeStore) has(key string) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	_, ok := f.objects[key]
	return ok
}

func (f *fakeStore) PresignPut(_ context.Context, key string, _ time.Duration) (string, error) {
	return "https://r2.test/put/" + key, nil
}

func (f *fakeStore) PresignGet(_ context.Context, key string, _ time.Duration) (string, error) {
	return "https://r2.test/get/" + key, nil
}

func (f *fakeStore) Stat(_ context.Context, key string) (storage.ObjectInfo, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	info, ok := f.objects[key]
	if !ok {
		return storage.ObjectInfo{}, storage.ErrNotFound
	}
	return info, nil
}

func (f *fakeStore) Delete(_ context.Context, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.objects, key)
	return nil
}

func newPhotoTestHandler(store storage.Store) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, false)
	if store != nil {
		srv.WithStorage(store)
	}
	srv.authLimiter = newIPLimiter(rate.Inf, 1_000_000, false)
	return srv.Routes()
}

func photoBody(id, entryID uuid.UUID) string {
	return `{"id":"` + id.String() + `","entry_id":"` + entryID.String() +
		`","content_type":"image/jpeg","size_bytes":200000,"width":1600,"height":1200,"position":0}`
}

// waitFor polls until cond is true (file removal happens in the background).
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for: %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func TestPhotoUploadFlow(t *testing.T) {
	store := newFakeStore()
	h := newPhotoTestHandler(store)
	user := createUser(t)
	photo, entry := uuid.New(), uuid.New()

	// 1. Announce the photo, get an upload link.
	rec := do(t, h, "POST", "/attachments", user, photoBody(photo, entry))
	expectStatus(t, rec, http.StatusCreated)
	created := decode[createAttachmentResponse](t, rec)
	if created.Uploaded || !strings.Contains(created.UploadURL, photo.String()) {
		t.Fatalf("expected an upload link, got %+v", created)
	}

	// Not uploaded yet: completing fails, and other devices don't see it.
	expectStatus(t, do(t, h, "POST", "/attachments/"+photo.String()+"/complete", user, ""), http.StatusConflict)
	if pulled := decode[attachmentPullResponse](t, do(t, h, "GET", "/attachments/pull?since=0", user, "")); len(pulled.Attachments) != 0 {
		t.Fatalf("unfinished photo leaked into pull: %+v", pulled)
	}

	// 2. The device uploads to storage (simulated), then confirms.
	store.put(attachmentKey(user, photo), 180000, "image/jpeg")
	rec = do(t, h, "POST", "/attachments/"+photo.String()+"/complete", user, "")
	expectStatus(t, rec, http.StatusOK)
	if got := decode[attachmentJSON](t, rec); got.SizeBytes != 180000 {
		t.Errorf("size should come from the real file, got %d", got.SizeBytes)
	}

	// Now it syncs, and can be downloaded.
	pulled := decode[attachmentPullResponse](t, do(t, h, "GET", "/attachments/pull?since=0", user, ""))
	if len(pulled.Attachments) != 1 || pulled.Attachments[0].EntryID != entry {
		t.Fatalf("pull = %+v", pulled)
	}
	rec = do(t, h, "GET", "/attachments/"+photo.String()+"/url", user, "")
	expectStatus(t, rec, http.StatusOK)

	// Announcing again is harmless (a retry after a lost response).
	rec = do(t, h, "POST", "/attachments", user, photoBody(photo, entry))
	expectStatus(t, rec, http.StatusCreated)
	if again := decode[createAttachmentResponse](t, rec); !again.Uploaded || again.UploadURL != "" {
		t.Errorf("retry should report the finished photo, got %+v", again)
	}

	// 3. Delete: other devices get a tombstone, and the file is removed.
	expectStatus(t, do(t, h, "DELETE", "/attachments/"+photo.String(), user, ""), http.StatusNoContent)
	waitFor(t, "file removed", func() bool { return !store.has(attachmentKey(user, photo)) })
	after := decode[attachmentPullResponse](t, do(t, h, "GET", "/attachments/pull?since="+itoa(pulled.NextCursor), user, ""))
	if len(after.Attachments) != 1 || after.Attachments[0].DeletedAt == nil {
		t.Fatalf("expected a tombstone, got %+v", after)
	}
	expectStatus(t, do(t, h, "GET", "/attachments/"+photo.String()+"/url", user, ""), http.StatusNotFound)
}

func TestPhotosArePrivate(t *testing.T) {
	h := newPhotoTestHandler(newFakeStore())
	owner, stranger := createUser(t), createUser(t)
	photo := uuid.New()
	expectStatus(t, do(t, h, "POST", "/attachments", owner, photoBody(photo, uuid.New())), http.StatusCreated)

	expectStatus(t, do(t, h, "POST", "/attachments", stranger, photoBody(photo, uuid.New())), http.StatusConflict)
	expectStatus(t, do(t, h, "POST", "/attachments/"+photo.String()+"/complete", stranger, ""), http.StatusNotFound)
	expectStatus(t, do(t, h, "GET", "/attachments/"+photo.String()+"/url", stranger, ""), http.StatusNotFound)
}

func TestPhotoValidation(t *testing.T) {
	h := newPhotoTestHandler(newFakeStore())
	user := createUser(t)
	bad := []string{
		`{"id":"` + uuid.NewString() + `","entry_id":"` + uuid.NewString() + `","content_type":"application/pdf","size_bytes":10,"width":1,"height":1}`,
		`{"id":"` + uuid.NewString() + `","entry_id":"` + uuid.NewString() + `","content_type":"image/jpeg","size_bytes":99999999,"width":1,"height":1}`,
		`{"id":"` + uuid.NewString() + `","entry_id":"` + uuid.NewString() + `","content_type":"image/jpeg","size_bytes":10,"width":0,"height":1}`,
	}
	for _, body := range bad {
		expectStatus(t, do(t, h, "POST", "/attachments", user, body), http.StatusUnprocessableEntity)
	}
}

func TestOversizedUploadIsRejectedAndRemoved(t *testing.T) {
	store := newFakeStore()
	h := newPhotoTestHandler(store)
	user := createUser(t)
	photo := uuid.New()
	expectStatus(t, do(t, h, "POST", "/attachments", user, photoBody(photo, uuid.New())), http.StatusCreated)

	store.put(attachmentKey(user, photo), 50<<20, "image/jpeg") // lied about the size
	expectStatus(t, do(t, h, "POST", "/attachments/"+photo.String()+"/complete", user, ""), http.StatusUnprocessableEntity)
	if store.has(attachmentKey(user, photo)) {
		t.Error("the oversized file should have been deleted")
	}
}

func TestPhotoLimitPerEntry(t *testing.T) {
	h := newPhotoTestHandler(newFakeStore())
	user := createUser(t)
	entry := uuid.New()
	for i := 0; i < maxAttachmentsPerEntry; i++ {
		expectStatus(t, do(t, h, "POST", "/attachments", user, photoBody(uuid.New(), entry)), http.StatusCreated)
	}
	expectStatus(t, do(t, h, "POST", "/attachments", user, photoBody(uuid.New(), entry)), http.StatusUnprocessableEntity)
}

func TestDeletingAnEntryDeletesItsPhotos(t *testing.T) {
	store := newFakeStore()
	h := newPhotoTestHandler(store)
	user := createUser(t)
	entry, photo := uuid.New(), uuid.New()
	now := time.Now().UTC().Format(time.RFC3339)

	push := `{"changes":[{"id":"` + entry.String() + `","title":"t","body":"b","created_at":"` + now + `","updated_at":"` + now + `"}]}`
	expectStatus(t, do(t, h, "POST", "/sync/push", user, push), http.StatusOK)
	expectStatus(t, do(t, h, "POST", "/attachments", user, photoBody(photo, entry)), http.StatusCreated)
	store.put(attachmentKey(user, photo), 1000, "image/jpeg")
	expectStatus(t, do(t, h, "POST", "/attachments/"+photo.String()+"/complete", user, ""), http.StatusOK)

	later := time.Now().Add(time.Second).UTC().Format(time.RFC3339)
	tomb := `{"changes":[{"id":"` + entry.String() + `","title":"","body":"","created_at":"` + now + `","updated_at":"` + later + `","deleted_at":"` + later + `"}]}`
	expectStatus(t, do(t, h, "POST", "/sync/push", user, tomb), http.StatusOK)

	waitFor(t, "photo file removed", func() bool { return !store.has(attachmentKey(user, photo)) })
	expectStatus(t, do(t, h, "GET", "/attachments/"+photo.String()+"/url", user, ""), http.StatusNotFound)
}

func TestPhotosNotConfigured(t *testing.T) {
	h := newPhotoTestHandler(nil)
	user := createUser(t)
	expectStatus(t, do(t, h, "POST", "/attachments", user, photoBody(uuid.New(), uuid.New())), http.StatusServiceUnavailable)
	// Syncing still works (there's just nothing to sync).
	expectStatus(t, do(t, h, "GET", "/attachments/pull?since=0", user, ""), http.StatusOK)
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }
