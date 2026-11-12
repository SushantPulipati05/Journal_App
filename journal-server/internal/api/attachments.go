package api

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/SushantPulipati05/journal-server/internal/db"
	"github.com/SushantPulipati05/journal-server/internal/storage"
)

const (
	maxAttachmentBytes    = 10 << 20 // 10 MB per photo (the app compresses well below this)
	maxAttachmentsPerEntry = 12
	maxImageSide          = 10000
	uploadLinkTTL         = 15 * time.Minute
	downloadLinkTTL       = 10 * time.Minute
)

var allowedImageTypes = map[string]bool{
	"image/jpeg": true,
	"image/png":  true,
	"image/webp": true,
}

// attachmentKey is where a photo lives in the bucket. Only the server knows it.
func attachmentKey(userID, id uuid.UUID) string {
	return "u/" + userID.String() + "/" + id.String()
}

type attachmentJSON struct {
	ID          uuid.UUID  `json:"id"`
	EntryID     uuid.UUID  `json:"entry_id"`
	ContentType string     `json:"content_type"`
	SizeBytes   int64      `json:"size_bytes"`
	Width       int32      `json:"width"`
	Height      int32      `json:"height"`
	Position    int32      `json:"position"`
	CreatedAt   time.Time  `json:"created_at"`
	DeletedAt   *time.Time `json:"deleted_at"`
	Version     int64      `json:"version"`
}

func toAttachmentJSON(a db.Attachment) attachmentJSON {
	return attachmentJSON{
		ID: a.ID, EntryID: a.EntryID, ContentType: a.ContentType, SizeBytes: a.SizeBytes,
		Width: a.Width, Height: a.Height, Position: a.Position,
		CreatedAt: a.CreatedAt, DeletedAt: a.DeletedAt, Version: a.Version,
	}
}

// withUserLock runs fn in a transaction holding the same per-user lock as
// /sync/push, so version numbers for one user always commit in order.
func (s *Server) withUserLock(ctx context.Context, userID uuid.UUID, fn func(q *db.Queries) error) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) // no-op after Commit
	if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1::text, 0))", userID.String()); err != nil {
		return err
	}
	if err := fn(s.queries.WithTx(tx)); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (s *Server) requireStorage(w http.ResponseWriter) bool {
	if s.store == nil {
		writeError(w, http.StatusServiceUnavailable, "photo storage is not configured")
		return false
	}
	return true
}

type createAttachmentRequest struct {
	ID          uuid.UUID `json:"id"`
	EntryID     uuid.UUID `json:"entry_id"`
	ContentType string    `json:"content_type"`
	SizeBytes   int64     `json:"size_bytes"`
	Width       int32     `json:"width"`
	Height      int32     `json:"height"`
	Position    int32     `json:"position"`
}

type createAttachmentResponse struct {
	Attachment      attachmentJSON `json:"attachment"`
	Uploaded        bool           `json:"uploaded"`
	UploadURL       string         `json:"upload_url,omitempty"`
	UploadExpiresAt *time.Time     `json:"upload_expires_at,omitempty"`
}

