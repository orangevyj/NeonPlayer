package com.orangestudio.neonplayer.data

import android.content.Context
import android.net.Uri

/**
 * Stores the app configuration in the app's private `SharedPreferences`.
 *
 * SharedPreferences live in internal storage, which Android preserves across app updates - they
 * are only wiped on uninstall or an explicit "Clear storage" - so the folders the user picked are
 * still there after updating the app.
 *
 * The folder *grants* are separate: they are persisted with
 * `ContentResolver.takePersistableUriPermission`, which also survives restarts and updates.
 */
class ConfigStore(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Selected directory trees, in the order the user added them. */
    fun directories(): List<Uri> =
        preferences.getString(KEY_DIRECTORIES, null)
            ?.split(SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.map(Uri::parse)
            .orEmpty()

    fun setDirectories(directories: List<Uri>) {
        // Tree URIs are percent-encoded and therefore never contain a newline, so a
        // newline-separated list round-trips safely while keeping the user's ordering.
        val encoded = directories.joinToString(SEPARATOR) { it.toString() }
        preferences.edit().putString(KEY_DIRECTORIES, encoded).apply()
    }

    /**
     * Whether the user has finished the first-run setup.
     *
     * This is a one-time latch rather than something derived from "is a folder configured?": the
     * setup screen is meant to be seen once, and clearing every folder afterwards is a deliberate
     * Settings action that should not drag the user back through onboarding.
     */
    fun isSetupComplete(): Boolean = preferences.getBoolean(KEY_SETUP_COMPLETE, false)

    fun setSetupComplete(complete: Boolean) {
        preferences.edit().putBoolean(KEY_SETUP_COMPLETE, complete).apply()
    }

    /**
     * Whether the neon glow is switched on.
     *
     * On by default - the glow is the app's look - with the switch there for anyone who finds the
     * pulsing distracting.
     */
    fun neonEffects(): Boolean = preferences.getBoolean(KEY_NEON_EFFECTS, true)

    fun setNeonEffects(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_NEON_EFFECTS, enabled).apply()
    }

    /**
     * The colour the neon burns in, packed ARGB.
     *
     * An Int rather than a string because that is the shape the UI wants: the picker works channel
     * by channel and the theme takes the value as it is, so nothing has to be parsed in between.
     */
    fun neonColor(): Int = preferences.getInt(KEY_NEON_COLOR, DefaultNeonColor)

    fun setNeonColor(argb: Int) {
        preferences.edit().putInt(KEY_NEON_COLOR, argb).apply()
    }

    /**
     * How hard the neon burns, as a multiplier over every glow in the app.
     *
     * `1f` is the look the app ships with, `0f` leaves the layout untouched but dark, and values
     * above one push the glow hotter than the default.
     */
    fun glowIntensity(): Float = preferences.getFloat(KEY_GLOW_INTENSITY, DefaultGlowIntensity)

    fun setGlowIntensity(intensity: Float) {
        preferences.edit().putFloat(KEY_GLOW_INTENSITY, intensity).apply()
    }

    /**
     * How readily the glow catches the bass.
     *
     * A gain over every measured bass hit: `1f` is the response the app ships with, below it only
     * the hardest hits are enough to light the tube, and above it gentler ones get through too.
     */
    fun bassSensitivity(): Float =
        preferences.getFloat(KEY_BASS_SENSITIVITY, DefaultBassSensitivity)

    fun setBassSensitivity(sensitivity: Float) {
        preferences.edit().putFloat(KEY_BASS_SENSITIVITY, sensitivity).apply()
    }

    /**
     * How the player chooses the next track.
     *
     * Defaults to [PlaybackMode.List] - in library order - because it is the only mode that needs
     * nothing measured in advance.
     */
    fun playbackMode(): PlaybackMode {
        val stored = preferences.getString(KEY_PLAYBACK_MODE, null)
        return PlaybackMode.entries.firstOrNull { it.name == stored } ?: PlaybackMode.List
    }

    fun setPlaybackMode(mode: PlaybackMode) {
        preferences.edit().putString(KEY_PLAYBACK_MODE, mode.name).apply()
    }

    companion object {

        /**
         * The neon orange the app started out with, and what the picker resets to. The UI palette
         * reads it too, so the default is written down in exactly one place.
         */
        val DefaultNeonColor = 0xFFFF7A1A.toInt()

        /** The glow strength the app ships with, and what the strength slider starts at. */
        const val DefaultGlowIntensity = 1f

        /** The bass response the app ships with, and what the sensitivity slider starts at. */
        const val DefaultBassSensitivity = 1f

        private const val PREFERENCES_NAME = "neonplayer_config"
        private const val KEY_DIRECTORIES = "directories"
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_NEON_EFFECTS = "neon_effects"
        private const val KEY_NEON_COLOR = "neon_color"
        private const val KEY_GLOW_INTENSITY = "glow_intensity"
        private const val KEY_BASS_SENSITIVITY = "bass_sensitivity"
        private const val KEY_PLAYBACK_MODE = "playback_mode"
        private const val SEPARATOR = "\n"
    }
}
