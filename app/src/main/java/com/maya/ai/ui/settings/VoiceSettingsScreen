@Composable
fun VoiceSettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.tts.status.collectAsStateWithLifecycle()
    val voices by vm.tts.voices.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
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

        CloudVoiceSection(vm)

        HorizontalDivider()
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
