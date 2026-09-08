package com.cybertech.mishai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
    val currentContext = androidx.compose.ui.platform.LocalContext.current

    var step by remember { mutableStateOf(1) }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("jani") }
    var gender by remember { mutableStateOf("male") }
    var emergency by remember { mutableStateOf("") }

    val nameValid = name.trim().isNotEmpty()
    val emergencyValid = emergency.trim().length >= 7

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MishColors.Background,
                        MishColors.BackgroundSoft,
                        MishColors.BackgroundDeep
                    )
                )
            )
    ) {
        // Ambient glow
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(220.dp)
                .offset(x = (-80).dp, y = (-80).dp)
                .blur(50.dp)
                .background(MishColors.Primary.copy(alpha = 0.2f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(280.dp)
                .offset(x = 100.dp, y = 100.dp)
                .blur(60.dp)
                .background(MishColors.Accent.copy(alpha = 0.15f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Progress indicator with glass bar
            LinearProgressIndicator(
                progress = { step / 5f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50.dp)),
                color = MishColors.Accent,
                trackColor = MishColors.GlassSurface,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Spacer(Modifier.height(28.dp))

            // Logo
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(12.dp, CircleShape)
                    .background(
                        Brush.linearGradient(listOf(MishColors.Primary, MishColors.Accent)),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text("M", fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Color.White)
            }
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Mish AI",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MishColors.TextPrimary
            )
            Text(
                text = "Cyber Tech Agency",
                fontSize = 12.sp,
                color = MishColors.Muted
            )
            Spacer(Modifier.height(28.dp))

            when (step) {
                1 -> {
                    AnimatedVisibility(step == 1) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Tumhara naam kya hai?",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MishColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Main aapko naam se jaanna chahti hoon",
                                fontSize = 13.sp,
                                color = MishColors.Muted
                            )
                            Spacer(Modifier.height(20.dp))
                            ModernTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = "Apna naam likho..."
                            )
                            Spacer(Modifier.height(28.dp))
                            ModernButton(
                                text = "Aage",
                                enabled = nameValid,
                                onClick = { step = 2 }
                            )
                        }
                    }
                }
                2 -> {
                    AnimatedVisibility(step == 2) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Apna role select karo",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MishColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Main aapko isi se address karoongi",
                                fontSize = 13.sp,
                                color = MishColors.Muted
                            )
                            Spacer(Modifier.height(20.dp))
                            FlowRowRoles(roles, role) { role = it }
                            Spacer(Modifier.height(28.dp))
                            ModernButton(text = "Aage", onClick = { step = 3 })
                        }
                    }
                }
                3 -> {
                    AnimatedVisibility(step == 3) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Gender select karo",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MishColors.TextPrimary
                            )
                            Spacer(Modifier.height(20.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                GenderChip("male", gender) { gender = it }
                                GenderChip("female", gender) { gender = it }
                                GenderChip("other", gender) { gender = it }
                            }
                            Spacer(Modifier.height(28.dp))
                            ModernButton(text = "Aage", onClick = { step = 4 })
                        }
                    }
                }
                4 -> {
                    AnimatedVisibility(step == 4) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Emergency number do",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MishColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "\"help\" ya \"bachao\" kabho to SMS + live location jayegi",
                                fontSize = 13.sp,
                                color = MishColors.Muted,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))
                            ModernTextField(
                                value = emergency,
                                onValueChange = { emergency = it },
                                placeholder = "+92 322 1234567",
                                keyboardType = KeyboardType.Phone
                            )
                            Spacer(Modifier.height(28.dp))
                            ModernButton(
                                text = "Aage",
                                enabled = emergencyValid,
                                onClick = { step = 5 }
                            )
                        }
                    }
                }
                5 -> {
                    AnimatedVisibility(step == 5) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Permissions aur overlay",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MishColors.TextPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Mic + Camera + Floating orb permission dein,\ntaake Mish kisi bhi app par kaam kare",
                                fontSize = 13.sp,
                                color = MishColors.Muted,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(20.dp))

                            Surface(
                                onClick = onOverlayRequest,
                                shape = RoundedCornerShape(16.dp),
                                color = MishColors.GlassSurface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.Primary.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "Floating orb permission on karo",
                                        color = MishColors.PrimaryLight,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Spacer(Modifier.height(28.dp))
                            ModernButton(
                                text = "Setup Complete — Start",
                                onClick = {
                                    val prefs = PreferencesManager(currentContext)
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
                                }
                            )
                        }
                    }
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
}

@Composable
private fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MishColors.InputBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.InputBorder),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(4.dp, RoundedCornerShape(16.dp))
    ) {
        Box(modifier = Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = MishColors.TextPrimary,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MishColors.Cursor),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                color = MishColors.TextHint,
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
    }
}

@Composable
private fun ModernButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = if (enabled) MishColors.Accent else MishColors.Accent.copy(alpha = 0.3f),
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(8.dp, RoundedCornerShape(18.dp))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
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
                        label = { Text(r, color = if (isSelected) Color.White else MishColors.TextSecondary) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MishColors.GlassSurface,
                            selectedContainerColor = MishColors.Accent,
                            selectedLabelColor = Color.White
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) MishColors.Accent
                            else MishColors.GlassBorder,
                            selectedBorderColor = MishColors.Accent,
                            disabledBorderColor = MishColors.GlassBorder,
                            disabledSelectedBorderColor = MishColors.GlassBorder
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
        label = { Text(value.replaceFirstChar { it.uppercase() }, color = if (isSelected) Color.White else MishColors.TextSecondary) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MishColors.GlassSurface,
            selectedContainerColor = MishColors.Primary
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = isSelected,
            borderColor = if (isSelected) MishColors.Primary
            else MishColors.GlassBorder,
            selectedBorderColor = MishColors.Primary,
            disabledBorderColor = MishColors.GlassBorder,
            disabledSelectedBorderColor = MishColors.GlassBorder
        )
    )
}

@Composable
private fun MishTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            background = MishColors.Background,
            surface = MishColors.BackgroundSoft,
            primary = MishColors.Accent,
            onSurface = MishColors.TextPrimary
        ),
        content = content
    )
}
