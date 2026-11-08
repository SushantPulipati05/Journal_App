package api

import (
	"context"
	"io"
	"log/slog"
	"net/http"
	"testing"
	"time"

	"github.com/google/uuid"
	"golang.org/x/time/rate"
)

// fakeMailer hands every "sent" code to the test through a channel.
type fakeMailer struct{ codes chan string }

func (m fakeMailer) SendPasswordResetCode(_ context.Context, _ string, code string) error {
	m.codes <- code
	return nil
}

func newResetTestHandler() (http.Handler, fakeMailer) {
	m := fakeMailer{codes: make(chan string, 10)}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, false).WithMailer(m)
	srv.authLimiter = newIPLimiter(rate.Inf, 1_000_000, false)
	return srv.Routes(), m
}

// requestCode asks for a reset and waits for the (background) email.
func requestCode(t *testing.T, h http.Handler, m fakeMailer, email string) string {
	t.Helper()
	rec := authedRequest(t, h, "POST", "/auth/password/forgot", "", `{"email":"`+email+`"}`)
	expectStatus(t, rec, http.StatusAccepted)
	select {
	case code := <-m.codes:
		return code
	case <-time.After(5 * time.Second):
		t.Fatal("no reset email was sent")
		return ""
	}
}

func resetBody(email, code, password string) string {
	return `{"email":"` + email + `","code":"` + code + `","password":"` + password + `"}`
}

func TestPasswordResetHappyPath(t *testing.T) {
	h, m := newResetTestHandler()
	email, oldSession := signup(t, h)
	code := requestCode(t, h, m, email)

	rec := authedRequest(t, h, "POST", "/auth/password/reset", "", resetBody(email, code, "brand-new-password"))
	expectStatus(t, rec, http.StatusOK)
	if s := decode[sessionResponse](t, rec); s.AccessToken == "" {
		t.Fatal("reset should sign the user in")
	}

	// New password works, old one doesn't.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+email+`","password":"brand-new-password"}`), http.StatusOK)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+email+`","password":"`+testPassword+`"}`), http.StatusUnauthorized)

	// Every old session is signed out.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/refresh", "",
		`{"refresh_token":"`+oldSession.RefreshToken+`"}`), http.StatusUnauthorized)

	// The code works only once.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
		resetBody(email, code, "another-password-1")), http.StatusBadRequest)
}

func TestPasswordResetUnknownEmailLooksTheSame(t *testing.T) {
	h, m := newResetTestHandler()
	rec := authedRequest(t, h, "POST", "/auth/password/forgot", "",
		`{"email":"`+uuid.NewString()+`@nobody.test"}`)
	expectStatus(t, rec, http.StatusAccepted)
	select {
	case <-m.codes:
		t.Fatal("no email should be sent for an unknown address")
	case <-time.After(300 * time.Millisecond):
	}
}

func TestPasswordResetWrongCodeBurnsAfterFiveTries(t *testing.T) {
	h, m := newResetTestHandler()
	email, _ := signup(t, h)
	code := requestCode(t, h, m, email)

	wrong := "000000"
	if code == wrong {
		wrong = "111111"
	}
	for i := 0; i < 5; i++ {
		expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
			resetBody(email, wrong, "brand-new-password")), http.StatusBadRequest)
	}
	// Even the right code is refused now.
	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
		resetBody(email, code, "brand-new-password")), http.StatusBadRequest)
}

func TestPasswordResetOnlyNewestCodeWorks(t *testing.T) {
	h, m := newResetTestHandler()
	email, _ := signup(t, h)
	first := requestCode(t, h, m, email)

	// Skip the one-minute cooldown by backdating the first code.
	_, err := testPool.Exec(context.Background(),
		"UPDATE password_resets SET created_at = now() - interval '2 minutes' WHERE used_at IS NULL")
	if err != nil {
		t.Fatal(err)
	}
	second := requestCode(t, h, m, email)
	if first == second {
		t.Skip("random codes happened to match")
	}

	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
		resetBody(email, first, "brand-new-password")), http.StatusBadRequest)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
		resetBody(email, second, "brand-new-password")), http.StatusOK)
}

func TestPasswordResetGoogleOnlyAccountCanSetAPassword(t *testing.T) {
	email := uuid.NewString() + "@gmail.test"
	m := fakeMailer{codes: make(chan string, 10)}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, false).
		WithGoogle(fakeGoogle{"tok": {Subject: "sub-" + uuid.NewString(), Email: email, EmailVerified: true}}).
		WithMailer(m)
	srv.authLimiter = newIPLimiter(rate.Inf, 1_000_000, false)
	h := srv.Routes()

	googleLogin(t, h, "tok", http.StatusCreated)
	code := requestCode(t, h, m, email)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/reset", "",
		resetBody(email, code, "now-i-have-a-password")), http.StatusOK)
	expectStatus(t, authedRequest(t, h, "POST", "/auth/login", "",
		`{"email":"`+email+`","password":"now-i-have-a-password"}`), http.StatusOK)
}

func TestPasswordResetNotConfigured(t *testing.T) {
	h := newTestHandler() // no WithMailer
	expectStatus(t, authedRequest(t, h, "POST", "/auth/password/forgot", "", `{"email":"a@b.test"}`),
		http.StatusServiceUnavailable)
}
