// Package api contains the HTTP handlers and routing.
package api

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/SushantPulipati05/journal-server/internal/db"
)

// Server holds everything the handlers need.
type Server struct {
	pool    *pgxpool.Pool
	queries *db.Queries
	logger  *slog.Logger
}

func NewServer(pool *pgxpool.Pool, logger *slog.Logger) *Server {
	return &Server{
		pool:    pool,
		queries: db.New(pool), // sqlc's generated constructor
		logger:  logger,
	}
}

// Routes wires URLs to handler methods.
func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", s.handleHealth)
	return mux
}

// GET /healthz — is the server up, and can it reach the database?
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()

	if err := s.pool.Ping(ctx); err != nil {
		s.logger.Error("health check failed", "error", err)
		writeError(w, http.StatusServiceUnavailable, "database unavailable")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}