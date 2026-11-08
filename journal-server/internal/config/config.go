// Package config loads server settings from environment variables.
package config

import (
	"errors"
	"os"
	"strings"
	"time"
)

type Config struct {
	Env            string // "development" or "production"
	Port           string
	DatabaseURL    string
	JWTSecret      string
	AccessTokenTTL time.Duration

	// TrustProxy: set TRUST_PROXY=true only when running behind a load
	// balancer that sets X-Forwarded-For (Fly.io, Cloud Run).
	TrustProxy bool

	// RunMigrations: set RUN_MIGRATIONS=true to apply pending database
	// migrations every time the server starts (used in production).
	RunMigrations bool

	// GoogleClientIDs: our OAuth client IDs from Google Cloud Console, comma
	// separated in GOOGLE_CLIENT_ID (Web client for Android, Desktop client for
	// the desktop app). Empty = Google sign-in is turned off.
	GoogleClientIDs []string

	// Email for "forgot password" codes, sent through resend.com.
	// No key: codes are printed to the log in development, and the feature
	// is off in production.
	ResendAPIKey string
	EmailFrom    string
}

func Load() (Config, error) {
	cfg := Config{
		Env:             getEnv("APP_ENV", "development"),
		Port:            getEnv("PORT", "9090"),
		DatabaseURL:     os.Getenv("DATABASE_URL"),
		JWTSecret:       os.Getenv("JWT_SECRET"),
		AccessTokenTTL:  15 * time.Minute,
		TrustProxy:      os.Getenv("TRUST_PROXY") == "true",
		RunMigrations:   os.Getenv("RUN_MIGRATIONS") == "true",
		GoogleClientIDs: splitList(os.Getenv("GOOGLE_CLIENT_ID")),
		ResendAPIKey:    os.Getenv("RESEND_API_KEY"),
		EmailFrom:       getEnv("EMAIL_FROM", "Journal <onboarding@resend.dev>"),
	}
	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}
	if len(cfg.JWTSecret) < 32 {
		return Config{}, errors.New("JWT_SECRET is required and must be at least 32 characters")
	}
	return cfg, nil
}

// getEnv returns the variable's value, or fallback if it's unset.
func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// splitList turns "a, b,,c" into ["a" "b" "c"].
func splitList(s string) []string {
	var out []string
	for _, part := range strings.Split(s, ",") {
		if p := strings.TrimSpace(part); p != "" {
			out = append(out, p)
		}
	}
	return out
}
