package api

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/google/uuid"
)

// authedRequest sends a request with an optional raw Bearer token.
func authedRequest(t *testing.T, h http.Handler, method, path, token, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

const testPassword = "correct-horse-battery"

// signup registers a brand-new account and returns its email and session.
func signup(t *testing.T, h http.Handler) (string, sessionResponse) {
	t.Helper()
	email := uuid.NewString() + "@test.local"
	rec := authedRequest(t, h, "POST", "/auth/signup", "",
		`{"email":"`+email+`","password":"`+testPassword+`","display_name":"Tester"}`)
	expectStatus(t, rec, http.StatusCreated)
	return email, decode[sessionResponse](t, rec)
}

func TestSignupLoginAndMe(t *testing.T) {
	h := newTestHandler()
	email, session := signup(t, h)

	if session.AccessToken == "" || session.RefreshToken == "" || session.TokenType != "Bearer" {
		t.Fatalf("incomplete session: %+v", session)
	}

	// The access token from signup works.
	rec := authedRequest(t, h, "GET", "/me", session.AccessToken, "")
	expectStatus(t, rec, http.StatusOK)
	me := decode[userResponse](t, rec)
	if me.Email != email || me.DisplayName != "Tester" {
		t.Errorf("me = %+v", me)
	}

	// Logging in works too, and email matching ignores case.
	rec = authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+strings.ToUpper(email)+`","password":"`+testPassword+`"}`)
	expectStatus(t, rec, http.StatusOK)
}

func TestSignupValidation(t *testing.T) {
	h := newTestHandler()
	email, _ := signup(t, h)

	cases := []struct {
		name string
		body string
		want int
	}{
		{"duplicate email", `{"email":"` + email + `","password":"` + testPassword + `"}`, http.StatusConflict},
		{"duplicate email, different case", `{"email":"` + strings.ToUpper(email) + `","password":"` + testPassword + `"}`, http.StatusConflict},
		{"weak password", `{"email":"new1@test.local","password":"short"}`, http.StatusUnprocessableEntity},
		{"bad email", `{"email":"not-an-email","password":"` + testPassword + `"}`, http.StatusUnprocessableEntity},
		{"unknown field", `{"emial":"x@test.local","password":"` + testPassword + `"}`, http.StatusBadRequest},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			expectStatus(t, authedRequest(t, h, "POST", "/auth/signup", "", c.body), c.want)
		})
	}
}

func TestLoginFailuresLookIdentical(t *testing.T) {
	h := newTestHandler()
	email, _ := signup(t, h)

	wrongPassword := authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+email+`","password":"wrong-password-here"}`)
	unknownEmail := authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"nobody-`+uuid.NewString()+`@test.local","password":"`+testPassword+`"}`)

	expectStatus(t, wrongPassword, http.StatusUnauthorized)
	expectStatus(t, unknownEmail, http.StatusUnauthorized)
	if wrongPassword.Body.String() != unknownEmail.Body.String() {
		t.Errorf("responses differ, which leaks which emails exist:\n%s\n%s",
			wrongPassword.Body.String(), unknownEmail.Body.String())
	}
}

func TestRequiresValidToken(t *testing.T) {
	h := newTestHandler()
	_, session := signup(t, h)

	cases := []struct {
		name   string
		header string
	}{
		{"no header", ""},
		{"garbage token", "Bearer not.a.real.token"},
		{"wrong scheme", "Basic " + session.AccessToken},
		{"token without scheme", session.AccessToken},
		{"empty bearer", "Bearer "},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := httptest.NewRequest("GET", "/me", nil)
			if c.header != "" {
				req.Header.Set("Authorization", c.header)
			}
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, req)
			expectStatus(t, rec, http.StatusUnauthorized)
		})
	}

	// The old Day-1 header must no longer grant access.
	req := httptest.NewRequest("GET", "/entries", nil)
	req.Header.Set("X-User-ID", uuid.NewString())
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	expectStatus(t, rec, http.StatusUnauthorized)
}

func TestRefreshRotationAndReuseDetection(t *testing.T) {
	h := newTestHandler()
	_, first := signup(t, h)

	// Refreshing hands back a different, working pair.
	rec := authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"`+first.RefreshToken+`"}`)
	expectStatus(t, rec, http.StatusOK)
	second := decode[sessionResponse](t, rec)
	if second.RefreshToken == first.RefreshToken {
		t.Fatal("refresh token was not rotated")
	}
	expectStatus(t, authedRequest(t, h, "GET", "/me", second.AccessToken, ""), http.StatusOK)

	// Replaying the OLD token is theft: rejected...
	rec = authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"`+first.RefreshToken+`"}`)
	expectStatus(t, rec, http.StatusUnauthorized)

	// ...and it kills the NEW token as well (the whole family).
	rec = authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"`+second.RefreshToken+`"}`)
	expectStatus(t, rec, http.StatusUnauthorized)
}

func TestRefreshRejectsUnknownToken(t *testing.T) {
	h := newTestHandler()
	rec := authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"does-not-exist"}`)
	expectStatus(t, rec, http.StatusUnauthorized)
}

func TestLogoutRevokesSession(t *testing.T) {
	h := newTestHandler()
	_, session := signup(t, h)

	expectStatus(t, authedRequest(t, h, "POST", "/auth/logout", "", `{"refresh_token":"`+session.RefreshToken+`"}`), http.StatusNoContent)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"`+session.RefreshToken+`"}`), http.StatusUnauthorized)

	// Logging out twice, or with junk, is harmless.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/logout", "", `{"refresh_token":"`+session.RefreshToken+`"}`), http.StatusNoContent)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/logout", "", `{"refresh_token":"junk"}`), http.StatusNoContent)
}

func TestDeleteAccountRemovesEverything(t *testing.T) {
	h := newTestHandler()
	_, session := signup(t, h)

	rec := authedRequest(t, h, "POST", "/entries", session.AccessToken, `{"title":"private thoughts"}`)
	expectStatus(t, rec, http.StatusCreated)
	entry := decode[entryResponse](t, rec)

	expectStatus(t, authedRequest(t, h, "DELETE", "/me", session.AccessToken, ""), http.StatusNoContent)

	// The entry row is physically gone, not just soft-deleted.
	var n int
	err := testPool.QueryRow(context.Background(),
		"SELECT count(*) FROM entries WHERE id = $1", entry.ID).Scan(&n)
	if err != nil || n != 0 {
		t.Fatalf("entry still in database: n=%d err=%v", n, err)
	}

	// The refresh token died with the account.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/refresh", "", `{"refresh_token":"`+session.RefreshToken+`"}`), http.StatusUnauthorized)

	// The access token is still cryptographically valid for up to 15 minutes,
	// but the account is gone, so /me must refuse it.
	expectStatus(t, authedRequest(t, h, "GET", "/me", session.AccessToken, ""), http.StatusUnauthorized)
}
