package com.orangestudio.neonplayer.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.orangestudio.neonplayer.R
import com.orangestudio.neonplayer.data.AudioEngine
import com.orangestudio.neonplayer.data.CoverArt
import com.orangestudio.neonplayer.data.MusicTrack
import com.orangestudio.neonplayer.data.PlaybackMode
import kotlinx.coroutines.delay

/** How the player is getting on with the track it was asked to play. */
sealed interface NeonPlayerStatus {

    /** Decoding the file; [fraction] runs `0..1`, or is null while the length is still unknown. */
    data class Preparing(val fraction: Float?) : NeonPlayerStatus

    /** Decoded and handed to the engine. */
    data object Ready : NeonPlayerStatus

    /** The file could not be read or decoded. */
    data object Failed : NeonPlayerStatus
}

/** How far the library measurement behind adaptive mode has got. */
data class AnalysisProgress(val done: Int, val total: Int)

/** Where each element's entrance sits in the sweep, counting upwards from the bottom. */
private const val EntranceOrderMode = 0
private const val EntranceOrderTransport = 1
private const val EntranceOrderTimeline = 2
private const val EntranceOrderTitle = 3
private const val EntranceOrderCover = 4
private const val EntranceOrderClose = 5

/** The gap between one element's entrance and the next. */
private const val EntranceStaggerMillis = 70L
private const val EntranceMillis = 460

/** Elements arrive slightly overlarge and travel in from beyond the bottom-left corner. */
private const val EntranceStartScale = 1.22f
private val EntranceStartX = 28.dp
private val EntranceStartY = 56.dp

private val PlayerPadding = 24.dp
private val TimelineTouchHeight = 56.dp
private val TimelineTrackHeight = 6.dp
private val TimelineKnobRadius = 7.dp
private val CoverCorner = 20.dp

/**
 * The full-screen player.
 *
 * Every element settles into place on the way in, each one a little later than the one below it, so
 * the wave of motion runs from the bottom-left corner up to the top-right one. On the way out the
 * same wave runs backwards and from the top down, which empties the window before the grey behind
 * it closes onto the little bar.
 */
@Composable
fun NeonPlayerScreen(
    track: MusicTrack,
    engine: AudioEngine,
    neonPulse: NeonPulse,
    mode: PlaybackMode,
    status: NeonPlayerStatus,
    progress: AnalysisProgress?,
    /** How far the window has closed, `0` while it is whole and `1` once it is the little bar. */
    exit: () -> Float,
    /** Whether this mount plays the entrance, or is arriving back from the little bar. */
    animateEntrance: Boolean,
    onCollapse: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onModeChange: (PlaybackMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = PlayerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Entrance(order = EntranceOrderClose, exit = exit, animate = animateEntrance) {
            CloseRow(onCollapse = onCollapse, neonPulse = neonPulse)
        }

        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            // The artwork is square and the column only knows how tall it may be, so the shorter of
            // the two constraints is what the cover is measured against.
            val side = minOf(maxWidth, maxHeight)

            Entrance(order = EntranceOrderCover, exit = exit, animate = animateEntrance) {
                CoverArt(
                    track = track,
                    engine = engine,
                    pulse = neonPulse,
                    status = status,
                    modifier = Modifier.size(side),
                )
            }
        }

        Entrance(order = EntranceOrderTitle, exit = exit, animate = animateEntrance) {
            TitleBlock(track = track, neonPulse = neonPulse)
        }

        Spacer(Modifier.height(18.dp))

        Entrance(order = EntranceOrderTimeline, exit = exit, animate = animateEntrance) {
            TimelineBlock(engine = engine, neonPulse = neonPulse)
        }

        Spacer(Modifier.height(10.dp))

        Entrance(order = EntranceOrderTransport, exit = exit, animate = animateEntrance) {
            TransportRow(
                engine = engine,
                neonPulse = neonPulse,
                onPrevious = onPrevious,
                onNext = onNext,
            )
        }

        Spacer(Modifier.height(18.dp))

        Entrance(order = EntranceOrderMode, exit = exit, animate = animateEntrance) {
            ModeBlock(
                mode = mode,
                progress = progress,
                neonPulse = neonPulse,
                onModeChange = onModeChange,
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

/** How much of the exit each element waits, so the window empties from the top down. */
private const val ExitStagger = 0.035f

/** How much of the exit one element takes to fade away. */
private const val ExitSpan = 0.18f

/**
 * Where the window is empty, as a fraction of the collapse into the little bar.
 *
 * The topmost element starts leaving straight away and the last one is gone here, so the window
 * reads it to know when the grey behind may start closing in on nothing.
 */
val PlayerExitEnd = EntranceOrderClose * ExitStagger + ExitSpan

/**
 * One element of the player's entrance.
 *
 * The animation is applied as a graphics layer, which is read while drawing rather than while
 * composing. Sixty frames a second of motion therefore never turns into sixty recompositions of
 * the element inside.
 */
@Composable
private fun Entrance(
    order: Int,
    exit: () -> Float,
    animate: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Arriving back from the little bar is not an entrance at all: the elements belong where they
    // are, and the same wave that took them out brings them home again.
    var started by remember { mutableStateOf(!animate) }
    LaunchedEffect(Unit) {
        if (!started) {
            delay(order * EntranceStaggerMillis)
            started = true
        }
    }

    val progress = animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = EntranceMillis, easing = FastOutSlowInEasing),
        label = "entrance",
    )

    Box(
        modifier = modifier.graphicsLayer {
            // Leaving runs the entrance backwards and starts at the top: the last element in is the
            // first one out, so the window empties downwards.
            val leaving = ((exit() - (EntranceOrderClose - order) * ExitStagger) / ExitSpan)
                .coerceIn(0f, 1f)
            val settled = progress.value * (1f - leaving)
            alpha = settled
            val scale = EntranceStartScale + (1f - EntranceStartScale) * settled
            scaleX = scale
            scaleY = scale
            translationX = -EntranceStartX.toPx() * (1f - settled)
            translationY = EntranceStartY.toPx() * (1f - settled)
        },
    ) {
        content()
    }
}

@Composable
private fun CloseRow(onCollapse: () -> Unit, neonPulse: NeonPulse) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeonIcon(
            painter = painterResource(R.drawable.ic_expand_more),
            contentDescription = stringResource(R.string.player_collapse),
            pulse = neonPulse,
            modifier = Modifier.clickable(onClick = onCollapse),
        )
    }
}

