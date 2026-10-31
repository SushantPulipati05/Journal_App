package api

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"golang.org/x/time/rate"
)

// strictHandler is a server that allows only 3 auth requests per IP, with
// no meaningful refill during the test.
func strictHandler(trustProxy bool) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := NewServer(testPool, logger, testTokens, trustProxy)
	srv.authLimiter = newIPLimiter(rate.Every(time.Hour), 3, trustProxy)
	return srv.Routes()
}

func loginFrom(h http.Handler, remoteAddr, forwardedFor string) *httptest.ResponseRecorder {
	req := httptest.NewRequest("POST", "/auth/login",
		strings.NewReader(`{"email":"nobody@test.local","password":"whatever-password"}`))
	req.Header.Set("Content-Type", "application/json")
	req.RemoteAddr = remoteAddr
	if forwardedFor != "" {
		req.Header.Set("X-Forwarded-For", forwardedFor)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestLoginIsRateLimited(t *testing.T) {
	h := strictHandler(false)

	// The first 3 attempts get through (and fail with 401: unknown account).
	for i := 0; i < 3; i++ {
		expectStatus(t, loginFrom(h, "203.0.113.5:1111", ""), http.StatusUnauthorized)
	}

	// The 4th is blocked before any password work happens.
	rec := loginFrom(h, "203.0.113.5:1111", "")
	expectStatus(t, rec, http.StatusTooManyRequests)
	if rec.Header().Get("Retry-After") == "" {
		t.Error("429 response is missing a Retry-After header")
	}

	// A different client is unaffected.
	expectStatus(t, loginFrom(h, "203.0.113.99:2222", ""), http.StatusUnauthorized)

	// Same IP, different source port: still the same client.
	expectStatus(t, loginFrom(h, "203.0.113.5:9999", ""), http.StatusTooManyRequests)
}

func TestForwardedForIgnoredUnlessTrusted(t *testing.T) {
	// Proxy NOT trusted: a client can't dodge the limit by lying in the header.
	h := strictHandler(false)
	for i := 0; i < 3; i++ {
		expectStatus(t, loginFrom(h, "203.0.113.5:1111", "10.0.0."+string(rune('1'+i))), http.StatusUnauthorized)
	}
	expectStatus(t, loginFrom(h, "203.0.113.5:1111", "10.0.0.9"), http.StatusTooManyRequests)
}

func TestForwardedForUsedBehindTrustedProxy(t *testing.T) {
	h := strictHandler(true)
	proxy := "10.1.1.1:5000" // every request really comes from the load balancer

	// The balancer appends the real client IP last. Two different users:
	for i := 0; i < 3; i++ {
		expectStatus(t, loginFrom(h, proxy, "198.51.100.1"), http.StatusUnauthorized)
	}
	expectStatus(t, loginFrom(h, proxy, "198.51.100.1"), http.StatusTooManyRequests)
	expectStatus(t, loginFrom(h, proxy, "198.51.100.2"), http.StatusUnauthorized)

	// A forged leading entry doesn't help: only the LAST one counts.
	expectStatus(t, loginFrom(h, proxy, "1.2.3.4, 198.51.100.1"), http.StatusTooManyRequests)
}

func TestRealIPPreferredBehindTrustedProxy(t *testing.T) {
	h := strictHandler(true)
	proxy := "10.1.1.1:5000"

	login := func(realIP, xff string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("POST", "/auth/login",
			strings.NewReader(`{"email":"nobody@test.local","password":"whatever-password"}`))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = proxy
		req.Header.Set("X-Real-IP", realIP)
		if xff != "" {
			req.Header.Set("X-Forwarded-For", xff)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	// X-Forwarded-For here ends in a proxy address shared by everyone (as it
	// does on Railway); X-Real-IP is what tells users apart.
	for i := 0; i < 3; i++ {
		expectStatus(t, login("198.51.100.1", "198.51.100.1, 100.64.0.7"), http.StatusUnauthorized)
	}
	expectStatus(t, login("198.51.100.1", "198.51.100.1, 100.64.0.7"), http.StatusTooManyRequests)
	expectStatus(t, login("198.51.100.2", "198.51.100.2, 100.64.0.7"), http.StatusUnauthorized)
}

func TestClientIPGroupsIPv6(t *testing.T) {
	mk := func(addr string) *http.Request {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = addr
		return r
	}
	a := clientIP(mk("[2001:db8:1:2:aaaa::1]:80"), false)
	b := clientIP(mk("[2001:db8:1:2:bbbb::9]:80"), false)
	c := clientIP(mk("[2001:db8:1:3::1]:80"), false)
	if a != b {
		t.Errorf("same /64 got different keys: %s vs %s", a, b)
	}
	if a == c {
		t.Errorf("different /64s got the same key: %s", a)
	}
	if got := clientIP(mk("192.0.2.7:1234"), false); got != "192.0.2.7" {
		t.Errorf("ipv4 key = %q", got)
	}
}
