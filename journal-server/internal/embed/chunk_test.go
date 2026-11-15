package embed

import (
	"strings"
	"testing"
)

func TestChunksJoinsShortParagraphs(t *testing.T) {
	got := Chunks("Monday", "Woke up late.\nWent to the gym.\n\nFelt good.")
	if len(got) != 1 || got[0] != "Woke up late.\nWent to the gym.\nFelt good." {
		t.Fatalf("got %q", got)
	}
}

func TestChunksSkipsPhotos(t *testing.T) {
	got := Chunks("t", "Before\n{{photo:11111111-2222-3333-4444-555555555555|60}}\nAfter")
	if len(got) != 1 || strings.Contains(got[0], "photo") {
		t.Fatalf("got %q", got)
	}
}

func TestChunksSplitsLongText(t *testing.T) {
	para := strings.TrimSpace(strings.Repeat("word ", 100))
	got := Chunks("t", para+"\n"+para+"\n"+para)
	if len(got) != 3 {
		t.Fatalf("want 3 chunks, got %d", len(got))
	}
	huge := strings.TrimSpace(strings.Repeat("w ", 500))
	got = Chunks("t", huge)
	for _, c := range got {
		if n := len(strings.Fields(c)); n > maxChunkWords {
			t.Fatalf("chunk has %d words", n)
		}
	}
	if len(got) != 3 {
		t.Fatalf("500 words -> want 3 chunks, got %d", len(got))
	}
}

func TestChunksTitleOnly(t *testing.T) {
	if got := Chunks("Just a title", "  \n"); len(got) != 1 || got[0] != "Just a title" {
		t.Fatalf("got %q", got)
	}
	if got := Chunks("", ""); got != nil {
		t.Fatalf("got %q", got)
	}
}

func TestChunksAreStable(t *testing.T) {
	body := "a b c\n" + strings.Repeat("x ", 300) + "\nend"
	a, b := Chunks("t", body), Chunks("t", body)
	if strings.Join(a, "|") != strings.Join(b, "|") {
		t.Fatal("same text must give the same chunks")
	}
}

func TestSnippet(t *testing.T) {
	if got := Snippet("short", 50); got != "short" {
		t.Fatal(got)
	}
	got := Snippet(strings.Repeat("hello ", 100), 40)
	if len([]rune(got)) > 41 || !strings.HasSuffix(got, "…") {
		t.Fatalf("got %q", got)
	}
}
