package io.github.ayaseminami.gnbp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.ayaseminami.gnbp.persistence.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF245B47),
    secondary = Color(0xFF65558F),
    tertiary = Color(0xFF8A4F29),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF91D5B5),
    secondary = Color(0xFFD0BCFF),
    tertiary = Color(0xFFFFB68A),
    background = Color(0xFF111411),
    surface = Color(0xFF191C19),
)

@Composable
fun GnbpTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val darkTheme = themeMode.resolveDarkTheme(isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

internal fun ThemeMode.resolveDarkTheme(systemInDarkTheme: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemInDarkTheme
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}
