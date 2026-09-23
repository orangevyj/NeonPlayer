package com.orangestudio.neonplayer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R
import com.orangestudio.neonplayer.data.AudioEngine
import com.orangestudio.neonplayer.data.SpectrumAnalyzer
import kotlinx.coroutines.launch

/** The height of the collapsed player - and the height the whole window closes down to. */
val MiniBarHeight: Dp = 72.dp

/** The corner the window rounds off to once it has become the bar. */
val MiniBarCorner: Dp = 22.dp

/** The air left between the bar and the tabs it floats above. */
val MiniBarGap: Dp = 8.dp

private val BarPadding = 10.dp
private val BarSpacing = 4.dp
private val BarHaloSize = 38.dp
private val BarIconSize = 20.dp

/** How far a finger has to travel sideways before the bar lets go of the player. */
private val DismissDistance = 96.dp

/** The band count the visualizer draws, and how much audio one frame of it is measured from. */
private const val BandCount = 26
private const val BarWindow = 512

/** Each control arrives a little after the one to its left, in the wave the full player uses. */
private const val RevealStagger = 0.12f
private const val RevealSpan = 0.55f
private val RevealStartScale = 0.90f
private val RevealStartX = 10.dp

/** How long the strip takes to slide off the edge once the finger has let go of it. */
private const val SwipeOutMillis = 180

/**
 * The player, shrunk to a strip above the tabs.
 *
 * The bar carries its own transport plus a visualizer that fills whatever is left, so the track can
 * be driven without opening the whole window again. Its card and its halo belong to the window
 * behind it - one surface for both, so nothing is ever drawn twice - which leaves this to draw only
 * what sits on top of that surface.
 *
 * A tap anywhere on the strip opens the full player again; a sideways swipe dismisses it, and the
 * same dismissal is offered to a screen reader as an action, since a swipe is not something one can
 * be told about.
 */
