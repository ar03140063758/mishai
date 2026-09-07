package com.cybertech.mishai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cybertech.mishai.ai.PreferencesManager
import com.cybertech.mishai.ui.theme.MishColors

/**
 * First-time onboarding: asks name, role, gender, emergency number,
 * then requests mic + camera permissions + overlay permission.
 */
class OnboardingActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        val requestOverlay = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { }

        setContent {
            MishTheme {
                OnboardingScreen(
                    onFinish = {
                        requestPermissions()
                        startActivity(Intent(this@OnboardingActivity, MainActivity::class.java))
                        finish()
                    },
                    onOverlayRequest = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val i = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:$packageName")
                            )
                            requestOverlay.launch(i)
                        }
                    }
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

@Composable
private fun OnboardingScreen(
    onFinish: () -> Unit,
    onOverlayRequest: () -> Unit
) {
    val roles = listOf("boss", "sir", "madam", "maam", "bhai", "jani", "jan", "yar")

    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("jani") }
    var gender by remember { mutableStateOf("male") }
    var emergency by remember { mutableStateOf("") }

    val nameValid = name.trim().isNotEmpty()
    val emergencyValid = emergency.trim().length >= 7

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MishColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))

        // Progress indicator
        LinearProgressIndicator(
            progress = { step / 5f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = MishColors.Accent,
            trackColor = MishColors.Surface
        )
        Spacer(Modifier.height(32.dp))

        Text(
            text = "Mish AI setup",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Developed by Cyber Tech Agency",
            fontSize = 13.sp,
            color = MishColors.Muted
        )
        Spacer(Modifier.height(32.dp))

        when (step) {
            1 -> {
                Text("Tumhara naam kya hai?", fontSize = 18.sp, color = Color.White)
                Text("User ka naam janshen aur anticipations karein 😊", fontSize = 12.sp, color = MishColors.Muted)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Naam") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MishColors.Accent,
                        cursorColor = MishColors.Accent,
                        focusedLabelColor = MishColors.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { step = 2 },
                    enabled = nameValid,
                    colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent)
                ) { Text("Aage") }
            }
            2 -> {
                Text("Apna role select karo", fontSize = 18.sp, color = Color.White)
                Text("Mehrbani se apna role chunein", fontSize = 12.sp, color = MishColors.Muted)
                Spacer(Modifier.height(16.dp))
                FlowRowRoles(roles, role) { role = it }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { step = 3 },
                    colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent)
                ) { Text("Aage") }
            }
            3 -> {
                Text("Gender select karo", fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GenderChip("male", gender) { gender = it }
                    GenderChip("female", gender) { gender = it }
                    GenderChip("other", gender) { gender = it }
                }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { step = 4 },
                    colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent)
                ) { Text("Aage") }
            }
            4 -> {
                Text("Emergency number do", fontSize = 18.sp, color = Color.White)
                Text(
                    "Jab tum 'help' ya 'bachao' kahoge, is number par SMS + live location jayegi",
                    fontSize = 13.sp,
                    color = MishColors.Muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = emergency,
                    onValueChange = { emergency = it },
                    label = { Text("Emergency Number (+923221234567)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MishColors.Accent,
                        cursorColor = MishColors.Accent,
                        focusedLabelColor = MishColors.Accent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = { step = 5 },
                    enabled = emergencyValid,
                    colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent)
                ) { Text("Aage") }
            }
            5 -> {
                Text("Permissions aur overlay", fontSize = 18.sp, color = Color.White)
                Text(
                    "Mic + Camera permission dena hoga, aur overlay permission se Mish kisi bhi app par floating dikhegi.",
                    fontSize = 13.sp,
                    color = MishColors.Muted,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = onOverlayRequest,
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Overlay Permission On Karo") }
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        val prefs = PreferencesManager(
                            androidx.compose.ui.platform.LocalContext.current
                        )
                        val profile = prefs.loadProfile()
                        prefs.saveProfile(
                            profile.copy(
                                name = name.trim(),
                                role = role,
                                gender = gender,
                                emergencyNumber = emergency.trim(),
                                setupComplete = true
                            )
                        )
                        onFinish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Setup Complete — Mish Start Karo") }
            }
        }

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Mish AI • Cyber Tech Agency",
            fontSize = 12.sp,
            color = MishColors.Muted
        )
    }
}

@Composable
private fun FlowRowRoles(roles: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        roles.chunked(2).forEach { rowRoles ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowRoles.forEach { r ->
                    val isSelected = r == selected
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(r) },
                        label = { Text(r) },
                        colors = FilterChipDefaults.filterChipColors(
                            labelColor = if (isSelected) Color.White else MishColors.Muted,
                            selectedContainerColor = MishColors.Accent
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun GenderChip(value: String, selected: String, onSelect: (String) -> Unit) {
    val isSelected = value == selected
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(value) },
        label = { Text(value.replaceFirstChar { it.uppercase() }) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = if (isSelected) Color.White else MishColors.Muted,
            selectedContainerColor = MishColors.Accent
        )
    )
}

@Composable
private fun MishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = MishColors.Background,
            surface = MishColors.Surface,
            primary = MishColors.Accent
        ),
        content = content
    )
}