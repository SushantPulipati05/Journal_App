package api

import (
	"context"

	"github.com/google/uuid"
)

// contextKey is a private type, so no other package can clash with our keys.
type contextKey string

const userIDKey contextKey = "userID"

func withUserID(ctx context.Context, id uuid.UUID) context.Context {
	return context.WithValue(ctx, userIDKey, id)
}

func userIDFrom(ctx context.Context) uuid.UUID {
	id, _ := ctx.Value(userIDKey).(uuid.UUID)
	return id
}