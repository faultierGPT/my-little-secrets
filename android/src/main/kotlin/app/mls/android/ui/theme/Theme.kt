package app.mls.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Maps the GENERATED design tokens ([MlsColor] / [MlsType], single source of truth in :design) onto
 * a Material3 theme. The brand fonts (Spectral / Geist) would be bundled as font resources for an
 * exact match; here we fall back to the platform serif/sans families per the editorial pairing.
 */
private val MlsColorScheme = darkColorScheme(
    primary = MlsColor.Accent,
    onPrimary = MlsColor.AccentOn,
    secondary = MlsColor.Accent,
    onSecondary = MlsColor.AccentOn,
    background = MlsColor.BgBase,
    onBackground = MlsColor.TextPrimary,
    surface = MlsColor.BgElevated,
    onSurface = MlsColor.TextPrimary,
    surfaceVariant = MlsColor.BgOverlay,
    onSurfaceVariant = MlsColor.TextSecondary,
    error = MlsColor.SemanticDanger,
    onError = MlsColor.SemanticDangerOn,
    outline = MlsColor.BorderHairline,
)

private fun MlsTypeRole.toTextStyle(): TextStyle = TextStyle(
    fontFamily = if (font == MlsFontRole.Serif) FontFamily.Serif else FontFamily.SansSerif,
    fontSize = sizeSp.sp,
    lineHeight = lineSp.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = letterSpacingEm.em,
    color = color,
)

private fun mlsTypography(): Typography = Typography(
    displaySmall = MlsType.Display.toTextStyle(),
    headlineMedium = MlsType.Title.toTextStyle(),
    titleLarge = MlsType.Title.toTextStyle(),
    bodyLarge = MlsType.Reading.toTextStyle(),
    bodyMedium = MlsType.UiBody.toTextStyle(),
    labelLarge = MlsType.Button.toTextStyle(),
    labelMedium = MlsType.UiLabel.toTextStyle(),
    bodySmall = MlsType.Meta.toTextStyle(),
)

@Composable
fun MlsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MlsColorScheme,
        typography = mlsTypography(),
        content = content,
    )
}
