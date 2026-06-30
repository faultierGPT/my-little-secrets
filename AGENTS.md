# AGENTS.md — my-little-secrets

**Zweck:** Orientierung für KI-Agenten (Claude Code / Hermes / Codex). Beschreibt Struktur,
Tech-Stack, Entscheidungen, aktuellen Stand, Build-Umgebung, Krypto-Invarianten und offene Punkte,
damit ein anderer Agent nahtlos weiterarbeiten kann.

---

## Letzter Durchlauf

**Aufgabe:** `./gradlew :android:assembleRelease` brach mit `CheckDuplicatesRunnable` ab —
Hunderte `Duplicate class com.goterl.lazysodium.*` und `Duplicate class com.sun.jna.*` zwischen
`lazysodium-android-5.1.0.aar` und `lazysodium-java-5.2.0.jar` (plus `jna-5.19.1` vs `jna-5.17.0`).

**Ursache:** `:core` deklarierte `lazysodium-java` + `jna` als `implementation`. Damit zogen
`:desktop` und `:server` das JVM-Binding transitiv über `:core` mit — soweit OK. Aber auch
`:android` zog es so transitiv mit, und fügte zusätzlich `lazysodium-android` + `jna`-AAR als
`implementation` hinzu. AGP kombinierte beides im Release-Classpath → `checkReleaseDuplicateClasses`
feuerte. Die Architektur ("libsodium-Binding ist austauschbar", siehe AGENTS.md-Bestimmungen) war
schon richtig dokumentiert, nur die Build-Konfiguration hatte das nicht umgesetzt.

**Fix:**

1. `core/build.gradle.kts`: `lazysodium-java` + `jna` von `implementation` auf `compileOnly`
   umgestellt. `:core` sieht die Symbole weiterhin beim Kompilieren, gibt sie aber NICHT mehr
   transitiv an Konsumenten weiter. Zusätzlich `testImplementation(libs.lazysodium.java)` +
   `testImplementation(libs.jna)`, damit `:core:test` seinen Default-`Sodium`-Binding
   (`LazySodiumJava(SodiumJava())`) zur Laufzeit findet.
2. `desktop/build.gradle.kts` + `server/build.gradle.kts`: Ziehen `lazysodium-java` + `jna` jetzt
   selbst als `implementation` (brauchen den Default-JVM-Binding für `Sodium.pwhash*` und
   `AuthVerifier`).
3. `android/build.gradle.kts`: unverändert — zieht weiterhin `lazysodium-android-5.1.0@aar` +
   `jna-5.17.0@aar` selbst.
4. `android/proguard-rules.pro`: `-dontwarn com.goterl.lazysodium.LazySodiumJava`,
   `-dontwarn com.goterl.lazysodium.SodiumJava` und vier `-dontwarn java.awt.*`-Regeln
   hinzugefügt. R8 sieht im statischen `<clinit>` von `Sodium` den JVM-Default und würde sonst
   bei `minifyReleaseWithR8` fehlschlagen — auf Android wird der Default aber durch
   `MlsApplication.onCreate()` über `Sodium.useBinding(...)` ersetzt, BEVOR irgendeine Crypto-
   Funktion läuft. Das `missing_rules.txt`, das R8 automatisch generiert, ist jetzt Teil der
   `proguard-rules.pro`.

**Verifikation:**

- `./gradlew :core:test :server:test :design:test` → `BUILD SUCCESSFUL` (1m 22s).
- `./gradlew :desktop:compileJava :desktop:compileTestJava` → grün (kein vollständiger
  `:desktop:test` im Sandbox-Container, da fontconfig/X11 fehlen — bekanntes Umgebungslimit).
- `./gradlew :android:assembleRelease` in einem Container mit JDK 21 + Android SDK 35 → `BUILD
  SUCCESSFUL` in 2m 47s, 50 tasks, 10 executed. Erzeugt
  `android/build/outputs/apk/release/android-release-unsigned.apk` (5.4 MB, valides APK per
  `file(1)`).