/**
 * The artwork, with a glow that swells with the music.
 *
 * The halo is a separate full-size layer drawn behind the artwork rather than something drawn by
 * the artwork itself, because the artwork's rounded corners clip what they draw and the glow is
 * meant to spill out past them.
 *
 * The glow comes from the beat map that was measured when the track was decoded, not from the
 * live audio, which is what lets it stay on the beat while the timeline is dragged in either
 * direction.
 */
@Composable
private fun CoverArt(
    track: MusicTrack,
    engine: AudioEngine,
    pulse: NeonPulse,
    status: NeonPlayerStatus,
    modifier: Modifier = Modifier,
) {
    val artwork = remember(track.uri, track.coverArt) {
        track.coverArt?.let(CoverArt::decode)?.asImageBitmap()
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    if (!pulse.on) return@drawBehind

                    val audio = engine.loaded.value ?: return@drawBehind
                    val strength = pulse.beat(audio.beats.strengthAt(engine.positionSeconds.value))
                    if (strength <= 0.001f) return@drawBehind

                    val middle = this.center
                    val radius = size.minDimension / 2f * (1.02f + 0.30f * strength)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                pulse.glowColor.copy(alpha = 0.55f * strength),
                                pulse.glowColor.copy(alpha = 0.18f * strength),
                                Color.Transparent,
                            ),
                            center = middle,
                            radius = radius,
                        ),
                        radius = radius,
                        center = middle,
                    )
                },
        )

        val shape = RoundedCornerShape(CoverCorner)

        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        } else {
            Image(
                painter = painterResource(CoverArt.placeholderFor(track.fileName)),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        }

        when (status) {
            is NeonPlayerStatus.Preparing -> PreparingOverlay(status.fraction)
            NeonPlayerStatus.Failed -> FailedOverlay()
            NeonPlayerStatus.Ready -> Unit
        }
    }
}

