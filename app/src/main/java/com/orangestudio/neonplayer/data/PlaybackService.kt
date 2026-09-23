package com.orangestudio.neonplayer.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.orangestudio.neonplayer.MainActivity
import com.orangestudio.neonplayer.R

/**
 * Keeps playback running with the app off screen, and hands the player to the system's controls.
 *
 * A foreground service because Android freezes the process as soon as the app leaves the screen:
 * without the promotion the audio would stutter the moment the user went elsewhere. The same
 * promotion is what puts the notification in the shade, and the [MediaSession] is what puts the
 * track on the lock screen and in the media chip in Quick Settings.
 *
 * The service does not own the player. The engine lives in the activity - it holds the decoded
 * track in RAM - so this is a thin shell: it turns taps into [PlaybackCommand]s and draws whatever
 * the activity last published through [PlaybackBridge].
 */
class PlaybackService : Service() {

    private lateinit var session: MediaSession
    private lateinit var manager: NotificationManager

    /** Whether the notification is up, so a bare start knows whether it still has to promote. */
    private var promoted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(NotificationManager::class.java)
        createChannel()

        session = MediaSession(this, SESSION_NAME).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    PlaybackBridge.send(PlaybackCommand.Play)
                }

                override fun onPause() {
                    PlaybackBridge.send(PlaybackCommand.Pause)
                }

                override fun onSkipToNext() {
                    PlaybackBridge.send(PlaybackCommand.Next)
                }

                override fun onSkipToPrevious() {
                    PlaybackBridge.send(PlaybackCommand.Previous)
                }

                override fun onStop() {
                    PlaybackBridge.send(PlaybackCommand.Stop)
                }

                override fun onSeekTo(positionMs: Long) {
                    PlaybackBridge.send(PlaybackCommand.Seek(positionMs / 1000f))
                }
            })
            setSessionActivity(openApp())
            isActive = true
        }

        PlaybackBridge.attachService(this)

        // The promotion cannot wait for the first state to arrive: a started foreground service has
        // only a few seconds to post its notification, so a placeholder goes out now and the real
        // one replaces it as soon as the player publishes anything - which is the same moment the
        // activity has finished decoding the track.
        goForeground(buildNotification(PlaybackBridge.nowPlaying))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The notification's buttons arrive back here as start commands: a PendingIntent aimed at
        // this service is the plainest way to deliver a tap, with no receiver to register and no
        // extra component to keep in step with the session's own callbacks.
        when (intent?.action) {
            ACTION_PREVIOUS -> PlaybackBridge.send(PlaybackCommand.Previous)
            ACTION_TOGGLE -> PlaybackBridge.send(PlaybackCommand.Toggle)
            ACTION_NEXT -> PlaybackBridge.send(PlaybackCommand.Next)
            ACTION_DISMISS -> PlaybackBridge.send(PlaybackCommand.Stop)
            null -> {
                // A bare start: the player asking for the notification to exist. Promoting again is
                // harmless while the service is already up, and it is the whole point when the
                // player starts something new in the moment between the foreground being given up
                // and the service actually going away - the system kills a started service that
                // never posts a notification.
                if (!promoted) goForeground(buildNotification(PlaybackBridge.nowPlaying))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PlaybackBridge.detachService(this)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    /**
     * Redraws the whole surface from one snapshot: the session's metadata, the position the lock
     * screen extrapolates its progress from, and the notification itself.
     *
     * A null [state] means nothing is playing any more, which takes the session down, drops the
     * notification and stops the service.
     */
    internal fun render(state: NowPlaying?) {
        if (state == null) {
            session.setMetadata(null)
            session.setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build(),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            promoted = false
            stopSelf()
            return
        }

        session.setMetadata(metadataFor(state))
        session.setPlaybackState(playbackStateFor(state))
        manager.notify(NOTIFICATION_ID, buildNotification(state))
    }

    /**
     * The session's metadata.
     *
     * The duration and the speed in the playback state are what let the lock screen run its own
     * progress bar between the moments the player tells it anything, so the position in here is
     * only a starting point.
     */
    private fun metadataFor(state: NowPlaying): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.track.title)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, (state.durationSeconds * 1000f).toLong())

        state.track.artist?.let { builder.putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
        state.artwork?.let { builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }

        return builder.build()
    }

    private fun playbackStateFor(state: NowPlaying): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SEEK_TO or
                PlaybackState.ACTION_STOP,
        )
        .setState(
            if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            (state.positionSeconds * 1000f).toLong(),
            if (state.playing) 1f else 0f,
        )
        .build()

    /**
     * The notification: cover art, the track, and the three controls.
     *
     * A platform [Notification.MediaStyle] rather than the support library's, so the app needs no
     * media dependency at all - everything it asks for has been in the framework since API 21.
     */
    private fun buildNotification(state: NowPlaying?): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(openApp())
            .setDeleteIntent(actionIntent(ACTION_DISMISS, REQUEST_DISMISS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )

        if (state == null) {
            builder
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_starting))
        } else {
            builder
                .setContentTitle(state.track.title)
                .setContentText(state.track.artist ?: getString(R.string.unknown_artist))
                .setColor(state.accent)
            state.artwork?.let { builder.setLargeIcon(it) }
        }

        // The middle button shows what pressing it will do, not what is happening now.
        val middle = if (state?.playing == true) {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_pause),
                getString(R.string.player_pause),
                actionIntent(ACTION_TOGGLE, REQUEST_TOGGLE),
            ).build()
        } else {
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_play),
                getString(R.string.player_play),
                actionIntent(ACTION_TOGGLE, REQUEST_TOGGLE),
            ).build()
        }

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_skip_previous),
                getString(R.string.player_previous),
                actionIntent(ACTION_PREVIOUS, REQUEST_PREVIOUS),
            ).build(),
        )
        builder.addAction(middle)
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_skip_next),
                getString(R.string.player_next),
                actionIntent(ACTION_NEXT, REQUEST_NEXT),
            ).build(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Put the notification up the moment it exists rather than letting the system hold it
            // back the usual ten seconds, which for a player reads as the controls not working.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_hint)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        manager.createNotificationChannel(channel)
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        promoted = true
    }

    /** A tap that lands back on this service, tagged with what it should do. */
    private fun actionIntent(action: String, request: Int): PendingIntent = PendingIntent.getService(
        this,
        request,
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {

        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val SESSION_NAME = "NeonPlayer"

        private const val ACTION_PREVIOUS = "com.orangestudio.neonplayer.action.PREVIOUS"
        private const val ACTION_TOGGLE = "com.orangestudio.neonplayer.action.TOGGLE"
        private const val ACTION_NEXT = "com.orangestudio.neonplayer.action.NEXT"
        private const val ACTION_DISMISS = "com.orangestudio.neonplayer.action.DISMISS"

        // Distinct request codes, so the pending intents are not folded into one another.
        private const val REQUEST_OPEN = 0
        private const val REQUEST_PREVIOUS = 1
        private const val REQUEST_TOGGLE = 2
        private const val REQUEST_NEXT = 3
        private const val REQUEST_DISMISS = 4

        /** Brings the notification up. Safe to call when the service is already running. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }
}
