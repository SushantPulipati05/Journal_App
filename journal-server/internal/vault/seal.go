// Package vault encrypts journal text before it is written to the database.
//
// It uses "envelope encryption": every user has their own random data key,
// and that key is stored encrypted ("wrapped") with one master key that lives
// only in an environment variable. Someone who steals the database (or a
// backup) gets wrapped keys and scrambled text, both useless without the
// master key. Deleting a user's key makes all their entries unreadable for
// good, backups included.
//
// Everything is AES-256-GCM, which also detects tampering: a modified
// ciphertext fails to decrypt instead of returning garbage.
package vault

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
)

// prefix marks an encrypted value. Text without it is old, not-yet-encrypted
// data and is returned as is, so the switch to encryption needs no downtime.
const prefix = "enc:v1:"

// KeySize is the length of every key: 32 bytes = AES-256.
const KeySize = 32

// ErrNoKey means a value is encrypted but this server has no master key.
var ErrNoKey = errors.New("vault: value is encrypted but ENCRYPTION_MASTER_KEY is not set")

// ParseMasterKey reads the master key from its base64 form
// (make one with: openssl rand -base64 32).
func ParseMasterKey(encoded string) ([]byte, error) {
	encoded = strings.TrimSpace(encoded)
	key, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		key, err = base64.RawStdEncoding.DecodeString(encoded)
	}
	if err != nil {
		return nil, errors.New("ENCRYPTION_MASTER_KEY must be base64 (make one with: openssl rand -base64 32)")
	}
	if len(key) != KeySize {
		return nil, fmt.Errorf("ENCRYPTION_MASTER_KEY must be %d bytes, got %d", KeySize, len(key))
	}
	return key, nil
}

// IsEncrypted reports whether a stored value is ciphertext.
func IsEncrypted(stored string) bool { return strings.HasPrefix(stored, prefix) }

func newAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != KeySize {
		return nil, fmt.Errorf("vault: key must be %d bytes", KeySize)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(block)
}

// seal encrypts with a fresh random nonce and returns nonce | ciphertext | tag.
// aad ("additional data") isn't stored but must match when opening: it ties
// the ciphertext to its place, so it can't be copied into another row.
func seal(aead cipher.AEAD, plaintext, aad []byte) []byte {
	nonce := make([]byte, aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		panic("vault: no randomness available: " + err.Error()) // never happens on a working OS
	}
	return aead.Seal(nonce, nonce, plaintext, aad)
}

func open(aead cipher.AEAD, data, aad []byte) ([]byte, error) {
	n := aead.NonceSize()
	if len(data) < n+aead.Overhead() {
		return nil, errors.New("vault: ciphertext too short")
	}
	return aead.Open(nil, data[:n], data[n:], aad)
}

// UserCipher encrypts and decrypts one user's text. A nil *UserCipher means
// "encryption is off": values are stored and returned as plain text.
type UserCipher struct {
	aead   cipher.AEAD
	macKey []byte
}

func newUserCipher(dataKey []byte) (*UserCipher, error) {
	aead, err := newAEAD(dataKey)
	if err != nil {
		return nil, err
	}
	// A separate key for hashing, derived from the data key, so the same key
	// is never used for two different jobs.
	mac := hmac.New(sha256.New, dataKey)
	mac.Write([]byte("journal: chunk hash key"))
	return &UserCipher{aead: aead, macKey: mac.Sum(nil)}, nil
}

func fieldAAD(entryID, field string) []byte { return []byte("entry:" + entryID + ":" + field) }

// Encrypt turns a field of an entry (field = "title" or "body") into stored text.
// Empty stays empty: there's nothing to hide, and deleted entries stay blank.
func (c *UserCipher) Encrypt(plaintext, entryID, field string) string {
	if c == nil || plaintext == "" {
		return plaintext
	}
	sealed := seal(c.aead, []byte(plaintext), fieldAAD(entryID, field))
	return prefix + base64.RawStdEncoding.EncodeToString(sealed)
}

// Decrypt is the opposite of Encrypt. Plain (not yet encrypted) text is
// returned unchanged.
func (c *UserCipher) Decrypt(stored, entryID, field string) (string, error) {
	if !IsEncrypted(stored) {
		return stored, nil
	}
	if c == nil {
		return "", ErrNoKey
	}
	data, err := base64.RawStdEncoding.DecodeString(stored[len(prefix):])
	if err != nil {
		return "", fmt.Errorf("vault: bad ciphertext encoding: %w", err)
	}
	plain, err := open(c.aead, data, fieldAAD(entryID, field))
	if err != nil {
		return "", fmt.Errorf("vault: cannot decrypt %s of entry %s: %w", field, entryID, err)
	}
	return string(plain), nil
}

// Hash is a keyed fingerprint of some text (HMAC-SHA256). It tells whether a
// paragraph changed without storing it, and unlike a plain hash nobody can
// guess short paragraphs ("ok", "tired") by hashing candidates.
func (c *UserCipher) Hash(text string) []byte {
	if c == nil {
		sum := sha256.Sum256([]byte(text))
		return sum[:]
	}
	mac := hmac.New(sha256.New, c.macKey)
	mac.Write([]byte(text))
	return mac.Sum(nil)
}

// wrapKey / unwrapKey protect a user's data key with the master key.
func wrapKey(master cipher.AEAD, dataKey []byte, userID string) []byte {
	return seal(master, dataKey, []byte("user-key:"+userID))
}

func unwrapKey(master cipher.AEAD, wrapped []byte, userID string) ([]byte, error) {
	key, err := open(master, wrapped, []byte("user-key:"+userID))
	if err != nil {
		return nil, fmt.Errorf("vault: cannot unwrap key of user %s (wrong ENCRYPTION_MASTER_KEY?): %w", userID, err)
	}
	return key, nil
}