@Composable
private fun PreparingOverlay(fraction: Float?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(CoverCorner))
            .background(Color.Black.copy(alpha = 0.62f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(38.dp), strokeWidth = 3.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                text = if (fraction != null) {
                    stringResource(R.string.player_preparing, (fraction * 100f).toInt())
                } else {
                    stringResource(R.string.player_preparing_unknown)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(CoverCorner))
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.player_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun TitleBlock(track: MusicTrack, neonPulse: NeonPulse) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        NeonText(
            text = track.title,
            style = MaterialTheme.typography.headlineSmall,
            pulse = neonPulse,
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = track.artist ?: stringResource(R.string.unknown_artist),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TimelineBlock(engine: AudioEngine, neonPulse: NeonPulse) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TimeRow(engine = engine)
        NeonTimeline(
            engine = engine,
            pulse = neonPulse,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The elapsed and total times.
 *
 * Deliberately its own composable: it reads the playhead, so it recomposes on every frame of
 * playback. Keeping it here means only two small labels do that rather than the whole player.
 */
@Composable
private fun TimeRow(engine: AudioEngine) {
    val duration = engine.loaded.value?.pcm?.durationSeconds ?: 0f
    val position = engine.positionSeconds.value

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = formatTime(position),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTime(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The scrub bar.
 *
 * Dragging does not merely move the playhead, it plays from wherever the finger is: the speed the
 * finger is travelling across the bar is measured and handed to the engine as the playback rate.
 * Drag forwards and the track plays fast; drag backwards and the engine is asked for a negative
 * rate, so the track plays in reverse.
 */
@Composable
private fun NeonTimeline(
    engine: AudioEngine,
    pulse: NeonPulse,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val trackColour = MaterialTheme.colorScheme.onSurfaceVariant
    val knobColour = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .height(TimelineTouchHeight)
            .pointerInput(engine) {
                val width = size.width.toFloat()
                var resumeAfterDrag = false
                var lastSeconds = 0f
                var lastNanos = 0L

                detectDragGestures(
                    onDragStart = { offset ->
                        val duration = engine.loaded.value?.pcm?.durationSeconds ?: 0f
                        if (duration > 0f) {
                            resumeAfterDrag = engine.isPlaying.value
                            dragging = true
                            lastSeconds = (offset.x / width).coerceIn(0f, 1f) * duration
                            lastNanos = System.nanoTime()
                            dragFraction = lastSeconds / duration
                            // Playing, not merely seeking: the drag is meant to be heard.
                            engine.play()
                            engine.seekTo(lastSeconds)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val duration = engine.loaded.value?.pcm?.durationSeconds ?: 0f
                        if (duration > 0f) {
                            val seconds = (change.position.x / width).coerceIn(0f, 1f) * duration
                            val now = System.nanoTime()
                            val elapsed = (now - lastNanos) / 1_000_000_000.0

                            if (elapsed > 0.0005) {
                                // How much audio the finger covered per second of real time. That
                                // is exactly the rate to render at, and it is negative when the
                                // finger is heading back towards the start.
                                engine.setRate(((seconds - lastSeconds) / elapsed).toFloat())
                                lastNanos = now
                                lastSeconds = seconds
                            }

                            dragFraction = seconds / duration
                            engine.seekTo(seconds)
                        }
                    },
                    onDragEnd = {
                        dragging = false
                        engine.setRate(1f)
                        if (!resumeAfterDrag) engine.pause()
                    },
                    onDragCancel = {
                        dragging = false
                        engine.setRate(1f)
                        if (!resumeAfterDrag) engine.pause()
                    },
                )
            }
            .drawBehind {
                val audio = engine.loaded.value ?: return@drawBehind
                val duration = audio.pcm.durationSeconds
                if (duration <= 0f) return@drawBehind

                val playhead = engine.positionSeconds.value
                val fraction = if (dragging) {
                    dragFraction
                } else {
                    (playhead / duration).coerceIn(0f, 1f)
                }

                val glowing = pulse.on
                val glow = if (glowing) pulse.glow else 0f
                val beat = if (glowing) pulse.beat(audio.beats.strengthAt(playhead)) else 0f

                val middle = size.height / 2f
                val barHeight = TimelineTrackHeight.toPx()
                val barRadius = barHeight / 2f
                val knobRadius = TimelineKnobRadius.toPx()

                drawRoundRect(
                    color = if (glowing) trackColour.copy(alpha = 0.28f) else trackColour.copy(alpha = 0.35f),
                    topLeft = Offset(0f, middle - barRadius),
                    size = Size(size.width, barHeight),
                    cornerRadius = CornerRadius(barRadius, barRadius),
                )

                val travelled = size.width * fraction
                if (travelled > 0f) {
                    val lit = (0.5f + 0.4f * glow + 0.4f * beat).coerceAtMost(1.3f)

                    // Bloom behind the lit section, in the same language as the icon halos.
                    drawRoundRect(
                        color = pulse.glowColor.copy(alpha = (0.26f * lit).coerceAtMost(1f)),
                        topLeft = Offset(0f, middle - barRadius * 2.4f),
                        size = Size(travelled, barHeight * 2.4f),
                        cornerRadius = CornerRadius(barRadius * 2.4f, barRadius * 2.4f),
                    )

                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(pulse.glowColor, pulse.coreColor, pulse.glowColor)),
                        topLeft = Offset(0f, middle - barRadius),
                        size = Size(travelled, barHeight),
                        cornerRadius = CornerRadius(barRadius, barRadius),
                    )
                }

                val knobX = travelled.coerceIn(knobRadius, size.width - knobRadius)
                val knobCentre = Offset(knobX, middle)

                if (glowing) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                pulse.glowColor.copy(alpha = 0.62f * (0.45f + 0.55f * beat)),
                                pulse.glowColor.copy(alpha = 0.20f),
                                Color.Transparent,
                            ),
                            center = knobCentre,
                            radius = knobRadius * 3.2f,
                        ),
                        radius = knobRadius * 3.2f,
                        center = knobCentre,
                    )
                }

                drawCircle(
                    color = if (glowing) pulse.coreColor else knobColour,
                    radius = knobRadius,
                    center = knobCentre,
                )
            },
    )
}

