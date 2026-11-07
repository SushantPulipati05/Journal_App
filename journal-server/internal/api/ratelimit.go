package api

import (
	"math"
	"net"
	"net/http"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/time/rate"
)

// ipLimiter gives every client IP its own token bucket: each request spends
// one token, and tokens refill at a steady rate. A bucket that is full lets a
// short burst through, then the client is held to the refill rate.
//
// It lives in memory, which is fine for one server instance. If we ever run
// several instances, each has its own buckets, so the real limit becomes
// (limit x instances). A shared store like Redis would fix that; that is a
// scale-up step, not a day-5 problem.
type ipLimiter struct {
	mu          sync.Mutex
	visitors    map[string]*visitor
	rate        rate.Limit
	burst       int
	trustProxy  bool
	lastCleanup time.Time
}

type visitor struct {
	limiter  *rate.Limiter
	lastSeen time.Time
}

func newIPLimiter(r rate.Limit, burst int, trustProxy bool) *ipLimiter {
	return &ipLimiter{
		visitors:    make(map[string]*visitor),
		rate:        r,
		burst:       burst,
		trustProxy:  trustProxy,
		lastCleanup: time.Now(),
	}
}

// allow spends one token for key and reports whether the request may proceed.
func (l *ipLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := time.Now()
	l.sweep(now)

	v, ok := l.visitors[key]
	if !ok {
		v = &visitor{limiter: rate.NewLimiter(l.rate, l.burst)}
		l.visitors[key] = v
	}
	v.lastSeen = now
	return v.limiter.Allow()
}

// sweep forgets clients we haven't seen for a while, so the map can't grow
// forever. It runs inside allow (at most once a minute), so there is no
// background goroutine to leak or shut down. Caller must hold l.mu.
func (l *ipLimiter) sweep(now time.Time) {
	if now.Sub(l.lastCleanup) < time.Minute {
		return
	}
	l.lastCleanup = now
	for key, v := range l.visitors {
		if now.Sub(v.lastSeen) > 10*time.Minute {
			delete(l.visitors, key)
		}
	}
}

// retryAfter is a hint, in seconds, for how long a blocked client should wait.
func (l *ipLimiter) retryAfter() int {
	if l.rate <= 0 {
		return 60
	}
	return max(1, int(math.Ceil(1/float64(l.rate))))
}

// rateLimit wraps a handler so each client IP is held to the limiter's budget.
func (s *Server) rateLimit(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r, s.authLimiter.trustProxy)
		if !s.authLimiter.allow(ip) {
			s.logger.Warn("rate limit hit", "ip", ip, "path", r.URL.Path)
			w.Header().Set("Retry-After", strconv.Itoa(s.authLimiter.retryAfter()))
			writeError(w, http.StatusTooManyRequests, "too many requests, please slow down")
			return
		}
		next(w, r)
	}
}

// clientIP works out who is calling.
//
// Normally that's the TCP peer. Behind a load balancer (Railway, Fly.io,
// Cloud Run) the peer is the balancer itself, so every user would share one
// bucket. With trustProxy on, we read headers the proxy sets instead:
//
//  1. X-Real-IP. Railway's public edge overwrites it with the real client
//     IP, and a client can't forge it through the edge. Railway recommends it
//     for rate limiting.
//  2. Otherwise the LAST X-Forwarded-For entry (the one our own proxy
//     appended). Earlier entries are written by the client and can be forged.
//
// Only turn trustProxy on when a proxy really sits in front, or anyone could
// spoof these headers and dodge the limit.
//
// IPv6 clients are grouped by /64, because one person usually controls a
// whole /64 and could otherwise pick a fresh address for every request.
func clientIP(r *http.Request, trustProxy bool) string {
	raw := ""
	if trustProxy {
		if real := strings.TrimSpace(r.Header.Get("X-Real-IP")); real != "" {
			raw = real
		} else if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
			parts := strings.Split(xff, ",")
			raw = strings.TrimSpace(parts[len(parts)-1])
		}
	}
	if raw == "" {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			host = r.RemoteAddr
		}
		raw = host
	}

	addr, err := netip.ParseAddr(raw)
	if err != nil {
		return raw
	}
	addr = addr.Unmap()
	if addr.Is6() {
		return netip.PrefixFrom(addr, 64).Masked().Addr().String()
	}
	return addr.String()
}
