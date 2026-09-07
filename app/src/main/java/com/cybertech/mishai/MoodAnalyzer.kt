package com.cybertech.mishai

import java.util.Locale

/**
 * Client-side mood analyzer (fallback / fast path).
 *
 * The production backend (Hugging Face speech-to-speech) does the real
 * emotion analysis on full voice + text. This lightweight analyzer gives an
 * immediate heuristic answer using Roman Urdu / Urdu keyword cues so the app
 * can still adjust tone even when the AI is warming up.
 */
object MoodAnalyzer {

    private val tiredWords = listOf(
        "thak", "neend", "tired", "arami", "so ja", "sleepy", "exhausted", "थक", "تھک",
        "dimag kharab", "bohot zyada kaam", "thak gaya", "thak gayi"
    )
    private val angryWords = listOf(
        "gussa", "naraz", "angry", "pagl", "band kar", "shut up", "chup", "غصہ",
        "sali", "bhad", "madarchod", "behenchod", "chal be", "kutte", "bakwas"
    )
    private val sadWords = listOf(
        "udaas", "dil toota", "sad", "rona", "ro raha", "dukhi", "دیس", "tension",
        "kabhi nahi", "aajkal", "akela", "alone", "uda", "دل", "afsos"
    )
    private val stressedWords = listOf(
        "stress", "pressure", "pareshan", "tension", "pata nahi kya karun",
        "bojh", "double", "kam zyada", "سٹریس", "دباؤ"
    )
    private val confusedWords = listOf(
        "samajh nahi", "pata nahi", "confuse", "kaise", "kyun", "kya hua",
        "هللا", "zra bata", "question", "سوال", "kya karun", "samjha nahi"
    )
    private val excitedWords = listOf(
        "wow", "yay", "awesome", "great", "zabardast", "kia baat", "excited",
        "chalo karein", "lets go", "maza aaya", "best", "site"
    )
    private val happyWords = listOf(
        "hahaha", "haha", "khus", "khush", "happy", "maza", "aacha", "acha",
        "پسند", "best", "love it", "fun", "site", "وہ", "masti", "khushi"
    )

    fun analyze(text: String, levelMeter: Float = 0f): Mood {
        val t = text.lowercase(Locale.getDefault())

        // Anger keywords dominate — always prioritize avoiding misses
        if (containsAny(t, angryWords)) return Mood.ANGRY
        if (containsAny(t, tiredWords)) return Mood.TIRED
        if (containsAny(t, stressedWords)) return Mood.SPRESSED
        if (containsAny(t, confusedWords)) return Mood.CONFUSED
        if (containsAny(t, sadWords)) return Mood.SAD
        if (containsAny(t, excitedWords)) return Mood.EXCITED
        if (containsAny(t, happyWords)) return Mood.HAPPY

        // Voice energy fallback (0..1 from amplitude)
        return when {
            levelMeter > 0.85f -> Mood.EXCITED
            levelMeter > 0.7f -> Mood.HAPPY
            levelMeter < 0.15f -> Mood.TIRED
            else -> Mood.NORMAL
        }
    }

    private fun containsAny(text: String, words: List<String>): Boolean {
        for (w in words) {
            if (text.contains(w)) return true
        }
        return false
    }
}
