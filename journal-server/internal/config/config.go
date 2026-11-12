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

	// Photo storage in any S3-compatible bucket (Backblaze B2, Cloudflare R2...).
	// All five set = photos on; any missing = off.
	S3Endpoint        string
	S3Region          string
	S3AccessKeyID     string
	S3SecretAccessKey string
	S3Bucket          string
}

// PhotosEnabled reports whether all photo storage settings are present.
func (c Config) PhotosEnabled() bool {
	return c.S3Endpoint != "" && c.S3Region != "" && c.S3AccessKeyID != "" && c.S3SecretAccessKey != "" && c.S3Bucket != ""
}

func Load() (Config, error) {
	cfg := Config{
		Env:               getEnv("APP_ENV", "development"),
		Port:              getEnv("PORT", "9090"),
		DatabaseURL:       os.Getenv("DATABASE_URL"),
		JWTSecret:         os.Getenv("JWT_SECRET"),
		AccessTokenTTL:    15 * time.Minute,
		TrustProxy:        os.Getenv("TRUST_PROXY") == "true",
		RunMigrations:     os.Getenv("RUN_MIGRATIONS") == "true",
		GoogleClientIDs:   splitList(os.Getenv("GOOGLE_CLIENT_ID")),
		ResendAPIKey:      os.Getenv("RESEND_API_KEY"),
		EmailFrom:         getEnv("EMAIL_FROM", "Journal <onboarding@resend.dev>"),
		S3Endpoint:        os.Getenv("S3_ENDPOINT"),
		S3Region:          os.Getenv("S3_REGION"),
		S3AccessKeyID:     os.Getenv("S3_ACCESS_KEY_ID"),
		S3SecretAccessKey: os.Getenv("S3_SECRET_ACCESS_KEY"),
		S3Bucket:          os.Getenv("S3_BUCKET"),
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