// POST /attachments — announce a photo and get a link to upload it to.
//
// Step 1 of 2. The device then PUTs the file to upload_url (straight to storage, not
// through this server) and calls POST /attachments/{id}/complete.
// Safe to repeat: the same id returns the same photo (and a fresh link).
func (s *Server) handleCreateAttachment(w http.ResponseWriter, r *http.Request) {
	if !s.requireStorage(w) {
		return
	}
	userID := userIDFrom(r.Context())
	var req createAttachmentRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	switch {
	case req.ID == uuid.Nil || req.EntryID == uuid.Nil:
		writeError(w, http.StatusUnprocessableEntity, "id and entry_id are required")
		return
	case !allowedImageTypes[req.ContentType]:
		writeError(w, http.StatusUnprocessableEntity, "only JPEG, PNG and WebP photos are supported")
		return
	case req.SizeBytes <= 0 || req.SizeBytes > maxAttachmentBytes:
		writeError(w, http.StatusUnprocessableEntity, "photo must be at most 10 MB")
		return
	case req.Width <= 0 || req.Height <= 0 || req.Width > maxImageSide || req.Height > maxImageSide:
		writeError(w, http.StatusUnprocessableEntity, "invalid photo size")
		return
	case req.Position < 0 || req.Position > 99:
		writeError(w, http.StatusUnprocessableEntity, "invalid position")
		return
	}

	var att db.Attachment
	errTooMany := errors.New("too many")
	errNotYours := errors.New("not yours")
	err := s.withUserLock(r.Context(), userID, func(q *db.Queries) error {
		n, err := q.CountEntryAttachments(r.Context(), db.CountEntryAttachmentsParams{EntryID: req.EntryID, UserID: userID, ID: req.ID})
		if err != nil {
			return err
		}
		if n >= maxAttachmentsPerEntry {
			return errTooMany
		}
		att, err = q.CreateAttachment(r.Context(), db.CreateAttachmentParams{
			ID: req.ID, UserID: userID, EntryID: req.EntryID, ContentType: req.ContentType,
			SizeBytes: req.SizeBytes, Width: req.Width, Height: req.Height, Position: req.Position,
		})
		if errors.Is(err, pgx.ErrNoRows) {
			return errNotYours
		}
		return err
	})
	switch {
	case errors.Is(err, errTooMany):
		writeError(w, http.StatusUnprocessableEntity, "an entry can have at most "+strconv.Itoa(maxAttachmentsPerEntry)+" photos")
		return
	case errors.Is(err, errNotYours):
		writeError(w, http.StatusConflict, "id already in use")
		return
	case err != nil:
		s.serverError(w, r, err)
		return
	}
	if att.DeletedAt != nil {
		writeError(w, http.StatusGone, "this photo was deleted")
		return
	}

	resp := createAttachmentResponse{Attachment: toAttachmentJSON(att), Uploaded: att.Uploaded}
	if !att.Uploaded {
		link, err := s.store.PresignPut(r.Context(), attachmentKey(userID, att.ID), uploadLinkTTL)
		if err != nil {
			s.serverError(w, r, err)
			return
		}
		expires := time.Now().Add(uploadLinkTTL)
		resp.UploadURL, resp.UploadExpiresAt = link, &expires
	}
	writeJSON(w, http.StatusCreated, resp)
}

// POST /attachments/{id}/complete — step 2: the file is in storage, check it and make
// the photo visible to the user's other devices.
func (s *Server) handleCompleteAttachment(w http.ResponseWriter, r *http.Request) {
	if !s.requireStorage(w) {
		return
	}
	userID := userIDFrom(r.Context())
	id, ok := pathUUID(w, r)
	if !ok {
		return
	}
	att, err := s.queries.GetAttachment(r.Context(), db.GetAttachmentParams{ID: id, UserID: userID})
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "photo not found")
		return
	} else if err != nil {
		s.serverError(w, r, err)
		return
	}
	if att.DeletedAt != nil {
		writeError(w, http.StatusGone, "this photo was deleted")
		return
	}
	if att.Uploaded {
		writeJSON(w, http.StatusOK, toAttachmentJSON(att)) // already done; a retry is fine
		return
	}

	key := attachmentKey(userID, id)
	info, err := s.store.Stat(r.Context(), key)
	if errors.Is(err, storage.ErrNotFound) {
		writeError(w, http.StatusConflict, "the file hasn't been uploaded yet")
		return
	} else if err != nil {
		s.serverError(w, r, err)
		return
	}
	// A presigned upload link can't limit the size, so check what actually arrived.
	if info.Size <= 0 || info.Size > maxAttachmentBytes || !strings.HasPrefix(info.ContentType, "image/") {
		_ = s.store.Delete(r.Context(), key)
		writeError(w, http.StatusUnprocessableEntity, "the uploaded file isn't a photo of at most 10 MB")
		return
	}

	err = s.withUserLock(r.Context(), userID, func(q *db.Queries) error {
		att, err = q.MarkAttachmentUploaded(r.Context(), db.MarkAttachmentUploadedParams{ID: id, UserID: userID, SizeBytes: info.Size})
		return err
	})
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusGone, "this photo was deleted")
		return
	} else if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toAttachmentJSON(att))
}

