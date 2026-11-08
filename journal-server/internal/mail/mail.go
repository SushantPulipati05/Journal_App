// Package mail sends the app's emails (only password-reset codes for now).
package mail

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"
)

// Mailer sends emails. It's an interface so tests (and local development) can
// use a fake instead of a real email service.
type Mailer interface {
	SendPasswordResetCode(ctx context.Context, to, code string) error
}

// Resend sends email through resend.com's HTTP API.
type Resend struct {
	apiKey string
	from   string // e.g. "Journal <no-reply@sushnt.dev>"
	client *http.Client
}

func NewResend(apiKey, from string) *Resend {
	return &Resend{apiKey: apiKey, from: from, client: &http.Client{Timeout: 10 * time.Second}}
}

func (m *Resend) SendPasswordResetCode(ctx context.Context, to, code string) error {
	text := fmt.Sprintf(
		"Your Journal password reset code is %s\n\n"+
			"It expires in 15 minutes. If you didn't ask to reset your password, ignore this email; your password stays the same.",
		code)
	html := fmt.Sprintf(`<p>Your Journal password reset code is</p>
<p style="font-size:28px;font-weight:bold;letter-spacing:6px">%s</p>
<p>It expires in 15 minutes. If you didn't ask to reset your password, ignore this email; your password stays the same.</p>`, code)

	body, err := json.Marshal(map[string]any{
		"from":    m.from,
		"to":      []string{to},
		"subject": "Your Journal reset code: " + code,
		"text":    text,
		"html":    html,
	})
	if err != nil {
		return err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, "https://api.resend.com/emails", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+m.apiKey)
	req.Header.Set("Content-Type", "application/json")

	resp, err := m.client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return fmt.Errorf("resend: HTTP %d: %s", resp.StatusCode, msg)
	}
	return nil
}

// LogMailer prints the code to the server log instead of emailing it.
// For local development only: never use it in production.
type LogMailer struct {
	Logger *slog.Logger
}

func (m LogMailer) SendPasswordResetCode(_ context.Context, to, code string) error {
	m.Logger.Info("DEV ONLY: password reset code (not emailed)", "to", to, "code", code)
	return nil
}
