package vault

import (
	"bytes"
	"crypto/rand"
	"strings"
	"testing"
)

func testCipher(t *testing.T) *UserCipher {
	t.Helper()
	key := make([]byte, KeySize)
	rand.Read(key)
	c, err := newUserCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	return c
}

func TestEncryptRoundTrip(t *testing.T) {
	c := testCipher(t)
	for _, text := range []string{"a", "Today I went for a walk.", "आज मौसम अच्छा था", strings.Repeat("long ", 20000)} {
		stored := c.Encrypt(text, "id-1", "body")
		if !IsEncrypted(stored) || (len(text) > 10 && strings.Contains(stored, text[:10])) {
			t.Fatalf("not encrypted: %.40q", stored)
		}
		got, err := c.Decrypt(stored, "id-1", "body")
		if err != nil || got != text {
			t.Fatalf("round trip failed: %v", err)
		}
	}
}

func TestSameTextEncryptsDifferently(t *testing.T) {
	c := testCipher(t)
	if c.Encrypt("same", "id", "body") == c.Encrypt("same", "id", "body") {
		t.Fatal("two encryptions of the same text must differ (random nonce)")
	}
}

func TestEmptyStaysEmpty(t *testing.T) {
	c := testCipher(t)
	if got := c.Encrypt("", "id", "title"); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestCiphertextIsBoundToItsPlace(t *testing.T) {
	c := testCipher(t)
	stored := c.Encrypt("secret", "entry-A", "body")
	if _, err := c.Decrypt(stored, "entry-B", "body"); err == nil {
		t.Fatal("ciphertext copied to another entry must not decrypt")
	}
	if _, err := c.Decrypt(stored, "entry-A", "title"); err == nil {
		t.Fatal("ciphertext copied to another field must not decrypt")
	}
}

func TestTamperingIsDetected(t *testing.T) {
	c := testCipher(t)
	stored := []byte(c.Encrypt("secret", "id", "body"))
	i := len(stored) - 3
	if stored[i] == 'A' {
		stored[i] = 'B'
	} else {
		stored[i] = 'A'
	}
	if _, err := c.Decrypt(string(stored), "id", "body"); err == nil {
		t.Fatal("tampered ciphertext must not decrypt")
	}
}

func TestOtherUsersKeyCannotDecrypt(t *testing.T) {
	a, b := testCipher(t), testCipher(t)
	if _, err := b.Decrypt(a.Encrypt("secret", "id", "body"), "id", "body"); err == nil {
		t.Fatal("another user's key must not decrypt")
	}
}

func TestPlainTextPassesThrough(t *testing.T) {
	c := testCipher(t)
	got, err := c.Decrypt("old plain entry", "id", "body")
	if err != nil || got != "old plain entry" {
		t.Fatalf("got %q, %v", got, err)
	}
	var off *UserCipher
	if off.Encrypt("x", "id", "body") != "x" {
		t.Fatal("nil cipher must store plain text")
	}
	if _, err := off.Decrypt(c.Encrypt("x", "id", "body"), "id", "body"); err != ErrNoKey {
		t.Fatalf("want ErrNoKey, got %v", err)
	}
}

func TestWrapUnwrap(t *testing.T) {
	master := make([]byte, KeySize)
	rand.Read(master)
	m, _ := newAEAD(master)
	dataKey := make([]byte, KeySize)
	rand.Read(dataKey)
	wrapped := wrapKey(m, dataKey, "user-1")
	got, err := unwrapKey(m, wrapped, "user-1")
	if err != nil || !bytes.Equal(got, dataKey) {
		t.Fatalf("unwrap failed: %v", err)
	}
	if _, err := unwrapKey(m, wrapped, "user-2"); err == nil {
		t.Fatal("a wrapped key must only unwrap for its own user")
	}
	other := make([]byte, KeySize)
	rand.Read(other)
	o, _ := newAEAD(other)
	if _, err := unwrapKey(o, wrapped, "user-1"); err == nil {
		t.Fatal("wrong master key must fail")
	}
}

func TestHashIsKeyed(t *testing.T) {
	a, b := testCipher(t), testCipher(t)
	if !bytes.Equal(a.Hash("tired"), a.Hash("tired")) {
		t.Fatal("hash must be stable")
	}
	if bytes.Equal(a.Hash("tired"), b.Hash("tired")) {
		t.Fatal("different users must get different hashes")
	}
}

func TestParseMasterKey(t *testing.T) {
	if _, err := ParseMasterKey("not base64!!"); err == nil {
		t.Fatal("want error")
	}
	if _, err := ParseMasterKey("c2hvcnQ="); err == nil {
		t.Fatal("short key must fail")
	}
	if _, err := ParseMasterKey("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="); err != nil {
		t.Fatal(err)
	}
}
