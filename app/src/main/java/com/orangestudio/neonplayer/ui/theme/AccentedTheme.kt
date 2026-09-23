package com.orangestudio.neonplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Re-tints the accent band of the scheme - primary, the containers and the variants - around the
 * colour the user picked for the glow.
 *
 * The neutral slots are deliberately left as [NeonPlayerTheme] set them: the chosen colour is the
 * light in the room, not the paint on the walls, and re-tinting the backgrounds and surfaces as
 * well would wash out the very glow the picker exists to control.
 */
@Composable
fun AccentedTheme(accent: Color, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme

    // Whether the base scheme is the dark one, read off the scheme itself rather than off the
    // system setting, so a forced theme still gets the matching set of tints.
    val dark = base.background.luminance() < 0.5f

    MaterialTheme(
        colorScheme = base.withAccent(accent = accent, dark = dark),
        typography = MaterialTheme.typography,
        content = content,
    )
}

internal fun ColorScheme.withAccent(accent: Color, dark: Boolean): ColorScheme {
    // A near-black pick would otherwise read as "the accent never changed at all", so anything too
    // dark to be seen on its own is lifted towards white first.
    val primary = if (accent.luminance() < 0.10f) mix(accent, Color.White, 0.45f) else accent

    // The accent buttons carry black or white text, whichever the picked colour can hold.
    val onPrimary = if (primary.luminance() > 0.45f) Color.Black else Color.White

    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = mix(accent, if (dark) Color.Black else Color.White, if (dark) 0.62f else 0.72f),
        onPrimaryContainer = mix(accent, if (dark) Color.White else Color.Black, if (dark) 0.80f else 0.72f),
        secondary = mix(primary, if (dark) Color.White else Color.Black, 0.35f),
        onSecondary = onPrimary,
        secondaryContainer = mix(accent, if (dark) Color.Black else Color.White, if (dark) 0.50f else 0.78f),
        onSecondaryContainer = mix(accent, if (dark) Color.White else Color.Black, if (dark) 0.85f else 0.78f),
        tertiary = mix(primary, if (dark) Color.White else Color.Black, 0.50f),
        onTertiary = onPrimary,
        surfaceVariant = mix(accent, if (dark) Color.Black else Color.White, if (dark) 0.74f else 0.80f),
        onSurfaceVariant = mix(accent, if (dark) Color.White else Color.Black, if (dark) 0.60f else 0.45f),
    )
}

/** A straight sRGB blend, so a pick and the tints derived from it stay in the same family. */
private fun mix(base: Color, other: Color, fraction: Float): Color = Color(
    red = base.red + (other.red - base.red) * fraction,
    green = base.green + (other.green - base.green) * fraction,
    blue = base.blue + (other.blue - base.blue) * fraction,
    alpha = base.alpha,
)
