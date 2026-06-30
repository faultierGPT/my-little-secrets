# SECURITY.md — Threat model & cryptographic contract

> Status: **DRAFT for confirmation.** This document is written *before* any application
> code exists, so that we agree on the security model first. It restates the fixed
> crypto contract in our own words. Sections marked _(to be completed at release)_ will
> be filled in during the signing/release phase but their shape is fixed here.
>
> This file is the **canonical security boundary description**. If code and this
> document ever disagree, that is a bug in the code (or a deliberate, justified change
> recorded here), not in this document.

---

## 1. One-sentence summary

A **zero-knowledge** notes service: notes are encrypted and decrypted **only** on the
user's own devices, the server stores **opaque ciphertext it cannot read**, and losing
the master password (with no recovery key) means the data is **cryptographically
unrecoverable** — by us, by the server operator, and by anyone who steals the database.

---

## 2. Threat model

### 2.1 Who we defend against (the adversaries)

We treat all of the following as **hostile** and design so they learn nothing about
note contents:

1. **A malicious or compromised server / DB operator.** Someone who can read the entire
   database, application memory snapshots is out of scope at rest, and all request
   handling. They must be **architecturally incapable** of reading notes.
2. **A passive or active network attacker.** TLS protects transport; additionally,
   nothing of value to an attacker (no usable plaintext, no usable credential) is
   exposed even if TLS were broken, because the payloads are already ciphertext and the
   stored credential is not directly replayable into note decryption.
3. **A database thief.** Someone who exfiltrates a full DB dump. They get **metadata
   only** (see 2.3). No note plaintext, no password, no key that decrypts notes.
4. **Our own logs and telemetry.** We treat our logs as a place an attacker may later
   read. Therefore secrets, ciphertext, tokens, `authKey`, emails, and request bodies
   are **never** written to logs.

### 2.2 What is protected (the server / network / DB operator can NEVER learn these)

- Note **titles**, **bodies**, and **tags**.
- The **master password** and **every key derived from it**
  (`masterKey`, `authKey` in any persistent form, `keyEncryptionKey`, `accountKey`).
- The **recovery key**.

### 2.3 What is explicitly NOT protected (stated honestly)

We do **not** claim to hide the following. Pretending otherwise would be dishonest:

- **That an account exists**, and the **email address** tied to it (needed to log in).
- The **number of notes** a user has, and roughly **how big** each note is
  (ciphertext length leaks an approximate plaintext length).
- **Timestamps**: account creation, and per-note create/update times.
- The **fact and timing of sync activity** (the server sees when a device pushes/pulls).
- **A compromised client device.** E2EE defends against a hostile *server*, not malware,
  a keylogger, or a coercive party with your unlocked phone/laptop. If the endpoint is
  owned, plaintext is exposed there — that is outside what cryptography can fix.

We **minimize** metadata exposure (e.g. soft-delete tombstones still leak that a note
once existed and when it died; we accept this as the cost of multi-device sync), but we
do not overstate the guarantee.

---

## 3. Cryptographic contract (FIXED)

All primitives come from **libsodium** via a vetted Kotlin/JVM binding. **No primitive
is hand-rolled.** No custom cipher, KDF, MAC, or "lightweight" anything.

### 3.1 Primitives

| Purpose | Primitive | libsodium function |
|---|---|---|
| Password hashing / KDF | **Argon2id** | `crypto_pwhash` (`crypto_pwhash_ALG_ARGON2ID13`) |
| Authenticated encryption (AEAD) | **XChaCha20-Poly1305 IETF** | `crypto_aead_xchacha20poly1305_ietf_{encrypt,decrypt}` |
| Subkey derivation | **HKDF-SHA-256** (canonical) or libsodium `crypto_kdf` (BLAKE2b) | `crypto_kdf_hkdf_sha256_*` / `crypto_kdf_derive_from_key` |
| Randomness | **CSPRNG only** | `randombytes_buf` |
| Constant-time compare | — | `sodium_memcmp` |
| Memory wipe | — | `sodium_memzero` |

