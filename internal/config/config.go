// Package config loads server settings from environment variables.
package config

import (
	"errors"
	"os"
)

type Config struct {
	Env         string // "development" or "production"
	Port        string
	DatabaseURL string
}

func Load() (Config, error) {
	cfg := Config{
		Env:         getEnv("APP_ENV", "development"),
		Port:        getEnv("PORT", "8080"),
		DatabaseURL: os.Getenv("DATABASE_URL"),
	}
	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
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