# my-little-secrets — Android client

A Jetpack Compose client over the shared `:core` crypto/sync engine. Zero-knowledge: the master
password and all keys stay on the device; the server only ever receives ciphertext.

## Why it is conditionally included in the root Gradle build

The Android Gradle Plugin **fails to configure without an installed Android SDK**. `settings.gradle.kts`
therefore includes `:android` only when `local.properties`, `ANDROID_HOME`, or `ANDROID_SDK_ROOT`
points at an SDK, or when an Android task such as `:android:assembleRelease` is requested explicitly.
This keeps SDK-less `:core`, `:server`, `:desktop`, and `:design` builds working.

## Building it

1. Install the Android SDK (Android Studio, or `cmdline-tools`), and set `ANDROID_HOME` (or create
   `local.properties` with `sdk.dir=/path/to/Android/sdk`).
2. Open the project in Android Studio (it reuses `:core` via `implementation(project(":core"))`),
   or build from the CLI:
   ```bash
   ./gradlew :android:assembleDebug
   ./gradlew :android:assembleRelease
   ```
3. Point the app at your server: edit `server_url` in `src/main/res/values/strings.xml`
   (`http://10.0.2.2:8080` is the host loopback as seen from the emulator).

### Optional Cloudflare Access service token

If the sync server is behind a Cloudflare Access Service Auth policy, inject the service token at
build time. Environment variables take precedence:

```bash
MLS_CF_ACCESS_CLIENT_ID=... \
MLS_CF_ACCESS_CLIENT_SECRET=... \
./gradlew :android:assembleRelease
```

For local Android Studio builds, put the values in the gitignored root `local.properties` file:

```properties
mls.cloudflare.access.clientId=...
mls.cloudflare.access.clientSecret=...
```

The app sends these as `CF-Access-Client-Id` and `CF-Access-Client-Secret` on every API request.
Both values must be set together. They are embedded in the APK, so only use this for private builds.

> **Version note:** `agp` in `gradle/libs.versions.toml` is pinned to an AGP 9.2.x release compatible
> with this repo's Gradle 9.6.1 wrapper. Do not downgrade to AGP 8.x with Gradle 9.

## Architecture

| Layer | File(s) | Responsibility |
|-------|---------|----------------|
| Native crypto bind | `MlsApplication.kt` | Installs `lazysodium-android` into core's `Sodium` at startup |
| Session controller | `session/AndroidVault.kt` | register / login / offline-unlock / sync / notes — coroutine-native over `:core` |
| Fast unlock | `session/BiometricUnlock.kt` | Seals the account key under a biometric-bound Keystore AES-GCM key |
| State | `ui/VaultViewModel.kt` | Single `StateFlow<UiState>` the UI renders; wipes passwords after use |
| UI | `ui/*.kt`, `ui/theme/Theme.kt` | Compose screens; theme from the **generated** design tokens |

The suspend-based `MlsApi` / `SyncEngine` are called directly from coroutines — no blocking bridge
(unlike the pure-Java desktop client).

## Security features

- **No plaintext at rest** — the local cache is `:core`'s `EncryptedFileNoteStore` (XChaCha20-Poly1305).
- **`FLAG_SECURE`** — blocks screenshots, screen recording, and the recents/overview thumbnail.
- **Lock on background** — `MainActivity.onStop()` wipes the in-memory account key; re-unlock required.
- **Biometric unlock** — the account key is sealed by a hardware-backed Keystore key requiring a
  strong biometric (`setUserAuthenticationRequired`), invalidated on biometric re-enrollment. It is
  never persisted in the clear; it exists in memory only after a successful auth.
- **Clipboard auto-clear** — a copied note body is wiped from the clipboard after 30s (if unchanged).
- **No backup leakage** — `allowBackup=false` + data-extraction rules exclude all app data from cloud
  backup and device transfer.
- **Offline unlock** — the cached (non-secret) wrapping material lets you open the vault with no
  network; sync stays disabled until the next online sign-in.

## Caveats

- The brand fonts (Spectral / Geist) aren't bundled yet; the theme falls back to platform serif/sans
  per the editorial pairing. Drop the font files in `res/font/` and wire them in `Theme.kt` to match
  the desktop exactly.
- This module's source has **not** been compiled in this repository's SDK-less CI. Treat Android API
  and resource changes as needing a real SDK build.
