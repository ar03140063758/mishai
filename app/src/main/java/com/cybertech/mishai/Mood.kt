package com.cybertech.mishai

/**
 * Mood categories detected from the user's voice + text.
 */
enum class Mood(
    val emoji: String,
    val label: String,
    val speed: Float,      // TTS speech rate multiplier
    val pitch: Float       // TTS pitch multiplier
) {
    HAPPY("😄", "Happy", 1.08f, 1.05f),
    SAD("😔", "Sad", 0.9f, 0.92f),
    ANGRY("😠", "Angry", 1.05f, 0.95f),
    STRESSED("😣", "Stressed", 1.02f, 0.98f),
    EXCITED("🤩", "Excited", 1.15f, 1.1f),
    NORMAL("🙂", "Normal", 1.0f, 1.0f),
    CONFUSED("🤔", "Confused", 0.95f, 1.0f),
    TIRED("😴", "Tired", 0.88f, 0.9f);

    companion object {
        fun fromString(value: String?): Mood {
            return entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: NORMAL
        }
    }
}
