# design/ — toolkit-neutral design tokens

The two clients use different UI toolkits — Android is **Jetpack Compose (Kotlin)**, desktop is
**pure Java + JavaFX** — but they must look identical. They can't share a UI toolkit, so they share
**values** instead: every color, type role, spacing step, radius, and motion duration is defined
**once** here and rendered into each toolkit's native form.

```
Tokens.kt  (the single source of truth, typed Kotlin)
   │
   ├─►  ComposeThemeRenderer   ─►  generated/compose/.../MlsDesignTokens.kt   (Android)
   ├─►  JavaFxCssRenderer      ─►  generated/javafx/mls-theme.css            (desktop)
   ├─►  JavaConstantsRenderer  ─►  generated/java/.../MlsTokens.java         (desktop, in-code)
   └─►  TokensJsonRenderer      ─►  generated/tokens.json                    (toolkit-neutral export)
```

Change a value in [`src/main/kotlin/app/mls/design/Tokens.kt`](src/main/kotlin/app/mls/design/Tokens.kt),
run `./gradlew :design:run`, and both clients move together. The generated files are committed so the
clients build without running the generator first; the generator just keeps them in sync.

## Visual language (fixed)

A warm, **near-black editorial** surface. Quiet, paper-like, text-first — built for reading.

- **Surfaces** layer by lightness, not by shadow: base `#16130F` → elevated `#1E1A15` → overlay
  `#231E18`, separated by a `#2A251E` hairline. The only shadow in the system is on detached
  popovers. No glassmorphism.
- **Text**: primary `#EDE6DA` (AAA on base), secondary `#A39A8B` (AA).
- **Exactly one accent** — muted amber `#C9A26B`. It is the *only* hue used for emphasis and the
  active call-to-action. There is no second accent, no gradient text.
- **Editorial type pairing**: a **serif** (Spectral → Newsreader → Source Serif 4) for titles and
  long-form reading, and a **grotesque** (Geist → IBM Plex Sans → Inter) for UI chrome.
- **Custom line icons** at a **1.5px stroke**, `currentColor`, round caps/joins — no emoji, no
  filled Material glyphs. See [`icons/`](icons/).

### On the one "danger" color

The brief mandates a single accent. `semantic.danger` (`#C16A52`, a muted terracotta) is **not** a
second brand accent — it is a status signal used *only* to mark an irreversible/destructive action
(deleting a note). It is deliberately low-chroma so it never competes with the amber for attention,
and it is the only place it appears. Text on it (`#16130F`) clears WCAG AA. These rules are enforced
by `TokensTest` (one accent hue; contrast thresholds), so a future edit that smuggles in a second
accent or an illegible pair fails the build.

## What's where

| Path | Role |
|---|---|
| `src/main/kotlin/app/mls/design/Tokens.kt` | **Canonical** tokens (the only place values live). |
| `src/main/kotlin/app/mls/design/Renderers.kt` | The four renderers (Compose / JavaFX CSS / Java / JSON). |
| `src/main/kotlin/app/mls/design/Generate.kt` | `main()` — writes all generated artifacts. |
| `src/test/kotlin/...` | Brand-invariant + renderer smoke tests. |
| `generated/` | Committed, regenerable outputs consumed by the clients. |
| `icons/` | Hand-authored 1.5px line-icon SVGs. |

## Commands

```bash
./gradlew :design:test                  # enforce the invariants (one accent, contrast, parity)
./gradlew :design:run                   # regenerate generated/ from Tokens.kt
./gradlew :design:run --args="<dir>"    # regenerate into a custom directory
```

This module depends only on `kotlinx-serialization-json` (for the JSON export). It deliberately does
**not** depend on Compose or JavaFX, so it stays buildable on any JVM and never couples the two
clients to each other.
