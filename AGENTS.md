# AGENTS.md — my-little-secrets

**Zweck:** Orientierung für KI-Agenten (Claude Code / Hermes / Codex). Beschreibt Struktur, Tech-Stack,
Entscheidungen, aktuellen Stand und offene Punkte, damit ein anderer Agent nahtlos weiterarbeiten kann.
Maßgeblich für Detailregeln bleibt `CLAUDE.md` (Build-Umgebung, Krypto-Invarianten, Konventionen).

---

## Letzter Durchlauf

**Aufgabe:** `./gradlew :android:assembleRelease` schlug fehl mit
`Error resolving plugin [id: 'org.jetbrains.kotlin.android', version: '2.4.0'] > … already on the
classpath with an unknown version, so compatibility cannot be checked`.

**Diagnose:** Klassischer Kotlin-Multi-Modul-Plugin-Classpath-Konflikt. Die Root-`build.gradle.kts`
deklariert `kotlin.jvm` und `kotlin.serialization` (`apply false`) — beide liegen **im selben
Artefakt** `org.jetbrains.kotlin:kotlin-gradle-plugin`, das damit auf dem geteilten Build-Plugin-
Classpath landet. `org.jetbrains.kotlin.android` steckt **im selben Artefakt**. Als `:android` es per
Catalog-Alias **mit** Version `2.4.0` erneut anforderte, fand Gradle das Plugin bereits auf dem
Classpath, konnte dessen Version aber nicht lesen → Fehler. `kotlin.plugin.serialization` hätte als
Nächstes denselben Fehler ausgelöst (auch im kotlin-gradle-plugin).

**Fix (angewandt, in `android/build.gradle.kts`):** `kotlin.android` und `kotlin.plugin.serialization`
**ohne** Version anfordern (`id("…")` statt `alias(libs.plugins…)`). Die Kotlin-Version bleibt zentral
über den Catalog-`kotlin`-Ref via die Root-Aliase gepinnt. `kotlin.compose` bleibt versioniert
(`alias`), da es ein **separates** Artefakt ist (compose-compiler-gradle-plugin), nicht auf dem
Classpath.

**Verifiziert:** `:android` temporär in `settings.gradle.kts` aufgenommen und `:android:help`
ausgeführt — der gemeldete Plugin-Resolution-Fehler ist **weg**; der Build kommt jetzt an der
Plugin-Auflösung **vorbei**. `settings.gradle.kts` wurde danach wieder zurückgesetzt (unverändert).

