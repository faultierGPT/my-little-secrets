# RELEASE.md — building, signing & verifying releases

"Signed" here means **publisher-verifiable artifact integrity**: every release artifact is hashed,
and the checksum manifest is signed with the project's GPG key. A user who trusts the published
public key can prove an artifact is exactly what the publisher built — no tampering in transit, on a
mirror, or on the release page. (This is independent of the end-to-end encryption: see `SECURITY.md`.)

| Artifact | Built by | Signed with |
|---|---|---|
| Desktop `.tar.gz` (portable) + `.deb` | `jpackage` (JavaFX + JRE bundled) | GPG over `SHA256SUMS` |
| Android `.apk` | `:android:assembleRelease` | Android keystore (APK signature) **and** GPG |
| Server image | `server/Dockerfile` → GHCR | image digest (immutable tag) |

> **Nothing secret is ever in the repo.** Keys live in `.signing/` (gitignored) locally and in the
> CI secret store for releases. `.gitignore` blocks `*.jks`, `*.gpg`, `keystore.properties`, etc.

## Publisher key

Record the real fingerprint here once generated, so users can verify out-of-band:

```
my-little-secrets release signing key
ed25519  FINGERPRINT: <fill in from scripts/gen-signing-keys.sh>
```

## One-time setup

```bash
# Generates a GPG key + an Android keystore into .signing/ (gitignored). Back them up offline —
# reuse the SAME keys for every release (a new Android key = a different, un-updatable app identity).
MLS_ANDROID_KEYSTORE_PASSWORD=… ./scripts/gen-signing-keys.sh
```

For CI, add these encrypted repository secrets (never logged):

| Secret | Value |
|---|---|
| `MLS_GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <id>` |
| `MLS_GPG_KEY_ID` | the key id / fingerprint |
| `MLS_ANDROID_KEYSTORE_BASE64` | `base64 -w0 .signing/android-release.jks` |
| `MLS_ANDROID_KEYSTORE_PASSWORD` / `MLS_ANDROID_KEY_ALIAS` / `MLS_ANDROID_KEY_PASSWORD` | keystore creds |

## Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0      # triggers .github/workflows/release.yml
```

The workflow packages the desktop app (Linux `.tar.gz` + `.deb` with a jlinked runtime), builds the
signed APK, builds + pushes the server image to GHCR, then hashes everything into `SHA256SUMS`, signs
it with GPG, and attaches the artifacts + `SHA256SUMS` + `.asc` signatures to the GitHub Release.

### Locally (without CI)

```bash
MLS_VERSION=0.1.0 ./scripts/package-desktop.sh          # -> dist/*.tar.gz (+ .deb if tooling present)
MLS_GPG_KEY_ID=<id> ./scripts/sign-artifacts.sh dist    # -> dist/SHA256SUMS(.asc) + per-artifact .asc
```

> `jpackage` builds a minimal runtime with `jlink`, which needs `binutils` (`objcopy`); `.deb` also
> needs `fakeroot`. Without them the script bundles the full JDK as the runtime (larger, still valid).

## Verifying a download (do this before installing)

```bash
# 1. Import the publisher key ONCE and check its fingerprint against the value above.
gpg --import publisher-pubkey.asc
gpg --fingerprint releases@…

# 2. Verify the signature over the checksum manifest, then every checksum.
./scripts/verify-artifacts.sh .         # gpg --verify SHA256SUMS.asc + sha256sum --check
```

**Android APK** — also confirm the APK's own signing certificate matches across versions:

```bash
apksigner verify --print-certs my-little-secrets-0.1.0.apk
```

The SHA-256 of the signing certificate must be identical for every release (a changed certificate
means a different signer — do not install over your existing app).

**Server image** — pull by immutable digest and run per `docker-compose.yml`:

```bash
docker pull ghcr.io/<owner>/my-little-secrets/server@sha256:<digest>
```

## Notes

- The desktop bundle embeds JavaFX + a JRE, so end users need no preinstalled Java.
- Brand fonts aren't bundled in the Android build yet (falls back to platform serif/sans); see
  `android/README.md`.
- Reproducibility: dependency versions are pinned in `gradle/libs.versions.toml`; the Gradle wrapper
  is committed. Bit-for-bit reproducible builds are not yet a guarantee — the GPG signature attests
  to the publisher, not to a from-source rebuild.
