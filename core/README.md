# core/ — shared crypto core (the security boundary)

A Kotlin JVM library holding **all** cryptography for `my-little-secrets`. Both clients (the
Kotlin/Compose Android app and the pure-Java/JavaFX desktop app) consume this exact compiled
module, so the crypto is written, tested, and audited **once**. See `../SECURITY.md` for the
threat model and `../ARCHITECTURE.md` for how it fits together.

> Nothing here invents cryptography. Every primitive is libsodium via `lazysodium-java`, and
> every call to it lives in a single auditable file: [`Sodium.kt`](src/main/kotlin/app/mls/core/crypto/Sodium.kt).

## What it provides (Phase 1)

- **Key hierarchy** (`KeyHierarchy`, `CryptoCore`): Argon2id master key → `crypto_kdf` subkeys
  (`authKey`, `keyEncryptionKey`) → wrapped random `accountKey`.
- **Authenticated encryption** (`NoteCrypto`, `AccountKeyCrypto`): XChaCha20-Poly1305 IETF with
  a fresh random 24-byte nonce per call and per-purpose associated-data binding.
- **Account-key wrapping**: change the password or use a recovery code without re-encrypting
  any note.
- **Recovery** (`RecoveryCode`): 256-bit Base32 code, second wrapping of the account key.
- **Server-side verifier** (`AuthVerifier`): `Argon2id(authKey)` as a constant-time PHC string.
- **Memory hygiene** (`SecretBytes`): keys are wipeable `byte[]`, never `String`.

## Build & test

```bash
# From the repo root (uses the committed Gradle wrapper + JDK 21):
./gradlew :core:test
```

The suite covers every mandatory case: encrypt→decrypt round-trips, KDF determinism, **wrong
password fails**, **tampered ciphertext/nonce/AAD fail authentication**, account-key wrap/unwrap,
password-change re-wrap, recovery unwrap, and a **"server can't read content"** proof that
decrypts from only the bytes the server stores. One test runs the real 256 MiB Argon2id profile.

## Public surface (high-level)

| Call | Purpose |
|---|---|
| `CryptoCore.register(password, params, withRecovery)` | Local signup → keys, wrapped account key, recovery code |
| `CryptoCore.deriveAuthKeyForLogin(password, salt, params)` | Produce the login credential proof |
| `CryptoCore.unlockWithPassword(password, salt, params, wrapped)` | Unwrap the in-memory account key |
| `CryptoCore.unlockWithRecovery(code, wrappedRecovery)` | Recover via the recovery code |
| `CryptoCore.rewrapForNewPassword(accountKey, newPassword, params)` | Password change (no note re-encryption) |
| `NoteCrypto.encrypt / decrypt(accountKey, noteId, …)` | Per-note AEAD |
| `AuthVerifier.create / verify` | Server-side credential storage (used by `:server`) |
