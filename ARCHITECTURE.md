# ARCHITECTURE.md

## Overview

`my-little-secrets` is a zero-knowledge, end-to-end-encrypted notes system. Plaintext exists
**only** on the two client devices; the server stores opaque ciphertext and syncs it between
devices. See `SECURITY.md` for the threat model and crypto contract — this document covers
**structure and the engineering decisions**, not the security model.

## Module layout

```
my-little-secrets/
├── core/        Kotlin JVM library — the security boundary. Crypto, models, API
│                client + sync + encrypted local cache, + a blocking adapter for Java.
├── server/      Ktor + PostgreSQL. Moves and stores ciphertext only.            (Phase 2)
├── desktop/     Pure Java + JavaFX client over :core (as a jar).        ✅ built + e2e-tested
├── android/     Jetpack Compose (Kotlin) client over :core.        📝 source (needs Android SDK)
├── design/      Toolkit-neutral design tokens + per-toolkit theme mappers.   ✅ built + tested
├── scripts/     Keystore/GPG generation, build, sign, verify.                   (Phase 6)
├── SECURITY.md  Threat model, crypto scheme, signing/verification.
├── ARCHITECTURE.md
└── RELEASE.md   Build, sign, verify a release.                                  (Phase 6)
```

## The one decision that matters most: one crypto core, shared

The single most important security decision is to **write the cryptographic core once, test
it once, audit it once, and share it across both clients.** Writing E2EE crypto twice in two
languages is how these apps get subtly broken. So `core/` is the security boundary and the
apps are deliberately thin UI over it.

### Consequence of the "pure Java + JavaFX desktop" choice

The desktop UI is **pure Java + JavaFX** (your call), while Android is **Kotlin + Compose**.
These are different UI toolkits, so they cannot share a single Compose UI. They **can** and
**do** share the same compiled crypto core:

- `core/` is a **Kotlin JVM library**. Kotlin compiles to standard JVM bytecode that is fully
  interoperable with Java, so the JavaFX desktop app consumes `core` as an ordinary jar, and
  the Android app consumes the same source/artifact. The crypto is written and audited exactly
  once.
- libsodium is reached through the **lazysodium** binding. Each client supplies the platform
  native library: `lazysodium-java` (JNA, bundled `.so`) on desktop, `lazysodium-android` on
  Android. The `core` code is identical; only the injected native binding differs.
- The `design/` layer is therefore **toolkit-neutral tokens** (colors, type scale, spacing,
  icon SVGs) with two thin theme implementations generated from the same values: a Compose
  `Theme` for Android and a JavaFX **CSS** stylesheet for desktop. One source of truth, two
  renderers — so both clients look identical without sharing a UI toolkit.

> The crypto contract is identical regardless of this choice; only the desktop UI language and
> the design-token delivery mechanism changed versus the all-Compose alternative.

## `core/` internal structure (Phase 1)

```
core/src/main/kotlin/app/mls/core/
├── crypto/
│   ├── Sodium.kt          The ONLY file that calls libsodium. Audit boundary.
│   ├── SecretBytes.kt     Wipeable byte[] key holder (never String).
│   ├── CryptoScheme.kt    Versions, crypto_kdf contexts, AEAD associated-data labels.
│   ├── KdfParams.kt       Argon2id profiles (DEFAULT 256 MiB, SENSITIVE, MOBILE, TEST_FAST).
│   ├── Encoding.kt        Base64 (wire) + Base32 (recovery code).
│   ├── KeyHierarchy.kt    deriveMasterKey / deriveAuthKey / deriveKek / deriveRecoveryWrapKey.
│   ├── AccountKeyCrypto.kt generate / wrap / unwrap (+ recovery variants).
│   ├── NoteCrypto.kt      encrypt / decrypt a NotePayload (note id bound as AAD).
│   ├── AuthVerifier.kt    Server-side Argon2id(authKey) PHC verifier (constant-time).
│   ├── RecoveryCode.kt    256-bit recovery secret, Base32 display/parse.
│   ├── Sealed.kt          (ciphertext, nonce, schemeVersion) ⇄ wire EncryptedBlob.
│   └── CryptoCore.kt      High-level facade: register / login / unlock / rewrap.
└── model/
    ├── NotePayload.kt     Plaintext note { title, body, tags, schemaVersion } (client-only).
    └── EncryptedBlob.kt   Wire form of one AEAD output (base64).
```

`core/` also contains `api/` (Ktor client), `sync/` (pull-since/push, tombstones, client-side
conflict resolution), `store/` (encrypted offline cache), and `jvm/` — a small **blocking adapter**
(`BlockingApi`, `BlockingSync`) that runs the suspending API to completion for the pure-Java desktop
client, which can't call Kotlin `suspend` functions directly.

## Clients

Both clients are thin UI over the same `core` session primitives; the controller logic is nearly
identical, differing only in how each reaches the suspend API.

```
desktop/  (pure Java + JavaFX)                  android/  (Kotlin + Compose)
  session/Vault.java   controller, blocking       session/AndroidVault.kt  controller, coroutines
  ui/*.java            JavaFX views + CSS theme    ui/*.kt                  Compose screens + theme
  reaches core via app.mls.core.jvm.Blocking*      reaches core's suspend API directly
  design tokens: generated MlsTokens.java + CSS    design tokens: generated MlsDesignTokens.kt
```

- **Desktop** is verified end-to-end here: `VaultIntegrationTest` drives the Java controller against
  a real in-process Netty+H2 server (`app.mls.server.Embedded`) over a loopback socket —
  register → encrypt → sync → second-device pull → decrypt → offline-unlock → wrong-password reject.
- **Android** reuses `core` unchanged; `Sodium.useBinding(...)` installs `lazysodium-android` at
  startup. It is kept out of the root `settings.gradle.kts` because the Android Gradle Plugin can't
  configure without an SDK (see `android/README.md`).

## Crypto primitive choices (and why)

| Primitive | Choice | Rationale |
|---|---|---|
| KDF | Argon2id (`crypto_pwhash`) | Memory-hard; per-user, upgradeable params. |
| Subkey derivation | `crypto_kdf_derive_from_key` (BLAKE2b) | Native libsodium subkey derivation, purpose-built for deriving independent subkeys from a high-entropy master key — which `masterKey` already is. Domain-separated by context + id. (HKDF-SHA-256 is the spec-named equivalent; we pin crypto_kdf because the binding exposes it directly and it fits the high-entropy-input case exactly.) |
| AEAD | XChaCha20-Poly1305 IETF | 192-bit nonce → random nonces are collision-safe; authenticated. |
| Verifier | `crypto_pwhash_str` / `_verify` | Self-describing PHC string, constant-time verify, rehash-aware. |
| AAD binding | per-purpose labels + note id | Ties each ciphertext to its role/id; server can't swap blobs undetected. |

## Build & toolchain

- **JDK 21** (Temurin), **Gradle 9.6.1** via the committed wrapper (`./gradlew`).
- Versions are centralized in `gradle/libs.versions.toml`, pinned to current stable verified
  against Maven Central at build time (not from memory).
- `./gradlew :core:test` compiles the crypto core and runs the full mandatory suite, including
  one run at the real 256 MiB Argon2id profile.
