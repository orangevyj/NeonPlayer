package com.orangestudio.neonplayer.data

import android.net.Uri
import kotlin.random.Random

/**
 * How the player decides what follows the current track.
 */
enum class PlaybackMode {
    /** Any track, in a shuffled order that works through the whole library before repeating. */
    Random,

    /** The library in the order it is listed. */
    List,

    /**
     * The closest-sounding track, judged from the waveform.
     *
     * Tracks still unheard are preferred, so an evening drifts through similar-sounding music
     * without retreading; once everything has been played the list starts over.
     */
    Adaptive,
}

/**
 * Chooses the next and previous tracks.
 *
 * Pure functions over the library and a set of already-played indices, so the rules can be
 * followed without running the player.
 */
object PlaybackQueue {

    /**
     * The index to play after [current], or null when there is nothing to play.
     *
     * [played] is updated in place with what has been heard. Every mode except [PlaybackMode.List]
     * works through the unplayed tracks before repeating, and starts the list over once they run
     * out.
     */
    fun next(
        mode: PlaybackMode,
        tracks: List<MusicTrack>,
        current: Int,
        played: MutableSet<Int>,
        profiles: Map<Uri, SpectralProfile> = emptyMap(),
        random: Random = Random.Default,
    ): Int? {
        if (tracks.isEmpty()) return null
        if (tracks.size == 1) return 0

        played += current

        var unplayed = tracks.indices.filterNot { it in played }
        if (unplayed.isEmpty()) {
            // Everything has been heard: start the list over. The current track is kept out so
            // "next" never simply replays what is already playing.
            played.clear()
            played += current
            unplayed = tracks.indices.filterNot { it == current }.ifEmpty { listOf(current) }
        }

        return when (mode) {
            PlaybackMode.List -> (current + 1) % tracks.size
            PlaybackMode.Random -> unplayed[random.nextInt(unplayed.size)]
            PlaybackMode.Adaptive -> closest(unplayed, tracks, current, profiles)
        }
    }

    /**
     * The index the back button should play.
     *
     * Prefers where the listener actually came from, and falls back to the previous entry in the
     * list when there is no history yet - which is what makes back work on the very first track.
     */
    fun previous(
        mode: PlaybackMode,
        tracks: List<MusicTrack>,
        current: Int,
        history: List<Int>,
    ): Int? {
        if (tracks.isEmpty()) return null

        val cameFrom = history.lastOrNull()
        if (cameFrom != null && cameFrom != current) return cameFrom

        if (tracks.size == 1) return 0
        return (current - 1 + tracks.size) % tracks.size
    }

    /** The unplayed track whose waveform is most like the one playing. */
    private fun closest(
        candidates: List<Int>,
        tracks: List<MusicTrack>,
        current: Int,
        profiles: Map<Uri, SpectralProfile>,
    ): Int {
        val reference = profiles[tracks[current].uri] ?: return candidates.first()

        var best = candidates.first()
        var bestDistance = Float.MAX_VALUE

        for (index in candidates) {
            val candidate = profiles[tracks[index].uri] ?: continue
            val distance = reference.distanceTo(candidate)
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }

        return best
    }
}