**Nonce discipline:** every AEAD encryption uses a **fresh random 24-byte nonce**
(`randombytes_buf`). XChaCha20's 192-bit nonce makes random nonces safe against
collision. A nonce is **never** reused with the same key. Nonces are stored/transmitted
in the clear alongside their ciphertext (they are not secret).

**Argon2id profile:** default to a **moderate-to-sensitive** profile,
**memory ≥ 256 MiB**, with `ops` chosen to land in a sane interactive latency budget.
Parameters are **stored per user** (`kdfParams`) so they can be **upgraded later**
without breaking existing accounts, and so low-end mobile can negotiate a lighter (but
still ≥ floor) profile at signup. Parameters are public.

### 3.2 Subkey derivation labels (pinned — as implemented)

`masterKey` is already a high-entropy 32-byte value (Argon2id output), which is exactly the
input `crypto_kdf` is designed for. The implementation pins **libsodium `crypto_kdf`
(`crypto_kdf_derive_from_key`, BLAKE2b)** with domain-separated 8-byte contexts + subkey ids:

- `authKey`           ← `crypto_kdf(masterKey, context = "mlskdf01", id = 1)`
- `keyEncryptionKey`  ← `crypto_kdf(masterKey, context = "mlskdf01", id = 2)`
- `recoveryWrapKey`   ← `crypto_kdf(recoveryKey, context = "mlsrcv01", id = 1)`

This is the spec's explicitly-permitted alternative to HKDF-SHA-256 ("HKDF-SHA-256 **or**
libsodium `crypto_kdf`"). We pin `crypto_kdf` because the binding exposes it directly and it
is purpose-built for deriving independent subkeys from a high-entropy master key — which
`masterKey` is. Distinct `(context, id)` pairs guarantee `authKey` and `keyEncryptionKey`
are cryptographically independent. HKDF-SHA-256 with `info` labels would be an equivalent,
drop-in choice; the pinned primitive is recorded here so doc and code never drift.

### 3.2.1 Associated-data binding (strengthening, does not weaken zero-knowledge)

Each AEAD ciphertext binds a domain label as **associated data (AAD)**, reconstructable at
decrypt time from already-stored fields:

- account-key wrap → AAD `"mls.accountKey.v1"`
- recovery wrap    → AAD `"mls.accountKey.recovery.v1"`
- note             → AAD `"mls.note.v1|<noteId>"`

Binding the note id ties a ciphertext to its id, so the server cannot silently swap one
note's blob onto another id without the client detecting it (the Poly1305 check fails). This
adds integrity, leaks nothing, and is fully within the XChaCha20-Poly1305 IETF contract.

### 3.3 Key hierarchy

Everything derives from the master password; a **random account key** is *wrapped* so the
password can change without re-encrypting a single note.

```
salt              = random 16 bytes                  (per user; stored server-side, PUBLIC)
kdfParams         = { algo: argon2id, mem, ops, parallelism }   (stored server-side, PUBLIC)

masterKey         = Argon2id(password, salt, kdfParams)         (NEVER leaves the device)
authKey           = HKDF(masterKey, info="auth")               (credential proof sent to server)
keyEncryptionKey  = HKDF(masterKey, info="kek")                (KEK — NEVER leaves the device)

accountKey        = random 32 bytes                            (generated ONCE at signup; the real data key)
wrappedAccountKey = XChaCha20Poly1305(accountKey, key=keyEncryptionKey, nonce=random24)

# every note:
notePlaintext     = JSON{ title, body, tags, schemaVersion }
noteCiphertext    = XChaCha20Poly1305(notePlaintext, key=accountKey, nonce=random24)
```

Why wrap a random account key instead of encrypting notes directly under a
password-derived key? So a **password change re-wraps one 32-byte key** instead of
re-encrypting every note, and so multiple wrappings (password, recovery key) can all
unlock the **same** account key.

### 3.4 What the server stores

**Per user:**
`email`, `salt`, `kdfParams`, `authVerifier = Argon2id(authKey)`, `wrappedAccountKey`,
`wrappedAccountKey_recovery` (optional), `schemeVersion`.

**Per note:**
`{ id, ciphertext, nonce, updatedAt, deleted, revision }`.

