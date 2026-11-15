package embed

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestOllamaClient(t *testing.T) {
	var got embedRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/embed" {
			t.Errorf("path %s", r.URL.Path)
		}
		json.NewDecoder(r.Body).Decode(&got)
		vecs := make([][]float32, len(got.Input))
		for i := range vecs {
			vecs[i] = []float32{3, 4}
		}
		json.NewEncoder(w).Encode(embedResponse{Embeddings: vecs})
	}))
	defer srv.Close()

	c := NewOllama(srv.URL+"/", "embeddinggemma")
	vecs, err := c.EmbedDocuments(context.Background(), "", []string{"a", "b"})
	if err != nil || len(vecs) != 2 {
		t.Fatalf("%v %v", vecs, err)
	}
	if got.Model != "embeddinggemma" || !strings.HasPrefix(got.Input[0], "title: none | text: ") {
		t.Fatalf("request %+v", got)
	}
	if d := Dot(vecs[0], vecs[1]); d < 0.999 || d > 1.001 {
		t.Fatalf("vectors must be normalized, dot = %v", d)
	}
	q, err := c.EmbedQuery(context.Background(), "stress")
	if err != nil || len(q) != 2 || !strings.HasPrefix(got.Input[0], "task: search result | query: ") {
		t.Fatalf("query %v %v %+v", q, err, got)
	}
}

func TestOllamaError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "model not found", http.StatusNotFound)
	}))
	defer srv.Close()
	if _, err := NewOllama(srv.URL, "x").EmbedQuery(context.Background(), "q"); err == nil || !strings.Contains(err.Error(), "404") {
		t.Fatalf("want 404 error, got %v", err)
	}
}
