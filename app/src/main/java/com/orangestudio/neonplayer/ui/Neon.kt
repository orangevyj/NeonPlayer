package com.orangestudio.neonplayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.data.ConfigStore

/**
 * The tube colour before the user has picked one, pushed towards full saturation so it reads as
 * neon. The picker starts here, and resets to here.
 */
val NeonGlow = Color(ConfigStore.DefaultNeonColor)

/** The hot, almost-white centre of a lit tube, for the default colour above. */
val NeonCore = Color(0xFFFFE6CC)

private val HaloSize = 44.dp
private val IconSize = 24.dp

/**
 * The brightness of the neon, travelling through the composition as one stable object.
 *
 * Passing the current brightness around as a plain `Float` would be a trap: it changes on every
 * frame of the pulse, so every composable between the state and the tube - the bottom bar, the
 * whole Music list - would recompose sixty times a second. Holding the animation as a `State`
 * instead keeps the callers skippable, leaving only the few leaves that draw the glow invalidated.
 */
@Stable
class NeonPulse internal constructor(
    private val brightness: State<Float>?,
    /** The colour the tube burns in. */
    val glowColor: Color,
    /** The hot centre of the tube. */
    val coreColor: Color,
    /**
     * How hard the tube burns: a multiplier over the pulse, `0` meaning no glow at all.
     *
     * Sitting here rather than at each call site is what lets the strength slider reach every
     * halo, sign, track and glyph without a second parameter threaded through the screens.
     */
    val intensity: Float = 1f,
    /**
     * How readily the glow catches the bass: a gain over every measured hit.
     *
     * Like [intensity] this rides on the pulse, so the response slider reaches the player's halo,
     * the timeline and the mini bar without a fourth parameter threaded through the screens.
     */
    val sensitivity: Float = 1f,
) {

    /** Whether the glow is lit at all. */
    val on: Boolean get() = brightness != null && intensity > 0.001f

    /** How bright the tube is right now, scaled by the strength the user picked. */
    val glow: Float get() = (brightness?.value ?: 0f) * intensity

    /**
     * How hard a measured bass hit should light the tube.
     *
     * The beat map already normalises every hit to `0..1`; this is where the user's bass response
     * becomes a gain over that, so turning it down leaves only the hardest hits glowing and turning
     * it up lets gentler ones through as well.
     */
    fun beat(strength: Float): Float = (strength * sensitivity).coerceIn(0f, 1f)

    internal companion object {
        /** The effects switched off: a tube that is simply dark. */
        val Off = NeonPulse(brightness = null, glowColor = NeonGlow, coreColor = NeonCore)
    }
}

/**
 * The shared neon pulse, so the bottom bar, the "Music" sign and the Settings switch breathe
 * together instead of drifting apart as separate animations would.
 *
 * The colour, the strength and the bass response ride on the pulse too. Everything that draws a
 * tube already takes the pulse, so putting them here changes the hue, the heat and the reaction of
 * the whole UI without a second, third and fourth parameter threaded through every screen.
 */
@Composable
fun rememberNeonPulse(
    enabled: Boolean,
    accent: Color = NeonGlow,
    intensity: Float = ConfigStore.DefaultGlowIntensity,
    sensitivity: Float = ConfigStore.DefaultBassSensitivity,
): NeonPulse {
    val coreColor = neonCoreOf(accent)
    if (!enabled) {
        return remember(accent, coreColor, intensity, sensitivity) {
            NeonPulse(null, accent, coreColor, intensity, sensitivity)
        }
    }

    val transition = rememberInfiniteTransition(label = "neon")
    val brightness = transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "neonPulse",
    )

    return remember(brightness, accent, coreColor, intensity, sensitivity) {
        NeonPulse(brightness, accent, coreColor, intensity, sensitivity)
    }
}

/**
 * The hot centre for a given tube colour: the colour itself, lifted towards white.
 *
 * A fixed near-white core only looks right under a warm colour - under a blue tube it turns into a
 * muddy grey - so the core is derived from the pick and stays in its family.
 */
fun neonCoreOf(accent: Color): Color {
    val lift = 0.80f
    return Color(
        red = accent.red + (1f - accent.red) * lift,
        green = accent.green + (1f - accent.green) * lift,
        blue = accent.blue + (1f - accent.blue) * lift,
        alpha = accent.alpha,
    )
}

/**
 * Concentric blurs stacked behind the glyphs: a wide faint haze, a tighter halo, then the bright
 * core on top. A [TextStyle] only carries a single `shadow`, so each layer is its own `Text`.
 */
private val GlowLayers = listOf(
    26f to 0.22f,
    14f to 0.34f,
    5f to 0.55f,
)

/**
 * Text rendered like a lit neon tube.
 *
 * With a dark [NeonPulse] this degrades to an ordinary `Text` that inherits the surrounding content
 * colour, so switching the effects off leaves the layout identical.
 *
 * [contentAlignment] only matters when the glow is on: the layers are stacked in a `Box`, and a
 * `Box` wider than its text - a full-width title, say - has to be told to centre them rather than
 * leave them at the start.
 */
@Composable
fun NeonText(
    text: String,
    style: TextStyle,
    pulse: NeonPulse,
    modifier: Modifier = Modifier,
    glowColor: Color = pulse.glowColor,
    coreColor: Color = pulse.coreColor,
    contentAlignment: Alignment = Alignment.TopStart,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    if (!pulse.on) {
        Text(
            text = text,
            style = style,
            modifier = modifier,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    // Read in composition: the brightness is baked into the glyph shadows, which Compose can only
    // rebuild by recomposing. That is affordable here - there are only a handful of such signs.
    val glow = pulse.glow

    Box(modifier = modifier, contentAlignment = contentAlignment) {
        GlowLayers.forEach { (blurRadius, alpha) ->
            Text(
                text = text,
                style = style.copy(
                    shadow = Shadow(
                        color = glowColor.copy(alpha = alpha * glow),
                        offset = Offset.Zero,
                        blurRadius = blurRadius,
                    ),
                ),
                color = glowColor,
                maxLines = maxLines,
                overflow = overflow,
            )
        }

        Text(
            text = text,
            style = style.copy(
                shadow = Shadow(
                    color = glowColor.copy(alpha = 0.85f * glow),
                    offset = Offset.Zero,
                    blurRadius = 4f,
                ),
            ),
            color = coreColor,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

/**
 * An icon sitting on a soft round halo.
 *
 * The halo is a radial gradient rather than a real blur because `Modifier.blur` needs
 * `RenderEffect`, which only exists from API 31 while this app supports API 28. It reads the pulse
 * inside `drawBehind`, so pulsing costs a redraw rather than a recomposition.
 *
 * The sizes are adjustable so the same halo can back a small transport button and the much larger
 * play button in the middle of the player.
 */
@Composable
fun NeonIcon(
    painter: Painter,
    contentDescription: String?,
    pulse: NeonPulse,
    modifier: Modifier = Modifier,
    glowColor: Color = pulse.glowColor,
    coreColor: Color = pulse.coreColor,
    haloSize: Dp = HaloSize,
    iconSize: Dp = IconSize,
) {
    Box(
        modifier = modifier
            .size(haloSize)
            .drawBehind {
                if (!pulse.on) return@drawBehind

                val glow = pulse.glow
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = 0.55f * glow),
                            glowColor.copy(alpha = 0.16f * glow),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            // Unspecified keeps the icon on the bottom bar's normal content colour.
            tint = if (pulse.on) coreColor else Color.Unspecified,
            modifier = Modifier.size(iconSize),
        )
    }
}
