package api

import (
	"errors"
	"net/http"
	"strings"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/SushantPulipati05/journal-server/internal/db"
)

type googleRequest struct {
	IDToken string `json:"id_token"`
}

// POST /auth/google — sign in (or sign up) with a Google ID token from the app.
//
//  1. Known Google account          → sign in               (200)
//  2. Email exists with a password  → link Google to it     (200)
//  3. Brand-new                     → create a Google user  (201)
//
// Linking by email is safe because we only accept emails Google has verified.
func (s *Server) handleGoogle(w http.ResponseWriter, r *http.Request) {
	if s.google == nil {
		writeError(w, http.StatusServiceUnavailable, "google sign-in is not configured")
		return
	}
	var req googleRequest
	if err := decodeJSON(w, r, &req); err != nil || req.IDToken == "" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	ident, err := s.google.Verify(r.Context(), req.IDToken)
	if err != nil {
		s.logger.Info("google token rejected", "error", err)
		writeError(w, http.StatusUnauthorized, "invalid google token")
		return
	}
	if !ident.EmailVerified {
		writeError(w, http.StatusUnauthorized, "google account email is not verified")
		return
	}
	email := strings.ToLower(strings.TrimSpace(ident.Email))
	sub := ident.Subject
	ctx := r.Context()
	status := http.StatusOK

	user, err := s.queries.GetUserByGoogleSub(ctx, &sub)
	switch {
	case err == nil:
		// 1. returning Google user

	case errors.Is(err, pgx.ErrNoRows):
		existing, err := s.queries.GetUserByEmail(ctx, email)
		switch {
		case err == nil:
			// 2. same email, signed up with a password earlier
			if existing.GoogleSub != nil && *existing.GoogleSub != sub {
				writeError(w, http.StatusConflict, "this email is linked to a different Google account")
				return
			}
			user, err = s.queries.LinkGoogleAccount(ctx, db.LinkGoogleAccountParams{ID: existing.ID, GoogleSub: &sub})
			if err != nil {
				s.serverError(w, r, err)
				return
			}

		case errors.Is(err, pgx.ErrNoRows):
			// 3. new account
			user, err = s.queries.CreateGoogleUser(ctx, db.CreateGoogleUserParams{
				Email:       email,
				GoogleSub:   &sub,
				DisplayName: strings.TrimSpace(ident.Name),
			})
			if err != nil {
				var pgErr *pgconn.PgError
				if errors.As(err, &pgErr) && pgErr.Code == "23505" {
					// Two sign-ins raced; the other one created the account.
					writeError(w, http.StatusConflict, "account was just created, please try again")
					return
				}
				s.serverError(w, r, err)
				return
			}
			status = http.StatusCreated

		default:
			s.serverError(w, r, err)
			return
		}

	default:
		s.serverError(w, r, err)
		return
	}

	session, err := s.issueSession(r, user.ID, uuid.New())
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, status, session)
}
