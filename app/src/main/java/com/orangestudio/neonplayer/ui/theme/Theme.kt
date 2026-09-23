package com.orangestudio.neonplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = OrangePrimaryDark,
    onPrimary = OrangeOnPrimaryDark,
    primaryContainer = OrangePrimaryContainerDark,
    onPrimaryContainer = OrangeOnPrimaryContainerDark,
    secondary = OrangeSecondaryDark,
    onSecondary = OrangeOnSecondaryDark,
    secondaryContainer = OrangeSecondaryContainerDark,
    onSecondaryContainer = OrangeOnSecondaryContainerDark,
    tertiary = OrangeTertiaryDark,
    onTertiary = OrangeOnTertiaryDark,
    background = OrangeBackgroundDark,
    onBackground = OrangeOnBackgroundDark,
    surface = OrangeSurfaceDark,
    onSurface = OrangeOnSurfaceDark,
    surfaceVariant = OrangeSurfaceVariantDark,
    onSurfaceVariant = OrangeOnSurfaceVariantDark,
)

private val LightColorScheme = lightColorScheme(
    primary = OrangePrimaryLight,
    onPrimary = OrangeOnPrimaryLight,
    primaryContainer = OrangePrimaryContainerLight,
    onPrimaryContainer = OrangeOnPrimaryContainerLight,
    secondary = OrangeSecondaryLight,
    onSecondary = OrangeOnSecondaryLight,
    secondaryContainer = OrangeSecondaryContainerLight,
    onSecondaryContainer = OrangeOnSecondaryContainerLight,
    tertiary = OrangeTertiaryLight,
    onTertiary = OrangeOnTertiaryLight,
    background = OrangeBackgroundLight,
    onBackground = OrangeOnBackgroundLight,
    surface = OrangeSurfaceLight,
    onSurface = OrangeOnSurfaceLight,
    surfaceVariant = OrangeSurfaceVariantLight,
    onSurfaceVariant = OrangeOnSurfaceVariantLight,
)

@Composable
fun NeonPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Kept as a switch: set to true to hand the palette back to the Android 12+ wallpaper colours
    // instead of the orange brand scheme.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
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
