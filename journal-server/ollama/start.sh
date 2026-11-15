#!/bin/sh
# Start Ollama, make sure the embedding model is downloaded, then keep running.
# With a Railway volume mounted at /root/.ollama the download happens only once.
set -e
MODEL="${EMBEDDING_MODEL:-embeddinggemma}"

ollama serve &
SERVER=$!

until ollama list >/dev/null 2>&1; do sleep 1; done
echo "pulling $MODEL (only slow the first time)"
ollama pull "$MODEL"
echo "ready: $MODEL"

wait $SERVER
