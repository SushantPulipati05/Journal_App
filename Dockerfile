# ---- Stage 1: build ----
# The big Go image compiles our code. None of it ships to production.
FROM golang:1.27-alpine AS build
WORKDIR /src

# Copy go.mod/go.sum first so Docker caches the dependency download
# and only repeats it when dependencies change.
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# CGO off = a fully static binary with no system libraries needed.
# -trimpath and -s -w make the binary smaller and drop local file paths.
ENV CGO_ENABLED=0
RUN go build -trimpath -ldflags="-s -w" -o /out/api ./cmd/api \
 && go build -trimpath -ldflags="-s -w" -o /out/migrate ./cmd/migrate

# ---- Stage 2: run ----
# Distroless: no shell, no package manager, nothing to attack except our binary.
# "nonroot" runs the process as an unprivileged user.
FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/api /api
COPY --from=build /out/migrate /migrate

ENV APP_ENV=production
EXPOSE 8080
ENTRYPOINT ["/api"]
