package com.maya.ai.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

@Composable
fun VoiceSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.tts.status.collectAsStateWithLifecycle()
    val voices by vm.tts.voices.collectAsStateWithLifecycle()
    val speaking by vm.tts.speaking.collectAsStateWithLifecycle()
    var voiceMenu by remember { mutableStateOf(false) }

    SettingsScaffold("Voice Settings", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("MAYA ke jawab bol kar sunao", Modifier.weight(1f))
            Switch(checked = s.speakEnabled, onCheckedChange = vm::setSpeakEnabled)
        }
        Text("Speed: ${"%.1f".format(s.speechRate)}x")
        Slider(value = s.speechRate, onValueChange = vm::setSpeechRate, valueRange = 0.5f..1.5f)
        Text("Volume: ${(s.volume * 100).toInt()}%")
        Slider(value = s.volume, onValueChange = vm::setVolume, valueRange = 0f..1f)

        Text("Voice", style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(onClick = { voiceMenu = true }, enabled = voices.isNotEmpty()) {
                Text(voices.firstOrNull { it.name == s.voiceName }?.label ?: "Automatic (best Indian voice)")
            }
            DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = { vm.setVoice(null); voiceMenu = false })
                voices.forEach { v ->
                    DropdownMenuItem(text = { Text(v.label) }, onClick = { vm.setVoice(v.name); voiceMenu = false })
                }
            }
        }
        Text("TTS status: $status", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::testVoice) { Text("Test voice") }
            OutlinedButton(onClick = vm::stopSpeaking, enabled = speaking) { Text("Stop") }
        }

        Text("Voice input bhasha", style = MaterialTheme.typography.titleSmall)
        listOf("hi-IN" to "Hindi", "en-IN" to "English (India) / Hinglish", "en-US" to "English (US)").forEach { (tag, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.inputLang == tag, onClick = { vm.setInputLang(tag) })
                Text(label)
            }
        }
        Text(
            "Note: Android speech recognition Hinglish ke liye alag mode nahi deta. Hindi mode aksar Devanagari deta hai, English (India) Roman mein likhta hai. Result phone ke speech engine par depend karta hai.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        HorizontalDivider()
        Text("Aur voices kaise install karein", style = MaterialTheme.typography.titleSmall)
        Text(
            "MAYA phone ke installed Android TTS voices use karti hai. Koi custom AI voice integrated nahi hai, aur female / cute awaaz ki guarantee nahi di ja sakti. Zyada voices ke liye: Settings > System > Languages > Text-to-speech output mein Google Speech Services chunein, phir Hindi (India) aur English (India) voice data install karein.",
            fontSize = 14.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { /* no handler on this phone */ }
            }) { Text("Install voice data") }
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) { /* no handler on this phone */ }
            }) { Text("TTS settings") }
        }
    }
}

@Composable
fun ApiSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val configured by vm.apiConfigured.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    var model by remember(s.model) { mutableStateOf(s.model) }

    SettingsScaffold("Gemini API", onBack) {
        Text(if (configured) "Status: API key saved (model: ${s.model})" else "Status: API key set nahi hai",
            style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text(if (configured) "Nayi API key (update ke liye)" else "Gemini API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it },
            label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("Default: ${com.maya.ai.data.MayaSettings.DEFAULT_MODEL}. Agar 'model unavailable' aaye to Google docs se current model naam daalein.") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.saveKey(key, model); key = "" }, enabled = !vm.busy) { Text("Validate & Save") }
            OutlinedButton(onClick = vm::clearKey, enabled = configured && !vm.busy) { Text("Clear key") }
            if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        vm.apiMessage?.let {
            Text(it, color = if (vm.apiMessageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        }
        TextButton(onClick = { uri.openUri("https://aistudio.google.com/apikey") }) { Text("Gemini API key kahan se milegi") }
        HorizontalDivider()
        Text("Suraksha ke baare mein", style = MaterialTheme.typography.titleSmall)
        Text(
            "Aapki key is phone par Android Keystore se encrypt hokar save hoti hai aur sirf Google Gemini ko bheji jaati hai. Phir bhi, agar phone rooted/compromised ho to key leak ho sakti hai. Kisi aur ke saath key share na karein, aur sirf limited-quota key use karein. Free-tier ki availability model, region aur Google ki policy par depend karti hai; unlimited free use ka koi vaada nahi hai.",
            fontSize = 14.sp,
        )
    }
}

@Composable
fun PrivacyScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SettingsScaffold("Permissions & Privacy", onBack) {
        Text("Permissions", style = MaterialTheme.typography.titleSmall)
        Text("- Microphone: sirf jab aap mic dabate hain. Background mein kabhi nahi sunti.\n- Internet: Gemini API ko request bhejne ke liye.\n- Accessibility (optional): sirf aapke enable karne par, Screen Control ke liye.\n\nContacts, SMS, call log aur location ki permission MAYA nahi maangti.", fontSize = 14.sp)
        Text("Data kahan jata hai", style = MaterialTheme.typography.titleSmall)
        Text("- Aapke messages (aur chat history ka limited hissa, last 12 messages) Google Gemini ko bheje jaate hain jawab ke liye.\n- Voice input Android ka speech recognizer handle karta hai; wo phone ke speech service (aksar Google) par audio process kar sakta hai. MAYA khud audio save ya upload nahi karti.\n- Screen ki contents Gemini ko nahi bheji jaatin.\n- Chat history sirf memory mein rehti hai; app band hone par chali jaati hai.", fontSize = 14.sp)
        Button(onClick = { confirm = true }) { Text("Saara local data delete karo") }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Saara data delete karein?") },
            text = { Text("API key, settings aur Screen Control preference hata di jayengi. Screen Control band kar diya jayega.") },
            confirmButton = { TextButton(onClick = { vm.clearAllData(); confirm = false }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    SettingsScaffold("About MAYA", onBack) {
        Text("MAYA AI v0.1.0", style = MaterialTheme.typography.titleMedium)
        Text("Ek open, personal-use Android AI assistant. Gemini API (aapki apni key), Android SpeechRecognizer aur TextToSpeech par bani hai.", fontSize = 15.sp)
        Text("Abhi ki limitations:\n- Sirf push-to-talk (wake word nahi).\n- Awaaz phone ke installed TTS voices par depend karti hai.\n- Screen Control: app kholna, back, scroll, aur focused text box mein likhna. Sab screens par kaam ki guarantee nahi.\n- Debug APK sirf testing ke liye hai.", fontSize = 14.sp)
    }
}
