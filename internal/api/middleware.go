package api

import (
	"net/http"
	"time"

	"github.com/google/uuid"
)

// requireUser identifies the caller and stores their ID in the request context.
// TEMPORARY (Day 1): trusts an X-User-ID header. Day 2 replaces this with JWT auth.
func (s *Server) requireUser(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := uuid.Parse(r.Header.Get("X-User-ID"))
		if err != nil {
			writeError(w, http.StatusUnauthorized, "missing or invalid X-User-ID header")
			return
		}
		next(w, r.WithContext(withUserID(r.Context(), id)))
	}
}

// statusRecorder remembers the status code a handler sent, for logging.
type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (rec *statusRecorder) WriteHeader(code int) {
	rec.status = code
	rec.ResponseWriter.WriteHeader(code)
}

// logRequests logs one line per request: method, path, status, duration.
func (s *Server) logRequests(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rec, r)
		s.logger.Info("request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rec.status,
			"duration", time.Since(start),
		)
	})
}

// recoverPanic turns a crash inside a handler into a 500 response
// instead of killing the connection.
func (s *Server) recoverPanic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if err := recover(); err != nil {
				s.logger.Error("panic in handler", "error", err, "path", r.URL.Path)
				writeError(w, http.StatusInternalServerError, "internal server error")
			}
		}()
		next.ServeHTTP(w, r)
	})
}