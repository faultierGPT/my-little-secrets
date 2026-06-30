package app.mls.design

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ---- shared identifier + color helpers ---------------------------------------------------

/** "bg.base" -> "BgBase", "semantic.danger.on" -> "SemanticDangerOn". */
internal fun pascal(token: String): String =
    token.split('.', '-', '_').filter { it.isNotEmpty() }.joinToString("") { seg ->
        seg.replaceFirstChar { it.uppercaseChar() }
    }

/** "bg.base" -> "BG_BASE". */
internal fun screamingSnake(token: String): String =
    token.split('.', '-', '_').filter { it.isNotEmpty() }.joinToString("_") { it.uppercase() }

/** "text.primary" -> "-mls-text-primary" (a JavaFX looked-up color name). */
internal fun cssVar(token: String): String = "-mls-" + token.replace('.', '-')

/** Compose `Color(0xAARRGGBB)` literal. */
internal fun composeColor(c: ColorToken): String {
    val (r, g, b, a) = c.rgba()
    return "Color(0x%02X%02X%02X%02X)".format(a, r, g, b)
}

/** A JavaFX-CSS paint: `#rrggbb` when opaque, else `rgba(r, g, b, a.aaa)`. */
internal fun javafxColor(c: ColorToken): String {
    val rgba = c.rgba()
    return if (rgba.opaque) "#%02x%02x%02x".format(rgba.r, rgba.g, rgba.b)
    else "rgba(%d, %d, %d, %.3f)".format(rgba.r, rgba.g, rgba.b, rgba.alphaFloat)
}

private const val GEN_BANNER_1 = "GENERATED FILE — do not edit by hand."
private const val GEN_BANNER_2 = "Source of truth: design/src/main/kotlin/app/mls/design/Tokens.kt"
private const val GEN_BANNER_3 = "Regenerate:      ./gradlew :design:run"

// ---- Compose theme (Android) -------------------------------------------------------------

/** Renders the canonical tokens as a self-contained Kotlin file for the Android Compose client. */
object ComposeThemeRenderer {
    const val PACKAGE = "app.mls.android.ui.theme"

    fun render(): String = buildString {
        appendLine("@file:Suppress(\"unused\", \"MagicNumber\", \"MemberVisibilityCanBePrivate\")")
        appendLine()
        appendLine("package $PACKAGE")
        appendLine()
        appendLine("import androidx.compose.ui.graphics.Color")
        appendLine()
        appendLine("// $GEN_BANNER_1")
        appendLine("// $GEN_BANNER_2")
        appendLine("// $GEN_BANNER_3")
        appendLine("// ${MlsTokens.NAME} design tokens v${MlsTokens.VERSION}")
        appendLine()
        appendLine("/** Brand palette — the only colors in the app. Exactly one accent: [Accent] (muted amber). */")
        appendLine("object MlsColor {")
        for (c in MlsTokens.Color.all) {
            appendLine("    /** ${c.doc} (${c.hex}) */")
            appendLine("    val ${pascal(c.name)} = ${composeColor(c)}")
        }
        appendLine("}")
        appendLine()
        appendLine("/** Which family a role uses. The theme maps these to bundled FontFamily. */")
        appendLine("enum class MlsFontRole { Serif, Grotesque }")
        appendLine()
        appendLine("/** Preferred font families per role (first that the client bundles wins). */")
        appendLine("object MlsFontFamilies {")
        for (f in MlsTokens.Font.all) {
            appendLine("    val ${pascal(f.name)} = listOf(${f.families.joinToString(", ") { "\"$it\"" }})")
        }
        appendLine("}")
        appendLine()
        appendLine("/** One typographic role: size/line in sp, numeric weight, tracking in em. */")
        appendLine("data class MlsTypeRole(")
        appendLine("    val sizeSp: Int,")
        appendLine("    val lineSp: Int,")
        appendLine("    val weight: Int,")
        appendLine("    val letterSpacingEm: Float,")
        appendLine("    val font: MlsFontRole,")
        appendLine("    val color: Color,")
        appendLine(")")
        appendLine()
        appendLine("/** The editorial type scale: serif for titles/reading, grotesque for UI chrome. */")
        appendLine("object MlsType {")
        for (t in MlsTokens.Type.all) {
            val font = if (t.font == "serif") "MlsFontRole.Serif" else "MlsFontRole.Grotesque"
            appendLine("    /** ${t.doc} */")
            appendLine(
                "    val ${pascal(t.name)} = MlsTypeRole(${t.sizeSp}, ${t.lineSp}, ${t.weight}, " +
                    "${"%.3f".format(t.letterSpacingEm)}f, $font, MlsColor.${pascal(t.color)})",
            )
        }
        appendLine("}")
        appendLine()
        appendLine("/** Spacing magnitudes in dp (apply `.dp` at the use site). 4dp grid. */")
        appendLine("object MlsSpace {")
        for (s in MlsTokens.Space.all) appendLine("    val Dp${s.dp} = ${s.dp}")
        appendLine("}")
        appendLine()
        appendLine("/** Corner radii in dp. */")
        appendLine("object MlsRadius {")
        for (r in MlsTokens.Radius.all) appendLine("    val ${pascal(r.name.removePrefix("radius."))} = ${r.dp}")
        appendLine("}")
        appendLine()
        appendLine("/** Hairline/border + focus-ring widths in dp. */")
        appendLine("object MlsStroke {")
        appendLine("    val Hairline = ${MlsTokens.BORDER_HAIRLINE_DP}")
        appendLine("    val Focus = ${MlsTokens.FOCUS_RING_DP}")
        appendLine("}")
        appendLine()
        appendLine("/** Motion durations in milliseconds. */")
        appendLine("object MlsMotion {")
        for (m in MlsTokens.Motion.all) appendLine("    val ${pascal(m.name.removePrefix("motion."))}Ms = ${m.ms}")
        appendLine("}")
    }
}

