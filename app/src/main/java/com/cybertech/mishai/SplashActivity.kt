package com.cybertech.mishai

import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import androidx.activity.ComponentActivity
import com.cybertech.mishai.ai.PreferencesManager

/**
 * Splash screen: plays loading_video.mp4 (8 seconds) auto.
 * After the video finishes, routes to onboarding (first run) or main UI.
 */
class SplashActivity : ComponentActivity() {

    private lateinit var videoView: VideoView
    private lateinit var prefs: PreferencesManager
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContentView(
            VideoView(this).apply {
                videoView = this
            }
        )

        val uri = Uri.parse("android.resource://" + packageName + "/" + R.raw.loading_video)
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { mp ->
            mp.isLooping = false
            mp.setVolume(0f, 0f)
        }
        videoView.setOnCompletionListener {
            proceedAfterSplash()
        }
        videoView.setOnErrorListener { _, _, _ ->
            // If video fails (missing codec etc.), still proceed after a delay
            handler.postDelayed({ proceedAfterSplash() }, 2000)
            true
        }
        videoView.start()
    }

    private fun proceedAfterSplash() {
        val profile = prefs.loadProfile()
        val target = if (profile.setupComplete) {
            Intent(this, MainActivity::class.java)
        } else {
            Intent(this, OnboardingActivity::class.java)
        }
        startActivity(target)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}