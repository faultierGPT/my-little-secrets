# my-little-secrets — Android client

A Jetpack Compose client over the shared `:core` crypto/sync engine. Zero-knowledge: the master
password and all keys stay on the device; the server only ever receives ciphertext.

## Why it isn't in the root Gradle build

The Android Gradle Plugin **fails to configure without an installed Android SDK**. If `:android`
were listed in the root `settings.gradle.kts`, every `./gradlew` invocation — including the fully
buildable `:core`, `:server`, and `:desktop` modules and their tests — would fail on a machine
without the SDK. So this module is deliberately kept out of `settings.gradle.kts`.

## Building it

1. Install the Android SDK (Android Studio, or `cmdline-tools`), and set `ANDROID_HOME` (or create
   `local.properties` with `sdk.dir=/path/to/Android/sdk`).
2. Add the module to the root build:
   ```kotlin
   // settings.gradle.kts
   include(":android")
   ```
3. Open the project in Android Studio (it reuses `:core` via `implementation(project(":core"))`),
   or build from the CLI:
   ```bash
   ./gradlew :android:assembleDebug
   ```
4. Point the app at your server: edit `server_url` in `src/main/res/values/strings.xml`
   (`http://10.0.2.2:8080` is the host loopback as seen from the emulator).

> **Version note:** `agp` in `gradle/libs.versions.toml` is a known-good baseline. Align the Android
> Gradle Plugin with your installed SDK and the root project's Gradle version if the build complains.

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
- This module's source has **not** been compiled in this repository's CI, which has no Android SDK.
  Treat versions/APIs as needing a first build in Android Studio.
