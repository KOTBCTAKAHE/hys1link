package io.github.kotbctakahe.hys1link.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import io.github.kotbctakahe.hys1link.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Accent palettes. Index is stored in Account.accent. */
data class Accent(val name: String, val light: Color, val lightContainer: Color, val dark: Color, val darkContainer: Color, val slab: Color)

val Accents = listOf(
    Accent("Хвоя", Color(0xFF006B54), Color(0xFFB4F0DA), Color(0xFF4FDDB5), Color(0xFF00513F), Color(0xFF0E3B33)),
    Accent("Залив", Color(0xFF00658E), Color(0xFFC6E7FF), Color(0xFF82CFFF), Color(0xFF004C6C), Color(0xFF0B2F42)),
    Accent("Слива", Color(0xFF7A4A8C), Color(0xFFF6D9FF), Color(0xFFE6B4F8), Color(0xFF603272), Color(0xFF34203D)),
    Accent("Янтарь", Color(0xFF7D5800), Color(0xFFFFDEA6), Color(0xFFF8BD45), Color(0xFF5F4100), Color(0xFF3A2C0E)),
)

private fun light(a: Accent) = lightColorScheme(
    primary = a.light, onPrimary = Color.White,
    primaryContainer = a.lightContainer, onPrimaryContainer = Color(0xFF0B201A),
    secondary = Color(0xFF4B635A), secondaryContainer = Color(0xFFCDE8DC), onSecondaryContainer = Color(0xFF082018),
    tertiary = Color(0xFF3F6375), tertiaryContainer = Color(0xFFC3E8FD),
    background = Color(0xFFEFF3F1), onBackground = Color(0xFF141C19),
    surface = Color(0xFFEFF3F1), onSurface = Color(0xFF17201D),
    surfaceVariant = Color(0xFFDCE5E0), onSurfaceVariant = Color(0xFF404945),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF2EF),
    surfaceContainer = Color(0xFFE7EDEA), surfaceContainerHigh = Color(0xFFE1E8E4), surfaceContainerHighest = Color(0xFFDBE3DF),
    outline = Color(0xFF707975), outlineVariant = Color(0xFFC0C9C4),
    error = Color(0xFFBA1A1A),
)

private fun dark(a: Accent) = darkColorScheme(
    primary = a.dark, onPrimary = Color(0xFF00382B),
    primaryContainer = a.darkContainer, onPrimaryContainer = Color(0xFFD5FFEE),
    secondary = Color(0xFFB2CCC1), secondaryContainer = Color(0xFF344C43), onSecondaryContainer = Color(0xFFCDE8DC),
    tertiary = Color(0xFFA7CCE0), tertiaryContainer = Color(0xFF264B5C),
    background = Color(0xFF0C1311), onBackground = Color(0xFFDEE5E1),
    surface = Color(0xFF0C1311), onSurface = Color(0xFFDEE5E1),
    surfaceVariant = Color(0xFF3F4945), onSurfaceVariant = Color(0xFFBFC9C3),
    surfaceContainerLowest = Color(0xFF0A110F), surfaceContainerLow = Color(0xFF151E1B),
    surfaceContainer = Color(0xFF19221F), surfaceContainerHigh = Color(0xFF232D29), surfaceContainerHighest = Color(0xFF2E3834),
    outline = Color(0xFF89938E), outlineVariant = Color(0xFF3F4945),
    error = Color(0xFFFFB4AB),
)

/** Onest (OFL) — designed with Cyrillic in mind, also covers Turkmen Latin (ň, ý, ž, ş, ä). */
val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

val AppTypography = Typography().let { t ->
    fun TextStyle.o(w: FontWeight? = null, ls: Float? = null) =
        copy(fontFamily = Onest, fontWeight = w ?: fontWeight, letterSpacing = ls?.sp ?: letterSpacing)
    t.copy(
        displayLarge = t.displayLarge.o(FontWeight.Bold, -1.2f),
        displayMedium = t.displayMedium.o(FontWeight.Bold, -1f),
        displaySmall = t.displaySmall.o(FontWeight.Bold, -0.8f),
        headlineLarge = t.headlineLarge.o(FontWeight.Bold, -0.8f),
        headlineMedium = t.headlineMedium.o(FontWeight.Bold, -0.6f),
        headlineSmall = t.headlineSmall.o(FontWeight.SemiBold, -0.3f),
        titleLarge = t.titleLarge.o(FontWeight.SemiBold, -0.3f),
        titleMedium = t.titleMedium.o(FontWeight.SemiBold, -0.1f),
        titleSmall = t.titleSmall.o(FontWeight.SemiBold),
        bodyLarge = TextStyle(fontFamily = Onest, fontSize = 16.sp, lineHeight = 23.sp),
        bodyMedium = t.bodyMedium.o(),
        bodySmall = t.bodySmall.o(),
        labelLarge = t.labelLarge.o(FontWeight.SemiBold),
        labelMedium = t.labelMedium.o(FontWeight.Medium),
        labelSmall = t.labelSmall.o(FontWeight.Medium),
    )
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun Hys1Theme(themeMode: Int, accent: Int, dynamic: Boolean, content: @Composable () -> Unit) {
    val darkMode = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val a = Accents.getOrElse(accent) { Accents[0] }
    val ctx = LocalContext.current
    val scheme: ColorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (darkMode) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkMode -> dark(a)
        else -> light(a)
    }
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}

fun slabColor(accent: Int) = Accents.getOrElse(accent) { Accents[0] }.slab