`authVerifier = Argon2id(authKey)` (with its **own** server-side salt) is stored instead
of `authKey` itself, so a **DB leak does not hand the attacker a usable credential** —
they would have to reverse Argon2id over a 256-bit input to recover `authKey`, which is
infeasible. The server compares `Argon2id(received authKey) == stored authVerifier` in
**constant time**.

### 3.5 Invariants the server MUST uphold (the zero-knowledge property)

The server **must never** receive, derive, store, or log:

- the plaintext **password**,
- `masterKey`, `keyEncryptionKey`, or `accountKey`,
- any **note plaintext** (title/body/tags),
- `authKey` in any **persisted** form (it transits RAM transiently during login — see
  the OPAQUE upgrade in §4.3 — but is never written down).

A DB dump, a full request log (were we to wrongly keep one), or a memory-at-rest dump
must reveal **nothing but the metadata in §2.3**.

### 3.6 Scheme versioning

Every encrypted record (and the user record) carries a **`schemeVersion` / `schemaVersion`**
tag so the crypto can evolve (new KDF params, new AEAD, rotated labels) **without
breaking old data**. Decryption dispatches on the stored version.

---

## 4. Authentication

### 4.1 Register

1. Client collects `email` + master `password`.
2. Client generates `salt` (16 random bytes) and chooses `kdfParams`.
3. Client derives `masterKey`, `authKey`, `keyEncryptionKey` **locally**.
4. Client generates random `accountKey`, computes `wrappedAccountKey`
   (and `wrappedAccountKey_recovery` if a recovery key was generated — see §5).
5. Client sends over **TLS**: `email, salt, kdfParams, authKey, wrappedAccountKey`
   (+ recovery blob). Server stores `Argon2id(authKey)` and the wrapped blobs.

The password and the device-only keys never leave the device.

### 4.2 Login

1. Client → `POST /auth/login/params { email }` → server returns `salt` + `kdfParams`.
2. Client derives `masterKey` → `authKey`.
3. Client → `POST /auth/login { email, authKey }`.
4. Server verifies `Argon2id(authKey)` against stored `authVerifier` (constant-time),
   subject to **rate limiting + lockout/backoff** (see §4.4). On success it issues a
   **short-lived session token** (opaque token or short-lived JWT) + refresh.
5. Client → `GET /account/key` → `wrappedAccountKey` (+ recovery blob).
6. Client unwraps `accountKey` locally with `keyEncryptionKey`. **`accountKey` lives in
   memory only**; it is never persisted in unwrapped form.

### 4.3 Hardening upgrade path — OPAQUE (documented, offered)

In the baseline, the server **briefly sees `authKey` in RAM** during a login request and
*could* maliciously log it. Even if it did, **it still cannot decrypt notes** (`authKey`
≠ `keyEncryptionKey` ≠ `accountKey`; it only proves the credential). But the gold
standard is an **augmented PAKE — OPAQUE** — where **no password-derived secret ever
reaches the server**. Plan: implement the baseline first; keep the auth layer behind an
interface so **OPAQUE** can replace it without touching the note-crypto core.

### 4.4 Rate limiting, lockout, logout

- **Per-account and per-IP rate limiting** with exponential **backoff/lockout** on
  `/auth/login` (and `/auth/login/params` to limit user-enumeration / salt-harvesting).
- `POST /auth/logout` revokes the session; **password change invalidates all sessions**.
- Generic error responses on login (no "user exists" vs "wrong password" distinction
  beyond what email-enumeration already leaks via params).

---

## 5. Recovery & password change

### 5.1 Recovery key (optional, at signup)

- Generate a **256-bit** recovery code, displayed as a **word list / base32** string.
- Store a **second wrapping**:
  `wrappedAccountKey_recovery = XChaCha20Poly1305(accountKey, key=HKDF(recoveryKey), nonce=random24)`.
- This is the **only** recovery path if the password is forgotten. The UI must state the
  tradeoff plainly: it recovers the account, **and** it is one more high-value secret the
  user must store safely. It is **opt-in**.

### 5.2 Password change

