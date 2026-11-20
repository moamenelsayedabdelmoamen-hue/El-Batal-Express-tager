package com.example

import com.example.splash.SplashSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SplashSessionStateTest {

    @Before
    fun setUp() {
        SplashSessionState.resetForTesting()
    }

    @Test
    fun `fresh app launch plays intro video once and skips after completion`() {
        assertFalse(SplashSessionState.shouldSkipIntroVideo())

        SplashSessionState.lastPlaybackPositionMs = 1_800L
        assertFalse(SplashSessionState.shouldSkipIntroVideo())

        SplashSessionState.markCompleted()
        assertTrue(SplashSessionState.hasCompletedIntroInSession)
        assertEquals(0L, SplashSessionState.lastPlaybackPositionMs)
        assertTrue(SplashSessionState.shouldSkipIntroVideo())
    }

    @Test
    fun `recreated activity with savedHasNavigated skips video to prevent replay`() {
        assertTrue(
            SplashSessionState.shouldSkipIntroVideo(
                hasCompletedInSession = false,
                savedHasNavigated = true
            )
        )
    }

    @Test
    fun `post splash routing sends verified firebase user to dashboard and others to login`() {
        assertEquals(
            MainActivity.START_DESTINATION_DASHBOARD,
            SplashSessionState.resolvePostSplashDestination(
                hasCurrentUser = true,
                isEmailVerified = true
            )
        )
        assertEquals(
            MainActivity.START_DESTINATION_LOGIN,
            SplashSessionState.resolvePostSplashDestination(
                hasCurrentUser = true,
                isEmailVerified = false
            )
        )
        assertEquals(
            MainActivity.START_DESTINATION_LOGIN,
            SplashSessionState.resolvePostSplashDestination(
                hasCurrentUser = false,
                isEmailVerified = false
            )
        )
    }
}
