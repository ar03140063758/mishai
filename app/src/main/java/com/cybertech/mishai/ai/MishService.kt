package com.cybertech.mishai.ai

import android.Manifest
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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.cybertech.mishai.MainActivity
import com.cybertech.mishai.MishBackend
import com.cybertech.mishai.MishServiceBridge
import com.cybertech.mishai.Mood
import com.cybertech.mishai.R
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground service that:
 *  1) runs a REAL continuous microphone stream via AudioRecord with a custom
 *     Voice Activity Detector (VAD) so the mic never "turns off" on small sounds.
 *  2) only forwards captured speech to SpeechRecognizer when VAD confirms the user
 *     is actually speaking (above silence threshold for a minimum duration).
 *  3) after Mish replies, automatically returns to listening (no manual retrigger).
 *  4) shows a floating, draggable + resizable, state-reactive orb.
 *
 *  Orb states: IDLE / LISTENING / THINKING / SPEAKING
 *  - Listening: reacts to live microphone amplitude
 *  - Speaking : reacts to TTS playback while the assistant talks
 */
class MishService : Service(), RecognitionListener {

    companion object {
        const val CHANNEL_ID = "mish_service_channel"
        const val NOTIFICATION_ID = 101
        const val ACTION_START = "com.cybertech.mishai.START"
        const val ACTION_STOP = "com.cybertech.mishai.STOP"

        var isRunning = false
            private set

        var onUserSpeech: ((String) -> Unit)? = null
        var onMishReply: ((String, Mood) -> Unit)? = null

        // UI reads the orb state + amplitude for transparency
        @Volatile var orbState: OrbState = OrbState.IDLE
        @Volatile var orbAmplitude: Float = 0f

        enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }
    }

    // Orb position/size persistence (in px, relative to screen)
    private val prefsForOrb get() = PreferencesManager(this)
    private var orbX = 0
    private var orbY = 0
    private var orbSizePx = 90f

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        backend = MishBackend(this)
        ttsEngine = android.speech.tts.TextToSpeech(applicationContext) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsEngine?.setLanguage(Locale.UK)
                ttsEngine?.setOnUtteranceProgressListener(object :
                    android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if ("mish_reply" == utteranceId) {
                            setOrbState(OrbState.SPEAKING)
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if ("mish_reply" == utteranceId) {
                            setOrbState(OrbState.LISTENING)
                        }
                    }
                    @Suppress("DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        if ("mish_reply" == utteranceId) {
                            setOrbState(OrbState.LISTENING)
                        }
                    }
                })
            }
        }
        createChannel()
        acquireWakeLock()
    }

    // ---------- Lifecycle ----------
    private lateinit var backend: MishBackend
    private var ttsEngine: android.speech.tts.TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopMish(); return START_NOT_STICKY }
        }
        isRunning = true
        startForeground(NOTIFICATION_ID, buildNotification())
        showOrb()
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        stopMish()
        super.onDestroy()
    }

    private fun stopMish() {
        isRunning = false
        stopOrbAnimation()
        removeOrb()
        stopListening()
        speechRecognizer?.destroy(); speechRecognizer = null
        ttsEngine?.stop(); ttsEngine?.shutdown()
        releaseWakeLock()
        scope.cancel()
        job.cancel()
    }

    // ---------- Wake lock ----------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MishAI::wake")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L)
        } catch (e: Exception) { e.printStackTrace() }
    }
    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { e.printStackTrace() }
        wakeLock = null
    }
    private fun refreshWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { e.printStackTrace() }
        acquireWakeLock()
    }

    // ---------- Notification ----------
    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Mish AI", NotificationManager.IMPORTANCE_LOW)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }
    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, MishService::class.java).setAction(ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mish AI")
            .setContentText("Ready — bolo aur jawab do")
            .setSmallIcon(R.drawable.ic_mish_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(R.drawable.ic_mish_notification, "Stop", stopPi)
            .build()
    }

    // =========================================================
    //  CONTINUOUS LISTENING (AudioRecord + custom VAD)
    // =========================================================
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    // VAD parameters
    private val SAMPLE_RATE = 16000
    private val SILENCE_THRESHOLD = 0.055f        // below = silence (tunable)
    private val MIN_SPEECH_MS = 180                 // must be speaking this long to be "speech"
    private val HOLD_MS = 500                       // silence this long ends a segment
    private val MIN_BARGE_MS = 1200                 // min utterance before barge-in

    private val speechSamples = mutableListOf<Short>()
    @Volatile private var segmentActive = false
    @Volatile private var segmentStartedAt = 0L
    @Volatile private var lastSpeechAt = 0L
    @Volatile private var recognizing = false      // SpeechRecognizer currently running
    @Volatile private var segmentPeakAmp = 0f

    /**
     * A complete voice segment was detected by our VAD above the silence
     * threshold for the minimum duration. We now hand the mic over to
     * SpeechRecognizer for actual transcription. Before starting, we pause
     * our own AudioRecord so both don't fight for the mic.
     */
    private fun onVoiceSegment(@Suppress("UNUSED_PARAMETER") clip: List<Short>,
                               @Suppress("UNUSED_PARAMETER") durationMs: Long) {
        if (recognizing || !isRunning) return
        recognizing = true
        setOrbState(OrbState.LISTENING)
        orbAmplitude = 0.9f

        // Pause our VAD monitor so SpeechRecognizer gets exclusive mic access
        pauseAudioMonitor()

        // Start a fresh recognizer for the ongoing utterance
        startSpeechRecognizer()

        // Safety: if recognizer never completes, resume monitoring
        uiHandler.postDelayed({ resumeAfterRecognition() }, 20000)
    }

    private fun startSpeechRecognizer() {
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                recognizing = false; resumeAudioMonitor(); return
            }
            suppressGoogleMicSound()
            val rec = SpeechRecognizer.createSpeechRecognizer(this)
            if (speechRecognizer != null && speechRecognizer !== rec) speechRecognizer?.destroy()
            speechRecognizer = rec
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra("android.speech.extra.DICTATION_MODE", true)
            }
            rec.setRecognitionListener(modelListener())
            rec.startListening(intent)
        } catch (e: Exception) {
            recognizing = false
            resumeAudioMonitor()
        }
    }

    private var savedNotifVol = 0
    private var savedSystemVol = 0

    private fun suppressGoogleMicSound() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedNotifVol = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
            savedSystemVol = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
        } catch (_: Exception) {}
    }

    private fun restoreAudioAfterRecognition() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, savedNotifVol, 0)
            am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemVol, 0)
        } catch (_: Exception) {}
    }

    // Listener used for the continuous conversation model
    private fun modelListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {
                if (isRunning && orbState == OrbState.LISTENING)
                    orbAmplitude = ((rmsdB + 3f) / 6f).coerceIn(0f, 1f)
            }
            override fun onBeginningOfSpeech() { setOrbState(OrbState.LISTENING); orbAmplitude = 0.6f }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                val t = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (t != null) {
                    MishServiceBridge.onUserSpeech?.invoke(t)
                    onUserSpeech?.invoke(t)
                }
            }
            override fun onResults(results: Bundle?) {
                recognizing = false
                restoreAudioAfterRecognition()
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.takeIf { it.isNotBlank() } ?: ""
                speechRecognizer?.destroy(); speechRecognizer = null
                resumeAudioMonitor()
                if (isRunning) handleRecognizedText(text)
            }
            override fun onError(error: Int) {
                recognizing = false
                restoreAudioAfterRecognition()
                speechRecognizer?.destroy(); speechRecognizer = null
                resumeAudioMonitor()
                if (isRunning) setOrbState(OrbState.LISTENING)
            }
        }
    }

    private fun resumeAfterRecognition() {
        if (recognizing) {
            recognizing = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy(); speechRecognizer = null
        }
        restoreAudioAfterRecognition()
        resumeAudioMonitor()
        setOrbState(OrbState.LISTENING)
    }

    private fun resumeAudioMonitor() {
        scope.launch {
            stopAudioMonitorInternal()
            delay(120)
            if (isRunning && !recognizing) startAudioMonitor()
        }
    }

    private fun pauseAudioMonitor() {
        try { audioRecord?.stop() } catch (e: Exception) {}
    }

    private fun startAudioMonitor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) return
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
        } catch (e: Exception) { null } ?: return
        try { audioRecord?.startRecording() } catch (e: Exception) { return }
        segmentActive = false
        audioThread = Thread {
            val buf = ShortArray(bufferSize / 2)
            while (Thread.currentThread().isAlive && isRunning && !recognizing) {
                val audio = audioRecord ?: break
                if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) break
                val read = try { audio.read(buf, 0, buf.size) } catch (e: Exception) { break }
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) sum += (buf[i].toDouble() * buf[i].toDouble())
                val rms = (sum / read).let { Math.sqrt(it) }
                val normAmp = (rms / 32768.0).toFloat()
                val liveAmp = (normAmp * 3.0f).coerceIn(0f, 1f).let { it * it }
                if (isRunning && orbState == OrbState.LISTENING) orbAmplitude = liveAmp

                if (normAmp > SILENCE_THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (!segmentActive) {
                        segmentActive = true
                        segmentStartedAt = now
                        speechSamples.clear()
                        segmentPeakAmp = 0f
                    }
                    if (normAmp > segmentPeakAmp) segmentPeakAmp = normAmp
                    lastSpeechAt = now
                    speechSamples.addAll(buf.take(read))
                    // Barge-in only on a genuinely loud, sustained utterance so the
                    // assistant won't interrupt its own TTS reply.
                    if (orbState == OrbState.SPEAKING &&
                        segmentPeakAmp > 0.22f &&
                        now - segmentStartedAt > MIN_BARGE_MS
                    ) {
                        scope.launch { bargeIn() }
                    }
                } else {
                    if (segmentActive) {
                        val now = System.currentTimeMillis()
                        if (now - lastSpeechAt > HOLD_MS) {
                            val duration = now - segmentStartedAt
                            val clip = speechSamples.toList()
                            segmentActive = false
                            // Only hand over to recognition when the assistant is NOT
                            // speaking, to avoid the assistant answering its own voice.
                            if (duration >= MIN_SPEECH_MS && clip.isNotEmpty() &&
                                orbState != OrbState.SPEAKING
                            ) {
                                scope.launch { onVoiceSegment(clip, duration) }
                                break
                            }
                        }
                    }
                }
            }
        }
        audioThread?.isDaemon = true
        audioThread?.start()
    }

    private fun stopAudioMonitorInternal() {
        segmentActive = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        audioThread = null
    }

    private fun stopListening() {
        segmentActive = false
        recognizing = false
        stopAudioMonitorInternal()
        uiHandler.removeCallbacksAndMessages(null)
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setOrbState(OrbState.IDLE)
            return
        }
        refreshWakeLock()
        setOrbState(OrbState.LISTENING)
        startAudioMonitor()
    }

    // ============ RecognitionListener (class-level, not used; listener is modelListener) ============
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onRmsChanged(rmsdB: Float) { if (isRunning && orbState == OrbState.LISTENING) orbAmplitude = ((rmsdB + 3f) / 6f).coerceIn(0f, 1f) }
    override fun onBeginningOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onResults(results: Bundle?) {}
    override fun onError(error: Int) {}

    // =========================================================
    //  HANDLE RECOGNIZED TEXT (always-on, hands-free)
    // =========================================================
    @Volatile private var conversationActive = true
    @Volatile private var waitingReply = false

    private fun handleRecognizedText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) { setOrbState(OrbState.LISTENING); return }

        // Always-on conversation mode: no wake word required after start.
        // The assistant stays active and processes whatever the user says.
        processCommand(text)
    }

    private fun processCommand(text: String) {
        if (text.isBlank() || waitingReply) { setOrbState(OrbState.LISTENING); return }
        waitingReply = true

        MishServiceBridge.onUserSpeech?.invoke(text)
        onUserSpeech?.invoke(text)

        if (EmergencyHelper.isEmergencyTrigger(text)) {
            val profile = prefsForOrb.loadProfile()
            EmergencyHelper.sendEmergencyAlert(this, profile)
            EmergencyHelper.openCall(this, profile.emergencyNumber)
            setOrbState(OrbState.SPEAKING)
            speak("Emergency alert bhej diya hai, saath mein call ho rahi hai.")
            waitingReply = false
            conversationActive = false
            setOrbState(OrbState.LISTENING)
            return
        }

        setOrbState(OrbState.THINKING)
        backend.ask(text, orbAmplitude, object : MishBackend.CallbackResult {
            override fun onSuccess(reply: MishBackend.MishReply) {
                waitingReply = false
                MishServiceBridge.onMishReply?.invoke(reply.text, reply.mood)
                onMishReply?.invoke(reply.text, reply.mood)
                // Speak the reply with the mood-adjusted rate/pitch
                setOrbState(OrbState.SPEAKING)
                speakWithMood(reply.text, reply.mood)
            }
            override fun onError(error: String) {
                waitingReply = false
                setOrbState(OrbState.SPEAKING)
                speak("Kuch ghalti hui, dobara kaho.")
            }
        })
    }

    // Auto-return to listening once reply finished (see TTS listener)
    private fun speak(text: String) {
        ttsEngine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "mish_reply")
    }
    private fun speakWithMood(text: String, mood: Mood) {
        val bundle = Bundle().apply {
            putFloat("rate", mood.speed)
            putFloat("pitch", mood.pitch)
        }
        ttsEngine?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, bundle, "mish_reply")
    }

    // Barge-in: user interrupted while assistant was speaking
    private fun bargeIn() {
        // Mute TTS immediately
        ttsEngine?.stop()
        setOrbState(OrbState.LISTENING)
        // The in-flight recognition will handle the new text.
        conversationActive = true
        // If a reply was waiting, reset it so new speech is processed.
        waitingReply = false
    }

    // =========================================================
    //  ORB — draggable, resizable, state-reactive, transparent
    // =========================================================
    private var overlayAdded = false
    private var rootView: View? = null
    private var coreView: View? = null
    private var haloView: View? = null
    private var rippleView: View? = null
    private var wm: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    private fun showOrb() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (!Settings.canDrawOverlays(this)) return
        if (overlayAdded) return
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val li = LayoutInflater.from(this)
            val view = li.inflate(R.layout.overlay_mish, null)
            rootView = view
            coreView = view.findViewById(R.id.core_view)
            haloView = view.findViewById(R.id.halo_view)
            rippleView = view.findViewById(R.id.ripple_view)

            val display = wm?.defaultDisplay
            val dm = android.graphics.Point().also { display?.getRealSize(it) }

            // Restore saved position (if any), else default top-center-ish
            val saved = loadOrbPrefs()
            orbSizePx = saved.size
            if (saved.x > 0) {
                orbX = saved.x
                orbY = saved.y
            } else {
                orbX = (dm.x / 2) - (orbSizePx / 2).toInt()
                orbY = (dm.y / 3)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = orbX
                y = orbY
            }
            overlayParams = params
            applyOrbSize()
            wm?.addView(view, params)
            overlayAdded = true

            // Touch handling: drag to move; long-press + drag to resize
            val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    orbSizePx = (orbSizePx * detector.scaleFactor).coerceIn(48f, 160f)
                    applyOrbSize()
                    return true
                }
            })
            var startX = 0; var startY = 0
            var startLx = orbX; var startLy = orbY
            var actionByPinch = false
            var downTime = 0L
            var moved = false

            view.setOnTouchListener { _, ev ->
                scaleDetector.onTouchEvent(ev)
                val scaleInProgress = scaleDetector.isInProgress
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = ev.rawX.toInt()
                        startY = ev.rawY.toInt()
                        startLx = orbX
                        startLy = orbY
                        downTime = System.currentTimeMillis()
                        moved = false
                        actionByPinch = scaleInProgress
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!scaleInProgress) {
                            val dx = ev.rawX.toInt() - startX
                            val dy = ev.rawY.toInt() - startY
                            if (abs(dx) > 4 || abs(dy) > 4) moved = true
                            if (moved && !actionByPinch) {
                                // Drag the orb
                                orbX = startLx + dx
                                orbY = startLy + dy
                                updateOrbPosition()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && System.currentTimeMillis() - downTime < 450) {
                            // Tap = restart listening / wake
                            onOrbTap()
                        }
                        saveOrbPrefs()
                    }
                }
                true
            }
            startOrbAnimation()
        } catch (e: Exception) {
            overlayAdded = false
        }
    }

    private fun applyOrbSize() {
        val px = orbSizePx
        coreView?.layoutParams = coreView?.layoutParams?.apply { width = px.toInt(); height = px.toInt() }
        haloView?.layoutParams = haloView?.layoutParams?.apply { width = (px * 1.3f).toInt(); height = (px * 1.3f).toInt() }
        rippleView?.layoutParams = rippleView?.layoutParams?.apply { width = (px * 1.1f).toInt(); height = (px * 1.1f).toInt() }
    }

    private fun updateOrbPosition() {
        overlayParams?.x = orbX
        overlayParams?.y = orbY
        try { wm?.updateViewLayout(rootView, overlayParams) } catch (e: Exception) {}
    }

    private fun onOrbTap() {
        if (!isRunning) return
        setOrbState(OrbState.LISTENING)
        // Optionally nudge recognition
    }

    private fun removeOrb() {
        if (!overlayAdded) return
        try { rootView?.let { wm?.removeView(it) } } catch (e: Exception) { e.printStackTrace() }
        overlayAdded = false
        rootView = null; coreView = null; haloView = null; rippleView = null
        overlayParams = null
    }

    // ----- Orb state animation -----
    private var pulsePhase = 0f
    private var currentAmplitude = 0f
    private var targetState = OrbState.IDLE
    private var stateChangedAt = 0L

    private fun setOrbState(state: OrbState) {
        targetState = state
        stateChangedAt = System.currentTimeMillis()
        orbState = state
        if (state == OrbState.LISTENING) orbAmplitude = 0f

        // Animate core background reflecting the state
        val res = when (state) {
            OrbState.IDLE -> R.drawable.bg_orb_idle
            OrbState.LISTENING -> R.drawable.bg_orb_idle
            OrbState.THINKING -> R.drawable.bg_orb_thinking
            OrbState.SPEAKING -> R.drawable.bg_orb_speaking
        }
        runOnMain { coreView?.setBackgroundResource(res) }

        // Ripple/glow react strongly for speaking & listening
        coreView?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(250)?.start()
    }

    private val orbRunnable = object : Runnable {
        override fun run() {
            if (!overlayAdded) return
            pulsePhase += 0.10f

            // Smooth the amplitude towards its live target
            val target = when (targetState) {
                OrbState.LISTENING -> orbAmplitude
                OrbState.SPEAKING -> 0.55f + 0.35f * sin(pulsePhase * 2.2f)
                OrbState.THINKING -> 0.3f + 0.25f * abs(sin(pulsePhase * 1.1f))
                OrbState.IDLE -> 0.12f + 0.08f * sin(pulsePhase)
            }
            currentAmplitude += (target - currentAmplitude) * 0.25f
            val a = currentAmplitude.coerceIn(0f, 1f)

            val core = coreView
            val halo = haloView
            val ripple = rippleView
            if (core != null) {
                when (targetState) {
                    OrbState.IDLE -> core.scaleX = 0.96f + 0.04f * sin(pulsePhase)
                    OrbState.LISTENING -> core.scaleX = 1f + 0.10f * a * Math.abs(sin(pulsePhase * 1.8f)).toFloat()
                    OrbState.THINKING -> core.scaleX = 0.94f + 0.06f * Math.abs(sin(pulsePhase * 0.9f)).toFloat()
                    OrbState.SPEAKING -> core.scaleX = (1f + 0.22f * Math.abs(sin(pulsePhase * 3.4f)).toFloat())
                }
                core.scaleY = core.scaleX
            }
            if (halo != null) {
                val haloScale = 1.05f + 0.35f * a
                halo.scaleX = haloScale
                halo.scaleY = haloScale
                halo.alpha = (0.25f + 0.5f * a).coerceIn(0f, 0.85f)
            }
            if (ripple != null) {
                val rScale = 1.0f + 0.5f * a
                ripple.scaleX = rScale
                ripple.scaleY = rScale
                ripple.alpha = (0.15f + 0.4f * a).coerceIn(0f, 0.6f)
            }

            core?.postDelayed(this, 33) // ~30 FPS
        }
    }

    private fun startOrbAnimation() {
        uiHandler.removeCallbacks(orbRunnable)
        pulsePhase = 0f
        currentAmplitude = 0f
        setOrbState(OrbState.LISTENING)
        rootView?.post(orbRunnable)
    }
    private fun stopOrbAnimation() {
        uiHandler.removeCallbacks(orbRunnable)
    }

    // ----- Orb prefs persistence -----
    private data class OrbPrefs(val x: Int, val y: Int, val size: Float)
    private fun loadOrbPrefs(): OrbPrefs {
        val sp = getSharedPreferences("orb_prefs", Context.MODE_PRIVATE)
        return OrbPrefs(
            sp.getInt("x", 0),
            sp.getInt("y", 0),
            sp.getFloat("size", 90f)
        )
    }
    private fun saveOrbPrefs() {
        getSharedPreferences("orb_prefs", Context.MODE_PRIVATE).edit()
            .putInt("x", orbX)
            .putInt("y", orbY)
            .putFloat("size", orbSizePx)
            .apply()
    }

    private fun runOnMain(block: () -> Unit) {
        scope.launch { block() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
