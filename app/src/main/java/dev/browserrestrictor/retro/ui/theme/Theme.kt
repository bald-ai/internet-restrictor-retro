package dev.browserrestrictor.retro.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.browserrestrictor.retro.R
import dev.browserrestrictor.retro.domain.ThemePreference

// Warm paper, hairlines, editorial serif, one vermillion accent.
val PaperLight = Color(0xFFF5F2EA)
val Paper2Light = Color(0xFFECE8DC)
val CardLight = Color(0xFFFBFAF5)
val InkLight = Color(0xFF171310)
val Ink2Light = Color(0xFF6B6355)
val Ink3Light = Color(0xFFA79E8D)

val PaperDark = Color(0xFF14110B)
val Paper2Dark = Color(0xFF1B1710)
val CardDark = Color(0xFF1E1913)
val InkDark = Color(0xFFF0EBDF)
val Ink2Dark = Color(0xFFA79F8E)
val Ink3Dark = Color(0xFF645D4E)

val AccentLight = Color(0xFFD9481C)
val AccentInkLight = Color(0xFFB93A14)
val AccentDark = Color(0xFFFF5A26)
val AccentInkDark = Color(0xFFFF7A4D)

val GoLight = Color(0xFF2E7D4F)
val WarnLight = Color(0xFFC07A1E)
val LowLight = Color(0xFFC23B2E)
val GoDark = Color(0xFF4DB284)
val WarnDark = Color(0xFFE0A33E)
val LowDark = Color(0xFFF0563F)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Manrope = FontFamily(
    Font(R.font.manrope_variable, FontWeight.Normal),
    Font(R.font.manrope_variable, FontWeight.Medium),
    Font(R.font.manrope_variable, FontWeight.SemiBold),
    Font(R.font.manrope_variable, FontWeight.Bold),
    Font(R.font.manrope_variable, FontWeight.ExtraBold),
)

val PillShape = RoundedCornerShape(percent = 50)

@Immutable
class EditorialColors(
    val accent: Color,
    val accentInk: Color,
    val go: Color,
    val warn: Color,
    val low: Color,
    val ink3: Color,
    val hairline: Color,
    val hairlineSoft: Color,
)

private val EditorialLight = EditorialColors(
    accent = AccentLight,
    accentInk = AccentInkLight,
    go = GoLight,
    warn = WarnLight,
    low = LowLight,
    ink3 = Ink3Light,
    hairline = InkLight.copy(alpha = 0.13f),
    hairlineSoft = InkLight.copy(alpha = 0.07f),
)

private val EditorialDark = EditorialColors(
    accent = AccentDark,
    accentInk = AccentInkDark,
    go = GoDark,
    warn = WarnDark,
    low = LowDark,
    ink3 = Ink3Dark,
    hairline = InkDark.copy(alpha = 0.15f),
    hairlineSoft = InkDark.copy(alpha = 0.07f),
)

val LocalEditorialColors = staticCompositionLocalOf { EditorialLight }

object EditorialTheme {
    val colors: EditorialColors
        @Composable
        get() = LocalEditorialColors.current
}

val SerifNote = TextStyle(
    fontFamily = InstrumentSerif,
    fontStyle = FontStyle.Italic,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
)

/** Styles a screen headline with the last word in italic vermillion, like the desktop gate. */
@Composable
fun editorialTitle(text: String): AnnotatedString {
    val accent = EditorialTheme.colors.accent
    val split = text.lastIndexOf(' ')
    return buildAnnotatedString {
        if (split <= 0 || split == text.length - 1) {
            append(text)
        } else {
            append(text.substring(0, split + 1))
            withStyle(SpanStyle(color = accent, fontStyle = FontStyle.Italic)) {
                append(text.substring(split + 1))
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = InkLight,
    onPrimary = PaperLight,
    secondary = AccentLight,
    onSecondary = PaperLight,
    tertiary = AccentInkLight,
    onTertiary = PaperLight,
    error = LowLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = CardLight,
    onSurface = InkLight,
    surfaceVariant = Paper2Light,
    onSurfaceVariant = Ink2Light,
    outline = Ink3Light,
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = PaperDark,
    secondary = AccentDark,
    onSecondary = PaperDark,
    tertiary = AccentInkDark,
    onTertiary = PaperDark,
    error = LowDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = Paper2Dark,
    onSurfaceVariant = Ink2Dark,
    outline = Ink3Dark,
    surfaceTint = Color.Transparent,
)

private val RestrictorTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 64.sp,
        lineHeight = 66.sp,
        letterSpacing = (-1.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 10.5.sp,
        letterSpacing = 1.5.sp,
    ),
)

@Composable
fun RestrictorTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = preference == ThemePreference.DARK
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEditorialColors provides if (dark) EditorialDark else EditorialLight,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = RestrictorTypography,
            content = content,
        )
    }
}
