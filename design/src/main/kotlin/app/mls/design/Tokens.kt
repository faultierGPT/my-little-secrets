package app.mls.design

import kotlinx.serialization.Serializable

/**
 * The CANONICAL design tokens for my-little-secrets — the single source of truth.
 *
 * Per `SECURITY.md`/the product brief (Section 9), the two clients use different UI toolkits
 * (Android = Jetpack Compose, desktop = pure Java + JavaFX) but must look identical. So every
 * visual value lives here exactly once, as typed Kotlin, and the [ComposeThemeRenderer],
 * [JavaFxCssRenderer], [TokensJsonRenderer], and [JavaConstantsRenderer] turn it into
 * per-toolkit artifacts. Change a value here, run `./gradlew :design:run`, and both clients move
 * together. Tests in `:design` enforce the brand invariants (one accent, valid hex, contrast).
 *
 * Visual language (fixed): a warm, near-black editorial surface; EXACTLY ONE brand accent
 * (muted amber); a serif for titles/reading paired with a grotesque for UI chrome; custom
 * ~1.5px line icons. No second accent, no gradient text, no glassmorphism.
 */
object MlsTokens {

    const val NAME = "my-little-secrets"
    const val VERSION = 1

    // ---- Color ---------------------------------------------------------------------------
    // Each token is an sRGB hex string, #RRGGBB or #RRGGBBAA (trailing pair = alpha). The
    // renderers convert to each toolkit's color form. These are the only colors in the system.
    object Color {
        // Surfaces — a warm near-black, layered by lightness rather than by shadow.
        val bgBase = ColorToken("bg.base", "#16130F", "App background — warm near-black.")
        val bgElevated = ColorToken("bg.elevated", "#1E1A15", "Raised surfaces: cards, sheets, the editor pane.")
        val bgOverlay = ColorToken("bg.overlay", "#231E18", "Menus/popovers — one step above elevated.")
        val borderHairline = ColorToken("border.hairline", "#2A251E", "1px hairline separators and input borders.")

        // Text.
        val textPrimary = ColorToken("text.primary", "#EDE6DA", "Primary reading/title text on dark surfaces.")
        val textSecondary = ColorToken("text.secondary", "#A39A8B", "Secondary text: metadata, captions, placeholders.")
        val textDisabled = ColorToken("text.disabled", "#A39A8B61", "Disabled text — secondary at 38% alpha.")

        // The ONE accent. Muted amber. Used for the active interaction/brand emphasis only.
        val accent = ColorToken("accent", "#C9A26B", "The single brand accent — muted amber. Interactive emphasis.")
        val onAccent = ColorToken("accent.on", "#16130F", "Text/icon drawn ON an amber fill — the base surface, for contrast.")
        val accentSubtle = ColorToken("accent.subtle", "#C9A26B1F", "Amber wash for selected rows/chips — accent at ~12% alpha.")

        // Interaction state overlays — tints of the primary text, kept warm and very low chroma
        // so they never read as a second accent.
        val stateHover = ColorToken("state.hover", "#EDE6DA0F", "Hover overlay — primary text at ~6% alpha.")
        val statePressed = ColorToken("state.pressed", "#EDE6DA1A", "Pressed overlay — primary text at ~10% alpha.")
        val focusRing = ColorToken("focus.ring", "#C9A26B", "Keyboard-focus ring — the accent, drawn at 2px.")

        // Semantic status. Deliberately NOT a brand accent: a single, low-chroma terracotta used
        // ONLY to mark irreversible/destructive actions (delete). Desaturated so it never competes
        // with the amber accent for attention. (See design/README.md for the rationale.)
        val danger = ColorToken("semantic.danger", "#C16A52", "Destructive-action signal (delete). A muted terracotta status color, not a second accent.")
        val onDanger = ColorToken("semantic.danger.on", "#16130F", "Text/icon on a danger fill.")

        // Scrim behind modal sheets.
        val scrim = ColorToken("scrim", "#0B0907B3", "Modal scrim — near-black at ~70% alpha.")

        val all: List<ColorToken> = listOf(
            bgBase, bgElevated, bgOverlay, borderHairline,
            textPrimary, textSecondary, textDisabled,
            accent, onAccent, accentSubtle,
            stateHover, statePressed, focusRing,
            danger, onDanger, scrim,
        )

        /** Tokens that constitute the brand "accent". There must be exactly one accent hue. */
        val accentHues: List<ColorToken> = listOf(accent)
    }

    // ---- Type ----------------------------------------------------------------------------
    // Editorial pairing: a serif for titles and long-form reading, a grotesque for UI chrome.
    // Families list preferred → fallbacks; clients bundle the first that they ship.
    object Font {
        val serif = FontStack(
            "serif",
            listOf("Spectral", "Newsreader", "Source Serif 4", "Georgia", "serif"),
            "Titles and long-form reading. Editorial, warm.",
        )
        val grotesque = FontStack(
            "grotesque",
            listOf("Geist", "IBM Plex Sans", "Inter", "system-ui", "sans-serif"),
            "UI chrome: buttons, labels, navigation, metadata.",
        )
        val all: List<FontStack> = listOf(serif, grotesque)
    }

