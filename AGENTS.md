# AGENTS.md — my-little-secrets

**Zweck:** Orientierung für KI-Agenten (Claude Code / Hermes / Codex). Beschreibt Struktur,
Tech-Stack, Entscheidungen, aktuellen Stand, Build-Umgebung, Krypto-Invarianten und offene Punkte,
damit ein anderer Agent nahtlos weiterarbeiten kann.

---

**Aufgabe:** `docker compose up -d --build` schlug im Server-Build-Step fehl mit
`Configuring project ':desktop' without an existing directory is not allowed. The configured
projectDirectory '/src/desktop' does not exist`. `:core`, `:server`, `:design` bauten grün, der
Crash kam beim Konfigurieren von `:desktop` während `:server:installDist`.

**Ursache:** Kontext- vs. Settings-Diskrepanz. `.dockerignore` exkludiert `desktop/` und
`android/` aus dem Docker-Build-Kontext (sinnvoll — ein JavaFX-Client und ein Compose-Modul
gehören nicht in ein Server-Image und würden den Kontext aufblähen). `settings.gradle.kts`
hatte `:desktop` aber unconditional via `include(":desktop")` deklariert, und Gradle 9.6.1
wirft bei fehlendem Projektverzeichnis einen harten Konfigurationsfehler — auch wenn der Task
`:desktop` gar nicht anfordert. Der `:android`-Fall war schon korrekt gelöst (conditional auf
`androidSdkPath()`/Task-Request); `:desktop` lief noch in der alten, nicht-bedingten Form.

**Fix — zwei Schichten, weil "nur das include" allein nicht reicht:**

1. `settings.gradle.kts`: `include(":desktop")` ist jetzt hinter `if (file("desktop").isDirectory)`
   (analog zu `:android`). In einer normalen Dev-Checkout ist das Verzeichnis da → `:desktop`
   wird konfiguriert, alles wie vorher. Im Docker-Build-Kontext ist es gestrippt → `:desktop`
   wird gar nicht erst included, der Konfigurationsfehler verschwindet.
2. `server/Dockerfile`: `./gradlew :server:installDist` bekommt `--configure-on-demand`. Damit
   konfiguriert Gradle nur noch `:server` und seine transitiven Project-Dependencies (`:core`).
   `:design`, `:desktop` (wenn included), `:android` (wenn included) werden gar nicht erst
   konfiguriert — kein Aufwand für Module, die der Server-Task nicht braucht. Auf einer
   normalen Dev-Maschine ist der Flag ein No-op: die Module werden unabhängig vom Flag
   konfiguriert, wenn ihr Verzeichnis da ist; die Tasks, die wir nicht anfordern, werden
   einfach nicht ausgeführt.

**Verifikation:**

- `./gradlew projects --no-daemon --console=plain` → zeigt `:core`, `:design`, `:desktop`,
  `:server` (lokaler Dev-Kontext, alle Verzeichnisse da).
- `./gradlew :server:installDist --no-daemon --configure-on-demand --console=plain`
  → `BUILD SUCCESSFUL`, nur `:core` + `:server` Tasks in der Konsole.
- `./gradlew :core:test :server:test :design:test --no-daemon --console=plain`
  → `BUILD SUCCESSFUL` (unverändertes Verhalten für die JVM-Suite).
- `docker compose build` → `Image ...-server Built`, kein `failed to solve:`.
- `docker compose up -d` → beide Container starten, db healthy, server `GET /health -> 200`,
  Ktor lauscht auf `0.0.0.0:8080`, Hikari-Pool gegen Postgres sauber.

**Wichtigste Erkenntnis:** Gradle 9 bricht hart ab, wenn ein included-Project-Verzeichnis
fehlt — auch wenn der angefragte Task das Projekt gar nicht braucht. Die saubere Antwort
ist, das include an die Verzeichnis-Existenz zu koppeln UND in Docker-Builds explizit
`--configure-on-demand` zu setzen, damit die Build-Konfiguration nur das konfiguriert, was
der angefragte Task wirklich braucht. Die AGENTS-Konvention dazu steht jetzt bei
"Wichtige Entscheidungen" (Conditional includes + Docker-Build-Filter).

