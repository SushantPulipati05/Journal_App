package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/SushantPulipati05/journal-server/internal/api"
	"github.com/SushantPulipati05/journal-server/internal/auth"
	"github.com/SushantPulipati05/journal-server/internal/config"
	"github.com/SushantPulipati05/journal-server/internal/dbmigrate"
	"github.com/SushantPulipati05/journal-server/internal/mail"
	"github.com/SushantPulipati05/journal-server/internal/storage"
)

func main() {
	if err := run(); err != nil {
		slog.Error("server stopped with error", "error", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}

	// Readable logs while developing, JSON logs in production.
	var logger *slog.Logger
	if cfg.Env == "production" {
		logger = slog.New(slog.NewJSONHandler(os.Stdout, nil))
	} else {
		logger = slog.New(slog.NewTextHandler(os.Stdout, nil))
	}

	// ctx is cancelled when you press Ctrl+C, or when the host sends SIGTERM.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	pool, err := pgxpool.New(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil {
		return err
	}
	logger.Info("connected to database")

	if cfg.RunMigrations {
		logger.Info("applying database migrations")
		if err := dbmigrate.Up(ctx, cfg.DatabaseURL); err != nil {
			return fmt.Errorf("migrations: %w", err)
		}
		logger.Info("database is up to date")
	}

	tokens, err := auth.NewTokenManager(cfg.JWTSecret, cfg.AccessTokenTTL)
	if err != nil {
		return fmt.Errorf("token manager: %w", err)
	}

	server := api.NewServer(pool, logger, tokens, cfg.TrustProxy)
	if len(cfg.GoogleClientIDs) > 0 {
		google, err := auth.NewGoogleVerifier(ctx, cfg.GoogleClientIDs)
		if err != nil {
			return fmt.Errorf("google verifier: %w", err)
		}
		server.WithGoogle(google)
		logger.Info("google sign-in enabled", "client_ids", len(cfg.GoogleClientIDs))
	} else {
		logger.Warn("GOOGLE_CLIENT_ID not set: google sign-in is disabled")
	}

	switch {
	case cfg.ResendAPIKey != "":
		server.WithMailer(mail.NewResend(cfg.ResendAPIKey, cfg.EmailFrom))
		logger.Info("password reset emails enabled", "from", cfg.EmailFrom)
	case cfg.Env != "production":
		server.WithMailer(mail.LogMailer{Logger: logger})
		logger.Warn("RESEND_API_KEY not set: reset codes will be printed to this log (development only)")
	default:
		logger.Warn("RESEND_API_KEY not set: forgot password is disabled")
	}

	if cfg.PhotosEnabled() {
		store, err := storage.NewS3(cfg.S3Endpoint, cfg.S3Region, cfg.S3AccessKeyID, cfg.S3SecretAccessKey, cfg.S3Bucket)
		if err != nil {
			return fmt.Errorf("photo storage: %w", err)
		}
		server.WithStorage(store)
		logger.Info("photo storage enabled", "endpoint", cfg.S3Endpoint, "bucket", cfg.S3Bucket)
	} else {
		logger.Warn("S3_* settings missing: photos are disabled")
	}

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           server.Routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	// Run the server in the background so main can wait for a shutdown signal.
	serverErr := make(chan error, 1)
	go func() {
		logger.Info("server listening", "port", cfg.Port, "env", cfg.Env)
		serverErr <- srv.ListenAndServe()
	}()

	// Wait for whichever happens first: the server fails, or we're told to stop.
	select {
	case err := <-serverErr:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		logger.Info("shutting down...")
	}

	// Give in-flight requests up to 10 seconds to finish.
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelShutdown()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		return err
	}
	logger.Info("server stopped cleanly")
	return nil
}
