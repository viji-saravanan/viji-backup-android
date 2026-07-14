package com.aryasubramani.vijibackup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    secondary = Color(0xFF4D635A),
    tertiary = Color(0xFF755B00),
    background = Color(0xFFF9FCFA),
    onBackground = Color(0xFF191C1B),
    surface = Color(0xFFF9FCFA),
    onSurface = Color(0xFF191C1B),
    surfaceVariant = Color(0xFFDAE5E0),
    onSurfaceVariant = Color(0xFF3F4945),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF53DBC5),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFB3CCC2),
    tertiary = Color(0xFFE7C349),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE1E3E1),
    surface = Color(0xFF101413),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBEC9C4),
    error = Color(0xFFFFB4AB),
)

@Composable
fun VijiBackupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
