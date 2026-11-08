package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/SushantPulipati05/journal-server/internal/auth"
	"github.com/SushantPulipati05/journal-server/internal/db"
)

const (
	resetCodeTTL      = 15 * time.Minute
	resetCodeCooldown = time.Minute // at most one email per account per minute
)

// One message for every way a reset can fail, so it never reveals whether
// an email is registered or which part was wrong.
const badResetCode = "invalid or expired code"

type forgotPasswordRequest struct {
	Email string `json:"email"`
}

// POST /auth/password/forgot — email a 6-digit reset code.
//
// Always answers 202 the same way, whether or not the account exists, and does
// the real work in the background so the response time doesn't give it away either.
func (s *Server) handleForgotPassword(w http.ResponseWriter, r *http.Request) {
	if s.mailer == nil {
		writeError(w, http.StatusServiceUnavailable, "password reset is not configured")
		return
	}
	var req forgotPasswordRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if _, err := mail.ParseAddress(email); err != nil {
		writeError(w, http.StatusUnprocessableEntity, "invalid email address")
		return
	}

	go s.sendResetCode(email)

	writeJSON(w, http.StatusAccepted, map[string]string{
		"status": "if an account exists for that email, a code is on its way",
	})
}

// sendResetCode runs in the background, after the response has been sent.
func (s *Server) sendResetCode(email string) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	user, err := s.queries.GetUserByEmail(ctx, email)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			s.logger.Error("password reset: look up user", "error", err)
		}
		return // unknown email: quietly do nothing
	}

	// Don't let anyone flood a person's inbox.
	if existing, err := s.queries.GetActivePasswordReset(ctx, user.ID); err == nil &&
		time.Since(existing.CreatedAt) < resetCodeCooldown {
		return
	}

	// Only the newest code works.
	if err := s.queries.InvalidatePasswordResets(ctx, user.ID); err != nil {
		s.logger.Error("password reset: invalidate old codes", "error", err)
		return
	}
	code, err := newResetCode()
	if err != nil {
		s.logger.Error("password reset: make code", "error", err)
		return
	}
	_, err = s.queries.CreatePasswordReset(ctx, db.CreatePasswordResetParams{
		UserID:    user.ID,
		CodeHash:  hashResetCode(user.ID, code),
		ExpiresAt: time.Now().Add(resetCodeTTL),
	})
	if err != nil {
		s.logger.Error("password reset: save code", "error", err)
		return
	}
	if err := s.mailer.SendPasswordResetCode(ctx, user.Email, code); err != nil {
		s.logger.Error("password reset: send email", "error", err)
	}
}

type resetPasswordRequest struct {
	Email    string `json:"email"`
	Code     string `json:"code"`
	Password string `json:"password"`
}

// POST /auth/password/reset — set a new password with the emailed code.
// Signs out every other device, then signs this one in.
func (s *Server) handleResetPassword(w http.ResponseWriter, r *http.Request) {
	var req resetPasswordRequest
	if err := decodeJSON(w, r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	if err := auth.ValidatePassword(req.Password); err != nil {
		writeError(w, http.StatusUnprocessableEntity, err.Error())
		return
	}
	ctx := r.Context()
	email := strings.ToLower(strings.TrimSpace(req.Email))
	code := strings.TrimSpace(req.Code)

	user, err := s.queries.GetUserByEmail(ctx, email)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusBadRequest, badResetCode)
			return
		}
		s.serverError(w, r, err)
		return
	}
	reset, err := s.queries.GetActivePasswordReset(ctx, user.ID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			writeError(w, http.StatusBadRequest, badResetCode)
			return
		}
		s.serverError(w, r, err)
		return
	}

	// Constant-time compare. After 5 wrong tries the code is burned
	// (a 6-digit code would otherwise be guessable).
	if subtle.ConstantTimeCompare([]byte(hashResetCode(user.ID, code)), []byte(reset.CodeHash)) != 1 {
		if _, err := s.queries.RecordPasswordResetFailure(ctx, reset.ID); err != nil {
			s.logger.Error("password reset: record failure", "error", err)
		}
		writeError(w, http.StatusBadRequest, badResetCode)
		return
	}

	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		s.serverError(w, r, err)
		return
	}

	// All-or-nothing: use the code, set the password, sign out everywhere.
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	defer tx.Rollback(ctx) // no-op after Commit
	q := s.queries.WithTx(tx)

	rows, err := q.MarkPasswordResetUsed(ctx, reset.ID)
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	if rows == 0 { // someone used it a moment ago
		writeError(w, http.StatusBadRequest, badResetCode)
		return
	}
	if err := q.UpdatePassword(ctx, db.UpdatePasswordParams{ID: user.ID, PasswordHash: &hash}); err != nil {
		s.serverError(w, r, err)
		return
	}
	if err := q.RevokeAllUserTokens(ctx, user.ID); err != nil {
		s.serverError(w, r, err)
		return
	}
	if err := tx.Commit(ctx); err != nil {
		s.serverError(w, r, err)
		return
	}

	session, err := s.issueSession(r, user.ID, uuid.New())
	if err != nil {
		s.serverError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

// newResetCode returns a random 6-digit code like "042917".
func newResetCode() (string, error) {
	n, err := rand.Int(rand.Reader, big.NewInt(1_000_000))
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", n.Int64()), nil
}

// hashResetCode mixes in the user ID so equal codes for different users hash differently.
func hashResetCode(userID uuid.UUID, code string) string {
	sum := sha256.Sum256([]byte(userID.String() + ":" + code))
	return hex.EncodeToString(sum[:])
}
