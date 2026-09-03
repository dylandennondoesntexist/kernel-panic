package app.kernelpanic.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF9E3D2F),
    onPrimary = Color.White,
    secondary = Color(0xFF755A18),
    background = Color(0xFFFFF9F0),
    surface = Color(0xFFFFF9F0),
    surfaceVariant = Color(0xFFF5E8D7),
    onBackground = Color(0xFF251B16),
    onSurface = Color(0xFF251B16),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A6),
    onPrimary = Color(0xFF5F150C),
    secondary = Color(0xFFE4C36A),
    background = Color(0xFF1E1D1B),
    surface = Color(0xFF1E1D1B),
    surfaceVariant = Color(0xFF39322C),
    onBackground = Color(0xFFF1E8E1),
    onSurface = Color(0xFFF1E8E1),
)

@Composable
fun KernelPanicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}
