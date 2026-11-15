package vault

import (
	"context"
	"crypto/cipher"
	"crypto/rand"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	"github.com/SushantPulipati05/journal-server/internal/db"
)

// masterKeyVersion is stored next to every wrapped key. When the master key
// is ever replaced, version 2 keys can be re-wrapped without touching entries.
const masterKeyVersion = 1

// cacheFor is how long an unwrapped user key stays in memory.
const cacheFor = 10 * time.Minute

// Keyring hands out each user's UserCipher, creating their data key the first
// time it's needed. A nil *Keyring means encryption is off.
type Keyring struct {
	db     db.DBTX
	master cipher.AEAD

	mu    sync.Mutex
	cache map[uuid.UUID]cachedCipher
}

type cachedCipher struct {
	cipher *UserCipher
	until  time.Time
}

// NewKeyring uses masterKey (32 bytes, see ParseMasterKey) to protect user keys.
func NewKeyring(conn db.DBTX, masterKey []byte) (*Keyring, error) {
	master, err := newAEAD(masterKey)
	if err != nil {
		return nil, err
	}
	return &Keyring{db: conn, master: master, cache: make(map[uuid.UUID]cachedCipher)}, nil
}

// ForUser returns the user's cipher (nil, nil when encryption is off).
func (k *Keyring) ForUser(ctx context.Context, userID uuid.UUID) (*UserCipher, error) {
	if k == nil {
		return nil, nil
	}
	now := time.Now()
	k.mu.Lock()
	if c, ok := k.cache[userID]; ok && now.Before(c.until) {
		k.mu.Unlock()
		return c.cipher, nil
	}
	k.mu.Unlock()

	dataKey, err := k.loadOrCreate(ctx, userID)
	if err != nil {
		return nil, err
	}
	c, err := newUserCipher(dataKey)
	if err != nil {
		return nil, err
	}

	k.mu.Lock()
	k.cache[userID] = cachedCipher{cipher: c, until: now.Add(cacheFor)}
	// Drop expired keys now and then, so memory doesn't grow forever.
	if len(k.cache) > 1000 {
		for id, cc := range k.cache {
			if now.After(cc.until) {
				delete(k.cache, id)
			}
		}
	}
	k.mu.Unlock()
	return c, nil
}

// Forget removes a user's key from memory (account deleted).
func (k *Keyring) Forget(userID uuid.UUID) {
	if k == nil {
		return
	}
	k.mu.Lock()
	delete(k.cache, userID)
	k.mu.Unlock()
}

func (k *Keyring) loadOrCreate(ctx context.Context, userID uuid.UUID) ([]byte, error) {
	wrapped, version, err := k.load(ctx, userID)
	if errors.Is(err, pgx.ErrNoRows) {
		// First time: make a random key for this user and store it wrapped.
		// If two requests race here, ON CONFLICT keeps the first one and both
		// read that one back.
		fresh := make([]byte, KeySize)
		if _, err := rand.Read(fresh); err != nil {
			return nil, err
		}
		_, err = k.db.Exec(ctx,
			`INSERT INTO user_keys (user_id, wrapped_key, master_key_version) VALUES ($1, $2, $3)
			 ON CONFLICT (user_id) DO NOTHING`,
			userID, wrapKey(k.master, fresh, userID.String()), masterKeyVersion)
		if err != nil {
			return nil, fmt.Errorf("vault: store user key: %w", err)
		}
		wrapped, version, err = k.load(ctx, userID)
	}
	if err != nil {
		return nil, fmt.Errorf("vault: load user key: %w", err)
	}
	if version != masterKeyVersion {
		return nil, fmt.Errorf("vault: user key wrapped with master key v%d, this server has v%d", version, masterKeyVersion)
	}
	return unwrapKey(k.master, wrapped, userID.String())
}

func (k *Keyring) load(ctx context.Context, userID uuid.UUID) ([]byte, int, error) {
	var wrapped []byte
	var version int
	err := k.db.QueryRow(ctx,
		`SELECT wrapped_key, master_key_version FROM user_keys WHERE user_id = $1`, userID,
	).Scan(&wrapped, &version)
	return wrapped, version, err
}