@Composable
fun NeonMiniBar(
    engine: AudioEngine,
    pulse: NeonPulse,
    reveal: () -> Float,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val playing = engine.isPlaying.value
    val expandLabel = stringResource(R.string.mini_bar_expand)
    val dismissLabel = stringResource(R.string.mini_bar_dismiss)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MiniBarHeight)
            .graphicsLayer { translationX = offset.value }
            .pointerInput(Unit) { detectTapGestures { onExpand() } }
            .pointerInput(Unit) {
                val dismissPx = DismissDistance.toPx()

                detectHorizontalDragGestures(
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    onDragEnd = {
                        val travelled = offset.value
                        when {
                            travelled > dismissPx -> scope.launch {
                                offset.animateTo(size.width.toFloat(), tween(SwipeOutMillis))
                                onDismiss()
                            }

                            travelled < -dismissPx -> scope.launch {
                                offset.animateTo(-size.width.toFloat(), tween(SwipeOutMillis))
                                onDismiss()
                            }

                            // Not far enough to mean anything: it goes back where it belongs.
                            else -> scope.launch {
                                offset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        }
                    },
                    onHorizontalDrag = { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + drag) }
                    },
                )
            }
            .semantics {
                contentDescription = expandLabel
                customActions = listOf(
                    CustomAccessibilityAction(dismissLabel) {
                        onDismiss()
                        true
                    },
                )
            }
            .padding(horizontal = BarPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BarSpacing),
    ) {
        Reveal(index = 0, reveal = reveal) {
            NeonIcon(
                painter = painterResource(R.drawable.ic_skip_previous),
                contentDescription = stringResource(R.string.player_previous),
                pulse = pulse,
                haloSize = BarHaloSize,
                iconSize = BarIconSize,
                modifier = Modifier.clickable(onClick = onPrevious),
            )
        }

        Reveal(index = 1, reveal = reveal) {
            NeonIcon(
                painter = painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(
                    if (playing) R.string.player_pause else R.string.player_play,
                ),
                pulse = pulse,
                haloSize = BarHaloSize,
                iconSize = BarIconSize,
                modifier = Modifier.clickable { engine.toggle() },
            )
        }

        Reveal(index = 2, reveal = reveal) {
            NeonIcon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.player_next),
                pulse = pulse,
                haloSize = BarHaloSize,
                iconSize = BarIconSize,
                modifier = Modifier.clickable(onClick = onNext),
            )
        }

        Reveal(index = 3, reveal = reveal, modifier = Modifier.weight(1f)) {
            Visualizer(
                engine = engine,
                pulse = pulse,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One element of the strip, arriving in the same left-to-right wave the full player comes in on.
 *
 * The transform is applied as a graphics layer, so the frames of the reveal are read while drawing
 * rather than while composing and the controls themselves are never recomposed for the motion.
 */
@Composable
private fun Reveal(
    index: Int,
    reveal: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.graphicsLayer {
            val settled = ((reveal() - index * RevealStagger) / RevealSpan).coerceIn(0f, 1f)
            alpha = settled
            val scale = RevealStartScale + (1f - RevealStartScale) * settled
            scaleX = scale
            scaleY = scale
            translationX = -RevealStartX.toPx() * (1f - settled)
        },
    ) {
        content()
    }
}

/**
 * The bars that fill the strip.
 *
 * Measured off the track that is already decoded in memory, around wherever the playhead happens to
 * be, for the same reason the glow is: a live audio tap either falls silent during a drag or drifts
 * away from what is being heard, while a window read off the decoded track stays on the music while
 * the timeline is dragged forwards or backwards.
 *
 * The measurement happens inside the draw pass - one small FFT a frame, into buffers that were
 * allocated once - so the bars cost a redraw rather than a recomposition.
 */
@Composable
private fun Visualizer(engine: AudioEngine, pulse: NeonPulse, modifier: Modifier = Modifier) {
    val analyser = remember { SpectrumAnalyzer(BandCount, BarWindow) }
    val bands = remember { FloatArray(BandCount) }
    val description = stringResource(R.string.mini_bar_visualizer)
    val quiet = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .drawBehind {
                val audio = engine.loaded.value ?: return@drawBehind
                if (size.width <= 0f) return@drawBehind

                val playhead = engine.positionSeconds.value
                analyser.measure(audio.pcm, playhead, bands)

                val lit = pulse.on
                val barColour = if (lit) pulse.glowColor else quiet
                val tipColour = if (lit) pulse.coreColor else quiet
                val glow = if (lit) pulse.glow else 0f
                val bass = pulse.beat(audio.beats.strengthAt(playhead))

                val slot = size.width / BandCount
                val breadth = slot * 0.55f
                val radius = CornerRadius(breadth / 2f, breadth / 2f)
                val shortest = size.height * 0.14f
                val tallest = size.height * 0.92f

                for (band in 0 until BandCount) {
                    // The bass lifts the whole row, not just its own end of it: the track hitting
                    // hard is felt across the strip rather than only under the leftmost bars.
                    val swell = (bands[band] * (0.65f + 0.45f * bass)).coerceIn(0f, 1f)
                    val tall = shortest + (tallest - shortest) * swell
                    val left = band * slot + (slot - breadth) / 2f
                    val top = (size.height - tall) / 2f

                    // A halo under each bar, so the strip reads as lit rather than printed.
                    drawRoundRect(
                        color = barColour.copy(alpha = 0.20f * glow * swell),
                        topLeft = Offset(left - breadth, top - breadth / 2f),
                        size = Size(breadth * 3f, tall + breadth),
                        cornerRadius = radius,
                    )

                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(tipColour, barColour),
                            startY = top,
                            endY = top + tall,
                        ),
                        topLeft = Offset(left, top),
                        size = Size(breadth, tall),
                        cornerRadius = radius,
                    )
                }
            },
    )
}
