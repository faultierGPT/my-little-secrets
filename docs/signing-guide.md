# Signing & verification — hands-on guide

A practical walkthrough for **creating** signed releases (publisher) and **verifying** them
(consumer). For the reference/overview see [`RELEASE.md`](../RELEASE.md).

There are two independent mechanisms:

| | Desktop "Java version" (`.tar.gz` / `.deb`) | Android `.apk` |
|---|---|---|
| Signature | **GPG** over a `SHA256SUMS` manifest (Linux has no OS app-signing) | **APK Signature Scheme v2/v3** (keystore), plus a GPG `.asc` for uniformity |
| Verify with | `gpg --verify` + `sha256sum --check` | `apksigner verify` |
| Identity anchor | GPG key fingerprint (published in `RELEASE.md`) | signing-cert SHA-256 (stable across every release) |

> In this repo's sandbox, `gpg`, `apksigner`, and the Android SDK aren't installed — run the
> publisher steps on a real release machine or via the CI release job. `./scripts/package-desktop.sh`
> (the desktop build) does run locally.

---

## Part A — Publisher: sign the desktop (Java/JavaFX) artifacts

### A1. Create the GPG signing key (the private key)

The private key is created together with its public half as one GPG keypair in your local keyring
(`~/.gnupg`).

**Option A — the bundled script:**

```bash
MLS_PUBLISHER_NAME="my-little-secrets" \
MLS_PUBLISHER_EMAIL="releases@yourdomain.tld" \
MLS_ANDROID_KEYSTORE_PASSWORD='…' \
  ./scripts/gen-signing-keys.sh
```

It runs `gpg --batch --gen-key` with this spec (Ed25519 signing key + Curve25519 subkey, 2-year
expiry), then writes `.signing/publisher-pubkey.asc` and prints the fingerprint:

```
%no-protection        ← no passphrase, so CI can sign unattended
Key-Type: eddsa
Key-Curve: ed25519
Subkey-Type: ecdh
Subkey-Curve: cv25519
Name-Real:  my-little-secrets
Name-Email: releases@yourdomain.tld
Expire-Date: 2y
%commit
```

`%no-protection` (no passphrase) is the trade-off for unattended CI signing. To sign **manually**
instead, drop that line so `gpg` sets a passphrase you choose — stronger, but CI then needs that
passphrase as a secret too.

**Option B — by hand:**

```bash
gpg --full-generate-key      # ECC (sign only) → Curve 25519 → name/email/expiry
gpg --list-secret-keys --keyid-format=long
gpg --fingerprint releases@yourdomain.tld
```

Record the fingerprint in [`RELEASE.md`](../RELEASE.md) — it's the anchor users check against.

### A2. Build and sign the artifacts

```bash
# build (runs anywhere with JDK 21)
MLS_VERSION=0.1.0 ./scripts/package-desktop.sh        # → dist/my-little-secrets-0.1.0-linux-x64.tar.gz (+ .deb)

# sign
MLS_GPG_KEY_ID='releases@yourdomain.tld' ./scripts/sign-artifacts.sh dist
```

`sign-artifacts.sh` produces, inside `dist/`:

- `SHA256SUMS` — SHA-256 of every artifact
- `SHA256SUMS.asc` — detached, armored GPG signature over that manifest (**the** signature that matters)
- `<artifact>.asc` — a per-artifact detached signature (convenience)

The manual equivalent:

```bash
cd dist
sha256sum *.tar.gz *.deb > SHA256SUMS
gpg --local-user releases@yourdomain.tld --armor --detach-sign --output SHA256SUMS.asc SHA256SUMS
# optional, per artifact:
gpg --local-user releases@yourdomain.tld --armor --detach-sign my-little-secrets-0.1.0-linux-x64.tar.gz
```

You sign the small **manifest** rather than each big binary — one signature + a list of hashes covers
everything cheaply. `--detach-sign` writes the signature to a separate `.asc`; `--armor` makes it text.

### A3. Publish

