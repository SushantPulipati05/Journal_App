// Package auth handles password hashing and login tokens.
// It knows nothing about HTTP or the database.
package auth

import (
	"errors"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

const (
	minPasswordLen = 8
	maxPasswordLen = 72 // bcrypt only uses the first 72 bytes
	bcryptCost     = 12
)

var ErrWeakPassword = errors.New("password must be 8 to 72 characters")

// ValidatePassword checks the length rules before we hash anything.
func ValidatePassword(password string) error {
	if utf8.RuneCountInString(password) < minPasswordLen || len(password) > maxPasswordLen {
		return ErrWeakPassword
	}
	return nil
}

// HashPassword turns a password into a bcrypt hash that is safe to store.
func HashPassword(password string) (string, error) {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcryptCost)
	if err != nil {
		return "", err
	}
	return string(hash), nil
}

// CheckPassword reports whether password matches the stored hash.
func CheckPassword(hash, password string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}