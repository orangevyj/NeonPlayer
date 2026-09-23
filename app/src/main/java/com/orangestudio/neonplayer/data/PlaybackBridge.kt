package com.orangestudio.neonplayer.data

import android.graphics.Bitmap

/** What the system media controls have asked the player to do. */
sealed interface PlaybackCommand {

    data object Play : PlaybackCommand

    data object Pause : PlaybackCommand

    data object Toggle : PlaybackCommand

    data object Next : PlaybackCommand

    data object Previous : PlaybackCommand

    /** The notification was dismissed, or the media chip was swiped away. */
    data object Stop : PlaybackCommand

    data class Seek(val seconds: Float) : PlaybackCommand
}

/**
 * What is playing, in the shape the notification and the session need it.
 *
 * [artwork] is already decoded: the player has a background thread for that sort of thing and the
 * service does not, so the service is handed a ready bitmap rather than a compressed cover.
 *
 * A data class so the player can tell "the same thing as last time" from "something has changed" and
 * leave the notification alone when nothing has moved.
 */
data class NowPlaying(
    val track: MusicTrack,
    val playing: Boolean,
    val positionSeconds: Float,
    val durationSeconds: Float,
    /** The colour the user picked for the glow, which the notification is tinted with. */
    val accent: Int,
    val artwork: Bitmap?,
)

/**
 * Where the notification service and the player meet.
 *
 * The engine belongs to the activity - it owns an `AudioTrack` and holds the decoded track in RAM -
 * and the activity is exactly the thing that stops existing when the app is off screen, so the
 * service cannot drive the engine directly. Instead everything passes through here: the service
 * leaves a command, the player picks it up while it is alive and calls the engine, and the player
 * posts its state back so the service has something to draw.
 */
object PlaybackBridge {

    /** Installed by the player while it can still act on a command, null once it is gone. */
    @Volatile
    var onCommand: ((PlaybackCommand) -> Unit)? = null

    /**
     * The player's latest state, kept here as well as in the notification.
     *
     * The service can be started a moment before the player publishes anything - and can be
     * restarted by the system - so it reads this to find out what it should be showing.
     */
    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    private var service: PlaybackService? = null

    internal fun attachService(service: PlaybackService) {
        this.service = service
        nowPlaying?.let { service.render(it) }
    }

    internal fun detachService(service: PlaybackService) {
        if (this.service === service) this.service = null
    }

    /** From the notification or the system UI towards the player. */
    internal fun send(command: PlaybackCommand) {
        onCommand?.invoke(command)
    }

    /** From the player towards the notification. A null [state] means nothing is playing. */
    fun publish(state: NowPlaying?) {
        nowPlaying = state
        service?.render(state)
    }
}
