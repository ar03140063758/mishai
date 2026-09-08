package com.cybertech.mishai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cybertech.mishai.ai.MishService
import com.cybertech.mishai.ai.PreferencesManager
import com.cybertech.mishai.ui.theme.MishColors
import kotlinx.coroutines.launch

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
                onStopMish = { stopMishService() },
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

    private fun stopMishService() {
        val intent = Intent(this, MishService::class.java).setAction(MishService.ACTION_STOP)
        startService(intent)
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
    onStopMish: () -> Unit,
    onOpenCamera: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = PreferencesManager(context)
    var profile by remember { mutableStateOf(prefs.loadProfile()) }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var inputText by remember { mutableStateOf("") }
    var isServiceRunning by remember { mutableStateOf(MishService.isRunning) }
    val listState = rememberLazyListState()

    // Handle typed text -> send to backend, show reply
    val backend = remember { MishBackend(context) }
    val mainScope = rememberCoroutineScope()
    fun sendTyped(text: String) {
        messages = messages + ChatMessage(text, isUser = true)
        backend.ask(text, 0f, object : MishBackend.CallbackResult {
            override fun onSuccess(reply: MishBackend.MishReply) {
                mainScope.launch {
                    messages = messages + ChatMessage(reply.mood.emoji + " " + reply.text, isUser = false)
                }
            }
            override fun onError(error: String) {
                mainScope.launch {
                    messages = messages + ChatMessage("Kuch ghalti hui, dobara try karo.", isUser = false)
                }
            }
        })
    }

    // Listen to background service events
    DisposableEffect(Unit) {
        MishServiceBridge.onUserSpeech = { text ->
            messages = messages + ChatMessage(text, isUser = true)
        }
        MishServiceBridge.onMishReply = { text, mood ->
            messages = messages + ChatMessage(mood.emoji + " " + text, isUser = false)
        }
        MishServiceBridge.onServiceState = { running ->
            isServiceRunning = running
        }
        onDispose {
            MishServiceBridge.onUserSpeech = null
            MishServiceBridge.onMishReply = null
            MishServiceBridge.onServiceState = null
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

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
        // Ambient glow blobs
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(200.dp)
                .offset(x = 80.dp, y = (-60).dp)
                .blur(40.dp)
                .background(MishColors.Primary.copy(alpha = 0.15f), CircleShape)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .size(240.dp)
                .offset(x = (-100).dp, y = 120.dp)
                .blur(50.dp)
                .background(MishColors.Accent.copy(alpha = 0.12f), CircleShape)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ===== Header (compact, premium) =====
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Logo orb
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .shadow(16.dp, CircleShape)
                        .background(
                            Brush.linearGradient(listOf(MishColors.Primary, MishColors.Accent)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "M",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "Mish AI",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MishColors.TextPrimary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Cyber Tech Agency",
                    fontSize = 12.sp,
                    color = MishColors.Muted,
                    letterSpacing = 1.2.sp
                )
            }

            Spacer(Modifier.height(20.dp))

            // ===== Service status chip =====
            ServiceStatusBar(
                isRunning = isServiceRunning,
                onToggle = {
                    if (isServiceRunning) onStopMish() else onStartMish()
                }
            )

            Spacer(Modifier.height(16.dp))

            // ===== Greeting =====
            Text(
                text = "Salam ${profile.displayName()} ${profile.roleWord()} 👋",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MishColors.TextPrimary
            )
            Text(
                text = if (isServiceRunning)
                    "Mish active hai — bas bolo, ya niche type karo"
                else
                    "Start karo aur baat shuru",
                fontSize = 12.sp,
                color = if (isServiceRunning) MishColors.Success else MishColors.Muted,
                modifier = Modifier.padding(top = 2.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ===== Action buttons row =====
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = if (isServiceRunning) MishColors.Success.copy(alpha = 0.15f)
                    else MishColors.Primary.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isServiceRunning) MishColors.Success.copy(alpha = 0.4f)
                        else MishColors.Primary.copy(alpha = 0.4f)
                    ),
                    onClick = {
                        if (isServiceRunning) onStopMish() else onStartMish()
                    }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isServiceRunning) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isServiceRunning) MishColors.Success else MishColors.PrimaryLight,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isServiceRunning) "Mish Active" else "Start Mish",
                            color = if (isServiceRunning) MishColors.Success else MishColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .shadow(8.dp, RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = MishColors.Accent.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.Accent.copy(alpha = 0.4f)),
                    onClick = onOpenCamera
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = null,
                            tint = MishColors.AccentLight,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Camera",
                            color = MishColors.TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ===== Overlay permission link =====
            TextButton(onClick = onRequestOverlay) {
                Text(
                    text = "Floating orb permission",
                    color = MishColors.Muted,
                    fontSize = 12.sp
                )
            }

            Spacer(Modifier.height(4.dp))

            // ===== Conversation area =====
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (messages.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MishColors.GlassSurface,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.GlassBorder),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = MishColors.PrimaryLight,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Baat shuru karo",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MishColors.TextPrimary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "\"Mish\" bolo aur bolna shuru karo,\nya neeche type karo",
                            textAlign = TextAlign.Center,
                            color = MishColors.Muted,
                            fontSize = 13.sp,
                            lineHeight = 20.sp
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(messages) { m ->
                            ChatBubble(m)
                        }
                    }
                }
            }

            // ===== Text input bar =====
            TextInputBar(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    if (inputText.isNotBlank()) {
                        sendTyped(inputText.trim())
                        inputText = ""
                    }
                }
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServiceStatusBar(isRunning: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = if (isRunning) MishColors.Success.copy(alpha = 0.12f) else MishColors.GlassSurface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isRunning) MishColors.Success.copy(alpha = 0.35f) else MishColors.GlassBorder
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) MishColors.Success else MishColors.Muted)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (isRunning) "Mish sun raha hai..." else "Tap to start Mish",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = if (isRunning) MishColors.Success else MishColors.Muted
            )
        }
    }
}

@Composable
private fun TextInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MishColors.InputBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.InputBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .shadow(12.dp, RoundedCornerShape(28.dp))
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = MishColors.TextPrimary,
                    fontSize = 15.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MishColors.Cursor),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (value.isEmpty()) {
                            Text(
                                text = "Mish se kuch bhi pucho...",
                                color = MishColors.TextHint,
                                fontSize = 15.sp
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onSend,
                shape = CircleShape,
                color = if (value.isNotBlank()) MishColors.Primary else MishColors.GlassSurfaceStrong,
                modifier = Modifier.size(42.dp),
                border = if (value.isBlank())
                    androidx.compose.foundation.BorderStroke(1.dp, MishColors.GlassBorder)
                else null
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (value.isNotBlank()) Color.White else MishColors.Muted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

data class ChatMessage(val text: String, val isUser: Boolean)

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        if (message.isUser) {
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                color = MishColors.Primary.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.Primary.copy(alpha = 0.25f)),
                modifier = Modifier
                    .widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    color = MishColors.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        } else {
            Surface(
                shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
                color = MishColors.GlassSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MishColors.GlassBorder),
                modifier = Modifier
                    .widthIn(max = 300.dp)
            ) {
                Text(
                    text = message.text,
                    color = MishColors.TextSecondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
    }
}
