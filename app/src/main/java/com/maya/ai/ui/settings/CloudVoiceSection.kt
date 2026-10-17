package com.maya.ai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.viewmodel.SettingsViewModel

@Composable
fun CloudVoiceSection(vm: SettingsViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val configured by vm.elevenConfigured.collectAsStateWithLifecycle()
    val status by vm.cloud.status.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var voiceId by remember(s.elevenVoiceId) { mutableStateOf(s.elevenVoiceId) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("ElevenLabs voice (cloud, optional)", style = MaterialTheme.typography.titleSmall)
        Text(
            "ON karne par MAYA ke jawab ka text ElevenLabs ko bheja jaata hai aur wahan se awaaz aati hai. Aapki ElevenLabs API key chahiye. Key na ho to phone ki apni awaaz chalti rahegi. Speed slider is voice par kaam nahi karta.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (configured) "ElevenLabs voice use karo" else "ElevenLabs voice use karo (pehle key save karein)",
                Modifier.weight(1f),
            )
            Switch(checked = s.useElevenLabs && configured, onCheckedChange = vm::setUseEleven, enabled = configured)
        }
        OutlinedTextField(
            value = voiceId, onValueChange = { voiceId = it },
            label = { Text("Voice ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it },
            label = { Text(if (configured) "Nayi ElevenLabs API key (update ke liye)" else "ElevenLabs API key") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveElevenSettings(key, voiceId); key = "" }) { Text("Save") }
            OutlinedButton(onClick = vm::clearElevenKey, enabled = configured) { Text("Clear key") }
        }
        if (status.isNotEmpty()) Text(status, fontSize = 14.sp)
    }
}
