# AGENTS.md — my-little-secrets

**Zweck:** Orientierung für KI-Agenten (Claude Code / Hermes / Codex). Beschreibt Struktur,
Tech-Stack, Entscheidungen, aktuellen Stand, Build-Umgebung, Krypto-Invarianten und offene Punkte,
damit ein anderer Agent nahtlos weiterarbeiten kann.

---

## Letzter Durchlauf

**Aufgabe:** `./gradlew :android:assembleRelease` fehlte im Sandbox-Container komplett die
Toolchain — kein JDK installiert, kein Android-SDK konfiguriert. Der vorhandene Build brach
mit `Directory '/home/app/tools/jdk-21.0.11+10' (Gradle property
'org.gradle.java.installations.paths') used for java installations does not exist` ab und
kaskadierte weiter zu `Kotlin Gradle plugin was loaded multiple times in different subprojects`
sowie `:android:validateSigningRelease` (Keystore fehlt). Gewünscht: Android-SDK (bzw. JDK +
SDK) installieren, bis der Build durchläuft.

**Fix:**

1. **JDK 21 installiert:** `apt-get install openjdk-21-jdk-headless` →
   `/usr/lib/jvm/java-21-openjdk-amd64`. `gradle.properties`
   (`org.gradle.java.installations.paths`) zeigte noch auf einen Temurin-Pfad
   (`/home/app/tools/jdk-21.0.11+10`) aus einem früheren Container — auf den Debian-apt-Pfad
   umgebogen. Der Kommentar in `gradle.properties` erklärt jetzt, dass der Pfad je nach Distro
   / Installationsmethode anzupassen ist (Temurin / SDKMAN / brew statt apt).
2. **Android-SDK konfiguriert:** `/tmp/mls-android-sdk` (cmdline-tools, build-tools 35.0.0 und
   36.0.0, platforms/android-35, platform-tools, Licenses) ist im Sandbox-Container bereits
   vorhanden. Per `local.properties` (`sdk.dir=/tmp/mls-android-sdk`, gitignored) für
   `settings.gradle.kts#androidSdkPath()` aktiviert.