---

## Letzter Durchlauf

**Aufgabe:** Signierte Release-APK (Android 14, arm64-v8a) startete auf dem Gerät und crashte
**sofort** mit `FATAL EXCEPTION: main — java.lang.NoClassDefFoundError: Failed resolution of:
Lcom/goterl/lazysodium/LazySodiumJava` in `MlsApplication.onCreate`. Der Build lief grün;
`R8` und `lintVitalRelease` waren sauber. Die APK enthielt nur die AAR-flavored libsodium-Klassen
(`LazySodiumAndroid`, `SodiumAndroid`, `LazySodium`, `interfaces/AEAD`, `interfaces/PwHash$Alg`,
`com.sun.jna.Native`, …) — **keine** JVM-`LazySodiumJava`/`SodiumJava` in den dex files.

**Ursache:** Architectural constraint collision, nicht der vorherige `checkReleaseDuplicateClasses`-Bug.
`:core` deklariert `lazysodium-java` + `jna` als `compileOnly`, damit die JVM-JAR nicht transitiv
im Android-Release-Classpath landet (siehe "Wichtige Entscheidungen"). Der Android-AAR
(`lazysodium-android`) liefert `LazySodiumAndroid` + `SodiumAndroid` + `com.sun.jna.*` als
Runtime-Klassen. `:core` selbst hatte in `Sodium.kt` aber einen JVM-Default-Binding
(`private var binding = LazySodiumJava(SodiumJava())`) plus field-initializer-Referenzen auf
`AEAD.XCHACHA20POLY1305_IETF_KEYBYTES` und ein `PwHash.Alg.PWHASH_ALG_ARGON2ID13`-Argument
im `cryptoPwHash(...)`-Call. Diese Symbole sind als `compileOnly` für Android nicht erreichbar
— sobald `MlsApplication.onCreate` auf Zeile `Sodium.useBinding(...)` zum ersten Mal auf
`Sodium` zugreift, läuft `Sodium.<clinit>`, der `LazySodiumJava` lädt → `ClassNotFoundException`
(siehe AGENTS.md "libsodium-Binding-Sichtbarkeit"). Der `compileOnly`-Boundary war also
*konsistent in der Bytecode-Sicht* (kein JAR-Leak ins APK), aber *inkonsistent in der
Laufzeit-Sicht* (`<clinit>` braucht die JVM-Klassen).

**Fix — `Sodium.kt` komplett umgebaut, damit `<clinit>` keine JVM-Klassen mehr anfasst:**

1. `binding: LazySodium` ist jetzt `@Volatile var ? = null`. Keine Default-Initialisierung im
   `<clinit>`. Access auf `binding` (via `ls`) wirft `IllegalStateException` mit präziser
   Anleitung, falls `useBinding(...)` noch nicht gerufen wurde (deutlich besser als das
   ursprüngliche `NoClassDefFoundError` mitten im `onCreate`).
2. AEAD / KDF / PWHASH-Größen-`const val`s sind hart gepingt (32 / 24 / 16 für
   `XCHACHA20POLY1305_IETF_*`, 32 `MASTER_KEY`, 8 `CONTEXT`, 16 `ARGON2ID_SALTBYTES`,
   128 `STRBYTES`) statt über `AEAD.XCHACHA20POLY1305_IETF_KEYBYTES` o.ä. geladen. Die
   libsodium-ABI-Werte sind stabil; ein Reference auf das Interface hätte dessen
   `<clinit>` auf der `:core`-Seite erzwungen.
3. `useBinding(...)` resolved `com.goterl.lazysodium.interfaces.PwHash$Alg.PWHASH_ALG_ARGON2ID13`
   reflektiv über den ClassLoader des aktiven Bindings (JVM-JAR unter Desktop/Server,
   Android-AAR unter Mobile), plus die passende `LazySodium#cryptoPwHash(...)`-Method. Beide
   werden einmalig pro Prozess gecacht. `pwhash(...)` selbst macht einen reflection-dispatch
   pro Call — KDF-Kosten dominieren, der Overhead ist nicht messbar.
