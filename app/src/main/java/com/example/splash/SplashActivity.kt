package com.example.splash

import android.content.ContentResolver
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.MainActivity
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standalone Video Splash Activity for El-Batal Express Merchant App.
 *
 * Plays the bundled offline `res/raw/intro.mp4` once automatically via Android Media3 ExoPlayer,
 * then verifies the existing Firebase Authentication session and navigates seamlessly to
 * [MainActivity] (either the Merchant Home Screen or the existing Login Screen).
 */
class SplashActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val KEY_PLAYBACK_POSITION = "splash_playback_position_ms"
        private const val KEY_HAS_NAVIGATED = "splash_has_navigated"
        private const val SAFETY_WATCHDOG_TIMEOUT_MS = 12_000L
    }

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var watchdogJob: Job? = null
    private var playbackPositionMs: Long = 0L
    private val hasNavigated = AtomicBoolean(false)

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                Log.d(TAG, "Intro video playback completed; checking Firebase Auth session.")
                completeIntroAndNavigate(withFade = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Intro video playback error (${error.errorCodeName}); falling back to auth flow.", error)
            completeIntroAndNavigate(withFade = false)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
            )
        }.onFailure { e ->
            Log.w(TAG, "Non-fatal enableEdgeToEdge warning on SplashActivity: ${e.message}")
        }

        val savedHasNavigated = savedInstanceState?.getBoolean(KEY_HAS_NAVIGATED, false) == true
        if (SplashSessionState.shouldSkipIntroVideo(SplashSessionState.hasCompletedIntroInSession, savedHasNavigated)) {
            completeIntroAndNavigate(withFade = false)
            return
        }

        playbackPositionMs = savedInstanceState
            ?.getLong(KEY_PLAYBACK_POSITION, SplashSessionState.lastPlaybackPositionMs)
            ?: SplashSessionState.lastPlaybackPositionMs

        // Ignore back presses while the short intro splash plays so it finishes cleanly.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally no-op during intro playback
            }
        })

        // Guard root container + Media3 PlayerView construction: PlayerView(context) inflates
        // PlayerControlView, RecyclerView, and fonts inside its 1-arg constructor before
        // `useController = false` runs. Any OEM inflation/surface error must safely skip to MainActivity.
        val setupSucceeded = try {
            val rootContainer = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                try {
                    setBackgroundResource(R.drawable.splash_video_background)
                } catch (bgEx: Exception) {
                    Log.w(TAG, "Failed to apply splash gradient drawable; falling back to solid color: ${bgEx.message}")
                    setBackgroundColor(Color.parseColor("#B80B06"))
                }
            }

            val view = PlayerView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                useController = false
                // Preserves original aspect ratio across phones, tablets, foldables, and landscape
                // without cropping the El-Batal Express logo while blending seamlessly into the crimson canvas.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.TRANSPARENT)
                setKeepContentOnPlayerReset(true)
                keepScreenOn = true
                isClickable = false
                isFocusable = false
                contentDescription = getString(R.string.splash_video_content_description)
            }

            rootContainer.addView(view)
            setContentView(rootContainer)
            playerView = view
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inflate SplashActivity PlayerView; skipping intro directly to MainActivity.", e)
            completeIntroAndNavigate(withFade = false)
            false
        }

        if (!setupSucceeded) return

        initializeAndStartPlayer()
        startSafetyWatchdog()
    }

    private fun initializeAndStartPlayer() {
        if (hasNavigated.get()) return
        if (exoPlayer != null) return

        try {
            val rawVideoUri = Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(packageName)
                .appendPath(R.raw.intro.toString())
                .build()

            val player = ExoPlayer.Builder(this).build().apply {
                // The intro video must always play silently in the merchant app.
                volume = 0f
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(playerListener)
                setMediaItem(MediaItem.fromUri(rawVideoUri))
                if (playbackPositionMs > 0L) {
                    seekTo(playbackPositionMs)
                }
                playWhenReady = true
                prepare()
            }

            exoPlayer = player
            playerView?.player = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Media3 ExoPlayer for intro video; proceeding to auth check.", e)
            completeIntroAndNavigate(withFade = false)
        }
    }

    private fun startSafetyWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            delay(SAFETY_WATCHDOG_TIMEOUT_MS)
            if (!hasNavigated.get()) {
                Log.w(TAG, "Splash video watchdog timeout reached; proceeding to Firebase Auth check.")
                completeIntroAndNavigate(withFade = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!hasNavigated.get()) {
            if (exoPlayer == null && playerView != null) {
                initializeAndStartPlayer()
            } else {
                exoPlayer?.playWhenReady = true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasNavigated.get()) {
            exoPlayer?.let { player ->
                if (player.playbackState == Player.STATE_ENDED) {
                    completeIntroAndNavigate(withFade = true)
                } else {
                    player.play()
                }
            }
        }
    }

    override fun onPause() {
        exoPlayer?.let { player ->
            val pos = player.currentPosition.coerceAtLeast(0L)
            playbackPositionMs = pos
            if (!SplashSessionState.hasCompletedIntroInSession) {
                SplashSessionState.lastPlaybackPositionMs = pos
            }
            player.pause()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        exoPlayer?.let { player ->
            playbackPositionMs = player.currentPosition.coerceAtLeast(0L)
        }
        outState.putLong(KEY_PLAYBACK_POSITION, playbackPositionMs)
        outState.putBoolean(KEY_HAS_NAVIGATED, hasNavigated.get())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        watchdogJob = null
        releasePlayer()
        playerView = null
        super.onDestroy()
    }

    private fun releasePlayer() {
        exoPlayer?.let { player ->
            try {
                player.removeListener(playerListener)
                playerView?.player = null
                player.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing ExoPlayer cleanly: ${e.message}")
            }
        }
        exoPlayer = null
    }

    private fun completeIntroAndNavigate(withFade: Boolean) {
        if (!hasNavigated.compareAndSet(false, true)) {
            return
        }

        watchdogJob?.cancel()
        watchdogJob = null
        SplashSessionState.markCompleted()

        var authLookupSucceeded = true
        val currentUser = try {
            Firebase.auth.currentUser
        } catch (e: Exception) {
            authLookupSucceeded = false
            Log.w(TAG, "Transient FirebaseAuth lookup failure after splash; deferring route check to MainActivity: ${e.message}")
            null
        }

        val destination = if (authLookupSucceeded) {
            SplashSessionState.resolvePostSplashDestination(
                hasCurrentUser = currentUser != null,
                isEmailVerified = currentUser?.isEmailVerified == true
            )
        } else {
            null
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            if (destination != null) {
                putExtra(MainActivity.EXTRA_START_DESTINATION, destination)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        startActivity(intent)
        if (withFade) {
            runCatching { applySmoothFadeTransition() }
                .onFailure { Log.w(TAG, "Non-fatal activity transition warning: ${it.message}") }
        }
        finish()
    }

    @Suppress("DEPRECATION")
    private fun applySmoothFadeTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                android.R.anim.fade_in,
                android.R.anim.fade_out
            )
        } else {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }
}