// ---- JavaFX CSS (desktop) ----------------------------------------------------------------

/** Renders the canonical tokens as a JavaFX stylesheet for the pure-Java desktop client. */
object JavaFxCssRenderer {

    fun render(): String = buildString {
        val serif = MlsTokens.Font.serif.css()
        val grotesque = MlsTokens.Font.grotesque.css()

        appendLine("/* $GEN_BANNER_1 */")
        appendLine("/* $GEN_BANNER_2 */")
        appendLine("/* $GEN_BANNER_3 */")
        appendLine("/* ${MlsTokens.NAME} design tokens v${MlsTokens.VERSION} — JavaFX stylesheet */")
        appendLine()
        appendLine("/* ---- tokens: looked-up colors + base typography ---- */")
        appendLine(".root {")
        for (c in MlsTokens.Color.all) {
            appendLine("    ${cssVar(c.name)}: ${javafxColor(c)};")
        }
        appendLine()
        appendLine("    -fx-background-color: ${cssVar("bg.base")};")
        appendLine("    -fx-font-family: $grotesque;")
        appendLine("    -fx-font-size: ${MlsTokens.Type.uiBody.sizeSp}px;")
        appendLine("    -fx-text-fill: ${cssVar("text.primary")};")
        appendLine("    /* tame the default JavaFX focus glow; we draw our own amber ring */")
        appendLine("    -fx-focus-color: ${cssVar("focus.ring")};")
        appendLine("    -fx-faint-focus-color: transparent;")
        appendLine("}")
        appendLine()
        appendLine("/* ---- surfaces ---- */")
        appendLine(".mls-app { -fx-background-color: ${cssVar("bg.base")}; }")
        appendLine(
            ".mls-surface {\n" +
                "    -fx-background-color: ${cssVar("bg.elevated")};\n" +
                "    -fx-background-radius: ${rad("radius.md")};\n" +
                "    -fx-border-color: ${cssVar("border.hairline")};\n" +
                "    -fx-border-width: ${MlsTokens.BORDER_HAIRLINE_DP};\n" +
                "    -fx-border-radius: ${rad("radius.md")};\n" +
                "}",
        )
        appendLine(
            ".mls-overlay {\n" +
                "    -fx-background-color: ${cssVar("bg.overlay")};\n" +
                "    -fx-background-radius: ${rad("radius.md")};\n" +
                "    -fx-border-color: ${cssVar("border.hairline")};\n" +
                "    -fx-border-width: ${MlsTokens.BORDER_HAIRLINE_DP};\n" +
                "    -fx-border-radius: ${rad("radius.md")};\n" +
                "    -fx-effect: dropshadow(gaussian, ${javafxColor(MlsTokens.Color.scrim)}, " +
                "${MlsTokens.Elevation.MENU_SHADOW_BLUR_DP}, 0.0, ${MlsTokens.Elevation.MENU_SHADOW_X_DP}, ${MlsTokens.Elevation.MENU_SHADOW_Y_DP});\n" +
                "}",
        )
        appendLine(".mls-divider { -fx-background-color: ${cssVar("border.hairline")}; -fx-pref-height: ${MlsTokens.BORDER_HAIRLINE_DP}; }")
        appendLine()
        appendLine("/* ---- typography ---- */")
        for (t in MlsTokens.Type.all) {
            val family = if (t.font == "serif") serif else grotesque
            val leading = (t.lineSp - t.sizeSp).coerceAtLeast(0)
            appendLine(".mls-${t.name.replace('.', '-')} {")
            appendLine("    -fx-font-family: $family;")
            appendLine("    -fx-font-size: ${t.sizeSp}px;")
            appendLine("    -fx-font-weight: ${t.weight};")
            appendLine("    -fx-text-fill: ${cssVar(t.color)};")
            if (leading > 0) appendLine("    -fx-line-spacing: ${leading}px;")
            appendLine("}")
        }
        appendLine()
        appendLine("/* ---- buttons ---- */")
        appendLine("/* quiet default: text on a hairline-bordered transparent surface */")
        appendLine(
            ".button {\n" +
                "    -fx-font-family: $grotesque;\n" +
                "    -fx-font-size: ${MlsTokens.Type.button.sizeSp}px;\n" +
                "    -fx-font-weight: ${MlsTokens.Type.button.weight};\n" +
                "    -fx-text-fill: ${cssVar("text.primary")};\n" +
                "    -fx-background-color: transparent;\n" +
                "    -fx-border-color: ${cssVar("border.hairline")};\n" +
                "    -fx-border-width: ${MlsTokens.BORDER_HAIRLINE_DP};\n" +
                "    -fx-background-radius: ${rad("radius.sm")};\n" +
                "    -fx-border-radius: ${rad("radius.sm")};\n" +
                "    -fx-padding: 8 14 8 14;\n" +
                "    -fx-cursor: hand;\n" +
                "}",
        )
        appendLine(".button:hover { -fx-background-color: ${cssVar("state.hover")}; }")
        appendLine(".button:pressed { -fx-background-color: ${cssVar("state.pressed")}; }")
        appendLine(".button:focused { -fx-border-color: ${cssVar("focus.ring")}; -fx-border-width: ${MlsTokens.FOCUS_RING_DP}; }")
        appendLine(".button:disabled { -fx-opacity: 0.38; }")
        appendLine()
        appendLine("/* primary: the one amber call-to-action */")
        appendLine(
            ".button.mls-primary {\n" +
                "    -fx-background-color: ${cssVar("accent")};\n" +
                "    -fx-text-fill: ${cssVar("accent.on")};\n" +
                "    -fx-border-color: transparent;\n" +
                "}",
        )
        appendLine(".button.mls-primary:hover { -fx-background-color: derive(${cssVar("accent")}, 8%); }")
        appendLine(".button.mls-primary:pressed { -fx-background-color: derive(${cssVar("accent")}, -8%); }")
        appendLine()
        appendLine("/* destructive: the muted terracotta status color, used only for delete */")
        appendLine(
            ".button.mls-danger {\n" +
                "    -fx-background-color: transparent;\n" +
                "    -fx-text-fill: ${cssVar("semantic.danger")};\n" +
                "    -fx-border-color: ${cssVar("semantic.danger")};\n" +
                "}",
        )
        appendLine(".button.mls-danger:hover { -fx-background-color: ${cssVar("semantic.danger")}; -fx-text-fill: ${cssVar("semantic.danger.on")}; }")
        appendLine()
        appendLine("/* ---- text inputs ---- */")
        appendLine(
            ".text-field, .text-area {\n" +
                "    -fx-font-family: $grotesque;\n" +
                "    -fx-font-size: ${MlsTokens.Type.uiBody.sizeSp}px;\n" +
                "    -fx-text-fill: ${cssVar("text.primary")};\n" +
                "    -fx-prompt-text-fill: ${cssVar("text.secondary")};\n" +
                "    -fx-background-color: ${cssVar("bg.elevated")};\n" +
                "    -fx-background-radius: ${rad("radius.sm")};\n" +
                "    -fx-border-color: ${cssVar("border.hairline")};\n" +
                "    -fx-border-width: ${MlsTokens.BORDER_HAIRLINE_DP};\n" +
                "    -fx-border-radius: ${rad("radius.sm")};\n" +
                "    -fx-padding: 8 10 8 10;\n" +
                "}",
        )
        appendLine(".text-area .content { -fx-background-color: ${cssVar("bg.elevated")}; }")
        appendLine(".text-field:focused, .text-area:focused { -fx-border-color: ${cssVar("focus.ring")}; -fx-border-width: ${MlsTokens.FOCUS_RING_DP}; }")
        appendLine()
        appendLine("/* ---- note list ---- */")
        appendLine(".list-view { -fx-background-color: transparent; -fx-border-color: transparent; }")
        appendLine(".list-view .list-cell { -fx-background-color: transparent; -fx-padding: 12 14 12 14; -fx-text-fill: ${cssVar("text.primary")}; }")
        appendLine(".list-view .list-cell:filled:hover { -fx-background-color: ${cssVar("state.hover")}; }")
        appendLine(".list-view .list-cell:filled:selected { -fx-background-color: ${cssVar("accent.subtle")}; }")
    }

