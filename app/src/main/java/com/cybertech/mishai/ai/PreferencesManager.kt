package com.cybertech.mishai.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cybertech.mishai.UserProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "mish_prefs")

/**
 * Stores/loads user setup in DataStore.
 * Used during onboarding and by services.
 */
class PreferencesManager(private val context: Context) {

    private object Keys {
        val NAME = stringPreferencesKey("user_name")
        val ROLE = stringPreferencesKey("user_role")
        val GENDER = stringPreferencesKey("user_gender")
        val EMERGENCY = stringPreferencesKey("emergency_number")
        val SETUP_DONE = booleanPreferencesKey("setup_complete")
        val ENDPOINT = stringPreferencesKey("backend_endpoint")
        val VOICE = stringPreferencesKey("voice_setting")
        val ENERGY_SAVER = booleanPreferencesKey("energy_saver")
    }

    fun loadProfile(): UserProfile = runBlocking {
        val p = context.dataStore.data.first()
        UserProfile(
            name = p[Keys.NAME] ?: "",
            role = p[Keys.ROLE] ?: "jani",
            gender = p[Keys.GENDER] ?: "male",
            emergencyNumber = p[Keys.EMERGENCY] ?: "",
            setupComplete = p[Keys.SETUP_DONE] ?: false
        )
    }

    fun saveProfile(profile: UserProfile) {
        runBlocking {
            context.dataStore.edit { p ->
                p[Keys.NAME] = profile.name
                p[Keys.ROLE] = profile.role
                p[Keys.GENDER] = profile.gender
                p[Keys.EMERGENCY] = profile.emergencyNumber
                p[Keys.SETUP_DONE] = profile.setupComplete
            }
        }
    }

    fun getEndpoint(): String? = runBlocking {
        context.dataStore.data.first()[Keys.ENDPOINT]
    }

    fun setEndpoint(url: String) {
        runBlocking {
            context.dataStore.edit { it[Keys.ENDPOINT] = url }
        }
    }

    fun getVoice(): String = runBlocking {
        context.dataStore.data.first()[Keys.VOICE] ?: "aegis_female_urdu"
    }

    fun setVoice(v: String) {
        runBlocking {
            context.dataStore.edit { it[Keys.VOICE] = v }
        }
    }
}