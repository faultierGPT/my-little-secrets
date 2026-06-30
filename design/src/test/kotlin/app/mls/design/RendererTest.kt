package app.mls.design

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Smoke-tests that each renderer emits the canonical values in its toolkit's form. */
class RendererTest {

    @Test
    fun `compose theme carries the palette and type scale`() {
        val out = ComposeThemeRenderer.render()
        assertTrue(out.contains("package ${ComposeThemeRenderer.PACKAGE}"))
        assertTrue(out.contains("object MlsColor"))
        assertTrue(out.contains("import androidx.compose.ui.graphics.Color"))
        // #C9A26B opaque -> Color(0xFFC9A26B)
        assertTrue(out.contains("Color(0xFFC9A26B)"), "accent missing from compose output")
        assertTrue(out.contains("val Display = MlsTypeRole(28"), "display role missing")
        assertTrue(out.contains("MlsFontRole.Serif"))
    }

    @Test
    fun `javafx css carries looked-up colors and component styles`() {
        val css = JavaFxCssRenderer.render()
        assertTrue(css.contains("-mls-accent: #c9a26b;"), "accent looked-up color missing")
        assertTrue(css.contains("-mls-bg-base: #16130f;"))
        assertTrue(css.contains(".button.mls-primary"), "primary button style missing")
        assertTrue(css.contains(".mls-display"))
        // Alpha colors must be emitted as rgba(), not an unsupported 8-digit hex.
        assertTrue(css.contains("-mls-state-hover: rgba("), "alpha color should be rgba() in JavaFX")
        assertFalse(css.contains("#EDE6DA0F"), "8-digit hex is invalid in JavaFX CSS")
    }

    @Test
    fun `java constants expose hex strings and font stacks`() {
        val java = JavaConstantsRenderer.render()
        assertTrue(java.contains("public final class MlsTokens"))
        assertTrue(java.contains("public static final String ACCENT = \"#C9A26B\";"))
        assertTrue(java.contains("SERIF_FAMILIES"))
        assertTrue(java.contains("public static final int RADIUS_MD = 10;"))
    }

    @Test
    fun `java constants have no duplicate field identifiers`() {
        // A flat Java class shares ONE identifier namespace across all types, so a color named
        // BORDER_HAIRLINE and an int width also named BORDER_HAIRLINE would be a compile error.
        // (Compose namespaces these under MlsColor / MlsStroke, hence no collision there.)
        val java = JavaConstantsRenderer.render()
        val decl = Regex("""public static final \S+(?:\[])? (\w+)\s*=""")
        val names = decl.findAll(java).map { it.groupValues[1] }.toList()
        val dupes = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(dupes.isEmpty(), "duplicate Java field identifiers would not compile: $dupes")
    }
}
