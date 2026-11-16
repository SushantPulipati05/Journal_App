// Package embed turns journal text into embeddings: lists of numbers where
// texts with similar meaning end up close together. Semantic search compares
// the embedding of your question with the embeddings of every paragraph.
package embed

import (
	"regexp"
	"strings"
)

// Chunk sizes, in words. One vector per whole entry would blur its topics
// together; one per paragraph keeps "the exam" clearly about the exam. Tiny
// paragraphs are joined so a chunk carries enough meaning on its own.
const (
	minChunkWords = 60
	maxChunkWords = 220
)

var photoLine = regexp.MustCompile(`\{\{photo:[^}]*\}\}`)

// The app stores formatting as Markdown. Its markers say nothing about meaning, and
// they'd show up in search snippets, so they're removed first.
var (
	linePrefix = regexp.MustCompile(`^(#{1,2} |[-*] \[[ xX]\] |[-*] |\d{1,3}\. )`)
	emphasis   = regexp.MustCompile(`\*\*(\S(?:.*?\S)?)\*\*|\*(\S(?:[^*]*?\S)?)\*`)
)

func plainLine(line string) string {
	line = linePrefix.ReplaceAllString(line, "")
	return emphasis.ReplaceAllString(line, "$1$2")
}

// Chunks splits an entry's body into the pieces that get one embedding each.
// The result is always the same for the same text, so a chunk's index can be
// used later to cut the matching paragraph back out (search snippets).
// An entry with only a title becomes one chunk holding the title.
func Chunks(title, body string) []string {
	body = photoLine.ReplaceAllString(body, "")
	var paragraphs []string
	for _, line := range strings.Split(body, "\n") {
		if p := strings.Join(strings.Fields(plainLine(strings.TrimSpace(line))), " "); p != "" {
			paragraphs = append(paragraphs, p)
		}
	}
	if len(paragraphs) == 0 {
		if t := strings.Join(strings.Fields(title), " "); t != "" {
			return []string{t}
		}
		return nil
	}

	var chunks []string
	var current []string
	words := 0
	flush := func() {
		if len(current) > 0 {
			chunks = append(chunks, strings.Join(current, "\n"))
			current, words = nil, 0
		}
	}
	for _, p := range paragraphs {
		w := strings.Fields(p)
		// A very long paragraph is cut into pieces of maxChunkWords.
		for len(w) > maxChunkWords {
			flush()
			chunks = append(chunks, strings.Join(w[:maxChunkWords], " "))
			w = w[maxChunkWords:]
		}
		if words > 0 && words+len(w) > maxChunkWords {
			flush()
		}
		current = append(current, strings.Join(w, " "))
		words += len(w)
		if words >= minChunkWords {
			flush()
		}
	}
	flush()
	return chunks
}

// Snippet shortens a chunk for showing in search results.
func Snippet(chunk string, maxRunes int) string {
	r := []rune(strings.ReplaceAll(chunk, "\n", " "))
	if len(r) <= maxRunes {
		return string(r)
	}
	cut := string(r[:maxRunes])
	if i := strings.LastIndex(cut, " "); i > maxRunes/2 {
		cut = cut[:i]
	}
	return cut + "…"
}
