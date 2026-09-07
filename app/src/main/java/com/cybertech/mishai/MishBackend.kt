package com.cybertech.mishai

import android.content.Context
import com.cybertech.mishai.ai.PreferencesManager
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Mish AI backend.
 *
 * Connects to a Hugging Face speech-to-speech style endpoint (or any
 * OpenAI-compatible chat endpoint) that performs:
 *   noise reduction -> STT -> mood analysis -> LLM -> (TTS returned as rate)
 *
 * The backend returns:
 *   { "response": "...", "mood": "HAPPY", "tts_rate": 1.0 }
 */
class MishBackend(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class MishReply(
        val text: String,
        val mood: Mood,
        val ttsRate: Float,
        val ttsPitch: Float
    )

    interface CallbackResult {
        fun onSuccess(reply: MishReply)
        fun onError(error: String)
    }

    fun ask(userInput: String, levelMeter: Float, cb: CallbackResult) {
        val prefs = PreferencesManager(context)
        val profile = prefs.loadProfile()
        val moodGuess = MoodAnalyzer.analyze(userInput, levelMeter)

        val endpoint = prefs.getEndpoint()

        if (endpoint.isNullOrBlank()) {
            // No backend configured -> local fallback reply so app always works
            cb.onSuccess(buildLocalReply(userInput, profile, moodGuess))
            return
        }

        val body = JSONObject().apply {
            put("message", userInput)
            put("user_name", profile.displayName())
            put("role", profile.roleWord())
            put("gender", profile.gender)
            put("detected_mood", moodGuess.name)
            put("energy", levelMeter)
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cb.onError(e.message ?: "Network error")
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val raw = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        cb.onError("Server error ${response.code}")
                        return
                    }
                    val json = JSONObject(raw)
                    val text = json.optString("response", "Kuch samajh nahi aaya, dobara batao.")
                    val mood = Mood.fromString(json.optString("mood"))
                    val rate = json.optDouble("tts_rate", mood.speed.toDouble()).toFloat()
                    val pitch = json.optDouble("tts_pitch", mood.pitch.toDouble()).toFloat()
                    cb.onSuccess(MishReply(text, mood, rate, pitch))
                } catch (e: Exception) {
                    cb.onError(e.message ?: "Parse error")
                }
            }
        })
    }

    /** On-device fallback when no backend is configured. */
    private fun buildLocalReply(input: String, profile: UserProfile, mood: Mood): MishReply {
        val name = profile.displayName()
        val role = profile.roleWord()

        val prefix = when (mood) {
            Mood.SAD -> "Arre $role $name, dil chhota mat karo 😔. "
            Mood.ANGRY -> "$name, chain se bhai, gussa na karo 😌. "
            Mood.TIRED -> "Hmm… lag raha hai aaj ka din kaafi heavy tha $role 😴. "
            Mood.STRESSED -> "$role $name, tension mat lo, sab theek ho jayega 🙏. "
            Mood.CONFUSED -> "Chalo $name, samjha deta hoon, kya baat hai 🤔? "
            Mood.EXCITED -> "Wah $role $name! Maza aaya jo suna 🤩. "
            Mood.HAPPY -> "Bohot acha, $role $name! Masti shuru karein 😄. "
            else -> "Haan $role $name, batao kya karna hai 🙂. "
        }

        val suffix = "Main hoon na Mish, sath hoon tumhare! Batao, aur kya? "
        return MishReply("$prefix $suffix", mood, mood.speed, mood.pitch)
    }
}
