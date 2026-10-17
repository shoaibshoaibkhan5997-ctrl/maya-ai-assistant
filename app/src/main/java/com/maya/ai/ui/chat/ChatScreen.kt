package com.maya.ai.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.R
import com.maya.ai.service.MayaAccessibilityService
import com.maya.ai.speech.SpeechRecognitionManager
import com.maya.ai.ui.Screen
import com.maya.ai.viewmodel.ChatMessage
import com.maya.ai.viewmodel.ChatViewModel
import com.maya.ai.viewmodel.Role

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onNavigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val apiOk by vm.apiConfigured.collectAsStateWithLifecycle()
    val svcOn by MayaAccessibilityService.connected.collectAsStateWithLifecycle()
    val speaking by vm.tts.speaking.collectAsStateWithLifecycle()
    val speechState by vm.speech.state.collectAsStateWithLifecycle()
    val listening = speechState is SpeechRecognitionManager.State.Listening
    val screenOn = svcOn && settings.screenControlEnabled
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening() else vm.onMicDenied()
    }
    fun onMic() {
        when {
            listening -> vm.stopListening()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                vm.startListening()
            else -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MayaLogo(32)
                        Spacer(Modifier.width(10.dp))
                        Text("MAYA AI", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    IconButton(onClick = vm::clearChat) { Icon(Icons.Filled.DeleteSweep, "Clear chat") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            Screen.entries.filter { it != Screen.CHAT }.forEach { s ->
                                DropdownMenuItem(text = { Text(s.title) }, onClick = { menuOpen = false; onNavigate(s) })
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (vm.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("MAYA soch rahi hai...", Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = vm::cancelGeneration) { Text("Cancel") }
                    }
                }
                if (listening) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Sun rahi hoon... boliye", Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = vm::stopListening) { Text("Stop") }
                    }
                }
                (speechState as? SpeechRecognitionManager.State.Error)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (speaking || vm.lastMayaText != null) {
                    Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (speaking) AssistChip(onClick = vm::stopSpeaking, label = { Text("Stop speaking") },
                            leadingIcon = { Icon(Icons.Filled.Stop, null) })
                        else AssistChip(onClick = vm::replay, label = { Text("Replay") },
                            leadingIcon = { Icon(Icons.Filled.Replay, null) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = vm.input,
                        onValueChange = { vm.input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("MAYA se kuch poochiye...") },
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    FilledTonalIconButton(
                        onClick = ::onMic,
                        colors = if (listening) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.error)
                        else IconButtonDefaults.filledTonalIconButtonColors(),
                    ) { Icon(if (listening) Icons.Filled.MicOff else Icons.Filled.Mic, if (listening) "Stop listening" else "Start listening") }
                    Spacer(Modifier.width(4.dp))
                    FilledIconButton(onClick = vm::send, enabled = vm.input.isNotBlank() && !vm.loading) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send")
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (apiOk) "Gemini key: set" else "Gemini key: missing", apiOk) { onNavigate(Screen.API) }
                StatusPill(if (screenOn) "Screen Control: ON" else "Screen Control: OFF", screenOn) { onNavigate(Screen.SCREEN_CONTROL) }
            }
            vm.pendingType?.let {
                Card(Modifier.padding(12.dp).fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Confirmation chahiye", style = MaterialTheme.typography.titleSmall)
                        Text("Text box mein likhna hai: \"${it.text}\"", Modifier.padding(vertical = 6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::confirmPending) { Text("Haan, likho") }
                            OutlinedButton(onClick = vm::cancelPending) { Text("Cancel") }
                        }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (vm.messages.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            MayaLogo(72)
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Namaste! Main MAYA hoon. Aaj main aapki kya madad kar sakti hoon?",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
                items(vm.messages) { msg -> MessageBubble(msg, onSpeak = { vm.speak(msg.text) }) }
            }
        }
    }
}

@Composable
fun MayaLogo(size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_maya_foreground), "MAYA logo", Modifier.fillMaxSize())
    }
}

@Composable
private fun StatusPill(label: String, ok: Boolean, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        leadingIcon = {
            Icon(
                if (ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                Modifier.size(16.dp),
                tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
            )
        },
    )
}

@Composable
private fun MessageBubble(msg: ChatMessage, onSpeak: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    when (msg.role) {
        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(color = cs.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 300.dp)) {
                Text(msg.text, Modifier.padding(12.dp), color = cs.onPrimaryContainer, fontSize = 16.sp)
            }
        }
        Role.MAYA -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(color = cs.surfaceVariant, shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("MAYA (AI jawab)", color = cs.primary, fontSize = 12.sp)
                    Text(msg.text, fontSize = 16.sp, modifier = Modifier.padding(top = 2.dp))
                    IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.VolumeUp, "Speak", Modifier.size(18.dp))
                    }
                }
            }
        }
        Role.STATUS -> Surface(
            color = cs.surface, shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (msg.ok) cs.secondary else cs.error),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (msg.ok) Icons.Filled.CheckCircle else Icons.Filled.Info, null, Modifier.size(18.dp),
                    tint = if (msg.ok) cs.secondary else cs.error)
                Spacer(Modifier.width(8.dp))
                Text(msg.text, fontSize = 14.sp)
            }
        }
    }
}