    // size/line in sp (Android) == pt-ish logical px (JavaFX); weight is CSS/Compose numeric.
    object Type {
        val display = TypeRole("display", "serif", 28, 34, 600, 0.0, "text.primary", "Note title in the editor; empty-state headline.")
        val title = TypeRole("title", "serif", 19, 26, 500, 0.0, "text.primary", "Note title in the list.")
        val reading = TypeRole("reading", "serif", 16, 27, 400, 0.0, "text.primary", "Note body — long-form reading.")
        val uiLabel = TypeRole("ui.label", "grotesque", 14, 20, 500, 0.0, "text.primary", "Form labels, section headers, active nav.")
        val uiBody = TypeRole("ui.body", "grotesque", 14, 21, 400, 0.0, "text.primary", "Default UI text, list subtitles.")
        val button = TypeRole("button", "grotesque", 14, 18, 600, 0.01, "text.primary", "Button labels.")
        val meta = TypeRole("meta", "grotesque", 12, 16, 400, 0.02, "text.secondary", "Timestamps, counts, breadcrumbs.")

        val all: List<TypeRole> = listOf(display, title, reading, uiLabel, uiBody, button, meta)
    }

    // ---- Space / radius / border ---------------------------------------------------------
    // 4dp base grid.
    object Space {
        val all: List<SpaceToken> = listOf(
            SpaceToken("space.0", 0), SpaceToken("space.1", 2), SpaceToken("space.2", 4),
            SpaceToken("space.3", 8), SpaceToken("space.4", 12), SpaceToken("space.5", 16),
            SpaceToken("space.6", 20), SpaceToken("space.7", 24), SpaceToken("space.8", 32),
            SpaceToken("space.9", 40), SpaceToken("space.10", 48), SpaceToken("space.11", 64),
        )
    }

    object Radius {
        val all: List<RadiusToken> = listOf(
            RadiusToken("radius.sm", 6), RadiusToken("radius.md", 10),
            RadiusToken("radius.lg", 14), RadiusToken("radius.pill", 999),
        )
    }

    /** Hairline borders only; the warm-surface system uses lightness, not shadow, for depth. */
    const val BORDER_HAIRLINE_DP = 1
    const val FOCUS_RING_DP = 2

    // ---- Motion --------------------------------------------------------------------------
    object Motion {
        val all: List<DurationToken> = listOf(
            DurationToken("motion.fast", 120),
            DurationToken("motion.base", 200),
            DurationToken("motion.slow", 320),
        )
        const val EASING_STANDARD = "cubic-bezier(0.2, 0.0, 0.0, 1.0)"
    }

    // ---- Elevation -----------------------------------------------------------------------
    // The only shadow in the system: detached popovers/menus. Everything else is flat.
    object Elevation {
        const val MENU_SHADOW_X_DP = 0
        const val MENU_SHADOW_Y_DP = 8
        const val MENU_SHADOW_BLUR_DP = 24
        const val MENU_SHADOW_COLOR = "#00000073" // black @ ~45%
    }
}

@Serializable
data class ColorToken(val name: String, val hex: String, val doc: String) {
    init {
        require(HEX.matches(hex)) { "Color $name has invalid hex '$hex' (expect #RRGGBB or #RRGGBBAA)" }
    }

    /** Parsed sRGB components, alpha in 0..255 (defaults to opaque). */
    fun rgba(): Rgba {
        val h = hex.removePrefix("#")
        val r = h.substring(0, 2).toInt(16)
        val g = h.substring(2, 4).toInt(16)
        val b = h.substring(4, 6).toInt(16)
        val a = if (h.length == 8) h.substring(6, 8).toInt(16) else 255
        return Rgba(r, g, b, a)
    }

    companion object {
        private val HEX = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
    }
}

data class Rgba(val r: Int, val g: Int, val b: Int, val a: Int) {
    val opaque: Boolean get() = a == 255
    val alphaFloat: Double get() = a / 255.0

    /** sRGB relative luminance (WCAG) for contrast checks. */
    fun relativeLuminance(): Double {
        fun chan(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * chan(r) + 0.7152 * chan(g) + 0.0722 * chan(b)
    }
}

/** WCAG contrast ratio between two opaque colors (1..21). */
fun contrastRatio(a: Rgba, b: Rgba): Double {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

@Serializable
data class FontStack(val name: String, val families: List<String>, val doc: String) {
    /** CSS/JavaFX font-family list, each non-keyword family quoted. */
    fun css(): String = families.joinToString(", ") { f ->
        if (f.matches(Regex("^[a-z-]+$"))) f else "\"$f\""
    }
}

@Serializable
data class TypeRole(
    val name: String,
    val font: String,            // "serif" | "grotesque" — references a FontStack name
    val sizeSp: Int,
    val lineSp: Int,
    val weight: Int,
    val letterSpacingEm: Double,
    val color: String,           // references a ColorToken name
    val doc: String,
)

@Serializable
data class SpaceToken(val name: String, val dp: Int)

@Serializable
data class RadiusToken(val name: String, val dp: Int)

@Serializable
data class DurationToken(val name: String, val ms: Int)
