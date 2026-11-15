# Journal

**An offline-first journaling app for Android and desktop, synced through a Go backend.**
Open it and you're on a blank page with the cursor blinking. Write, add photos inline or dictate, and the entry
appears on your other devices. It works fully offline; sync catches up when the internet comes back.

> Built end to end by me: the Go API, the database design, the sync protocol and the Kotlin
> Compose Multiplatform client. It started as a project to learn Go.

<!-- Screenshots: add images to docs/ and link them here -->
<!-- ![Write screen](docs/write.png) ![Journals](docs/journals.png) ![Desktop](docs/desktop.png) -->

---

## Features

**Writing**
- Opens straight onto a blank entry. The first line is the title and everything below it is the body.
- **Photos inline** between paragraphs: pick from the gallery or take one with the camera. Resize by dragging the edges (aspect ratio kept). Photos are shrunk to 1600 px and EXIF data (location etc.) is stripped before saving.
- **Voice dictation**: words appear live as you speak, using the phone's speech recognition, in English and 9 Indian languages. Filler words ("um", "uh") and stuttered repeats are cleaned out. On Android 13+ it keeps listening across pauses, so words aren't lost between sentences. On Windows the mic button opens Windows voice typing.
- Autosave while typing, word count, adjustable text size and serif/sans fonts.

**Browsing**
- **Search by meaning:** "when was I stressed about placements" finds an entry that says "can't sleep, interviews next week", even with no words in common. Word search still runs on the device and works offline.
- Journals list with word search; on desktop, a **gallery** or **timeline** view with entries opening in a side sheet.
- Swipe to delete with **Undo**, and a **Trash** that keeps deleted entries for 30 days.
- Skeleton loaders and smooth transitions throughout.

**Privacy**
- **Entries are encrypted at rest** on the server (AES-256-GCM, a separate key per user). A leaked database or backup is unreadable, and deleting an account destroys its key, so even old backups can't be read.

**Accounts and sync**
- Email + password or **Sign in with Google** (Android and desktop).
- Password reset by emailed code, and account deletion.
- **Offline-first:** everything is saved locally first and synced in the background.
- **No lost edits:** if the same entry is edited on two devices, the changes are **merged line by line** instead of one overwriting the other.

**Platforms**
- Android and Windows desktop (installer built with Compose Desktop).
- One shared Kotlin codebase; an iOS target is set up but not yet tested.

---

## Architecture

```mermaid
flowchart LR
    subgraph Client["Kotlin Compose Multiplatform app (Android / Desktop)"]
        UI["Compose UI"] --> VM["ViewModels"]
        VM --> Local[("SQLite<br/>entries, photos, prefs")]
        Sync["Sync engine<br/>push / pull + 3-way merge"] --> Local
        PSync["Photo sync"] --> Local
    end

    Sync -- "HTTPS + JWT" --> API
    PSync -- "HTTPS + JWT" --> API

    subgraph Server["Go API on Railway"]
        API["net/http API<br/>auth, sync, attachments"] --> PG[("PostgreSQL")]
        API -- "presigned URLs" --> S3[("S3-compatible<br/>bucket")]
    end

    PSync -- "upload / download<br/>directly with presigned URL" --> S3
    API -- "private network" --> Ollama["Ollama<br/>EmbeddingGemma"]
    API -. "reset emails" .-> Mail["Resend"]
    API -. "verify ID token" .-> Google["Google Sign-In"]
```

### How sync works
- **IDs come from the device.** Every entry gets a UUID on the device when it's created, so entries can be written offline and never collide.
- **Push, then pull.** The client pushes local changes, then pulls everything that changed on the server since its last **cursor**.
- **Deletes are tombstones.** A deleted entry is kept as a "deleted" record, so the delete reaches every device.
- **Conflicts are merged, not overwritten.** Each entry stores the last version both sides agreed on (the *base*). When local and server edits meet, a **three-way merge** combines them line by line, and the merged result is pushed back.
- **Guards:** the server rejects timestamps more than 5 minutes in the future, so a device with a wrong clock can't "win" every conflict.

