package fi.tukkateatteri.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
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

@Composable
fun TukkateatteriTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme,
        content = content
    )
}
