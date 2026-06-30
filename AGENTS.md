# AGENTS.md — my-little-secrets

**Zweck:** Orientierung für KI-Agenten (Claude Code / Hermes / Codex). Beschreibt Struktur,
Tech-Stack, Entscheidungen, aktuellen Stand, Build-Umgebung, Krypto-Invarianten und offene Punkte,
damit ein anderer Agent nahtlos weiterarbeiten kann.

---

## Letzter Durchlauf

**Aufgabe:** `AGENTS.md` und `CLAUDE.md` zusammenführen, so dass nur `AGENTS.md` bleibt, und den
Buildfehler bei `./gradlew :android:assembleRelease`
(`com/android/build/gradle/BaseExtension`) beheben.

**Ergebnis:** `CLAUDE.md` wurde gelöscht und dessen relevante Inhalte (Umgebung, Befehle,
Architektur, Security-Invarianten, Konventionen) wurden in diese Datei übernommen. Der Android-Build
wurde auf AGP `9.2.1` migriert und nutzt jetzt AGP 9 Built-in Kotlin; `org.jetbrains.kotlin.android`
wird im Android-Modul nicht mehr angewandt. Der Root-`build.gradle.kts` lädt Kotlin-Plugins nicht mehr
per `apply false` auf den gemeinsamen Plugin-Classpath, weil genau das AGP 9/Kotlin in `:android`
gegen entfernte Legacy-Typen (`BaseVariant`/`BaseExtension`) laufen ließ. `settings.gradle.kts` nimmt
`:android` nun automatisch auf, wenn ein Android-SDK konfiguriert ist oder ein Android-Task explizit
angefordert wird.

**Verifiziert:** `./gradlew help`, `./gradlew :android:help` und `./gradlew build` funktionieren
weiterhin ohne Android-SDK. `./gradlew :android:assembleRelease` kommt über Plugin-Auflösung und
Android-Projektkonfiguration hinweg; in dieser Umgebung stoppt es anschließend erwartungsgemäß bei
`SDK location not found`, weil kein `ANDROID_HOME` und kein `local.properties` mit `sdk.dir` vorhanden
ist. Der ursprüngliche `BaseExtension`-Fehler ist damit beseitigt; ein vollständiger APK-Build braucht
eine Maschine mit Android-SDK. `:android:help` meldet noch eine Kotlin-Plugin-Classloader-Warnung
zwischen `:android` und `:core`; das ist dokumentiert, weil die empfohlene Root-`apply false`-Variante
hier den AGP-9/Kotlin-Legacy-API-Crash reaktiviert.

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
- `:android`: Plugin-/Gradle-Konfiguration ist auf Gradle `9.6.1` repariert. Ein echter
  `assembleRelease` braucht weiterhin ein Android-SDK.
- `CLAUDE.md` existiert nicht mehr; diese Datei ist die maßgebliche Agenten-Anleitung.

## Offene Punkte / Next Steps

1. Auf einer Maschine mit Android-SDK `./gradlew :android:assembleRelease` vollständig ausführen.
2. Android-Quellcode erstmals real kompilieren und API-/Dependency-Probleme beheben, falls der
   SDK-Build weitere Fehler zeigt.
3. Falls AGP/KGP künftig stabil ohne Klassenlader-Warnungen zusammenarbeitet, prüfen, ob eine
   einheitlichere Kotlin-Plugin-Classpath-Strategie wieder möglich ist, ohne `BaseVariant`/
   `BaseExtension`-Fehler zu reaktivieren.
4. Brand-Fonts (Spectral/Geist) in `android/.../res/font/` bündeln und in `Theme.kt` verdrahten.