### How encryption works (envelope encryption)
- **Two layers of keys:** every user gets a random 256-bit **data key**. It encrypts their titles and bodies (AES-256-GCM). The data key is stored **wrapped**, i.e. encrypted with one **master key** that exists only in a Railway variable, never in the database.
- **Tamper-proof and tied to its place:** each value gets a fresh random nonce and is bound to its entry ID and field. A ciphertext copied into another row, or edited, fails to decrypt.
- **Invisible to the app:** the server encrypts on push and decrypts on pull, so the app is unchanged. Entries written before encryption was turned on are encrypted in the background at start-up.
- **Crypto-shredding:** deleting an account deletes its data key, so every copy of its entries becomes unreadable, including old backups.
- **Honest limits:** this protects the stored data, not against the server itself (it can decrypt). Photos rely on the storage provider's encryption at rest. End-to-end encryption is on the roadmap.

### How search by meaning works
- **Embeddings:** an open model (Google's **EmbeddingGemma**, served by **Ollama** as a private Railway service) turns text into a list of 768 numbers that captures its meaning. Similar meanings land close together.
- **Indexing in the background:** saving an entry queues a job in the same transaction. A Go worker leases jobs (`FOR UPDATE SKIP LOCKED`, retries with exponential backoff), decrypts the entry, splits it into paragraph-sized chunks and embeds only the chunks whose keyed hash changed. Chunk text is never stored; only vectors and an HMAC.
- **Searching:** the question is embedded, compared with every chunk of that user (dot product), and the best paragraph of each entry is decrypted as a snippet. The app shows these under "Similar in meaning", next to its own offline word matches.

### How photos work
- **Inside the entry:** a photo is stored as a marker line, `{{photo:<uuid>|<width%>}}`. Its position syncs with the entry text.
- **Upload:** the image file itself is uploaded **straight to object storage**. The API only hands out short-lived **presigned URLs** (15 minutes to upload, 10 to download), so image bytes never pass through the Go server.
- **Memory:** photos are decoded at display size, with a 48 MB in-memory cache, to keep memory low on older phones.
- **Cleanup:** photos no entry uses any more are cleaned up after a grace period, so "Undo" still works.

---

## Tech stack

| Area | Choices |
|---|---|
| **Backend** | Go 1.27, `net/http` (Go 1.22+ routing), PostgreSQL, **pgx** + **sqlc** (type-safe SQL), **goose** migrations |
| **Auth** | bcrypt (cost 12), JWT access tokens (15 min) + refresh tokens (30 days), Google ID-token verification |
| **Security** | AES-256-GCM envelope encryption at rest, per-IP rate limiting on auth routes, constant-time login (no user enumeration), presigned storage URLs |
| **Search** | EmbeddingGemma via Ollama (self-hosted), background embedding worker on a Postgres job table, cosine similarity in Go |
| **Storage** | S3-compatible bucket via **minio-go** |
| **Email** | Resend (password-reset codes) |
| **Client** | Kotlin Multiplatform, **Compose Multiplatform**, Koin (DI), Ktor (HTTP), AndroidX SQLite (bundled), WorkManager (background sync) |
| **Speech** | Android `SpeechRecognizer` (segmented sessions on Android 13+), Windows voice typing on desktop |
| **Infra** | Docker (multi-stage, distroless, non-root), Railway (health-checked deploys), GitHub Actions CI |
| **Testing** | Go tests against a real Postgres via **testcontainers**; Kotlin tests for the sync engine, text merge and API client |

---

## Repository layout

```
Journal/
├── journal-server/            Go backend
│   ├── cmd/api                HTTP server entry point
│   ├── cmd/migrate            migration runner
│   ├── internal/api           handlers: auth, sync, entries, attachments, rate limiting
│   ├── internal/auth          passwords, JWTs, Google token checks
│   ├── internal/db            sqlc-generated queries
│   ├── internal/storage       S3 presigned URLs
│   ├── internal/vault         encryption at rest (envelope encryption, per-user keys)
│   ├── internal/embed         chunking + the Ollama embedding client
│   ├── ollama/                the embedding model's Railway service (Dockerfile)
│   ├── migrations             SQL migrations (embedded in the binary)
│   └── Dockerfile, railway.toml, docker-compose.yml
├── JournalApp/                Kotlin Compose Multiplatform client
│   ├── shared/                all app logic and UI (commonMain + android/jvm/ios parts)
│   ├── androidApp/            Android entry point
│   ├── desktopApp/            Desktop entry point + Windows installer config
│   └── iosApp/                iOS entry point (not tested yet)
└── .github/workflows/         server CI: go vet, go test -race, Docker build
```

---

## Running it locally

### Backend
Requirements: Go 1.27+, Docker.

```bash
cd journal-server
docker compose up -d            # Postgres on localhost:5433, Ollama on :11434
docker compose exec ollama ollama pull embeddinggemma   # once, for search

# Windows PowerShell: load dev variables
. .\dev.ps1
# or set them yourself:
# DATABASE_URL=postgres://journal:journal@localhost:5433/journal?sslmode=disable
# JWT_SECRET=<any long random string>

go run ./cmd/migrate            # apply migrations
go run ./cmd/api                # API on :8080

go test -race ./...             # tests (starts a throwaway Postgres container)
```

`requests.http` has example requests you can run from the IDE.

**Environment variables**

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | Postgres connection string |
| `JWT_SECRET` | Signs access tokens |
| `RUN_MIGRATIONS` | `true` = run migrations at startup (used on Railway) |
| `TRUST_PROXY` | `true` behind a proxy, so rate limiting sees the real client IP |
| `GOOGLE_CLIENT_ID` | Comma-separated Google OAuth client IDs accepted for sign-in |
| `RESEND_API_KEY` | Sends password-reset emails |
| `S3_ENDPOINT`, `S3_REGION`, `S3_BUCKET`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` | Photo storage |
| `ENCRYPTION_MASTER_KEY` | Base64 of 32 random bytes (`openssl rand -base64 32`). Set = entries encrypted at rest. **Back it up: losing it loses every entry.** |
| `EMBEDDING_URL`, `EMBEDDING_MODEL` | Ollama address and model (default `embeddinggemma`). Set = search by meaning on |

### App
Requirements: Android Studio or IntelliJ IDEA, JDK 17+.

```bash
cd JournalApp
./gradlew :desktopApp:run                    # desktop
./gradlew :androidApp:assembleDebug          # Android debug APK
./gradlew :desktopApp:packageExe             # Windows installer
./gradlew :shared:jvmTest                    # shared tests
```

The server URL and Google client ID live in `shared/src/commonMain/kotlin/dev/sushnt/journal/ApiConfig.kt`.
Signing keys (`keystore.properties`, `*.jks`) and `google-desktop.properties` are kept out of git.

---

## API overview

| Method | Path | |
|---|---|---|
| `POST` | `/auth/signup`, `/auth/login`, `/auth/google`, `/auth/refresh`, `/auth/logout` | Accounts and tokens |
| `POST` | `/auth/password/forgot`, `/auth/password/reset` | Password reset by email code |
| `GET` / `DELETE` | `/me` | Profile, account deletion |
| `POST` | `/sync/push` | Send local changes |
| `GET` | `/sync/pull?cursor=` | Changes since the cursor |
| `POST` | `/attachments`, `/attachments/{id}/complete` | Get an upload URL, confirm the upload |
| `GET` | `/attachments/{id}/url`, `/attachments/pull` | Download URL, photo changes since a cursor |
| `DELETE` | `/attachments/{id}` | Remove a photo |
| `GET` | `/search?q=` | Entries closest in meaning, with the matching paragraph |
| `GET` | `/healthz` | Health check (used by Railway) |

---

## Roadmap
- **Better search:** full-text search (SQLite FTS5) with highlighted snippets.
- **Calendar view,** "On this day" and life *chapters* (named date ranges).
- **More from the embeddings:** a 3D map of related entries, automatic themes over time, "you've written about this before".
- **End-to-end encryption** with on-device search.
- **Mood and reflection:** gentle monthly trends.
- **Releases:** iOS and macOS builds, then the Play Store.