    private fun rad(name: String): Int = MlsTokens.Radius.all.first { it.name == name }.dp
}

// ---- Java constants (desktop programmatic use) -------------------------------------------

/** Renders the tokens as plain Java constants the JavaFX client can use in code (e.g. Color.web). */
object JavaConstantsRenderer {
    const val PACKAGE = "app.mls.desktop.design"

    fun render(): String = buildString {
        appendLine("package $PACKAGE;")
        appendLine()
        appendLine("// $GEN_BANNER_1")
        appendLine("// $GEN_BANNER_2")
        appendLine("// $GEN_BANNER_3")
        appendLine()
        appendLine("/** ${MlsTokens.NAME} design tokens v${MlsTokens.VERSION}. Hex colors are sRGB #RRGGBB[AA]. */")
        appendLine("public final class MlsTokens {")
        appendLine("    private MlsTokens() {}")
        appendLine()
        for (c in MlsTokens.Color.all) {
            appendLine("    /** ${c.doc} */")
            appendLine("    public static final String ${screamingSnake(c.name)} = \"${c.hex}\";")
        }
        appendLine()
        for (f in MlsTokens.Font.all) {
            val arr = f.families.joinToString(", ") { "\"$it\"" }
            appendLine("    public static final String[] ${screamingSnake(f.name)}_FAMILIES = { $arr };")
        }
        appendLine()
        for (s in MlsTokens.Space.all) appendLine("    public static final int ${screamingSnake(s.name)} = ${s.dp};")
        appendLine()
        for (r in MlsTokens.Radius.all) appendLine("    public static final int ${screamingSnake(r.name)} = ${r.dp};")
        appendLine()
        appendLine("    public static final int BORDER_HAIRLINE = ${MlsTokens.BORDER_HAIRLINE_DP};")
        appendLine("    public static final int FOCUS_RING = ${MlsTokens.FOCUS_RING_DP};")
        appendLine()
        for (m in MlsTokens.Motion.all) appendLine("    public static final int ${screamingSnake(m.name)}_MS = ${m.ms};")
        appendLine("}")
    }
}

// ---- tokens.json (toolkit-neutral export) ------------------------------------------------

@Serializable
data class DesignTokensExport(
    val name: String,
    val version: Int,
    val colors: List<ColorToken>,
    val fonts: List<FontStack>,
    val type: List<TypeRole>,
    val space: List<SpaceToken>,
    val radius: List<RadiusToken>,
    val motion: List<DurationToken>,
)

object TokensJsonRenderer {
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    fun export(): DesignTokensExport = DesignTokensExport(
        name = MlsTokens.NAME,
        version = MlsTokens.VERSION,
        colors = MlsTokens.Color.all,
        fonts = MlsTokens.Font.all,
        type = MlsTokens.Type.all,
        space = MlsTokens.Space.all,
        radius = MlsTokens.Radius.all,
        motion = MlsTokens.Motion.all,
    )

    fun render(): String = json.encodeToString(DesignTokensExport.serializer(), export())
}
