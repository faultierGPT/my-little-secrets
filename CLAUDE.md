# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Environment setup (do this first, every session)

The JDK and Gradle live under `~/tools`, not on the default PATH. Before ANY `gradle`/`java`/`jar`
command:

```bash
source "$HOME/tools/env.sh"   # JAVA_HOME=jdk-21.0.11, PATH gets gradle-9.6.1
```

Gradle 9.6.1 is also available via the committed wrapper (`./gradlew`). JDK 21 (Temurin) is required.

**This environment cannot:** build the Android APK (no Android SDK), or pixel-render JavaFX
(no fontconfig/X11/GL libs — `Font.getDefault()` fails). Both are real limitations, not bugs. CI
(`.github/workflows/`) covers what can't be built/run here.

## Common commands

```bash
./gradlew build                 # build + test all JVM modules (core, server, desktop, design)
./gradlew :core:test            # crypto core suite (includes one real 256 MiB Argon2id run; ~slow)
./gradlew :server:test          # server, against embedded H2 (no Docker needed)
./gradlew :desktop:test         # desktop controller, end-to-end vs an in-process Netty+H2 server
./gradlew :design:test          # design-token invariants (WCAG contrast, single accent, JSON round-trip)
./gradlew :design:run           # REGENERATE design artifacts after editing Tokens.kt/Renderers.kt
./gradlew :desktop:run          # launch the JavaFX app (needs a display; won't render here)

# Single test (JUnit 5):
./gradlew :core:test --tests "app.mls.core.jvm.BlockingBridgeTest"
./gradlew :desktop:test --tests "app.mls.desktop.VaultIntegrationTest"

./scripts/package-desktop.sh    # jpackage a self-contained desktop artifact (verified to work here)
docker compose up --build       # run the server with PostgreSQL
```

The `:android` module is **deliberately excluded** from `settings.gradle.kts` (AGP fails to configure
without an SDK, which would break every Gradle invocation). To work on it, add `include(":android")`
and have an SDK — see `android/README.md`.

## Architecture: one crypto core, two clients

The single most important design decision: **the cryptography is written, tested, and audited once**
in `core/` (Kotlin JVM library) and shared by both clients. Never reimplement crypto in a client.

```
core/        crypto + DTOs + Ktor API client + offline-first SyncEngine + encrypted cache + jvm/ bridge
server/      Ktor + Netty; stores ONLY ciphertext (PostgreSQL prod, H2 tests). app.mls.server.Embedded
             boots it in-process for tests.
design/      ONE Kotlin source of tokens (Tokens.kt) → generated Compose theme + JavaFX CSS + Java
             constants + tokens.json (Renderers.kt → Generate.kt). Output committed under generated/.
desktop/     Pure Java + JavaFX over core (verified end-to-end here).
android/     Kotlin + Compose over core (source only; not built here).
```

### How each client reaches the suspend API (key interop detail)

`MlsApi` and `SyncEngine.sync()` are `suspend`; `CryptoCore` and the local `SyncEngine`
list/save/delete are synchronous.

- **Desktop is pure Java** and can't call `suspend` functions, so it goes through
  `app.mls.core.jvm.BlockingApi` / `BlockingSync` (a `runBlocking` adapter added to `core`). Desktop
  also relies on `@JvmOverloads` on the `SyncEngine` and `KtorApiClient` constructors. Keep the
  desktop module 100% Java — do interop accommodations in `core`, not in `desktop`.
- **Android uses the suspend API directly** from coroutines (no bridge).

### libsodium binding is swappable

All crypto flows through the single file `core/.../crypto/Sodium.kt`. It defaults to
`LazySodiumJava` (desktop/server). Android calls `Sodium.useBinding(LazySodiumAndroid(...))` at
startup (`MlsApplication`) because lazysodium-java's natives don't cover Android ABIs. Don't hardcode
the binding.

### The session controller pattern

Both clients have a near-identical controller (`desktop/.../session/Vault.java`,
`android/.../session/AndroidVault.kt`) that owns the unlocked `accountKey` (as wipeable
`SecretBytes`) + an `EncryptedFileNoteStore` + a `SyncEngine`. Login flow:
`loginParams → deriveAuthKeyForLogin → login(authKey) → getAccountKey → unlockWithPassword`. Both
also persist a **non-secret** profile (public salt/params + the KEK-wrapped account key) to enable
offline unlock without the password.

### Design tokens are generated — never hand-edit `generated/`

`design/generated/**` (`MlsTokens.java`, `MlsDesignTokens.kt`, `mls-theme.css`, `tokens.json`) is the
build output of `:design:run`. The desktop and android modules consume it via `sourceSets` srcDirs.
Edit `Tokens.kt`/`Renderers.kt`, run `./gradlew :design:run`, and re-run `:design:test`. Note the
Java renderer flattens everything into one class, so color and width constants must not collide
(there's a regression test for duplicate identifiers).

## Non-negotiable security invariants (see SECURITY.md for the full contract)

- The server must NEVER receive the master password, master key, KEK, or account key — only the
  password-derived `authKey` (it stores `Argon2id(authKey)`) and ciphertext.
- Never invent crypto. Only libsodium primitives via `Sodium.kt`: Argon2id, XChaCha20-Poly1305 IETF
  (fresh random 24-byte nonce per encryption), `crypto_kdf`. The note id is bound as AEAD associated
  data.
- Keys/secrets are `SecretBytes` (wipeable `byte[]`), never `String`; wipe after use.
- Never log request bodies, ciphertext, tokens, `authKey`, or emails.
- Metadata is explicitly NOT protected, and a compromised client is out of scope — state this
  honestly, don't overclaim.

## Conventions

- Dependency versions are centralized and pinned in `gradle/libs.versions.toml`; verify against the
  registry rather than bumping from memory.
- Tests are JUnit 5 (`useJUnitPlatform()`).
- Releases: `scripts/` (key gen, packaging, GPG sign/verify) + tag `v*` triggers
  `.github/workflows/release.yml`; procedures in `RELEASE.md`. Signing keys come only from env/CI
  secrets or the gitignored `.signing/`.