- APK-Inhalt verifiziert: `lib/{arm64-v8a,armeabi-v7a,armeabi,x86,x86_64}/libsodium.so` +
  `libjnidispatch.so` (aus dem JNA-AAR). **Keine** `LazySodiumJava` / `SodiumJava` / JVM-JNA-Klassen
  im APK — das Duplikat-Problem ist weg.
- Lint-Vital (`lintVitalRelease`) ohne Errors.
- Release-APK ist korrekt unsigned, weil `MLS_ANDROID_KEYSTORE` im Container nicht gesetzt war —
  `android/build.gradle.kts` lässt in dem Fall die APK bewusst unsigned, statt den Build zu failen
  (siehe Kommentar im Build-Skript).

**Wichtigste Erkenntnis:** Das `checkReleaseDuplicateClasses`-Problem war kein AGP-9-Inkompatibilitäts-
Bug, sondern eine fehlerhafte Dependency-Sichtbarkeit. Die korrekte Konvention ist jetzt: `:core`
deklariert seine libsodium-API-Symbole als `compileOnly`, jede Plattform (Desktop/Server mit
JVM-Binding, Android mit AAR-Binding) bringt ihre Variante selbst als `implementation` mit.
Diese Aufteilung gehört ab jetzt zu den Konventionen — sie ist unten bei den "Wichtigen
Entscheidungen" festgehalten.

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

Vor jedem `gradle`/`java`/`jar`-Kommando:

```bash
source "$HOME/tools/env.sh"   # JAVA_HOME=jdk-21.0.11, PATH bekommt gradle-9.6.1
```

Diese Umgebung kann kein Android-APK bauen, weil kein Android-SDK installiert ist. Sie kann außerdem
JavaFX nicht pixel-rendern, weil fontconfig/X11/GL fehlen (`Font.getDefault()` scheitert). Das sind
Umgebungslimits, keine Projektbugs.

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
- **Android/AGP:** `:android` nutzt AGP 9 Built-in Kotlin. Den Root-`plugins { kotlin... apply false }`
  Block nicht wieder einführen, solange AGP/KGP sonst gegen entfernte Android-Legacy-APIs crasht.

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
- `:android`: Plugin-/Gradle-Konfiguration ist auf Gradle `9.6.1` repariert. Echte
  `assembleRelease`-Builds laufen jetzt durch (siehe "Letzter Durchlauf" oben) — Voraussetzung ist
  weiterhin ein Android-SDK + `local.properties`-`sdk.dir` oder `ANDROID_HOME`. Die frische
  Release-APK liegt unter
  `android/build/outputs/apk/release/android-release-unsigned.apk` (unsigned, solange
  `MLS_ANDROID_KEYSTORE` nicht gesetzt ist).
- **libsodium-Sichtbarkeit:** `:core` deklariert `lazysodium-java` + `jna` jetzt `compileOnly`;
  Konsumenten ziehen die plattformpassende Variante selbst. Diese Konvention ist bei den
  "Wichtigen Entscheidungen" festgehalten.
- `CLAUDE.md` existiert nicht mehr; diese Datei ist die maßgebliche Agenten-Anleitung.

## Offene Punkte / Next Steps

1. Release-APK auf einer Maschine mit Android-SDK + `MLS_ANDROID_KEYSTORE`-Env-Variablen signieren
   und auf einem echten Gerät / Emulator verifizieren (Login-Flow, Sync, Biometric-Unlock).
2. Brand-Fonts (Spectral/Geist) in `android/.../res/font/` bündeln und in `Theme.kt` verdrahten.
3. JNA-AAR schleppt JNI-`libjnidispatch.so` für `mips` und `mips64` mit; falls Releases keine
   MIPS-Architektur mehr unterstützen müssen, `packaging.jniLibs.useLegacyPackaging` / `abiFilters`
   in `android/build.gradle.kts` einschränken, um die APK-Größe zu drücken.
4. Falls AGP/KGP künftig stabil ohne Klassenlader-Warnungen zusammenarbeitet, prüfen, ob eine
   einheitlichere Kotlin-Plugin-Classpath-Strategie wieder möglich ist, ohne `BaseVariant`/
   `BaseExtension`-Fehler zu reaktivieren.