// GET /attachments/{id}/url — a short-lived link to download the photo.
func (s *Server) handleAttachmentURL(w http.ResponseWriter, r *http.Request) {
	if !s.requireStorage(w) {
		return
	}
	userID := userIDFrom(r.Context())
	id, ok := pathUUID(w, r)
	if !ok {
		return
	}
	att, err := s.queries.GetAttachment(r.Context(), db.GetAttachmentParams{ID: id, UserID: userID})
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && (!att.Uploaded || att.DeletedAt != nil)) {
		writeError(w, http.StatusNotFound, "photo not found")
		return
	} else if err != nil {
		s.serverError(w, r, err)
		return
	}
	link, err := s.store.PresignGet(r.Context(), attachmentKey(userID, id), downloadLinkTTL)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"url": link, "expires_at": time.Now().Add(downloadLinkTTL)})
}

// DELETE /attachments/{id} — remove one photo (from all devices).
func (s *Server) handleDeleteAttachment(w http.ResponseWriter, r *http.Request) {
	userID := userIDFrom(r.Context())
	id, ok := pathUUID(w, r)
	if !ok {
		return
	}
	err := s.withUserLock(r.Context(), userID, func(q *db.Queries) error {
		_, err := q.DeleteAttachment(r.Context(), db.DeleteAttachmentParams{ID: id, UserID: userID})
		if errors.Is(err, pgx.ErrNoRows) {
			return nil // unknown or already deleted: deleting is idempotent
		}
		return err
	})
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	s.removeFiles(userID, []uuid.UUID{id})
	w.WriteHeader(http.StatusNoContent)
}

type attachmentPullResponse struct {
	Attachments []attachmentJSON `json:"attachments"`
	NextCursor  int64            `json:"next_cursor"`
	HasMore     bool             `json:"has_more"`
}

// GET /attachments/pull?since=<cursor>&limit=<n> — like /sync/pull, for photos.
// The device keeps a separate cursor for photos.
func (s *Server) handlePullAttachments(w http.ResponseWriter, r *http.Request) {
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
	rows, err := s.queries.ListAttachmentChanges(r.Context(), db.ListAttachmentChangesParams{
		UserID: userIDFrom(r.Context()), Since: since, PageSize: int32(limit + 1),
	})
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	hasMore := len(rows) > limit
	if hasMore {
		rows = rows[:limit]
	}
	resp := attachmentPullResponse{Attachments: make([]attachmentJSON, 0, len(rows)), NextCursor: since, HasMore: hasMore}
	for _, a := range rows {
		resp.Attachments = append(resp.Attachments, toAttachmentJSON(a))
		resp.NextCursor = a.Version
	}
	writeJSON(w, http.StatusOK, resp)
}

// removeFiles deletes photo files from storage in the background. It runs after the
// database change is committed; if it fails, the file is orphaned but no longer
// reachable by anyone (only the server can make links to it).
func (s *Server) removeFiles(userID uuid.UUID, ids []uuid.UUID) {
	if s.store == nil || len(ids) == 0 {
		return
	}
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		for _, id := range ids {
			if err := s.store.Delete(ctx, attachmentKey(userID, id)); err != nil {
				s.logger.Warn("could not delete photo file", "attachment_id", id, "error", err)
			}
		}
	}()
}

// pathUUID reads the {id} part of the URL.
func pathUUID(w http.ResponseWriter, r *http.Request) (uuid.UUID, bool) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid id")
		return uuid.Nil, false
	}
	return id, true
}
