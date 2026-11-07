package api

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"testing"

	"github.com/google/uuid"
	"golang.org/x/time/rate"

	"github.com/SushantPulipati05/journal-server/internal/auth"
)

// fakeGoogle stands in for Google: each "token" string maps to an identity.
type fakeGoogle map[string]auth.GoogleIdentity

func (f fakeGoogle) Verify(_ context.Context, token string) (auth.GoogleIdentity, error) {
	if id, ok := f[token]; ok {
		return id, nil
	}
	return auth.GoogleIdentity{}, auth.ErrInvalidGoogleToken
}

func newGoogleTestHandler(f fakeGoogle) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, false).WithGoogle(f)
	srv.authLimiter = newIPLimiter(rate.Inf, 1_000_000, false)
	return srv.Routes()
}

func googleLogin(t *testing.T, h http.Handler, token string, want int) sessionResponse {
	t.Helper()
	rec := authedRequest(t, h, "POST", "/auth/google", "", `{"id_token":"`+token+`"}`)
	expectStatus(t, rec, want)
	if want >= 300 {
		return sessionResponse{}
	}
	return decode[sessionResponse](t, rec)
}

func meOf(t *testing.T, h http.Handler, access string) userResponse {
	t.Helper()
	rec := authedRequest(t, h, "GET", "/me", access, "")
	expectStatus(t, rec, http.StatusOK)
	return decode[userResponse](t, rec)
}

func TestGoogleSignupThenSignin(t *testing.T) {
	email := uuid.NewString() + "@gmail.test"
	h := newGoogleTestHandler(fakeGoogle{
		"tok": {Subject: "sub-" + uuid.NewString(), Email: email, EmailVerified: true, Name: "Ann"},
	})

	first := googleLogin(t, h, "tok", http.StatusCreated)
	me1 := meOf(t, h, first.AccessToken)
	if me1.Email != email || me1.DisplayName != "Ann" {
		t.Fatalf("me = %+v", me1)
	}

	second := googleLogin(t, h, "tok", http.StatusOK)
	if me2 := meOf(t, h, second.AccessToken); me2.ID != me1.ID {
		t.Fatalf("second sign-in made a new account: %v vs %v", me2.ID, me1.ID)
	}
}

func TestGoogleLinksExistingPasswordAccount(t *testing.T) {
	plain := newTestHandler()
	email, session := signup(t, plain)
	original := meOf(t, plain, session.AccessToken)

	h := newGoogleTestHandler(fakeGoogle{
		"tok": {Subject: "sub-" + uuid.NewString(), Email: email, EmailVerified: true},
	})
	g := googleLogin(t, h, "tok", http.StatusOK)
	if me := meOf(t, h, g.AccessToken); me.ID != original.ID {
		t.Fatalf("expected the existing account to be linked")
	}

	// The password still works after linking.
	rec := authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+email+`","password":"`+testPassword+`"}`)
	expectStatus(t, rec, http.StatusOK)
}

func TestGoogleRejectsBadTokens(t *testing.T) {
	h := newGoogleTestHandler(fakeGoogle{
		"unverified": {Subject: "sub-" + uuid.NewString(), Email: uuid.NewString() + "@x.test", EmailVerified: false},
	})
	googleLogin(t, h, "forged", http.StatusUnauthorized)
	googleLogin(t, h, "unverified", http.StatusUnauthorized)

	rec := authedRequest(t, h, "POST", "/auth/google", "", `{}`)
	expectStatus(t, rec, http.StatusBadRequest)
}

func TestGoogleDifferentSubForLinkedEmailIsRejected(t *testing.T) {
	email := uuid.NewString() + "@gmail.test"
	h := newGoogleTestHandler(fakeGoogle{
		"a": {Subject: "sub-a-" + uuid.NewString(), Email: email, EmailVerified: true},
		"b": {Subject: "sub-b-" + uuid.NewString(), Email: email, EmailVerified: true},
	})
	googleLogin(t, h, "a", http.StatusCreated)
	googleLogin(t, h, "b", http.StatusConflict)
}

func TestGoogleNotConfigured(t *testing.T) {
	h := newTestHandler() // no WithGoogle
	rec := authedRequest(t, h, "POST", "/auth/google", "", `{"id_token":"x"}`)
	expectStatus(t, rec, http.StatusServiceUnavailable)
}

func TestGoogleOnlyAccountCannotUsePasswordLogin(t *testing.T) {
	email := uuid.NewString() + "@gmail.test"
	h := newGoogleTestHandler(fakeGoogle{
		"tok": {Subject: "sub-" + uuid.NewString(), Email: email, EmailVerified: true},
	})
	googleLogin(t, h, "tok", http.StatusCreated)
	rec := authedRequest(t, h, "POST", "/auth/login", "", `{"email":"`+email+`","password":"anything-at-all"}`)
	expectStatus(t, rec, http.StatusUnauthorized)
}
