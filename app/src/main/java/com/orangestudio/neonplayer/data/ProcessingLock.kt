package com.orangestudio.neonplayer.data

import android.content.Context
import android.os.PowerManager

/**
 * Holds the CPU awake for the length of a decode or a measuring pass.
 *
 * Android stops scheduling a process shortly after the screen goes off unless something holds a wake
 * lock, which is long enough for a decode that was running in the background to stall part-way
 * through and only finish once the phone is picked up again. A *partial* lock keeps the CPU running
 * without keeping the screen on, and it is released however the work ends - including when the work
 * is cancelled, which is why every caller pairs [acquire] with a `finally`.
 *
 * Only touched from the main thread (the pipeline runs on `Dispatchers.Main`), so the count below
 * needs no synchronisation. It is counted by hand rather than by the platform because a decode and a
 * measuring pass can overlap: one acquire for the first holder, one release for the last.
 */
class ProcessingLock(context: Context) {

    private val wakeLock: PowerManager.WakeLock? =
        (context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }

    private var holders = 0

    /** Keeps the CPU awake until the matching [release]. */
    fun acquire() {
        if (holders++ == 0) {
            // A timeout, so a lock can never outlive the work it was taken for even if a caller
            // somehow fails to release it: ten minutes is longer than any decode or scan here.
            runCatching { wakeLock?.acquire(TIMEOUT_MILLIS) }
        }
    }

    /** Gives the wake lock back once the last holder is done with it. */
    fun release() {
        if (--holders <= 0) {
            holders = 0
            runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "neonplayer:processing"
        const val TIMEOUT_MILLIS = 10L * 60L * 1000L
    }
}
