package com.example.splash

import com.example.MainActivity

/**
 * Process-scoped state tracking whether the animated intro splash video has already played
 * during the current app launch session.
 *
 * - On a fresh cold launch (new process), [hasCompletedIntroInSession] starts as `false`, so the
 *   intro video plays once automatically from start to finish.
 * - On returning from background, screen rotation, or Activity recreation within the same process,
 *   [hasCompletedIntroInSession] prevents replaying the video, while [lastPlaybackPositionMs]
 *   ensures an in-progress playback resumes from its last timestamp rather than restarting at 0.
 */
object SplashSessionState {

    @Volatile
    var hasCompletedIntroInSession: Boolean = false

    @Volatile
    var lastPlaybackPositionMs: Long = 0L

    fun shouldSkipIntroVideo(
        hasCompletedInSession: Boolean = hasCompletedIntroInSession,
        savedHasNavigated: Boolean = false
    ): Boolean = hasCompletedInSession || savedHasNavigated

    fun resolvePostSplashDestination(
        hasCurrentUser: Boolean,
        isEmailVerified: Boolean
    ): String {
        return if (hasCurrentUser && isEmailVerified) {
            MainActivity.START_DESTINATION_DASHBOARD
        } else {
            MainActivity.START_DESTINATION_LOGIN
        }
    }

    fun markCompleted() {
        hasCompletedIntroInSession = true
        lastPlaybackPositionMs = 0L
    }

    internal fun resetForTesting() {
        hasCompletedIntroInSession = false
        lastPlaybackPositionMs = 0L
    }
}