3. **Kotlin-Plugin-Warning behoben:** `:core` zieht `libs.plugins.kotlin.jvm`, `:android` zieht
   `libs.plugins.kotlin.compose`. Beide wurden unabhängig voneinander geladen, was Gradle 9.6.1
   mit `The Kotlin Gradle plugin was loaded multiple times in different subprojects` anmeckert.
   Fix: Root `build.gradle.kts` deklariert jetzt alle vier vom Build benutzten Plugins
   (`kotlin.jvm`, `kotlin.serialization`, `android.application`, `kotlin.compose`) zentral mit
   `apply false`. Subprojekte ziehen weiterhin nur per `alias(libs.plugins.…)` ohne Version.
   Hinweis aus der vorigen Runde ("AGP/KGP crasht auf entfernten Android-Legacy-APIs, also
   keinen Root-Block einführen") bezog sich auf das explizite `kotlin.android` aus AGP-8-Zeiten;
   die jetzt gelisteten vier Plugins existieren in AGP 9 / KGP 2.4.0 alle nativ und sind safe.
4. **`validateSigningRelease`:** Hatte sich im alten Lauf als Folgefehler des fehlenden JDK
   aufgehängt (AGP konnte das Signing-Config-Validation-Skript nicht ausführen und fiel auf
   Defaults zurück, die nach `.signing/android-release.jks` suchten). Mit lauffähigem JDK läuft
   die Task sauber durch — `signingConfig = null` (bei fehlendem `MLS_ANDROID_KEYSTORE`)
   erzeugt eine korrekt *unsigned* APK, kein Fehler. Kein Code-Fix im Build-Skript nötig.

**Verifikation:**

- `./gradlew :android:assembleRelease --console=plain --warning-mode=all --rerun-tasks` →
  `BUILD SUCCESSFUL` in 2m 46s, 50 tasks / 50 executed. **Keine** "Kotlin Gradle plugin was
  loaded multiple times"-Warnung mehr in `--warning-mode=all`. Nur die generische
  Gradle-10-Deprecation "Project object as dependency notation" (betrifft `implementation(
  project(":core"))` etc. — vorher schon da, nicht durch diesen Lauf verursacht).
- `./gradlew build` → `BUILD SUCCESSFUL` in 1m 12s, 120 tasks / 65 executed. Damit laufen auch
  `:core:test`, `:server:test`, `:design:test`, `:desktop:test` (mit Monocle) und der
  Android-Debug-Build (`assembleDebug` + `lintDebug`) durch.
- APK-Artefakt: `android/build/outputs/apk/release/android-release-unsigned.apk` (5.4 MB,
  valides APK per `file(1)`), korrekt unsigned weil `MLS_ANDROID_KEYSTORE` im Container
  fehlt. Inhalt unverändert: `lib/{arm64-v8a,armeabi-v7a,armeabi,x86,x86_64}/libsodium.so`
  + `libjnidispatch.so` (aus dem JNA-AAR), keine JVM-JNA-/`LazySodiumJava`-Klassen, das
  Duplikat-Problem aus dem vorherigen Lauf bleibt gelöst.

**Wichtigste Erkenntnis:** Der ursprüngliche Build-Fehler war **kein** Bug im Repo, sondern
eine fehlende lokale Toolchain (JDK 21 + Android-SDK + korrekte `gradle.properties`-
Toolchain-Pfade). Sobald beide vorhanden sind und die vier Plugins am Root mit `apply false`
zentral deklariert sind, läuft der Android-Release-Build inkl. R8 / lintVitalRelease sauber
durch. Die `:android:validateSigningRelease`-Fehlermeldung aus dem Log war ein Folgefehler
des JDK-Problems, nicht ein echtes Signierproblem — der bestehende `signingConfig = null`-Pfad
bei fehlendem `MLS_ANDROID_KEYSTORE` funktioniert weiterhin korrekt.

---

## Tech-Stack

- **Sprache/Build:** Kotlin `2.4.0`, Java 21 (Temurin), Gradle `9.6.1` (Wrapper), JUnit 5.
- **Krypto:** libsodium via `core/.../crypto/Sodium.kt` (LazySodiumJava Desktop/Server,
  LazySodiumAndroid auf Android). Argon2id, XChaCha20-Poly1305 IETF, `crypto_kdf`.
- **Server:** Ktor + Netty; PostgreSQL (Prod) / H2 (Tests). Speichert nur Ciphertext.
- **Desktop:** reines Java + JavaFX über `:core`.
- **Android:** Kotlin + Jetpack Compose über `:core`; AGP `9.2.1`, Built-in Kotlin, Compose Compiler
  Plugin.
- **Versionen:** zentral und gepinnt in `gradle/libs.versions.toml`; nicht aus dem Gedächtnis bumpen,
  sondern gegen Maven/Google Maven prüfen.

## Module / Struktur

```text
core/     Krypto + DTOs + Ktor-API-Client + offline-first SyncEngine + verschl. Cache + jvm/-Bridge
server/   Ktor/Netty; nur Ciphertext. app.mls.server.Embedded bootet in-process für Tests
design/   EINE Token-Quelle (Tokens.kt) -> generierte Compose-Theme/JavaFX-CSS/Java-Konstanten/tokens.json
desktop/  reines Java + JavaFX über core
android/  Kotlin + Compose über core; in settings.gradle.kts nur bei SDK oder explizitem Android-Task
```

## Build / Umgebung

**JDK 21 wird benötigt** (für Gradle 9.6.1 und AGP 9.2.1). Auf Debian / Ubuntu:

```bash
sudo apt-get install -y openjdk-21-jdk-headless
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
java --version    # openjdk 21.0.x
```

Der exakte Toolchain-Pfad wird in `gradle.properties` via `org.gradle.java.installations.paths`
gesetzt (Standard: `/usr/lib/jvm/java-21-openjdk-amd64`). Bei Temurin / SDKMAN / brew / etc. muss
dieser Pfad an die lokale Installation angepasst werden — `auto-download` ist explizit
**ausgeschaltet**, der Build holt sich nichts selbst aus dem Netz.

**Android SDK:** Pflicht nur für `:android`-Tasks. Entweder

- `local.properties` mit `sdk.dir=/path/to/Android/sdk` anlegen (gitignored, von
  `settings.gradle.kts#androidSdkPath()` gelesen), oder
- `ANDROID_HOME` / `ANDROID_SDK_ROOT` als Env-Variable setzen.

Mit gesetztem SDK wird `:android` automatisch inkludiert; sonst nur, wenn ein expliziter
Android-Task (z.B. `:android:assembleRelease`) angefordert wird — so bleibt der JVM-Build
SDK-los lauffähig.

**JavaFX-Pixel-Rendering:** In headless-Containern fehlen fontconfig/X11/GL — `Font.getDefault()`
schlägt fehl. `:desktop:test` läuft dann mit Monocle im Headless-Modus (`prism.order=sw`,
`glass.platform=Monocle`); pixelbasierte Snapshots brauchen ein echtes Display. Das ist ein
Umgebungslimit, kein Projektbug.

## Häufige Kommandos

```bash
./gradlew build                 # build + test alle JVM-Module (core, server, desktop, design)
./gradlew :core:test            # Crypto-Core-Suite; enthält einen echten 256 MiB Argon2id-Lauf
./gradlew :server:test          # Server gegen embedded H2, kein Docker nötig
./gradlew :desktop:test         # Desktop-Controller E2E gegen in-process Netty+H2
./gradlew :design:test          # Design-Token-Invarianten
./gradlew :design:run           # Design-Artefakte nach Tokens.kt/Renderers.kt regenerieren
./gradlew :desktop:run          # JavaFX-App starten, braucht ein Display
./scripts/package-desktop.sh    # jpackage Desktop-Artefakt
docker compose up --build       # Server mit PostgreSQL starten
```

Einzeltests:

```bash
./gradlew :core:test --tests "app.mls.core.jvm.BlockingBridgeTest"
./gradlew :desktop:test --tests "app.mls.desktop.VaultIntegrationTest"
```

Für Android: SDK installieren und `ANDROID_HOME` setzen oder `local.properties` mit
`sdk.dir=/path/to/Android/sdk` anlegen. `settings.gradle.kts` nimmt `:android` dann automatisch auf;
bei expliziten Android-Tasks wie `:android:assembleRelease` wird das Modul ebenfalls aufgenommen.

## Wichtige Entscheidungen

- **Eine Krypto-Quelle:** Krypto lebt nur in `core/`, von beiden Clients geteilt. Nie im Client
  reimplementieren.
- **Suspend-Interop:** Desktop ist 100 % Java und kann keine `suspend`-Funktionen aufrufen. Es nutzt
  `app.mls.core.jvm.BlockingApi` / `BlockingSync` (`runBlocking`-Adapter in `core`). Android nutzt
  die Suspend-API direkt. Interop-Anpassungen gehören in `core`, nicht in `desktop`.
- **libsodium-Binding ist austauschbar:** Alle Krypto-Flows laufen durch `core/.../crypto/Sodium.kt`.
  Desktop/Server nutzen LazySodiumJava; Android setzt beim Start in `MlsApplication` LazySodiumAndroid.
- **libsodium-Binding-Sichtbarkeit ist `compileOnly` in `:core`:** `:core` deklariert
  `libs.lazysodium.java` + `libs.jna` als `compileOnly`, NICHT als `implementation`. Sonst landet
  `lazysodium-java` (jar) transitiv im Android-Classpath und kollidiert dort mit
  `lazysodium-android` (aar) — AGP's `checkReleaseDuplicateClasses` wirft dann Hunderte
  `Duplicate class com.goterl.lazysodium.*` / `Duplicate class com.sun.jna.*`-Einträge. Jeder
  Konsument von `:core` muss seine Plattform-Variante selbst als `implementation` mitbringen:
  `desktop` + `server` → `lazysodium-java` + `jna` (jar); `android` → `lazysodium-android@aar` +
  `jna@aar`. Für `:core:test` wird `lazysodium-java` + `jna` zusätzlich als `testImplementation`
  deklariert, weil der Default-`Sodium`-Binding (`LazySodiumJava(SodiumJava())`) im `<clinit>` zur
  Testlaufzeit tatsächlich aufgelöst wird.
- **R8 sieht `Sodium.<clinit>`:** Da `Sodium` einen JVM-Default-Binding hat, muss `android/proguard-rules.pro`
  `-dontwarn com.goterl.lazysodium.LazySodiumJava` und `-dontwarn com.goterl.lazysodium.SodiumJava`
  enthalten (plus die JNA-AWT-Helper). Auf Android ersetzt `MlsApplication.onCreate()` den Binding
  per `Sodium.useBinding(...)`, BEVOR irgendeine Crypto-Funktion läuft. Der Inhalt von
  `android/build/outputs/mapping/release/missing_rules.txt` (von R8 generiert) gehört ins Repo,
  nicht nur lokal.
- **Session-Controller-Pattern:** Desktop `session/Vault.java` und Android
  `session/AndroidVault.kt` besitzen den entsperrten `accountKey` als wipeable `SecretBytes`, den
  verschlüsselten lokalen Store und die SyncEngine. Login:
  `loginParams -> deriveAuthKeyForLogin -> login(authKey) -> getAccountKey -> unlockWithPassword`.
- **Design-Tokens generiert:** `design/generated/**` ist Build-Output von `:design:run` und wird nie
  von Hand editiert. Nach Änderungen an `Tokens.kt`/`Renderers.kt`: `./gradlew :design:run` und
  `./gradlew :design:test`.
- **Android/AGP:** `:android` nutzt AGP 9 Built-in Kotlin. **Root `build.gradle.kts` deklariert alle
  vier vom Build benutzten Plugins** (`kotlin.jvm`, `kotlin.serialization`, `android.application`,
  `kotlin.compose`) zentral mit `apply false`, damit Gradle sie nicht pro Subprojekt unabhängig
  nachlädt (sonst: `The Kotlin Gradle plugin was loaded multiple times in different
  subprojects`). Subprojekte ziehen weiterhin nur per `alias(libs.plugins.…)` ohne eigene
  Version. Der ältere Hinweis ("kein Root-Block, weil AGP/KGP auf entfernten Android-Legacy-APIs
  crasht") bezog sich auf `kotlin.android` aus AGP-8-Zeiten — die jetzt gelisteten vier
  Plugin-IDs existieren in AGP 9 / KGP 2.4.0 alle nativ und sind safe.

## Security-Invarianten

- Der Server darf nie Master-Passwort, Master-Key, KEK oder Account-Key erhalten. Er bekommt nur den
  passwortabgeleiteten `authKey` (gespeichert als `Argon2id(authKey)`) und Ciphertext.
- Keine eigene Kryptografie erfinden. Nur libsodium-Primitiven via `Sodium.kt`: Argon2id,
  XChaCha20-Poly1305 IETF mit frischem 24-Byte-Nonce pro Verschlüsselung, `crypto_kdf`.
- Note-IDs sind als AEAD associated data gebunden.
- Keys/Secrets sind `SecretBytes` (wipeable `byte[]`), nie `String`; nach Gebrauch wipen.
- Nie Request-Bodies, Ciphertext, Tokens, `authKey` oder E-Mail-Adressen loggen.
- Metadaten sind ausdrücklich nicht geschützt; ein kompromittierter Client ist out of scope. Nicht
  überclaimen.

## Konventionen

- Dependency-Versionen bleiben in `gradle/libs.versions.toml`.
- Tests sind JUnit 5 (`useJUnitPlatform()`).
- Releases laufen über `scripts/` (Key-Gen, Packaging, GPG sign/verify) und Tags `v*` für
  `.github/workflows/release.yml`; Details stehen in `RELEASE.md`.
- Signing-Keys kommen nur aus Env/CI-Secrets oder der gitignorierten `.signing/`.

## Aktueller Stand

- `core`, `server`, `desktop`, `design`: Standard-JVM-Build ohne Android-SDK konfiguriert weiterhin.
- `:android`: Plugin-/Gradle-Konfiguration läuft auf Gradle `9.6.1` + AGP `9.2.1` + KGP `2.4.0`.
  `./gradlew :android:assembleRelease` baut im Sandbox-Container (JDK 21 via
  `openjdk-21-jdk-headless`, Android SDK unter `/tmp/mls-android-sdk`) eine 5.4 MB unsigned
  APK in ~2m 46s. Voraussetzung auf einer anderen Maschine: JDK 21 (Pfad in `gradle.properties`
  `org.gradle.java.installations.paths` ggf. anpassen) + Android-SDK (`local.properties`
  `sdk.dir` oder `ANDROID_HOME`). Frische Release-APK liegt unter
  `android/build/outputs/apk/release/android-release-unsigned.apk` (unsigned, solange
  `MLS_ANDROID_KEYSTORE` nicht gesetzt ist).
- **libsodium-Sichtbarkeit:** `:core` deklariert `lazysodium-java` + `jna` als `compileOnly`;
  Konsumenten ziehen die plattformpassende Variante selbst. Diese Konvention ist bei den
  "Wichtigen Entscheidungen" festgehalten.
- **Plugin-Classloader:** Alle vier Build-Plugins sind zentral in der Root `build.gradle.kts` mit
  `apply false` deklariert (siehe "Wichtige Entscheidungen" → Android/AGP).
- `CLAUDE.md` existiert nicht mehr; diese Datei ist die maßgebliche Agenten-Anleitung.

## Offene Punkte / Next Steps

1. Release-APK auf einer Maschine mit Android-SDK + `MLS_ANDROID_KEYSTORE`-Env-Variablen signieren
   und auf einem echten Gerät / Emulator verifizieren (Login-Flow, Sync, Biometric-Unlock).
2. Brand-Fonts (Spectral/Geist) in `android/.../res/font/` bündeln und in `Theme.kt` verdrahten.
3. JNA-AAR schleppt JNI-`libjnidispatch.so` für `mips` und `mips64` mit; falls Releases keine
   MIPS-Architektur mehr unterstützen müssen, `packaging.jniLibs.useLegacyPackaging` / `abiFilters`
   in `android/build.gradle.kts` einschränken, um die APK-Größe zu drücken.
4. `implementation(project(":core"))` etc. nutzt noch die `Project`-Dependency-Notation, die in
   Gradle 10 entfernt wird (Deprecation-Warning sichtbar in `--warning-mode=all`). Migration auf
   `provider { project(":core") }` / `dependencies.create(project(...))`, wenn das Repo auf
   Gradle 10 springt.
