package com.cybertech.mishai.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.location.LocationManager
import com.cybertech.mishai.UserProfile
import java.util.Date

/**
 * Emergency helper — triggered on "help"/"bachao".
 * Sends SMS with Google Maps live-location link to the emergency contact,
 * then (if permitted) opens the dialer to call the number.
 */
object EmergencyHelper {

    const val TRIGGER_HELP = "help"
    const val TRIGGER_BACHAO = "bachao"

    fun isEmergencyTrigger(text: String): Boolean {
        val t = text.lowercase()
        return t.contains(TRIGGER_HELP) || t.contains(TRIGGER_BACHAO)
    }

    fun sendEmergencyAlert(context: Context, profile: UserProfile) {
        val number = profile.emergencyNumber.trim()
        if (number.isEmpty()) return

        val loc = getCurrentLocation(context)
        val mapsLink = if (loc != null) {
            "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
        } else {
            "Location unavailable at request time"
        }

        val time = java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.getDefault())
            .format(Date())

        val message = "\u26A0 MISH EMERGENCY ALERT \u26A0\n" +
            "${profile.displayName()} (${profile.roleWord()}) ne help maangi.\n" +
            "Time: $time\n" +
            "Live Location: $mapsLink"

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                SmsManager.getDefault().sendTextMessage(number, null, message, null, null)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun openCall(context: Context, number: String) {
        val i = android.content.Intent(android.content.Intent.ACTION_DIAL)
        i.data = android.net.Uri.parse("tel:$number")
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }

    private fun getCurrentLocation(context: Context): Location? {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            for (p in providers) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) return null
                val loc = lm.getLastKnownLocation(p)
                if (loc != null) return loc
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}