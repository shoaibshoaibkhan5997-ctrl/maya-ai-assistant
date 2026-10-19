package com.maya.ai.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.domain.ActionResult
import com.maya.ai.launcher.AppLauncher
import com.maya.ai.launcher.AppTarget
import com.maya.ai.launcher.QuickActions
import com.maya.ai.speech.SpeechRecognitionManager
import com.maya.ai.ui.Screen
import com.maya.ai.ui.chat.MayaLogo
import com.maya.ai.viewmodel.ChatViewModel
import com.maya.ai.viewmodel.Role
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Neon = Color(0xFF3EA6FF)
private val Soft = Color(0xFFB9C3E8)
private val GoodText = Color(0xFF9FE1CB)
private val BadText = Color(0xFFFF8A9B)
private const val TWO_PI = (2.0 * PI).toFloat()

private enum class HomeDialog { APPS, MORE }

/**
 * New main screen. It shares the one existing ChatViewModel, so voice input/output, Gemini,
 * App Launcher and Screen Control are the same implementation as in the Chat screen.
 * [avatar] can be swapped for a real 3D view without changing anything else here.
 */
@Composable
fun HomeScreen(
    vm: ChatViewModel,
    onNavigate: (Screen) -> Unit,
    avatar: @Composable (AvatarState, State<Float>, Modifier) -> Unit = { s, p, m -> MayaAvatar(s, p, m) },
) {
    val ctx = LocalContext.current
    val apiOk by vm.apiConfigured.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val speechState by vm.speech.state.collectAsStateWithLifecycle()
    val listening = speechState is SpeechRecognitionManager.State.Listening

    var note by remember { mutableStateOf<String?>(null) }
    var noteOk by remember { mutableStateOf(true) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val avatarState = when {
        listening -> AvatarState.LISTENING
        vm.loading -> AvatarState.THINKING
        speaking -> AvatarState.SPEAKING
        else -> AvatarState.IDLE
    }

    val transition = rememberInfiniteTransition(label = "maya-phase")
    val phase = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable<Float>(tween(6000, easing = LinearEasing)),
        label = "phase",
    )

    fun report(r: ActionResult) { note = r.message; noteOk = r.success }
    fun hasMic() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoiceMode() else vm.onMicDenied()
    }
    fun onMic() {
        when {
            vm.voiceMode -> vm.stopVoiceMode("Voice Mode band kiya gaya. Mic band hai.")
            !apiOk -> { note = "Not configured: Gemini API key set nahi hai."; noteOk = false }
            hasMic() -> { note = null; vm.startVoiceMode() }
            else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF04050D), Color(0xFF0A1030), Color(0xFF04050D)))
        )
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {

            // top bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MayaLogo(36)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("MAYA", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text("AI Assistant", color = Soft, fontSize = 12.sp)
                }
                IconButton(onClick = { dialog = HomeDialog.MORE }) {
                    Icon(Icons.Filled.Settings, "Settings", tint = Color.White)
                }
            }

            // scrollable middle: avatar, panel, quick actions
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val side = minOf(maxWidth * 0.78f, 300.dp)
                    Box(Modifier.size(side)) {
                        avatar(avatarState, phase, Modifier.fillMaxSize())
                        GreetingBubble(phase, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = 4.dp))
                    }
                }

                Spacer(Modifier.height(8.dp))

                Glass(Modifier.fillMaxWidth()) {
                    Text("Hello! I'm Maya", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Your AI Assistant. How can I help you today?",
                        color = Soft, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp),
                    )

                    val live = when {
                        vm.voiceMode -> vm.voiceStatus
                        listening -> "Listening..."
                        vm.loading -> "Thinking..."
                        speaking -> "Speaking..."
                        else -> null
                    }
                    live?.let { Text(it, color = Neon, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }

                    (speechState as? SpeechRecognitionManager.State.Error)?.takeIf { !vm.voiceMode }?.let {
                        Text(it.message, color = BadText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    note?.let {
                        Text(it, color = if (noteOk) GoodText else BadText, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                    if (!apiOk) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Not configured: Gemini API key.", color = BadText, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onNavigate(Screen.API) }) { Text("API Settings") }
                        }
                    }

                    vm.messages.lastOrNull()?.let { m ->
                        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.12f))
                        Text(
                            (if (m.role == Role.MAYA) "MAYA: " else "") + m.text,
                            color = if (m.role == Role.STATUS && !m.ok) BadText else Color.White,
                            fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(Icons.Filled.Apps, "Open Apps", Modifier.weight(1f)) { dialog = HomeDialog.APPS }
                    QuickTile(Icons.Filled.RecordVoiceOver, "Voice Control", Modifier.weight(1f)) { onNavigate(Screen.VOICE) }
                    QuickTile(Icons.Filled.Search, "Search", Modifier.weight(1f)) { report(AppLauncher.launch(ctx, AppTarget.GOOGLE)) }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(Icons.Filled.MusicNote, "Play Music", Modifier.weight(1f)) { report(QuickActions.openMusic(ctx)) }
                    QuickTile(Icons.Filled.PhotoCamera, "Take a Photo", Modifier.weight(1f)) { report(QuickActions.openCamera(ctx)) }
                    QuickTile(Icons.Filled.MoreHoriz, "More", Modifier.weight(1f)) { dialog = HomeDialog.MORE }
                }
                Spacer(Modifier.height(16.dp))
            }

            // bottom: confirmation / STOP cards, then the mic row
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                vm.pendingPlan?.let { plan ->
                    Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Text("Confirmation chahiye", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(
                            plan.mapIndexed { i, s -> "${i + 1}. ${s.describe()}" }.joinToString("\n"),
                            color = Soft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::confirmPending) { Text("Haan, chalao") }
                            OutlinedButton(onClick = vm::cancelPending) { Text("Cancel") }
                        }
                    }
                }
                if (vm.running) {
                    Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("MAYA phone chala rahi hai...", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Button(onClick = vm::stopPlan) { Text("STOP") }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ShortcutItem(Modifier.weight(1f), "MAYA", onClick = { scope.launch { scroll.animateScrollTo(0) } }) {
                        MayaLogo(32)
                    }
                    MicButton(active = vm.voiceMode, listening = listening, phase = phase, onClick = ::onMic)
                    ShortcutItem(Modifier.weight(1f), "Chat", onClick = { onNavigate(Screen.CHAT) }) {
                        Icon(Icons.Filled.ChatBubbleOutline, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                }
                Text(
                    when {
                        vm.voiceMode -> "Tap to stop"
                        else -> "Tap to talk"
                    },
                    color = Soft, fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
            }
        }
    }

    when (dialog) {
        HomeDialog.APPS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Open Apps") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    AppTarget.entries.forEach { t ->
                        TextButton(
                            onClick = { dialog = null; report(AppLauncher.launch(ctx, t)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t.label) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        HomeDialog.MORE -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("More") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Screen.entries.filter { it != Screen.HOME }.forEach { s ->
                        TextButton(
                            onClick = { dialog = null; onNavigate(s) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(s.title) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
        )
        null -> Unit
    }
}

@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .clip(shape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = Neon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun ShortcutItem(modifier: Modifier, label: String, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Spacer(Modifier.height(2.dp))
        Text(label, color = Soft, fontSize = 12.sp)
    }
}

@Composable
private fun GreetingBubble(phase: State<Float>, modifier: Modifier) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .graphicsLayer {
                val a = appear.value
                scaleX = a
                scaleY = a
                alpha = a.coerceIn(0f, 1f)
                translationY = sin(phase.value * TWO_PI * 2f) * 3.dp.toPx()
            }
            .clip(shape)
            .background(Color(0xFF1A2350))
            .border(1.dp, Neon.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("Hi! I'm Maya", color = Color.White, fontSize = 13.sp)
    }
}

/** Glowing mic. The waveform ring is drawn only while the recognizer is really listening. */
@Composable
private fun MicButton(active: Boolean, listening: Boolean, phase: State<Float>, onClick: () -> Unit) {
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val d = size.minDimension
            drawCircle(Neon.copy(alpha = if (active) 0.28f else 0.16f), d * 0.46f, c)
            if (listening) {
                val p = phase.value
                val inner = d * 0.34f + d * 0.02f
                val n = 28
                for (i in 0 until n) {
                    val a = i * (TWO_PI / n)
                    val amp = 0.03f + 0.09f * abs(sin(p * TWO_PI * 3f + i * 0.9f))
                    val outer = inner + d * amp
                    drawLine(
                        color = Neon,
                        start = Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                        end = Offset(c.x + cos(a) * outer, c.y + sin(a) * outer),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(if (active) Neon else Color(0xFF14204A))
                .border(2.dp, Neon, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (active) Icons.Filled.Stop else Icons.Filled.Mic,
                if (active) "Stop voice mode" else "Start voice mode",
                tint = if (active) Color(0xFF04050D) else Color.White,
                modifier = Modifier.size(34.dp),
            )
        }
    }
}
