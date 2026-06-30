# my-little-secrets

A **zero-knowledge, end-to-end-encrypted notes system**. Notes are encrypted and decrypted only
on your own devices; the server stores opaque ciphertext it is architecturally incapable of
reading. Lose the master password (with no recovery key) and the data is cryptographically gone —
by design.

> **Read [`SECURITY.md`](SECURITY.md) first.** It is the canonical threat model and crypto
> contract. [`ARCHITECTURE.md`](ARCHITECTURE.md) covers structure; [`RELEASE.md`](RELEASE.md)
> covers signing/verification.

## Components

| Module | What it is | Status |
|---|---|---|
| [`core/`](core/) | Shared **Kotlin crypto core** — the security boundary, used by both clients | ✅ built + tested |
| [`server/`](server/) | **Ktor** sync server, stores only ciphertext (PostgreSQL; H2 for tests) | ✅ built + tested |
| `core/` networking | Typed API client + offline-first sync + encrypted cache | ✅ built + tested |
| [`design/`](design/) | Toolkit-neutral design tokens → Compose theme + JavaFX CSS, one source | ✅ built + tested |
| [`desktop/`](desktop/) | **Pure Java + JavaFX** client (GPG-signed artifacts) | ✅ built + end-to-end tested |
| [`android/`](android/) | **Jetpack Compose** client (signed APK) | 📝 source complete (needs an Android SDK to build) |

The `desktop/` client reaches the core's suspend API through a small blocking adapter in
`core` (`app.mls.core.jvm`), keeping the desktop module 100% Java. The `android/` client uses the
suspend API directly from coroutines. `android/` is intentionally **not** in the root
`settings.gradle.kts` (the Android Gradle Plugin can't configure without an SDK, which would break
the other modules' builds) — see [`android/README.md`](android/README.md).

## Crypto in one box (full detail in `SECURITY.md`)

```
masterKey        = Argon2id(password, salt, kdfParams)        (never leaves device)
authKey          = crypto_kdf(masterKey, "auth")             (server stores only Argon2id(authKey))
keyEncryptionKey = crypto_kdf(masterKey, "kek")              (never leaves device)
accountKey       = random 32 bytes                           (the real data key)
wrappedAccountKey= XChaCha20Poly1305(accountKey, kek)
noteCiphertext   = XChaCha20Poly1305(JSON{title,body,tags}, accountKey)
```

All primitives are **libsodium** (via lazysodium). Nothing is hand-rolled.

## Build & test

```bash
./gradlew test                  # crypto core + server suites (real libsodium, embedded H2)
./gradlew :core:test            # crypto core only
./gradlew :server:test          # server only
./gradlew :desktop:test         # desktop controller, end-to-end vs an in-process server
./gradlew :design:test          # design-token invariants (WCAG contrast, single accent, …)
./gradlew :desktop:run          # launch the JavaFX desktop app
./scripts/package-desktop.sh    # self-contained desktop artifact (JavaFX + JRE bundled)
docker compose up --build       # run the server locally with PostgreSQL
```

CI builds + tests every module on push/PR and compiles the Android client; tagging `v*` runs the
signed-release pipeline. See [`RELEASE.md`](RELEASE.md) for signing and **how to verify a download**.

Toolchain: JDK 21, Gradle via the committed wrapper (`./gradlew`). Dependency versions are
pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml), verified against current
stable at build time.

## What's protected vs. not (honestly)

- **Protected:** note titles/bodies/tags, the master password, and every key derived from it.
- **Not protected:** metadata — that an account exists, the email, note count, approximate
  ciphertext sizes, timestamps — and a compromised client device. E2EE defends against a hostile
  server, not malware on your own phone/laptop.