**Wichtigste Erkenntnis / nächster Blocker:** Nach dem Fix scheitert der Build später an
`NoClassDefFoundError: com/android/build/gradle/BaseExtension`. Das ist **AGP 8.7.3 inkompatibel mit
dem im Wrapper gepinnten Gradle 9.6.1** (AGP 9 hat den alten DSL-Typ `BaseExtension` entfernt). Um
`:android` gegen Gradle 9.6.1 zu bauen, muss auf **AGP 9.x** migriert werden (AGP 9.0 braucht Gradle
≥ 9.1, AGP 9.2.0 ≥ 9.4.1) inkl. AGP-9-DSL-Migration. Das braucht ein echtes Android-SDK und wurde
hier **nicht** durchgeführt/verifiziert (siehe Kommentar bei `agp` in `gradle/libs.versions.toml` und
„Offene Punkte"). Geänderte Dateien dieses Laufs: `android/build.gradle.kts`,
`gradle/libs.versions.toml` (nur Kommentar), `AGENTS.md` (neu).

---

## Tech-Stack

- **Sprache/Build:** Kotlin 2.4.0, Java 21 (Temurin), Gradle 9.6.1 (Wrapper). JUnit 5.
- **Krypto:** libsodium via `core/.../crypto/Sodium.kt` (LazySodiumJava Desktop/Server,
  LazySodiumAndroid auf Android). Argon2id, XChaCha20-Poly1305 IETF, `crypto_kdf`.
- **Server:** Ktor + Netty; PostgreSQL (Prod) / H2 (Tests). Speichert **nur Ciphertext**.
- **Desktop:** reines Java + JavaFX über `:core` (E2E hier verifizierbar).
- **Android:** Kotlin + Jetpack Compose über `:core` (nur Quellcode; hier nicht baubar — kein SDK).
- **Versionen** zentral & gepinnt in `gradle/libs.versions.toml` (nicht aus dem Gedächtnis bumpen).

## Module / Struktur

```
core/     Krypto + DTOs + Ktor-API-Client + offline-first SyncEngine + verschl. Cache + jvm/-Bridge
server/   Ktor/Netty; nur Ciphertext. app.mls.server.Embedded bootet in-process für Tests
design/   EINE Token-Quelle (Tokens.kt) -> generierte Compose-Theme/JavaFX-CSS/Java-Konstanten/tokens.json
desktop/  reines Java + JavaFX über core
android/  Kotlin + Compose über core (NICHT in settings.gradle.kts; AGP braucht SDK)
```

## Wichtige Entscheidungen (Kontext)

- **Eine Krypto-Quelle:** Krypto lebt nur in `core/`, von beiden Clients geteilt — **nie** im Client
  reimplementieren.
- **Suspend-Interop:** Desktop ist 100 % Java und kann keine `suspend`-Funktionen aufrufen → nutzt
  `app.mls.core.jvm.BlockingApi`/`BlockingSync` (runBlocking-Adapter in `core`). Android nutzt die
  Suspend-API direkt. Interop-Anpassungen in `core`, nicht in `desktop`.
- **`:android` bewusst aus `settings.gradle.kts` ausgeschlossen:** AGP scheitert ohne installiertes
  Android-SDK und würde sonst **jeden** `./gradlew`-Aufruf (auch core/server/desktop) brechen. Zum
  Bauen: SDK installieren, `include(":android")` ergänzen — siehe `android/README.md`.
- **Design-Tokens generiert:** `design/generated/**` ist Build-Output von `:design:run`. Nie von Hand
  editieren; `Tokens.kt`/`Renderers.kt` ändern, `:design:run`, dann `:design:test`.
- **Security-Invarianten:** Server bekommt nie Passwort/Master-Key/KEK/Account-Key — nur `authKey`
  (gespeichert als `Argon2id(authKey)`) und Ciphertext. Keys als wipeable `SecretBytes`. Nie Bodies/
  Ciphertext/Tokens/authKey/E-Mails loggen. Details: `SECURITY.md` / `CLAUDE.md`.

## Build / Umgebung (Kurz)

- **Vor jedem java/gradle/jar:** `source "$HOME/tools/env.sh"` (JAVA_HOME jdk-21, Gradle 9.6.1 im PATH).
- Diese Umgebung kann **nicht**: Android-APK bauen (kein SDK), JavaFX pixel-rendern (kein
  fontconfig/X11/GL). CI (`.github/workflows/`) deckt ab, was hier nicht geht.
- `./gradlew build` baut+testet alle JVM-Module (core, server, desktop, design).

## Aktueller Stand

- core/server/desktop/design: baubar & getestet (laut CLAUDE.md + Memory; Desktop E2E verifiziert).
- `:android`-Plugin-Block: **gefixt** (Plugin-Resolution-Konflikt behoben, verifiziert). Quellcode des
  Android-Moduls weiterhin in keinem CI mit echtem SDK kompiliert.

## Offene Punkte / Next Steps

1. **AGP-9-Migration für `:android` (Hauptblocker für `assembleRelease`):** Auf einer Maschine **mit**
   Android-SDK:
   - `agp` in `gradle/libs.versions.toml` auf eine Gradle-9.6.1-kompatible 9.x-Version setzen (gegen
     die Registry prüfen — nicht aus dem Gedächtnis; AGP 9.2.0 verlangt Gradle ≥ 9.4.1).
   - `include(":android")` in `settings.gradle.kts` ergänzen, `ANDROID_HOME`/`local.properties` setzen.
   - AGP-9-DSL-Migration in `android/build.gradle.kts`: `kotlinOptions {}` →
     `kotlin { compilerOptions { jvmTarget … } }`, `BaseExtension`-Nutzung entfernen (Übergangsweise
     ggf. `android.newDsl=false` in `gradle.properties`), `compileSdk`/`targetSdk` ggf. anheben.
   - `./gradlew :android:assembleRelease` real bauen & testen, dann `settings.gradle.kts` wieder
     ausschließen (Repo-Invariante) oder dokumentiert lassen.
2. Android-Quellcode erstmals real kompilieren (APIs/Versionen sind ungetestet, siehe
   `android/README.md` „Caveats").
3. Brand-Fonts (Spectral/Geist) in `android/.../res/font/` bündeln und in `Theme.kt` verdrahten.
