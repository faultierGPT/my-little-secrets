@file:Suppress("unused", "MagicNumber", "MemberVisibilityCanBePrivate")

package app.mls.android.ui.theme

import androidx.compose.ui.graphics.Color

// GENERATED FILE — do not edit by hand.
// Source of truth: design/src/main/kotlin/app/mls/design/Tokens.kt
// Regenerate:      ./gradlew :design:run
// my-little-secrets design tokens v1

/** Brand palette — the only colors in the app. Exactly one accent: [Accent] (muted amber). */
object MlsColor {
    /** App background — warm near-black. (#16130F) */
    val BgBase = Color(0xFF16130F)
    /** Raised surfaces: cards, sheets, the editor pane. (#1E1A15) */
    val BgElevated = Color(0xFF1E1A15)
    /** Menus/popovers — one step above elevated. (#231E18) */
    val BgOverlay = Color(0xFF231E18)
    /** 1px hairline separators and input borders. (#2A251E) */
    val BorderHairline = Color(0xFF2A251E)
    /** Primary reading/title text on dark surfaces. (#EDE6DA) */
    val TextPrimary = Color(0xFFEDE6DA)
    /** Secondary text: metadata, captions, placeholders. (#A39A8B) */
    val TextSecondary = Color(0xFFA39A8B)
    /** Disabled text — secondary at 38% alpha. (#A39A8B61) */
    val TextDisabled = Color(0x61A39A8B)
    /** The single brand accent — muted amber. Interactive emphasis. (#C9A26B) */
    val Accent = Color(0xFFC9A26B)
    /** Text/icon drawn ON an amber fill — the base surface, for contrast. (#16130F) */
    val AccentOn = Color(0xFF16130F)
    /** Amber wash for selected rows/chips — accent at ~12% alpha. (#C9A26B1F) */
    val AccentSubtle = Color(0x1FC9A26B)
    /** Hover overlay — primary text at ~6% alpha. (#EDE6DA0F) */
    val StateHover = Color(0x0FEDE6DA)
    /** Pressed overlay — primary text at ~10% alpha. (#EDE6DA1A) */
    val StatePressed = Color(0x1AEDE6DA)
    /** Keyboard-focus ring — the accent, drawn at 2px. (#C9A26B) */
    val FocusRing = Color(0xFFC9A26B)
    /** Destructive-action signal (delete). A muted terracotta status color, not a second accent. (#C16A52) */
    val SemanticDanger = Color(0xFFC16A52)
    /** Text/icon on a danger fill. (#16130F) */
    val SemanticDangerOn = Color(0xFF16130F)
    /** Modal scrim — near-black at ~70% alpha. (#0B0907B3) */
    val Scrim = Color(0xB30B0907)
}

/** Which family a role uses. The theme maps these to bundled FontFamily. */
enum class MlsFontRole { Serif, Grotesque }

/** Preferred font families per role (first that the client bundles wins). */
object MlsFontFamilies {
    val Serif = listOf("Spectral", "Newsreader", "Source Serif 4", "Georgia", "serif")
    val Grotesque = listOf("Geist", "IBM Plex Sans", "Inter", "system-ui", "sans-serif")
}

/** One typographic role: size/line in sp, numeric weight, tracking in em. */
data class MlsTypeRole(
    val sizeSp: Int,
    val lineSp: Int,
    val weight: Int,
    val letterSpacingEm: Float,
    val font: MlsFontRole,
    val color: Color,
)

/** The editorial type scale: serif for titles/reading, grotesque for UI chrome. */
object MlsType {
    /** Note title in the editor; empty-state headline. */
    val Display = MlsTypeRole(28, 34, 600, 0.000f, MlsFontRole.Serif, MlsColor.TextPrimary)
    /** Note title in the list. */
    val Title = MlsTypeRole(19, 26, 500, 0.000f, MlsFontRole.Serif, MlsColor.TextPrimary)
    /** Note body — long-form reading. */
    val Reading = MlsTypeRole(16, 27, 400, 0.000f, MlsFontRole.Serif, MlsColor.TextPrimary)
    /** Form labels, section headers, active nav. */
    val UiLabel = MlsTypeRole(14, 20, 500, 0.000f, MlsFontRole.Grotesque, MlsColor.TextPrimary)
    /** Default UI text, list subtitles. */
    val UiBody = MlsTypeRole(14, 21, 400, 0.000f, MlsFontRole.Grotesque, MlsColor.TextPrimary)
    /** Button labels. */
    val Button = MlsTypeRole(14, 18, 600, 0.010f, MlsFontRole.Grotesque, MlsColor.TextPrimary)
    /** Timestamps, counts, breadcrumbs. */
    val Meta = MlsTypeRole(12, 16, 400, 0.020f, MlsFontRole.Grotesque, MlsColor.TextSecondary)
}

/** Spacing magnitudes in dp (apply `.dp` at the use site). 4dp grid. */
object MlsSpace {
    val Dp0 = 0
    val Dp2 = 2
    val Dp4 = 4
    val Dp8 = 8
    val Dp12 = 12
    val Dp16 = 16
    val Dp20 = 20
    val Dp24 = 24
    val Dp32 = 32
    val Dp40 = 40
    val Dp48 = 48
    val Dp64 = 64
}

/** Corner radii in dp. */
object MlsRadius {
    val Sm = 6
    val Md = 10
    val Lg = 14
    val Pill = 999
}

/** Hairline/border + focus-ring widths in dp. */
object MlsStroke {
    val Hairline = 1
    val Focus = 2
}

/** Motion durations in milliseconds. */
object MlsMotion {
    val FastMs = 120
    val BaseMs = 200
    val SlowMs = 320
}
