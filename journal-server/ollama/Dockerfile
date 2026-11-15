# The embedding model for semantic search, as its own Railway service.
# Ollama serves the model over HTTP; the Go API calls it on Railway's private
# network (http://ollama.railway.internal:11434). Never give this service a
# public domain: it has no login.
FROM ollama/ollama:latest

# Listen on every address (Railway's private network uses IPv6), not just localhost.
ENV OLLAMA_HOST=[::]:11434
# Keep the model loaded for 30 minutes after the last request, then free the memory.
ENV OLLAMA_KEEP_ALIVE=30m

COPY start.sh /start.sh
ENTRYPOINT ["/bin/sh", "/start.sh"]
