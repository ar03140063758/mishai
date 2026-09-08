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
        val n = name.trim().ifEmpty { "dost" }
        return n
    }

    fun roleWord(): String = role.trim().ifEmpty { "jani" }
}