4. JVM-Default-Init als Fallback: ein `init`-Block probiert `Class.forName(
   "com.goterl.lazysodium.LazySodiumJava", …)` und ruft `useBinding(...)` mit dem JVM-Binding,
   wenn das JAR auf dem Classpath ist. Auf Android (`compileOnly` ⇒ JVM-JAR nicht da)
   schlägt das `forName` fehl, der `init`-Block macht silent nichts, und `MlsApplication.
   onCreate` ruft `useBinding(LazySodiumAndroid(SodiumAndroid()))` ganz normal. Desktop,
   Server und `:core:test` brauchen daher keinen expliziten Setup mehr.
5. Architektur ist jetzt symmetrisch: jeder Plattform-Consumer (Desktop `main`, Server
   `main`, Android `MlsApplication.onCreate`) installiert seinen Binding über `useBinding(...)`
   bevor irgendwelches Crypto läuft. Die `init`-Block-Default für JVM ist nur Komfort für
   Test-Runner.

**Verifikation:**

- `./gradlew :core:test :server:test :design:test --console=plain` → `BUILD SUCCESSFUL`
  (14 tasks, 9 executed) — `:core:test` enthält einen echten 256 MiB Argon2id-Run, also
  trifft der reflection-Dipatch im Hot-Path eines echten KDF-Laufs. Alle grün.
- `./gradlew :android:assembleRelease --console=plain` → `BUILD SUCCESSFUL` in ~3s
  (51 tasks, 4 executed, Rest UP-TO-DATE nach dem ersten Cold-Build). `minifyReleaseWithR8`,
  `lintVitalRelease`, `validateSigningRelease`, `packageRelease` alle grün.
- `dexdump -l plain classes.dex` zeigt die erwartete Klassen-Mischung:
  `LazySodiumAndroid`, `SodiumAndroid`, `LazySodium`, `interfaces/AEAD`, `interfaces/PwHash$Alg`,
  `com.sun.jna.Native`, `NativeLong`, … — **keine** `LazySodiumJava`, `SodiumJava`,
  `JnaRuntime`, oder andere JVM-flavored Binding-Klassen im APK. R8-Bereinigung war sauber.
- APK: `android/build/outputs/apk/release/android-release.apk` (5.4 MB, signiert mit
  Test-Keystore für die Verifikation), enthält `lib/{arm64-v8a,armeabi-v7a,armeabi,x86,
  x86_64}/libsodium.so` + `libjnidispatch.so` für 5 ABIs. Auf dem Zielgerät (arm64-v8a,
  Android 14) crasht sie nicht mehr beim Start.

**Wichtigste Erkenntnis:** Die alte Story ("compileOnly löse das Duplikat-Problem") hat die
funktionale Konsequenz — `Sodium.<clinit>` darf dann auch auf `:core`-Seite nichts
JVM-Spezifisches mehr referenzieren — übersehen. Der eigentliche Beweis, dass das Layout
wirklich funktionierte, wäre immer ein App-Start auf einem Gerät gewesen, nicht nur ein
APK-Inhalts-Audit. Die Reflection-Umstellung von `pwhash(...)` ist die einzige Stelle in
`:core`, die JVM- und Android-spezifische Diskriminierung braucht; alle anderen
`Sodium`-Operationen (`kdfDerive`, `aead*`, `pwhashStr*`, `randomBytes`, `memzero`) laufen
über die `LazySodium`-Interface und sind 100 % portabel.

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

- **Conditional project-includes in `settings.gradle.kts`:** `:android` UND `:desktop` werden nur
  included, wenn ihr Verzeichnis da ist bzw. wenn ein Android-SDK/Task vorliegt. Hintergrund:
  Gradle 9 wirft einen harten Konfigurationsfehler, wenn ein `include(...)` auf ein fehlendes
  Verzeichnis zeigt — auch wenn der angefragte Task das Modul gar nicht braucht. Der
  Server-Docker-Build exkludiert `desktop/` und `android/` per `.dockerignore` (sie sind nicht
  Teil des Server-Images und würden den Build-Kontext unnötig aufblähen), also MUSS das
  include in `settings.gradle.kts` an die Verzeichnis-Existenz gekoppelt sein, sonst bricht
  `docker compose build`. Auf einer normalen Dev-Maschine verhalten sich die conditional
  includes transparent — die Verzeichnisse sind da, die Module werden konfiguriert.
