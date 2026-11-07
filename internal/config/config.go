// Package config loads server settings from environment variables.
package config

import (
	"errors"
	"os"
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
}

func Load() (Config, error) {
	cfg := Config{
		Env:            getEnv("APP_ENV", "development"),
		Port:           getEnv("PORT", "9090"),
		DatabaseURL:    os.Getenv("DATABASE_URL"),
		JWTSecret:      os.Getenv("JWT_SECRET"),
		AccessTokenTTL: 15 * time.Minute,
		TrustProxy:     os.Getenv("TRUST_PROXY") == "true",
		RunMigrations:  os.Getenv("RUN_MIGRATIONS") == "true",
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
