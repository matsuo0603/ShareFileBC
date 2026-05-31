package com.example.sharefilebc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,

    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,

    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,

    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,

    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,

    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,

    error = md_theme_light_error,
    onError = md_theme_light_onError,

    outline = md_theme_light_outline
)

private val DarkColors = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,

    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,

    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,

    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,

    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,

    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,

    error = md_theme_dark_error,
    onError = md_theme_dark_onError,

    outline = md_theme_dark_outline
)

@Composable
fun ShareFileBCTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // お手本と同様：固定配色を優先（Dynamic Colorは使わない）
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