- **Docker-Builds filtern mit `--configure-on-demand`:** `server/Dockerfile` ruft
  `./gradlew :server:installDist --no-daemon --configure-on-demand`. Damit konfiguriert Gradle
  nur `:server` und seine transitiven Project-Dependencies (`:core`) — auch wenn
  `settings.gradle.kts` (in einem Dev-Kontext) noch `:design`/`:desktop`/`:android` includen
  würde. Auf der Dev-Maschine ist der Flag ein No-op; im Container ist er die Versicherung,
  dass fehlende oder irrelevante Module den Server-Build nicht aufhalten.
- **Eine Krypto-Quelle:** Krypto lebt nur in `core/`, von beiden Clients geteilt. Nie im Client
  reimplementieren.
- **Suspend-Interop:** Desktop ist 100 % Java und kann keine `suspend`-Funktionen aufrufen. Es nutzt
  `app.mls.core.jvm.BlockingApi` / `BlockingSync` (`runBlocking`-Adapter in `core`). Android nutzt
  die Suspend-API direkt. Interop-Anpassungen gehören in `core`, nicht in `desktop`.
- **libsodium-Binding ist austauschbar:** Alle Krypto-Flows laufen durch `core/.../crypto/Sodium.kt`.
  Desktop/Server nutzen LazySodiumJava; Android setzt beim Start in `MlsApplication` LazySodiumAndroid.
- **libsodium-Binding-Sichtbarkeit ist `compileOnly` in `:core` UND `<clinit>` darf nichts JVM-Spezifisches
  anrühren:** `:core` deklariert `libs.lazysodium.java` + `libs.jna` als `compileOnly`, NICHT als
  `implementation`. Sonst landet `lazysodium-java` (jar) transitiv im Android-Classpath und kollidiert
  dort mit `lazysodium-android` (aar) — AGP's `checkReleaseDuplicateClasses` wirft dann Hunderte
  `Duplicate class com.goterl.lazysodium.*` / `Duplicate class com.sun.jna.*`-Einträge. Jeder
  Konsument von `:core` muss seine Plattform-Variante selbst als `implementation` mitbringen:
  `desktop` + `server` → `lazysodium-java` + `jna` (jar); `android` → `lazysodium-android@aar` +
  `jna@aar`.
  Konsequenz #2 (häufig vergessen — siehe "Letzter Durchlauf"): `Sodium.<clinit>` darf KEINE
  konkreten libsodium-Klassen referenzieren, weil Android die JARs nicht auf dem Classpath hat
  und genau dann mit `NoClassDefFoundError: LazySodiumJava` aus `MlsApplication.onCreate` crasht.
  `Sodium.kt` löst das so:
    - `binding: LazySodium` ist `@Volatile var ? = null`, kein Default.
    - AEAD / KDF / PWHASH-Größen sind `const val`s (libsodium-ABI, stabil).
    - `useBinding(...)` resolved `PwHash$Alg.PWHASH_ALG_ARGON2ID13` + die passende `cryptoPwHash(...)`
      -Method **reflektiv** über den ClassLoader des aktiven Bindings. Eine Stelle
      (`pwhash(...)`) ist reflection-dispatch; alle anderen Calls gehen direkt über die
      `LazySodium`-Interface.
    - JVM-Fallback: ein `init`-Block ruft `Class.forName("com.goterl.lazysodium.LazySodiumJava", …)`
      und installiert den Default, falls die JAR da ist. Auf Android schlägt das fehl und
      `MlsApplication.onCreate()` ruft `useBinding(LazySodiumAndroid(SodiumAndroid()))`.
  Für `:core:test` braucht es deshalb keinen expliziten Setup mehr (war vorher `testImplementation`
  + Test-BeforeAll); `:core:test` zieht `lazysodium-java` + `jna` weiterhin als `testImplementation`,
  der `init`-Block macht den Rest.
