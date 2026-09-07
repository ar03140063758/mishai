package com.cybertech.mishai

/**
 * User profile data collected during onboarding.
 * Stored via DataStore preferences.
 */
data class UserProfile(
    val name: String = "",
    val role: String = "jani",
    val gender: String = "male",
    val emergencyNumber: String = "",
    val setupComplete: Boolean = false
) {
    fun displayName(): String {
        val r = role.trim().ifEmpty { "jani" }
        val n = name.trim().ifEmpty { "dost" }
        // e.g. "Jani", "Boss", "bhai" — keep as provided
        return n
    }

    fun roleWord(): String = role.trim().ifEmpty { "jani" }
}
