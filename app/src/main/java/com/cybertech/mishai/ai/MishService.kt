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
import android.os.IBinder
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
 *  1) listens for the wake word "Mish" (and variations mish/meesh/mishi)
 *  2) once triggered, activates AI conversation via MishBackend
 *  3) shows a floating overlay animation with the conversation bubble
 *
 * Runs in background even when app is closed, so "Mish" activates instantly
 * from any screen or app.
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

    private lateinit var prefs: PreferencesManager
    private lateinit var backend: MishBackend

    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningSession = false
    private var overlayAdded = false
    private var overlayView: View? = null
    private var waveView: View? = null
    private var bubbleText: TextView? = null
    private var triggerJob: Job? = null
    private var currentMaxAmp = 0f

    // TTS
    private var ttsEngine: android.speech.tts.TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferencesManager(this)
        backend = MishBackend(this)
        ttsEngine = android.speech.tts.TextToSpeech(applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                val langRes = ttsEngine?.setLanguage(Locale.UK)
                // allow any fallback; do not error
                if (langRes == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                    || langRes == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    // fallback to English
                }
            }
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        startWakeWordListening()

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        ttsEngine?.stop()
        ttsEngine?.shutdown()
        removeOverlay()
        super.onDestroy()
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

    // ---------- Wake word detection ----------
    private fun startWakeWordListening() {
        if (listeningSession) return
        listeningSession = true
        startRecognition(
            onStart = {},
            onResult = { text ->
                val t = text.lowercase(Locale.getDefault())
                val wake = WAKE_WORDS.any { t.contains(it) }
                if (wake) {
                    showOverlay()
                    triggerJob = scope.launch {
                        delay(250)
                        eligibleForConversation()
                    }
                } else {
                    if (!conversationActive) startWakeWordListening()
                }
            },
            onError = {
                scheduleRestart()
            }
        )
    }

    // ---------- Conversation capture ----------
    private var conversationActive = false

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
        startRecognition(
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
                    scheduleRestart(3000)
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

    // ---------- Shared recognition helper (wake + capture) ----------
    private var currentOnResult: ((String) -> Unit)? = null
    private var currentOnStart: (() -> Unit)? = null
    private var currentOnError: (() -> Unit)? = null

    private fun startRecognition(
        onStart: () -> Unit,
        onResult: (String) -> Unit,
        onError: () -> Unit
    ) {
        try {
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            rec.setRecognitionListener(this)
            rec.startListening(intent)
        } catch (e: Exception) {
            currentOnError?.invoke()
        }
    }

    private fun scheduleRestart(delayMs: Long = 1500) {
        scope.launch {
            delay(delayMs)
            startWakeWordListening()
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

    // ---------- Overlay (floating animation) ----------
    @SuppressLint("InflateParams")
    private fun showOverlay() {
        if (overlayAdded || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val hasOverlayPermission = packageManager.checkPermission(
            "android.permission.SYSTEM_ALERT_WINDOW",
            packageName
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasOverlayPermission) {
            return
        }

        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val li = LayoutInflater.from(this)
            val view = li.inflate(R.layout.overlay_mish, null)
            bubbleText = view.findViewById(R.id.bubble_text)
            waveView = view.findViewById(R.id.wave_view)

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
                y = 220
            }
            wm.addView(view, params)
            overlayAdded = true
            overlayView = view
            animateWave()
        } catch (e: Exception) {
            overlayAdded = false
        }
    }

    private fun animateWave() {
        val w = waveView ?: return
        val anim = w.animate()
            .scaleX(1.12f).scaleY(1.12f).setDuration(650).withLayer()
            .withEndAction {
                w.animate().scaleX(1f).scaleY(1f).setDuration(650).withLayer()
                    .withEndAction { if (overlayAdded) animateWave() }
                    .start()
            }
        anim.start()
    }

    private fun updateBubble(text: String) {
        bubbleText?.text = text
    }

    private fun removeOverlay() {
        if (!overlayAdded) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            overlayView?.let { wm.removeView(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayAdded = false
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}