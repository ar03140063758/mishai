package com.cybertech.mishai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybertech.mishai.ai.MishService
import com.cybertech.mishai.ai.PreferencesManager
import com.cybertech.mishai.ui.theme.MishColors

class MainActivity : ComponentActivity() {

    private lateinit var prefs: PreferencesManager

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PreferencesManager(this)

        setContent {
            MainScreen(
                onStartMish = { startMishService() },
                onOpenCamera = {
                    startActivity(Intent(this@MainActivity, CameraActivity::class.java))
                },
                onRequestOverlay = { requestOverlay() }
            )
        }
    }

    private fun startMishService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlay()
        }
        val intent = Intent(this, MishService::class.java).setAction(MishService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(i)
        }
    }
}

@Composable
private fun MainScreen(
    onStartMish: () -> Unit,
    onOpenCamera: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.let {
        PreferencesManager(it)
    }
    var profile by remember { mutableStateOf(prefs.loadProfile()) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val openLink = { url: String ->
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    // Listen to background service events
    DisposableEffect(Unit) {
        MishServiceBridge.onUserSpeech = { text ->
            messages = messages + ChatMessage(text, isUser = true)
        }
        MishServiceBridge.onMishReply = { text, mood ->
            messages = messages + ChatMessage(mood.emoji + " " + text, isUser = false)
        }
        onDispose {
            MishServiceBridge.onUserSpeech = null
            MishServiceBridge.onMishReply = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MishColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Branding header
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Mish AI",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MishColors.White
                )
                Text(
                    text = "Developed by Cyber Tech Agency",
                    fontSize = 13.sp,
                    color = MishColors.Muted
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SocialChip("Website") { openLink("https://www.cybertechagency.com/mish") }
                    SocialChip("Facebook") { openLink("https://facebook.com/cybertechagencyy") }
                    SocialChip("YouTube") { openLink("https://youtube.com/@cybertechagency") }
                    SocialChip("WhatsApp") { openLink("https://wa.me/923224278925") }
                }
                Text(
                    text = "CEO & Developer: Mehar Ahmad Raza • +92 322 4278925",
                    fontSize = 11.sp,
                    color = MishColors.Muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "Salam ${profile.displayName()} (${profile.roleWord()}) 👋",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MishColors.White
            )
            Spacer(Modifier.height(4.dp))
            if (MishService.isRunning) {
                Text(
                    text = "● Mish active — kisi bhi app par 'Mish' bolo",
                    fontSize = 13.sp,
                    color = MishColors.Accent
                )
            } else {
                Text(
                    text = "Start Mish karo, phir background mein 'Mish' bolo",
                    fontSize = 13.sp,
                    color = MishColors.Muted
                )
            }

            Spacer(Modifier.height(16.dp))

            // Start Mish button
            Button(
                onClick = onStartMish,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MishColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.GraphicEq, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (MishService.isRunning) "Mish Running…" else "Start Mish", fontSize = 18.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Camera button
            OutlinedButton(
                onClick = onOpenCamera,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MishColors.Accent),
                border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Camera — Mish dekhegi aur batayegi", fontSize = 16.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Overlay permission quick toggle
            TextButton(onClick = {
                onRequestOverlay()
            }) {
                Text("Overlay permission (floating Mish)")
            }

            Spacer(Modifier.height(16.dp))

            // Conversation transcript
            if (messages.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { m ->
                        ChatBubble(m)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Mish se baat shuru karo…\n\"Mish kaho aur bolna shuru karo\"",
                        textAlign = TextAlign.Center,
                        color = MishColors.Muted,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
private fun SocialChip(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MishColors.Surface,
        onClick = onClick
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            fontSize = 12.sp,
            color = MishColors.Muted
        )
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (message.isUser) MishColors.Surface else MishColors.Primary.copy(alpha = 0.5f),
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
        ) {
            Text(
                text = message.text,
                color = MishColors.White,
                fontSize = 14.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}