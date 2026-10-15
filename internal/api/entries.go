package api

import (
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/SushantPulipati05/journal-server/internal/db"
)

const (
	maxTitleLen     = 200
	maxBodyLen      = 100_000
	defaultPageSize = 50
	maxPageSize     = 100
)

// ---------- request / response shapes ----------

type createEntryRequest struct {
	ID    *uuid.UUID `json:"id"` // optional: devices send their own ID
	Title string     `json:"title"`
	Body  string     `json:"body"`
}

type updateEntryRequest struct {
	Title string `json:"title"`
	Body  string `json:"body"`
}

// entryResponse is what clients see. It deliberately hides user_id and version.
type entryResponse struct {
	ID        uuid.UUID `json:"id"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

func toEntryResponse(e db.Entry) entryResponse {
	return entryResponse{
		ID:        e.ID,
		Title:     e.Title,
		Body:      e.Body,
		CreatedAt: e.CreatedAt,
		UpdatedAt: e.UpdatedAt,
	}
}

// ---------- handlers ----------

// POST /entries
func (s *Server) handleCreateEntry(w http.ResponseWriter, r *http.Request) {
	var req createEntryRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if msg := validateEntry(req.Title, req.Body); msg != "" {
		writeError(w, http.StatusUnprocessableEntity, msg)
		return
	}

	id := uuid.New()
	if req.ID != nil {
		id = *req.ID
	}

	entry, err := s.queries.CreateEntry(r.Context(), db.CreateEntryParams{
		ID:     id,
		UserID: userIDFrom(r.Context()),
		Title:  req.Title,
		Body:   req.Body,
	})
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) {
			switch pgErr.Code {
			case "23505": // unique_violation
				writeError(w, http.StatusConflict, "an entry with this id already exists")
				return
			case "23503": // foreign_key_violation
				writeError(w, http.StatusUnauthorized, "unknown user")
				return
			}
		}
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, toEntryResponse(entry))
}

// GET /entries?limit=50&offset=0
func (s *Server) handleListEntries(w http.ResponseWriter, r *http.Request) {
	limit, err := queryInt(r, "limit", defaultPageSize, 1, maxPageSize)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}
	offset, err := queryInt(r, "offset", 0, 0, 1_000_000)
	if err != nil {
		writeError(w, http.StatusBadRequest, err.Error())
		return
	}

	entries, err := s.queries.ListEntries(r.Context(), db.ListEntriesParams{
		UserID: userIDFrom(r.Context()),
		Limit:  int32(limit),
		Offset: int32(offset),
	})
	if err != nil {
		s.serverError(w, r, err)
		return
	}

	resp := make([]entryResponse, 0, len(entries)) // [] instead of null when empty
	for _, e := range entries {
		resp = append(resp, toEntryResponse(e))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"entries": resp,
		"limit":   limit,
		"offset":  offset,
	})
}

// GET /entries/{id}
func (s *Server) handleGetEntry(w http.ResponseWriter, r *http.Request) {
	id, ok := parseEntryID(w, r)
	if !ok {
		return
	}
	entry, err := s.queries.GetEntry(r.Context(), db.GetEntryParams{
		ID:     id,
		UserID: userIDFrom(r.Context()),
	})
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "entry not found")
		return
	}
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toEntryResponse(entry))
}

// PUT /entries/{id}
func (s *Server) handleUpdateEntry(w http.ResponseWriter, r *http.Request) {
	id, ok := parseEntryID(w, r)
	if !ok {
		return
	}
	var req updateEntryRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid JSON: "+err.Error())
		return
	}
	if msg := validateEntry(req.Title, req.Body); msg != "" {
		writeError(w, http.StatusUnprocessableEntity, msg)
		return
	}

	entry, err := s.queries.UpdateEntry(r.Context(), db.UpdateEntryParams{
		ID:     id,
		UserID: userIDFrom(r.Context()),
		Title:  req.Title,
		Body:   req.Body,
	})
	if errors.Is(err, pgx.ErrNoRows) {
		writeError(w, http.StatusNotFound, "entry not found")
		return
	}
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, toEntryResponse(entry))
}

// DELETE /entries/{id}  (soft delete)
func (s *Server) handleDeleteEntry(w http.ResponseWriter, r *http.Request) {
	id, ok := parseEntryID(w, r)
	if !ok {
		return
	}
	rows, err := s.queries.SoftDeleteEntry(r.Context(), db.SoftDeleteEntryParams{
		ID:     id,
		UserID: userIDFrom(r.Context()),
	})
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	if rows == 0 {
		writeError(w, http.StatusNotFound, "entry not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---------- helpers ----------

func validateEntry(title, body string) string {
	if strings.TrimSpace(title) == "" && strings.TrimSpace(body) == "" {
		return "entry needs a title or a body"
	}
	if utf8.RuneCountInString(title) > maxTitleLen {
		return "title is too long (max 200 characters)"
	}
	if utf8.RuneCountInString(body) > maxBodyLen {
		return "body is too long (max 100,000 characters)"
	}
	return ""
}

func parseEntryID(w http.ResponseWriter, r *http.Request) (uuid.UUID, bool) {
	id, err := uuid.Parse(r.PathValue("id"))
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid entry id")
		return uuid.Nil, false
	}
	return id, true
}

// queryInt reads an optional integer query parameter within [min, max].
func queryInt(r *http.Request, key string, fallback, min, max int) (int, error) {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return fallback, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n < min || n > max {
		return 0, errors.New(key + " must be a number between " + strconv.Itoa(min) + " and " + strconv.Itoa(max))
	}
	return n, nil
}

// serverError logs the real error but tells the client nothing internal.
func (s *Server) serverError(w http.ResponseWriter, r *http.Request, err error) {
	s.logger.Error("internal error", "error", err, "method", r.Method, "path", r.URL.Path)
	writeError(w, http.StatusInternalServerError, "internal server error")
}