- Re-derive `keyEncryptionKey` from the **new** password (new `salt`/`kdfParams` allowed).
- **Re-wrap the same `accountKey`** under the new KEK → new `wrappedAccountKey`.
- **Notes are never re-encrypted** (their key, `accountKey`, is unchanged).
- Update `authVerifier` for the new `authKey`. **Invalidate all existing sessions.**

---

## 6. Cross-cutting hygiene (applies to every module)

- **Keys/secrets are zeroable byte arrays, never `String`** (Strings are immutable and
  linger in the heap). Wipe with `sodium_memzero` / explicit fill immediately after use.
  Use libsodium secure-memory helpers where the binding exposes them.
- **No secrets, keystores, or signing keys in the repo or CI logs.** Generation scripts
  + env vars / secret store only.
- **Fresh random nonce per encryption**, never reused with the same key.
- **No plaintext note content, tokens, keys, or emails in logs / crash reports**, on any
  module. No analytics / telemetry in the clients.
- **Optional TLS certificate pinning** on the clients (called out as optional hardening).
- **All dependency versions pinned and locked**; current stable verified at build time,
  not hardcoded from memory.

### 6.1 Independent adversarial review (foundation)

The crypto core + server were put through a multi-lens adversarial review (primitive usage, key
hierarchy / zero-knowledge boundary, secret hygiene, server leakage, sync/cache), each finding
verified by an independent skeptic. **No critical/high issues; the zero-knowledge contract held** —
nonce freshness, KDF domain separation, AAD binding, Argon2id parameters, ciphertext-only storage,
hashed session tokens, and parameterized SQL were all confirmed clean. Six lower-severity findings
were fixed:

- **Optimistic-concurrency enforced server-side** on note writes — a stale base revision is now a
  `409`, not a silent overwrite (was: `revision` received but ignored). Client re-pulls/merges/retries.
- **Auth throttle no longer trusts `X-Forwarded-For`** unless `MLS_TRUSTED_PROXY_HOPS > 0`, plus a
  per-email backstop independent of source IP (was: leftmost XFF spoofable → throttle bypass).
- **Application-level request body cap on every endpoint**, enforced independent of `Content-Length`
  (was: `/auth/register` unbounded; chunked uploads bypassed the note `Content-Length` check).
- **Recovery code and the returned `authKey` are now wipeable `SecretBytes`** (were bare arrays),
  closing the last gaps in the zeroization discipline.

---

## 7. Signing & artifact verification _(shape fixed; specifics completed at release)_

### 7.1 Android APK

- Release build via Gradle `signingConfigs` using a **release keystore**, with **APK
  Signature Scheme v2/v3** (v4 where relevant). Verified with `apksigner verify`.
- **Keystore + passwords are never committed** — read from env vars / Gradle properties.
  A documented script generates the keystore; `RELEASE.md` documents the process.
- Because the app is **sideloaded**, the signing certificate's **SHA-256 fingerprint**
  is **published in the repo** so any downloaded APK can be verified as authentic.
- Goal: **reproducible builds** so the published APK can be independently rebuilt.

### 7.2 Linux desktop — what "signed" honestly means

Linux has **no OS-enforced application signing** like Android's APK signatures or macOS
codesign/notarization. On Linux, "signed" = **publisher-verifiable artifact integrity
via GPG**. We deliver:

- Packaging via **`jpackage`** to an **AppImage** (primary, self-contained) plus
  `.deb` and `.rpm`.
- **GPG detached signatures** (`.sig`) for every artifact, plus a **GPG-signed
  `SHA256SUMS`**. AppImage embedded GPG signature (`appimagetool --sign`) where used.
- The **GPG public key fingerprint published in the repo**, with exact
  `gpg --verify` / `sha256sum -c` steps documented in `RELEASE.md`.
- Signing key/passphrase **never committed**.
- Same reproducible-build goal where feasible.

---

## 8. Open decisions / upgrade path (tracked)

- **OPAQUE** PAKE to replace the baseline `authKey` flow (§4.3).
- Optional **TLS certificate pinning** on clients.
- **Reproducible builds** for both Android and Linux artifacts.
- Per-device session/key management and remote revocation (future).
