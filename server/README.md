# server/ — Ktor sync server (ciphertext only)

Stores and syncs **opaque encrypted blobs**. It is architecturally incapable of reading note
content: it never receives the password, `masterKey`, `keyEncryptionKey`, or `accountKey`, and it
stores the login credential only as `Argon2id(authKey)`. See `../SECURITY.md`.

## Run it

```bash
# 1) one-command self-host (PostgreSQL + server)
cp .env.example .env        # edit MLS_DB_PASSWORD
docker compose up --build   # server on 127.0.0.1:8080

# 2) or run against your own Postgres without Docker
MLS_DB_URL=jdbc:postgresql://localhost:5432/mls MLS_DB_USER=mls MLS_DB_PASSWORD=… \
  ./gradlew :server:run

# tests (no Docker needed — uses embedded H2)
./gradlew :server:test
```

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/auth/register` | — | Store user record + wrapped blobs (`Argon2id(authKey)` only) |
| POST | `/auth/login/params` | — | Return `salt` + `kdfParams` for an email |
| POST | `/auth/login` | — | Verify `authKey`, issue session token |
| POST | `/auth/logout` | bearer | Revoke the presented session |
| GET | `/account/key` | bearer | Return `wrappedAccountKey` (+ recovery blob) |
| POST | `/account/password` | bearer | Re-wrap + rotate credential; invalidates all sessions |
| GET | `/notes?since=<ts>` | bearer | Encrypted notes (incl. tombstones) changed after `ts` |
| PUT | `/notes/{id}` | bearer | Upsert an encrypted note |
| DELETE | `/notes/{id}` | bearer | Soft-delete (tombstone; ciphertext cleared) |
| GET | `/health` | — | Liveness |

## Security posture (enforced in code, verified in tests)

- **Ciphertext only.** `ServerZeroKnowledgeTest` registers, syncs a note, then reads the raw DB
  rows and asserts the plaintext never appears and the credential is an Argon2id PHC string.
- **No secrets in logs.** `CallLogging` prints only `METHOD path -> status`; bodies, headers,
  query strings, tokens, and emails are never logged (`SECURITY.md` §5).
- **Auth on every note/account endpoint** via opaque bearer tokens; only `SHA-256(token)` is stored.
- **Rate limiting + lockout** per `email|ip` on the login endpoints (`AuthFlowTest`).
- **Security headers**: HSTS (when enabled), `X-Content-Type-Options`, `X-Frame-Options: DENY`,
  `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.
- **Oversized-payload rejection** (413) before reading the body; **note-id validation**.
- **CORS default-deny** (native clients don't need it; opt in via `MLS_CORS_HOSTS`).

## TLS

The container speaks plain HTTP and binds to loopback. **Terminate TLS at a reverse proxy** in
front (Caddy/nginx/Traefik) and forward to `127.0.0.1:8080`. Example (Caddy):

```
notes.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

Optional client-side **TLS certificate pinning** is called out as hardening in `SECURITY.md`.

## Configuration (env vars)

| Var | Default | Notes |
|---|---|---|
| `MLS_PORT` | `8080` | |
| `MLS_DB_URL` / `MLS_DB_USER` / `MLS_DB_PASSWORD` / `MLS_DB_DRIVER` | postgres@localhost | H2 auto-detected for tests |
| `MLS_TOKEN_TTL_SECONDS` | `3600` | Session token lifetime |
| `MLS_MAX_NOTE_BYTES` | `1048576` | Max ciphertext per note (1 MiB) |
| `MLS_HSTS` | `true` | Emit HSTS header |
| `MLS_CORS_HOSTS` | _(empty)_ | Comma-separated allowed browser origins |
| `MLS_LOGIN_MAX_ATTEMPTS` / `MLS_LOGIN_LOCKOUT_SECONDS` | `10` / `300` | Login throttle |

> Single-node self-host uses an in-memory login throttle. For multi-node, back it with Redis.
