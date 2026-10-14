package com.yan.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = NeumorphismPrimarySoft,
    secondary = NeumorphismOutline,
    tertiary = NeumorphismSurface,
    background = NeumorphismBackground,
    onBackground = NeumorphismTextPrimary,
    surface = NeumorphismSurface,
    onSurface = NeumorphismTextPrimary,
    surfaceVariant = NeumorphismSurfacePressed,
    onSurfaceVariant = NeumorphismTextSecondary,
    outline = NeumorphismOutline
)

private val LightColorScheme = lightColorScheme(
    primary = NeumorphismPrimary,
    onPrimary = NeumorphismShadowLight,
    primaryContainer = NeumorphismPrimarySoft,
    onPrimaryContainer = NeumorphismTextPrimary,
    secondary = NeumorphismTextSecondary,
    onSecondary = NeumorphismShadowLight,
    background = NeumorphismBackground,
    onBackground = NeumorphismTextPrimary,
    surface = NeumorphismSurface,
    onSurface = NeumorphismTextPrimary,
    surfaceVariant = NeumorphismSurfacePressed,
    onSurfaceVariant = NeumorphismTextSecondary,
    outline = NeumorphismOutline,
    outlineVariant = NeumorphismOutline,
    error = Color(0xFFC44E52),
    onError = NeumorphismShadowLight,
    errorContainer = Color(0xFFF7D5D6),
    onErrorContainer = Color(0xFF7A2327)
)

@Composable
fun ClientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}