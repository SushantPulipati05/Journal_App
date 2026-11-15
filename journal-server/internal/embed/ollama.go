package embed

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"strings"
	"time"
)

// Client talks to an Ollama server running an embedding model (we use
// Google's open EmbeddingGemma). On Railway it runs as a separate service on
// the private network, so journal text never leaves our own servers.
type Client struct {
	baseURL string
	model   string
	http    *http.Client
}

// NewOllama: baseURL like "http://ollama.railway.internal:11434", model like "embeddinggemma".
func NewOllama(baseURL, model string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		model:   model,
		http:    &http.Client{Timeout: 90 * time.Second},
	}
}

// Model is stored next to every vector: vectors from different models can't
// be compared, so changing the model means re-embedding.
func (c *Client) Model() string { return c.model }

// EmbeddingGemma was trained with these prompts; using them improves results.
// Documents and questions get different ones on purpose.
func documentPrompt(title, chunk string) string {
	if strings.TrimSpace(title) == "" {
		title = "none"
	}
	return "title: " + title + " | text: " + chunk
}

func queryPrompt(q string) string { return "task: search result | query: " + q }

// EmbedDocuments returns one vector per chunk of an entry.
func (c *Client) EmbedDocuments(ctx context.Context, title string, chunks []string) ([][]float32, error) {
	inputs := make([]string, len(chunks))
	for i, ch := range chunks {
		inputs[i] = documentPrompt(title, ch)
	}
	return c.embed(ctx, inputs)
}

// EmbedQuery returns the vector of a search question.
func (c *Client) EmbedQuery(ctx context.Context, q string) ([]float32, error) {
	v, err := c.embed(ctx, []string{queryPrompt(q)})
	if err != nil {
		return nil, err
	}
	return v[0], nil
}

type embedRequest struct {
	Model    string   `json:"model"`
	Input    []string `json:"input"`
	Truncate bool     `json:"truncate"`
}

type embedResponse struct {
	Embeddings [][]float32 `json:"embeddings"`
}

func (c *Client) embed(ctx context.Context, inputs []string) ([][]float32, error) {
	if len(inputs) == 0 {
		return nil, nil
	}
	body, _ := json.Marshal(embedRequest{Model: c.model, Input: inputs, Truncate: true})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/api/embed", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("embed: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		msg, _ := io.ReadAll(io.LimitReader(resp.Body, 300))
		return nil, fmt.Errorf("embed: ollama answered %d: %s", resp.StatusCode, strings.TrimSpace(string(msg)))
	}
	var out embedResponse
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return nil, fmt.Errorf("embed: bad response: %w", err)
	}
	if len(out.Embeddings) != len(inputs) {
		return nil, errors.New("embed: wrong number of vectors in response")
	}
	for _, v := range out.Embeddings {
		normalize(v)
	}
	return out.Embeddings, nil
}

// normalize scales v to length 1, so similarity is a plain dot product.
func normalize(v []float32) {
	var sum float64
	for _, x := range v {
		sum += float64(x) * float64(x)
	}
	if sum == 0 {
		return
	}
	inv := float32(1 / math.Sqrt(sum))
	for i := range v {
		v[i] *= inv
	}
}

// Dot is the similarity of two normalized vectors: 1 = same meaning, ~0 = unrelated.
func Dot(a, b []float32) float32 {
	if len(a) != len(b) {
		return -1
	}
	var s float32
	for i := range a {
		s += a[i] * b[i]
	}
	return s
}
