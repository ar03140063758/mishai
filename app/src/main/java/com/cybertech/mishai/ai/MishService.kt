package com.cybertech.mishai.ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.cybertech.mishai.MainActivity
import com.cybertech.mishai.MishBackend
import com.cybertech.mishai.MishServiceBridge
import com.cybertech.mishai.Mood
import com.cybertech.mishai.R
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that:
 *  1) listens for the wake word "Mish" (and variations) using on-device
 *     speech recognition so it works from any app / background
 *  2) once triggered, activates AI conversation via MishBackend
 *  3) shows a Siri-like floating animation (breathing circle) at all times
 *
 * Notes:
 *  - Wake listening uses "en-US" because "Mish" is recognised reliably there
 *    on every device; "ur-PK" is unavailable on most phones.
 *  - A wakelock keeps the mic+sensor alive even when the screen is on/off.
 */
class MishService : Service(), RecognitionListener {

    companion object {
        const val CHANNEL_ID = "mish_service_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "com.cybertech.mishai.START"
        const val ACTION_STOP = "com.cybertech.mishai.STOP"

        private val WAKE_WORDS = listOf("mish", "meesh", "mishi", "mishy", "mitch", "mishai", "meysh")

        var isRunning = false
            private set

        /** Hook used by the Compose UI to receive spoken text events. */
        var onUserSpeech: ((String) -> Unit)? = null
        var onMishReply: ((String, Mood) -> Unit)? = null
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: PreferencesManager
    private lateinit var backend: MishBackend

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningSession = false
    private var conversationActive = false
    private var overlayAdded = false
    private var overlayView: View? = null
    private var waveView: View? = null
    private var ringView: View? = null
    private var bubbleText: TextView? = null
    private var triggerJob: Job? = null
    private var errorBackoffMs = 800L
    private var currentMaxAmp = 0f
    private var wakeLock: PowerManager.WakeLock? = null

    // TTS
    private var ttsEngine: android.speech.tts.TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        backend = MishBackend(this)
        ttsEngine = android.speech.tts.TextToSpeech(applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsEngine?.setLanguage(Locale.UK)
            }
        }
        createChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMish()
                return START_NOT_STICKY
            }
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        // Show Siri-like animation immediately so the user sees Mish is alive.
        showOverlay("Mish sun rahi hai... 'Mish' bolo")
        startWakeWordListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMish()
        super.onDestroy()
    }

    private fun stopMish() {
        isRunning = false
        triggerJob?.cancel()
        triggerJob = null
        removeOverlay()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        releaseWakeLock()
    }

    // ---------- Wake lock (keeps mic live even when screen off) ----------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MishAI::wake")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
    }

    private fun refreshWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        acquireWakeLock()
    }

    // ---------- Foreground notification ----------
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mish AI Listening",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MishService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mish AI is listening")
            .setContentText("Say \"Mish\" anytime to talk")
            .setSmallIcon(R.drawable.ic_mish_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(R.drawable.ic_mish_notification, "Stop", stopPi)
            .build()
    }

    // ---------- Wake word detection (en-US, reliable on all devices) ----------
    private fun startWakeWordListening() {
        if (listeningSession) return
        refreshWakeLock()
        startRecognition(
            language = "en-US",
            onStart = {},
            onResult = { text ->
                errorBackoffMs = 800L
                val t = text.lowercase(Locale.getDefault())
                val wake = WAKE_WORDS.any { t.contains(it) }
                if (wake) {
                    updateBubble("Haan! Mish yahan hai 🙂")
                    triggerJob = scope.launch {
                        delay(250)
                        eligibleForConversation()
                    }
                } else {
                    if (!conversationActive) startWakeWordListening()
                }
            },
            onError = {
                scope.launch {
                    delay(errorBackoffMs)
                    errorBackoffMs = (errorBackoffMs * 1.6f).toLong().coerceAtMost(4000L)
                    if (!conversationActive) startWakeWordListening()
                }
            }
        )
    }

    // ---------- Conversation capture ----------
    private fun eligibleForConversation() {
        if (conversationActive) return
        conversationActive = true
        val profile = prefs.loadProfile()
        val name = profile.displayName()
        val role = profile.roleWord()
        speak("Haan $role $name, kaho, kya karna hai? Mish sun rahi hai.")
        startCaptureConversation()
    }

    private fun startCaptureConversation() {
        errorBackoffMs = 800L
        startRecognition(
            language = "en-US",
            onStart = {},
            onResult = { text ->
                if (text.isBlank()) {
                    conversationActive = false
                    startWakeWordListening()
                    return@startRecognition
                }
                MishServiceBridge.onUserSpeech?.invoke(text)
                onUserSpeech?.invoke(text)

                if (EmergencyHelper.isEmergencyTrigger(text)) {
                    val profile = prefs.loadProfile()
                    EmergencyHelper.sendEmergencyAlert(this, profile)
                    EmergencyHelper.openCall(this, profile.emergencyNumber)
                    speak("Emergency alert bhej diya hai, saath mein call ho rahi hai.")
                    conversationActive = false
                    scope.launch {
                        delay(3000)
                        startWakeWordListening()
                    }
                    return@startRecognition
                }

                backend.ask(text, currentMaxAmp, object : MishBackend.CallbackResult {
                    override fun onSuccess(reply: MishBackend.MishReply) {
                        runOnMain {
                            updateBubble(reply.text)
                            MishServiceBridge.onMishReply?.invoke(reply.text, reply.mood)
                            onMishReply?.invoke(reply.text, reply.mood)
                            speak(reply.text)
                            conversationActive = false
                            startWakeWordListening()
                        }
                    }

                    override fun onError(error: String) {
                        runOnMain {
                            speak("Kuch ghalti hui, dobara kaho.")
                            conversationActive = false
                            startWakeWordListening()
                        }
                    }
                })
            },
            onError = {
                conversationActive = false
                startWakeWordListening()
            }
        )
    }

    private fun speak(text: String) {
        ttsEngine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "mish_reply")
    }

    // ---------- Shared recognition helper ----------
    private var currentOnStart: (() -> Unit)? = null
    private var currentOnResult: ((String) -> Unit)? = null
    private var currentOnError: (() -> Unit)? = null

    private fun startRecognition(
        language: String,
        onStart: () -> Unit,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ) {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                currentOnError?.invoke()
                return
            }
            val rec = SpeechRecognizer.createSpeechRecognizer(this)
            if (speechRecognizer != null && speechRecognizer !== rec) {
                speechRecognizer?.destroy()
            }
            speechRecognizer = rec
            currentOnStart = onStart
            currentOnResult = onResult
            currentOnError = onError
            listeningSession = true

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            rec.setRecognitionListener(this)
            rec.startListening(intent)
        } catch (e: Exception) {
            currentOnError?.invoke()
        }
    }

    private fun runOnMain(block: () -> Unit) {
        scope.launch { block() }
    }

    // ---------- RecognitionListener ----------
    override fun onReadyForSpeech(params: Bundle?) { currentOnStart?.invoke() }
    override fun onRmsChanged(rmsdB: Float) {
        currentMaxAmp = ((rmsdB + 3f) / 6f).coerceIn(0f, 1f)
    }
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onResults(results: Bundle?) {
        listeningSession = false
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() } ?: ""
        currentOnResult?.invoke(text)
    }
    override fun onError(error: Int) {
        listeningSession = false
        currentOnError?.invoke()
    }

    // ---------- Siri-like floating animation ----------
    @SuppressLint("InflateParams")
    private fun showOverlay(bubble: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) return
        if (overlayAdded) {
            bubbleText?.text = bubble
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val li = LayoutInflater.from(this)
            val view = li.inflate(R.layout.overlay_mish, null)
            bubbleText = view.findViewById(R.id.bubble_text)
            waveView = view.findViewById(R.id.wave_view)
            ringView = view.findViewById(R.id.ring_view)
            bubbleText?.text = bubble

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 240
            }
            wm.addView(view, params)
            overlayAdded = true
            overlayView = view
            startPulseAnimation()
        } catch (e: Exception) {
            overlayAdded = false
        }
    }

    /** Breathing Siri-like pulse: core circle + expanding halo ring. */
    private var pulsePhase = 0f
    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!overlayAdded) return
            pulsePhase += 0.12f
            val core = 1f + 0.15f * sin(pulsePhase)
            waveView?.scaleX = core
            waveView?.scaleY = core

            val halo = 0.9f + 0.3f * sin(pulsePhase + 1.2f)
            ringView?.scaleX = halo
            ringView?.scaleY = halo
            ringView?.alpha = (0.25f + 0.2f * (0.5f + 0.5f * sin(pulsePhase + 1.2f)))
                .coerceIn(0f, 1f)

            waveView?.postDelayed(this, 50)
        }
    }

    private fun startPulseAnimation() {
        uiHandler.removeCallbacks(pulseRunnable)
        pulsePhase = 0f
        waveView?.post(pulseRunnable)
    }

    private fun updateBubble(text: String) {
        bubbleText?.text = text
        bubbleText?.postDelayed({
            if (overlayAdded) bubbleText?.text = "Mish sun rahi hai..."
        }, 5000)
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        uiHandler.removeCallbacks(pulseRunnable)
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayAdded = false
        overlayView = null
        waveView = null
        ringView = null
        bubbleText = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}