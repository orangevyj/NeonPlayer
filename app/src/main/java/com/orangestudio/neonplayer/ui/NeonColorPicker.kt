package com.orangestudio.neonplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R
import com.orangestudio.neonplayer.data.ConfigStore
import java.util.Locale
import kotlin.math.roundToInt

/** One 8-bit channel - exactly what the ARGB the app stores is made of. */
private const val ChannelMax = 255f

/**
 * The colour picker for the neon.
 *
 * Three channel sliders rather than a colour wheel: the app stores packed ARGB and draws with those
 * same three channels, so a slider per channel maps straight onto the stored value with nothing in
 * between that could round the pick into a slightly different colour.
 *
 * The swatch is the preview. It is drawn the way a lit tube looks - the colour with a hot centre -
 * so a drag shows what the glow will do without leaving the screen.
 */
@Composable
fun NeonColorPicker(
    accent: Color,
    onAccentChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.neon_color_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.neon_color_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Swatch(accent = accent)
        }

        ChannelSlider(
            label = stringResource(R.string.neon_color_red),
            channel = accent.red,
            accent = accent,
            onChannelChange = { onAccentChange(accent.copy(red = it)) },
        )
        ChannelSlider(
            label = stringResource(R.string.neon_color_green),
            channel = accent.green,
            accent = accent,
            onChannelChange = { onAccentChange(accent.copy(green = it)) },
        )
        ChannelSlider(
            label = stringResource(R.string.neon_color_blue),
            channel = accent.blue,
            accent = accent,
            onChannelChange = { onAccentChange(accent.copy(blue = it)) },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hexOf(accent),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            TextButton(onClick = { onAccentChange(Color(ConfigStore.DefaultNeonColor)) }) {
                Text(stringResource(R.string.neon_color_reset))
            }
        }
    }
}

/** The colour as a lit tube shows it: the hue around the outside, the hot core in the middle. */
@Composable
private fun Swatch(accent: Color) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .drawBehind {
                val centre = Offset(size.width / 2f, size.height / 2f)
                val radius = size.minDimension / 2f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(neonCoreOf(accent), accent, accent),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            },
    )
}

@Composable
private fun ChannelSlider(
    label: String,
    channel: Float,
    accent: Color,
    onChannelChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = MaterialTheme.typography.labelLarge)

        Slider(
            value = channel * ChannelMax,
            onValueChange = { onChannelChange(it / ChannelMax) },
            valueRange = 0f..ChannelMax,
            // A step per integer value, so a slider lands on one of the 256 values the config can
            // actually hold rather than on a float that gets rounded on the way out.
            steps = ChannelMax.toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        Text(
            text = (channel * ChannelMax).roundToInt().toString(),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(32.dp),
        )
    }
}

/**
 * The strength of the neon: one multiplier over every glow in the app.
 *
 * `100%` is the look the app ships with, `0%` leaves the layout exactly as it is but dark, and
 * anything above that pushes every halo, sign and track hotter than the default.
 */
@Composable
fun NeonIntensitySlider(
    intensity: Float,
    accent: Color,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    NeonFactorSlider(
        title = stringResource(R.string.neon_intensity_title),
        hint = stringResource(R.string.neon_intensity_hint),
        reset = stringResource(R.string.neon_intensity_reset),
        value = intensity,
        defaultValue = ConfigStore.DefaultGlowIntensity,
        accent = accent,
        onValueChange = onIntensityChange,
        modifier = modifier,
    )
}

/**
 * How readily the glow catches the bass.
 *
 * `100%` is the response the app ships with; below it the glow answers only the hardest hits, and
 * above it gentler ones are enough to make the tube jump.
 */
@Composable
fun NeonSensitivitySlider(
    sensitivity: Float,
    accent: Color,
    onSensitivityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    NeonFactorSlider(
        title = stringResource(R.string.neon_sensitivity_title),
        hint = stringResource(R.string.neon_sensitivity_hint),
        reset = stringResource(R.string.neon_sensitivity_reset),
        value = sensitivity,
        defaultValue = ConfigStore.DefaultBassSensitivity,
        accent = accent,
        onValueChange = onSensitivityChange,
        modifier = modifier,
    )
}

/** The range both multipliers share: `0` (off), `1` (the default) and hotter past it. */
private const val FactorMax = 2f

/**
 * A titled multiplier slider with its own readout and reset.
 *
 * Shared by the two sliders above because they differ only in what they name and which value they
 * carry, and a second copy of the drag/readout/reset row would be a second place to keep in step.
 */
@Composable
private fun NeonFactorSlider(
    title: String,
    hint: String,
    reset: String,
    value: Float,
    defaultValue: Float,
    accent: Color,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = 0f..FactorMax,
                // 5% per step: fine enough to tune by eye, even enough that the readout below only
                // ever shows whole percentages.
                steps = 39,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.weight(1f),
            )

            Text(
                text = "${(value * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))

            TextButton(onClick = { onValueChange(defaultValue) }) {
                Text(reset)
            }
        }
    }
}

private fun hexOf(colour: Color): String = String.format(
    Locale.US,
    "#%02X%02X%02X",
    (colour.red * 255f).roundToInt(),
    (colour.green * 255f).roundToInt(),
    (colour.blue * 255f).roundToInt(),
)
