package fi.tukkateatteri.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color

private val lightColorScheme = lightColorScheme(
    primary = Color(0xFF315F87),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D35),

    secondary = Color(0xFF52606F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4F7),
    onSecondaryContainer = Color(0xFF0E1D2A),

    tertiary = Color(0xFF246B45),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1F4DD),
    onTertiaryContainer = Color(0xFF002110),

    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF191C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1E),
    surfaceVariant = Color(0xFFE4E8EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF)
)

data class QuantityButtonColors(
    val container: Color,
    val content: Color,
    val disabledContainer: Color,
    val disabledContent: Color
)

val LocalQuantityButtonColors = staticCompositionLocalOf {
    QuantityButtonColors(
        container = Color.Unspecified,
        content = Color.Unspecified,
        disabledContainer = Color.Unspecified,
        disabledContent = Color.Unspecified
    )
}

private val quantityButtonColors = QuantityButtonColors(
    container = Color(0xFFE1E5E9),
    content = Color(0xFF35393E),
    disabledContainer = Color(0xFF9A9A9A),
    disabledContent = Color(0xFF3F4244)
)

private fun Color.darken(amount: Float): Color =
    lerp(this, Color.Black, amount)

@Composable
fun TukkateatteriTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalQuantityButtonColors provides quantityButtonColors
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme,
            content = content
        )
    }
}
