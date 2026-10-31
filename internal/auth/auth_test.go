package auth

import (
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
)

const testSecret = "test-secret-that-is-at-least-32-characters-long"

func TestPasswordHashing(t *testing.T) {
	hash, err := HashPassword("correct horse")
	if err != nil {
		t.Fatalf("hash: %v", err)
	}
	if hash == "correct horse" {
		t.Fatal("hash must not equal the password")
	}
	if !CheckPassword(hash, "correct horse") {
		t.Error("right password was rejected")
	}
	if CheckPassword(hash, "wrong horse") {
		t.Error("wrong password was accepted")
	}
}

func TestValidatePassword(t *testing.T) {
	cases := []struct {
		password string
		ok       bool
	}{
		{"short", false},
		{"exactly8", true},
		{strings.Repeat("a", 72), true},
		{strings.Repeat("a", 73), false},
	}
	for _, c := range cases {
		err := ValidatePassword(c.password)
		if (err == nil) != c.ok {
			t.Errorf("ValidatePassword(len %d) error = %v, want ok=%v", len(c.password), err, c.ok)
		}
	}
}

func TestAccessTokenRoundTrip(t *testing.T) {
	m, err := NewTokenManager(testSecret, 15*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	userID := uuid.New()

	token, expires, err := m.IssueAccessToken(userID)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if time.Until(expires) < 14*time.Minute {
		t.Errorf("expiry too soon: %v", expires)
	}

	got, err := m.ParseAccessToken(token)
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got != userID {
		t.Errorf("user id = %s, want %s", got, userID)
	}
}

func TestAccessTokenRejections(t *testing.T) {
	m, _ := NewTokenManager(testSecret, 15*time.Minute)
	token, _, _ := m.IssueAccessToken(uuid.New())

	t.Run("tampered", func(t *testing.T) {
		if _, err := m.ParseAccessToken(token + "x"); err == nil {
			t.Error("tampered token was accepted")
		}
	})

	t.Run("different secret", func(t *testing.T) {
		other, _ := NewTokenManager(testSecret+"-other", 15*time.Minute)
		if _, err := other.ParseAccessToken(token); err == nil {
			t.Error("token signed with another secret was accepted")
		}
	})

	t.Run("expired", func(t *testing.T) {
		short, _ := NewTokenManager(testSecret, -time.Minute) // already expired
		old, _, _ := short.IssueAccessToken(uuid.New())
		if _, err := m.ParseAccessToken(old); err == nil {
			t.Error("expired token was accepted")
		}
	})

	t.Run("garbage", func(t *testing.T) {
		if _, err := m.ParseAccessToken("not.a.jwt"); err == nil {
			t.Error("garbage was accepted")
		}
	})
}

func TestShortSecretRejected(t *testing.T) {
	if _, err := NewTokenManager("too-short", time.Minute); err == nil {
		t.Error("short secret was accepted")
	}
}

func TestRefreshTokens(t *testing.T) {
	a, hashA, err := NewRefreshToken()
	if err != nil {
		t.Fatal(err)
	}
	b, _, _ := NewRefreshToken()

	if a == b {
		t.Error("two refresh tokens were identical")
	}
	if hashA == a {
		t.Error("hash must differ from the token")
	}
	if HashRefreshToken(a) != hashA {
		t.Error("hashing is not repeatable")
	}
}