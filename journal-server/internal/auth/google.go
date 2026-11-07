package auth

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"time"

	"google.golang.org/api/idtoken"
	"google.golang.org/api/option"
)

// GoogleIdentity is who Google says the user is, taken from a verified ID token.
type GoogleIdentity struct {
	Subject       string // Google's permanent, unique ID for the account ("sub")
	Email         string
	EmailVerified bool
	Name          string
}

// GoogleVerifier checks a Google ID token. It's an interface so tests can use a fake.
type GoogleVerifier interface {
	Verify(ctx context.Context, idToken string) (GoogleIdentity, error)
}

var ErrInvalidGoogleToken = errors.New("invalid google token")

type googleVerifier struct {
	validator *idtoken.Validator
	audience  string
}

// NewGoogleVerifier accepts only tokens issued for audience, which is the
// "Web application" OAuth client ID from Google Cloud Console. The Android app
// asks Google for a token addressed to that same ID.
func NewGoogleVerifier(ctx context.Context, audience string) (GoogleVerifier, error) {
	// A plain HTTP client: verifying needs only Google's public keys, no credentials.
	v, err := idtoken.NewValidator(ctx, option.WithHTTPClient(&http.Client{Timeout: 10 * time.Second}))
	if err != nil {
		return nil, err
	}
	return &googleVerifier{validator: v, audience: audience}, nil
}

// Verify checks the signature (against Google's published keys), expiry,
// audience and issuer, then returns the identity inside.
func (g *googleVerifier) Verify(ctx context.Context, token string) (GoogleIdentity, error) {
	p, err := g.validator.Validate(ctx, token, g.audience)
	if err != nil {
		return GoogleIdentity{}, fmt.Errorf("%w: %v", ErrInvalidGoogleToken, err)
	}
	if p.Issuer != "accounts.google.com" && p.Issuer != "https://accounts.google.com" {
		return GoogleIdentity{}, fmt.Errorf("%w: wrong issuer %q", ErrInvalidGoogleToken, p.Issuer)
	}
	email, _ := p.Claims["email"].(string)
	verified, _ := p.Claims["email_verified"].(bool)
	name, _ := p.Claims["name"].(string)
	if p.Subject == "" || email == "" {
		return GoogleIdentity{}, fmt.Errorf("%w: missing sub or email", ErrInvalidGoogleToken)
	}
	return GoogleIdentity{Subject: p.Subject, Email: email, EmailVerified: verified, Name: name}, nil
}