Upload: the artifacts **+** `SHA256SUMS` **+** `SHA256SUMS.asc` **+** `publisher-pubkey.asc`.

### A4. The same thing in CI

Export the private key once into an encrypted repo secret; `.github/workflows/release.yml` re-imports
it on a `v*` tag and runs the same `sign-artifacts.sh`:

```bash
gpg --armor --export-secret-keys releases@yourdomain.tld   # → secret MLS_GPG_PRIVATE_KEY
gpg --fingerprint releases@yourdomain.tld                  # → secret MLS_GPG_KEY_ID
```

> The private key (and the `MLS_GPG_PRIVATE_KEY` secret) is the one thing you can never leak or lose:
> leak it and anyone can forge "official" builds; lose it and you can't publish verifiable updates
> under the same identity. Keep an offline backup; never let it touch the repo (`.gitignore` blocks
> `*.gpg` / `*.key` / `.signing/`).

---

## Part B — Publisher: build the signed Android `.apk`

**Prerequisites:** Android SDK + `ANDROID_HOME` (or `local.properties` `sdk.dir=…`), and
`echo 'include(":android")' >> settings.gradle.kts` (the module is excluded from the default build
because AGP can't configure without an SDK).

### B1. Create the keystore (once, ever)

```bash
MLS_ANDROID_KEYSTORE_PASSWORD='your-strong-pass' ./scripts/gen-signing-keys.sh
# → .signing/android-release.jks   (alias mls-release, RSA 4096)
```

or directly:

```bash
keytool -genkeypair -v -keystore android-release.jks -alias mls-release \
  -keyalg RSA -keysize 4096 -validity 10000
```

⚠️ **Back it up and reuse it for every release.** Android identifies an app by its signing
certificate — a new key = a *different app* that can't update over the installed one. Never commit
the keystore (`*.jks` is gitignored).

### B2. Build the signed release

The `signingConfigs` in `android/build.gradle.kts` reads **only** from environment variables:

```bash
export MLS_ANDROID_KEYSTORE="$PWD/.signing/android-release.jks"
export MLS_ANDROID_KEYSTORE_PASSWORD='your-strong-pass'
export MLS_ANDROID_KEY_ALIAS='mls-release'
export MLS_ANDROID_KEY_PASSWORD='your-strong-pass'   # defaults to the store password if unset

./gradlew :android:assembleRelease
# → android/build/outputs/apk/release/android-release.apk   (signed, minified)
```

If the env vars are unset the build still succeeds but yields an *unsigned* APK (so a plain CI
build-check needs no secrets). CI's `release.yml` restores the keystore from
`MLS_ANDROID_KEYSTORE_BASE64` and sets these vars from repo secrets.

### B3. Confirm it's signed

```bash
apksigner verify --print-certs android/build/outputs/apk/release/android-release.apk
```

(`apksigner` is in the SDK's `build-tools/<version>/`.) The printed certificate SHA-256 must be
identical across every release you publish.

---

## Part C — Consumer: verify a download

### C1. Desktop (`.tar.gz` / `.deb`) — GPG

```bash
# once: import the publisher key and CHECK its fingerprint against RELEASE.md (out-of-band)
gpg --import publisher-pubkey.asc
gpg --fingerprint releases@yourdomain.tld

# verify signature over the manifest, then every checksum
./scripts/verify-artifacts.sh .
#   ≡ gpg --verify SHA256SUMS.asc SHA256SUMS && sha256sum --check --strict SHA256SUMS
```

A `Good signature` + all-`OK` checksums means the download is authentic and untampered. Then:

```bash
tar xzf my-little-secrets-0.1.0-linux-x64.tar.gz
./my-little-secrets/bin/my-little-secrets        # JavaFX + a JRE are bundled — no Java needed
```

### C2. Android (`.apk`)

```bash
apksigner verify --print-certs my-little-secrets-0.1.0.apk
```

The signing-cert SHA-256 must match what the publisher documents — and must be the same digest you
saw on the previous version (a changed certificate = a different signer; do not install over your
existing app).