@Composable
private fun TransportRow(
    engine: AudioEngine,
    neonPulse: NeonPulse,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // Only changes when playback starts or stops, so this is not a per-frame read.
    val playing = engine.isPlaying.value

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            iconRes = R.drawable.ic_skip_previous,
            contentDescription = stringResource(R.string.player_previous),
            pulse = neonPulse,
            onClick = onPrevious,
        )

        Spacer(Modifier.width(14.dp))

        TransportButton(
            iconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            contentDescription = stringResource(
                if (playing) R.string.player_pause else R.string.player_play
            ),
            pulse = neonPulse,
            onClick = { engine.toggle() },
            haloSize = 96.dp,
            iconSize = 44.dp,
        )

        Spacer(Modifier.width(14.dp))

        TransportButton(
            iconRes = R.drawable.ic_skip_next,
            contentDescription = stringResource(R.string.player_next),
            pulse = neonPulse,
            onClick = onNext,
        )
    }
}

@Composable
private fun TransportButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    pulse: NeonPulse,
    onClick: () -> Unit,
    haloSize: Dp = 56.dp,
    iconSize: Dp = 26.dp,
) {
    NeonIcon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        pulse = pulse,
        modifier = Modifier.clickable(onClick = onClick),
        haloSize = haloSize,
        iconSize = iconSize,
    )
}

@Composable
private fun ModeBlock(
    mode: PlaybackMode,
    progress: AnalysisProgress?,
    neonPulse: NeonPulse,
    onModeChange: (PlaybackMode) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.player_mode_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.width(12.dp))

            PlaybackMode.entries.forEachIndexed { index, entry ->
                if (index > 0) Spacer(Modifier.width(8.dp))
                ModeChip(
                    label = stringResource(modeLabel(entry)),
                    selected = entry == mode,
                    pulse = neonPulse,
                    onClick = { onModeChange(entry) },
                )
            }
        }

        if (progress != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.player_analyzing, progress.done, progress.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeChip(
    label: String,
    selected: Boolean,
    pulse: NeonPulse,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(percent = 50)

    Box(
        modifier = Modifier
            .clip(shape)
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(width = 1.dp, color = pulse.glowColor.copy(alpha = 0.75f), shape = shape)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (selected) {
            NeonText(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                pulse = pulse,
            )
        } else {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Minutes and seconds, as a track listing shows them. */
private fun formatTime(seconds: Float): String {
    if (!seconds.isFinite() || seconds <= 0f) return "0:00"
    val whole = seconds.toInt()
    val remainder = whole % 60
    val padded = if (remainder < 10) "0$remainder" else "$remainder"
    return "${whole / 60}:$padded"
}

@StringRes
private fun modeLabel(mode: PlaybackMode): Int = when (mode) {
    PlaybackMode.Random -> R.string.player_mode_random
    PlaybackMode.List -> R.string.player_mode_list
    PlaybackMode.Adaptive -> R.string.player_mode_adaptive
}
