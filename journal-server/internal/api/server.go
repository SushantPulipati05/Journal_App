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
	"github.com/SushantPulipati05/journal-server/internal/mail"
	"github.com/SushantPulipati05/journal-server/internal/storage"
)

// Server holds everything the handlers need.
type Server struct {
	pool    *pgxpool.Pool
	queries *db.Queries
	logger  *slog.Logger
	tokens  *auth.TokenManager

	// authLimiter slows down password guessing on the public auth endpoints.
	authLimiter *ipLimiter

	// google verifies "Sign in with Google" tokens. nil = Google sign-in is off.
	google auth.GoogleVerifier

	// mailer sends password-reset codes. nil = "forgot password" is off.
	mailer mail.Mailer

	// store keeps photo files (S3-compatible: Backblaze B2). nil = photos are off.
	store storage.Store
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

// WithGoogle turns on POST /auth/google.
func (s *Server) WithGoogle(v auth.GoogleVerifier) *Server {
	s.google = v
	return s
}

// WithMailer turns on the "forgot password" endpoints.
func (s *Server) WithMailer(m mail.Mailer) *Server {
	s.mailer = m
	return s
}

// WithStorage turns on photo uploads.
func (s *Server) WithStorage(st storage.Store) *Server {
	s.store = st
	return s
}

// Routes wires URLs to handler methods, wrapped in middleware.
func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", s.handleHealth)

	// Accounts and sessions (public: the caller has no token yet), so they
	// are rate limited per IP. Logout isn't: it needs a valid refresh token.
	mux.HandleFunc("POST /auth/signup", s.rateLimit(s.handleSignup))
	mux.HandleFunc("POST /auth/login", s.rateLimit(s.handleLogin))
	mux.HandleFunc("POST /auth/google", s.rateLimit(s.handleGoogle))
	mux.HandleFunc("POST /auth/refresh", s.rateLimit(s.handleRefresh))
	mux.HandleFunc("POST /auth/password/forgot", s.rateLimit(s.handleForgotPassword))
	mux.HandleFunc("POST /auth/password/reset", s.rateLimit(s.handleResetPassword))
	mux.HandleFunc("POST /auth/logout", s.handleLogout)

	// The signed-in user's own account.
	mux.HandleFunc("GET /me", s.requireUser(s.handleMe))
	mux.HandleFunc("DELETE /me", s.requireUser(s.handleDeleteMe))

	// Sync: devices push offline changes and pull everyone else's.
	mux.HandleFunc("POST /sync/push", s.requireUser(s.handlePush))
	mux.HandleFunc("GET /sync/pull", s.requireUser(s.handlePull))

	// Photos: announce + upload link, confirm, download link, delete, sync.
	mux.HandleFunc("POST /attachments", s.requireUser(s.handleCreateAttachment))
	mux.HandleFunc("POST /attachments/{id}/complete", s.requireUser(s.handleCompleteAttachment))
	mux.HandleFunc("GET /attachments/{id}/url", s.requireUser(s.handleAttachmentURL))
	mux.HandleFunc("DELETE /attachments/{id}", s.requireUser(s.handleDeleteAttachment))
	mux.HandleFunc("GET /attachments/pull", s.requireUser(s.handlePullAttachments))

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
