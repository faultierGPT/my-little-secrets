package app.mls.design

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Enforces the brand invariants from the design brief (Section 9) as code, not convention. */
class TokensTest {

    private fun color(name: String) = MlsTokens.Color.all.first { it.name == name }

    @Test
    fun `there is exactly one brand accent`() {
        assertEquals(1, MlsTokens.Color.accentHues.size, "the design mandates a single accent; no second hue")
        assertEquals("#C9A26B", MlsTokens.Color.accent.hex, "the accent is the muted amber")
    }

    @Test
    fun `every color is a valid sRGB hex`() {
        // Construction validates the pattern; assert the set is non-trivial and parses to rgba.
        assertTrue(MlsTokens.Color.all.size >= 12)
        MlsTokens.Color.all.forEach { it.rgba() }
    }

    @Test
    fun `text and key fills meet WCAG contrast on their backgrounds`() {
        val base = color("bg.base").rgba()
        val accent = MlsTokens.Color.accent.rgba()
        val danger = MlsTokens.Color.danger.rgba()

        assertTrue(contrastRatio(color("text.primary").rgba(), base) >= 7.0, "primary text should hit AAA on the base surface")
        assertTrue(contrastRatio(color("text.secondary").rgba(), base) >= 4.5, "secondary text should hit AA on the base surface")
        assertTrue(contrastRatio(accent, base) >= 3.0, "the accent should be legible as UI/large text on the base surface")
        assertTrue(contrastRatio(MlsTokens.Color.onAccent.rgba(), accent) >= 4.5, "text on an amber fill must hit AA")
        assertTrue(contrastRatio(MlsTokens.Color.onDanger.rgba(), danger) >= 4.5, "text on a danger fill must hit AA")
    }

    @Test
    fun `the type pairing is editorial - serif for reading, grotesque for chrome`() {
        assertEquals("serif", MlsTokens.Type.display.font)
        assertEquals("serif", MlsTokens.Type.title.font)
        assertEquals("serif", MlsTokens.Type.reading.font)
        assertEquals("grotesque", MlsTokens.Type.uiLabel.font)
        assertEquals("grotesque", MlsTokens.Type.button.font)
        assertEquals("grotesque", MlsTokens.Type.meta.font)
        // Every type role references a real color and font stack.
        MlsTokens.Type.all.forEach { role ->
            assertTrue(MlsTokens.Color.all.any { it.name == role.color }, "${role.name} -> unknown color ${role.color}")
            assertTrue(MlsTokens.Font.all.any { it.name == role.font }, "${role.name} -> unknown font ${role.font}")
        }
    }

    @Test
    fun `tokens json export round-trips`() {
        val json = Json { prettyPrint = true; encodeDefaults = true }
        val text = TokensJsonRenderer.render()
        val back = json.decodeFromString(DesignTokensExport.serializer(), text)
        assertEquals(MlsTokens.NAME, back.name)
        assertEquals(MlsTokens.Color.all.size, back.colors.size)
        assertEquals(MlsTokens.Type.all.size, back.type.size)
    }
}
