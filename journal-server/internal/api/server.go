// Package api contains the HTTP handlers and routing.
package api

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/time/rate"

	"github.com/SushantPulipati05/journal-server/internal/auth"
	"github.com/SushantPulipati05/journal-server/internal/db"
)

// Server holds everything the handlers need.
type Server struct {
	pool    *pgxpool.Pool
	queries *db.Queries
	logger  *slog.Logger
	tokens  *auth.TokenManager

	// authLimiter slows down password guessing on the public auth endpoints.
	authLimiter *ipLimiter
}

// trustProxy should be true only when the server runs behind a load balancer
// that sets X-Forwarded-For (Fly.io, Cloud Run). See clientIP.
func NewServer(pool *pgxpool.Pool, logger *slog.Logger, tokens *auth.TokenManager, trustProxy bool) *Server {
	return &Server{
		pool:    pool,
		queries: db.New(pool),
		logger:  logger,
		tokens:  tokens,

		// 5 attempts in a burst, then one every 6 seconds (10 a minute).
		authLimiter: newIPLimiter(rate.Every(6*time.Second), 5, trustProxy),
	}
}

// Routes wires URLs to handler methods, wrapped in middleware.
func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", s.handleHealth)

	// Accounts and sessions (public: the caller has no token yet), so they
	// are rate limited per IP. Logout isn't: it needs a valid refresh token.
	mux.HandleFunc("POST /auth/signup", s.rateLimit(s.handleSignup))
	mux.HandleFunc("POST /auth/login", s.rateLimit(s.handleLogin))
	mux.HandleFunc("POST /auth/refresh", s.rateLimit(s.handleRefresh))
	mux.HandleFunc("POST /auth/logout", s.handleLogout)

	// The signed-in user's own account.
	mux.HandleFunc("GET /me", s.requireUser(s.handleMe))
	mux.HandleFunc("DELETE /me", s.requireUser(s.handleDeleteMe))

	// Sync: devices push offline changes and pull everyone else's.
	mux.HandleFunc("POST /sync/push", s.requireUser(s.handlePush))
	mux.HandleFunc("GET /sync/pull", s.requireUser(s.handlePull))

	// Journal entries.
	mux.HandleFunc("POST /entries", s.requireUser(s.handleCreateEntry))
	mux.HandleFunc("GET /entries", s.requireUser(s.handleListEntries))
	mux.HandleFunc("GET /entries/{id}", s.requireUser(s.handleGetEntry))
	mux.HandleFunc("PUT /entries/{id}", s.requireUser(s.handleUpdateEntry))
	mux.HandleFunc("DELETE /entries/{id}", s.requireUser(s.handleDeleteEntry))

	// Outermost first: recover from panics, then log every request.
	return s.recoverPanic(s.logRequests(mux))
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
