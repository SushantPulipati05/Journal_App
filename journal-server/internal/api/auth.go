package api

import (
	"errors"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/SushantPulipati05/journal-server/internal/auth"
	"github.com/SushantPulipati05/journal-server/internal/db"
)

const refreshTokenTTL = 30 * 24 * time.Hour

// dummyHash lets login do the same amount of bcrypt work whether or not the
// email exists. Without it, "unknown email" answers in 1ms and "wrong
// password" in 250ms, and an attacker can use that to list registered emails.
var dummyHash = func() string {
	h, err := auth.HashPassword("dummy-password-for-timing")
	if err != nil {
		panic(err)
	}
	return h
}()

type sessionResponse struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token"`
	TokenType    string    `json:"token_type"`
	ExpiresAt    time.Time `json:"expires_at"`
}

type userResponse struct {
	ID          uuid.UUID `json:"id"`
	Email       string    `json:"email"`
	DisplayName string    `json:"display_name"`
	CreatedAt   time.Time `json:"created_at"`
}

// issueSession creates a short-lived access token and a new refresh token.
// All refresh tokens descended from one login share a familyID.
func (s *Server) issueSession(r *http.Request, userID, familyID uuid.UUID) (sessionResponse, error) {
	access, expiresAt, err := s.tokens.IssueAccessToken(userID)
	if err != nil {
		return sessionResponse{}, err
	}
	refresh, hash, err := auth.NewRefreshToken()
	if err != nil {
		return sessionResponse{}, err
	}
	err = s.queries.CreateRefreshToken(r.Context(), db.CreateRefreshTokenParams{
		UserID:    userID,
		TokenHash: hash,
		FamilyID:  familyID,
		ExpiresAt: time.Now().Add(refreshTokenTTL),
	})
	if err != nil {
		return sessionResponse{}, err
	}
	return sessionResponse{
		AccessToken:  access,
		RefreshToken: refresh,
		TokenType:    "Bearer",
		ExpiresAt:    expiresAt,
	}, nil
}

type signupRequest struct {
	Email       string `json:"email"`
	Password    string `json:"password"`
	DisplayName string `json:"display_name"`
}

// POST /auth/signup
func (s *Server) handleSignup(w http.ResponseWriter, r *http.Request) {
	var req signupRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	email := strings.ToLower(strings.TrimSpace(req.Email))
	if _, err := mail.ParseAddress(email); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid email address")
		return
	}
	if err := auth.ValidatePassword(req.Password); err != nil {
		writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		s.serverError(w, r, err)
		return
	}

	user, err := s.queries.CreateUser(r.Context(), db.CreateUserParams{
		Email:        email,
		PasswordHash: &hash,
		DisplayName:  strings.TrimSpace(req.DisplayName),
	})
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			writeError(w, http.StatusConflict, "email already registered")
			return
		}
		s.serverError(w, r, err)
		return
	}

	session, err := s.issueSession(r, user.ID, uuid.New())
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, session)
}

type loginRequest struct {
	Email    string `json:"email"`
	Password string `json:"password"`
}

// POST /auth/login
func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))

	user, err := s.queries.GetUserByEmail(r.Context(), email)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		s.serverError(w, r, err)
		return
	}

	// Pick the hash to compare against. An unknown email, or a Google-only
	// account with no password, both fall back to the dummy hash.
	hash := dummyHash
	found := err == nil && user.PasswordHash != nil
	if found {
		hash = *user.PasswordHash
	}
	ok := auth.CheckPassword(hash, req.Password)
	if !found || !ok {
		// One message for every failure, so the response never says
		// which half was wrong.
		writeError(w, http.StatusUnauthorized, "invalid email or password")
		return
	}

	session, err := s.issueSession(r, user.ID, uuid.New())
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

type refreshRequest struct {
	RefreshToken string `json:"refresh_token"`
}

// POST /auth/refresh — trade a refresh token for a new pair (rotation).
func (s *Server) handleRefresh(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}

	stored, err := s.queries.GetRefreshTokenByHash(r.Context(), auth.HashRefreshToken(req.RefreshToken))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusUnauthorized, "invalid refresh token")
			return
		}
		s.serverError(w, r, err)
		return
	}

	// REUSE DETECTION. A token that was already rotated is being presented
	// again. Either the user's copy or an attacker's copy was stolen. We
	// can't tell which, so we kill the whole family and force a fresh login.
	if stored.RevokedAt != nil {
		s.logger.Warn("refresh token reuse detected", "user_id", stored.UserID, "family_id", stored.FamilyID)
		if err := s.queries.RevokeTokenFamily(r.Context(), stored.FamilyID); err != nil {
			s.serverError(w, r, err)
			return
		}
		writeError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}
	if time.Now().After(stored.ExpiresAt) {
		writeError(w, http.StatusUnauthorized, "refresh token expired")
		return
	}

	// Rotate: revoke the old token. The query only matches rows that are not
	// yet revoked, so if two requests race with the same token, exactly one
	// gets rows == 1 and the loser is treated as reuse.
	rows, err := s.queries.RevokeRefreshToken(r.Context(), stored.ID)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	if rows == 0 {
		_ = s.queries.RevokeTokenFamily(r.Context(), stored.FamilyID)
		writeError(w, http.StatusUnauthorized, "invalid refresh token")
		return
	}

	session, err := s.issueSession(r, stored.UserID, stored.FamilyID)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

// POST /auth/logout — revoke the whole session family for this refresh token.
func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	var req refreshRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	stored, err := s.queries.GetRefreshTokenByHash(r.Context(), auth.HashRefreshToken(req.RefreshToken))
	switch {
	case err == nil:
		if err := s.queries.RevokeTokenFamily(r.Context(), stored.FamilyID); err != nil {
			s.serverError(w, r, err)
			return
		}
	case !errors.Is(err, pgx.ErrNoRows):
		s.serverError(w, r, err)
		return
	}
	// Always 204, even for an unknown token. Logout is idempotent.
	w.WriteHeader(http.StatusNoContent)
}

// GET /me
func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	user, err := s.queries.GetUserByID(r.Context(), userIDFrom(r.Context()))
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusUnauthorized, "account no longer exists")
			return
		}
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, userResponse{
		ID:          user.ID,
		Email:       user.Email,
		DisplayName: user.DisplayName,
		CreatedAt:   user.CreatedAt,
	})
}

// DELETE /me — permanently deletes the account. Entries and refresh tokens
// go with it through the ON DELETE CASCADE foreign keys. Google Play
// requires apps that create accounts to offer this.
func (s *Server) handleDeleteMe(w http.ResponseWriter, r *http.Request) {
	rows, err := s.queries.DeleteUser(r.Context(), userIDFrom(r.Context()))
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	if rows == 0 {
		writeError(w, http.StatusNotFound, "account not found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