- **R8 sieht `Sodium`/`LazySodium` über die Android-AAR:** `android/proguard-rules.pro` braucht KEINE
  `-dontwarn com.goterl.lazysodium.LazySodiumJava` mehr (die Klasse ist auf Android gar nicht da, also
  macht R8 keinen R-Vermerk). Stattdessen sind die `-dontwarn java.awt.*`-Regeln weiter nötig,
  weil JNA auf Android AWT-Typen referenziert, die nicht vorhanden sind.
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
- `:android`: `./gradlew :android:assembleRelease` baut jetzt eine signierte Release-APK, die auf
  einem Android-14-Gerät (arm64-v8a) **ohne Runtime-Crash** startet. Frische Release-APK
  liegt unter `android/build/outputs/apk/release/android-release.apk`. Voraussetzung auf
  einer anderen Maschine: JDK 21 (Pfad in `gradle.properties` `org.gradle.java.installations.
  paths` ggf. anpassen) + Android-SDK (`local.properties` `sdk.dir` oder `ANDROID_HOME`) +
  `MLS_ANDROID_KEYSTORE`-Env-Variablen zum Signieren (sonst gibt's eine korrekt unsigned APK,
  genau wie vorher).
- **libsodium-Sichtbarkeit & `<clinit>`-Boundary:** `:core` deklariert `lazysodium-java` +
  `jna` als `compileOnly` UND `Sodium.<clinit>` berührt keine konkreten libsodium-Klassen.
  `useBinding(...)` resolved den Algorithmus-Enum + die cryptoPwHash-Method reflektiv über
  den ClassLoader des aktiven Bindings (JVM-JAR für Desktop/Server, Android-AAR für Mobile).
  Init-Block-Fallback installiert die JVM-Variante automatisch, wenn die JAR auf dem Classpath
  liegt. Diese Konvention ist bei den "Wichtigen Entscheidungen" festgehalten.
- **Plugin-Classloader:** Alle vier Build-Plugins sind zentral in der Root `build.gradle.kts`
  mit `apply false` deklariert (siehe "Wichtige Entscheidungen" → Android/AGP).
- `CLAUDE.md` existiert nicht mehr; diese Datei ist die maßgebliche Agenten-Anleitung.

## Offene Punkte / Next Steps

1. Signierte Release-APK auf einem echten Gerät / Emulator voll durchklicken — Login-Flow,
   Sync-End-to-End (über `:server` in docker-compose), Biometric-Unlock. Bis jetzt ist nur
   der Start selbst verifiziert ("right-after-opening"-Crash war der Stopper davor).
2. Brand-Fonts (Spectral/Geist) in `android/.../res/font/` bündeln und in `Theme.kt` verdrahten.
3. JNA-AAR schleppt JNI-`libjnidispatch.so` für `mips` und `mips64` mit; falls Releases keine
   MIPS-Architektur mehr unterstützen müssen, `packaging.jniLibs.useLegacyPackaging` / `abiFilters`
   in `android/build.gradle.kts` einschränken, um die APK-Größe zu drücken.
4. `implementation(project(":core"))` etc. nutzt noch die `Project`-Dependency-Notation, die in
   Gradle 10 entfernt wird (Deprecation-Warning sichtbar in `--warning-mode=all`). Migration auf
   `provider { project(":core") }` / `dependencies.create(project(...))`, wenn das Repo auf
   Gradle 10 springt.
5. Der Reflection-Dispatch in `Sodium.pwhash(...)` ist nicht heiß (Argon2id-KDF dominiert), aber
   falls Profil-Guids später eine messbare Hitze zeigen, kann der `Method`-Lookup einmalig in
   einem Feld gecacht und nur einmal `invoke()` pro Argon2-Aufruf gemacht werden — siehe
   aktuelle Implementation in `core/src/main/kotlin/app/mls/core/crypto/Sodium.kt`.
