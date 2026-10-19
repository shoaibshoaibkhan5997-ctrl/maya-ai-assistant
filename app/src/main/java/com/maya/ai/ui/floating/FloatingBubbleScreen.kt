package com.maya.ai.ui.floating

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.overlay.BubbleVoiceState
import com.maya.ai.overlay.FloatingBubbleService
import com.maya.ai.ui.settings.SettingsScaffold

@Composable
fun FloatingBubbleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val running by FloatingBubbleService.running.collectAsStateWithLifecycle()
    val voice by FloatingBubbleService.voiceState.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var micGranted by remember { mutableStateOf(hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) }
    var explain by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // Coming back from Android Settings: re-check the real permission state.
    LifecycleResumeEffect(Unit) {
        granted = Settings.canDrawOverlays(ctx)
        micGranted = hasPermission(ctx, Manifest.permission.RECORD_AUDIO)
        onPauseOrDispose { }
    }

    fun startBubble() {
        try {
            FloatingBubbleService.start(ctx)
            note = null
        } catch (e: Exception) {
            note = "Bubble shuru nahi ho paya. App ko dobara kholkar try karein."
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        micGranted = hasPermission(ctx, Manifest.permission.RECORD_AUDIO)
        // The bubble works even if the mic or notification permission was refused.
        startBubble()
    }

    fun enable() {
        if (!Settings.canDrawOverlays(ctx)) {
            explain = true
            return
        }
        val needed = buildList {
            if (!hasPermission(ctx, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permLauncher.launch(needed.toTypedArray()) else startBubble()
    }

    SettingsScaffold("Floating Bubble", onBack) {
        Text("Floating bubble: ${if (running) "ON" else "OFF"}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Display over other apps permission: " + if (granted) "diya gaya hai" else "nahi diya gaya",
            fontSize = 15.sp,
        )
        Text(
            "Mic permission (voice listening ke liye): " + if (micGranted) "diya gaya hai" else "nahi diya gaya",
            fontSize = 15.sp,
        )
        Text(
            "Bubble mic: " + when (voice) {
                BubbleVoiceState.STOPPED -> "band"
                BubbleVoiceState.LISTENING -> "sun rahi hai"
                BubbleVoiceState.THINKING -> "soch rahi hai"
                BubbleVoiceState.SPEAKING -> "bol rahi hai"
            },
            fontSize = 15.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::enable, enabled = !running) { Text("Enable bubble") }
            OutlinedButton(onClick = { FloatingBubbleService.stop(ctx) }, enabled = running) { Text("Stop bubble") }
        }
        OutlinedButton(
            onClick = { FloatingBubbleService.stopListening(ctx) },
            enabled = voice != BubbleVoiceState.STOPPED,
        ) { Text("Stop listening") }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }

        HorizontalDivider()
        Text("Yeh kaise kaam karta hai", style = MaterialTheme.typography.titleSmall)
        Text(
            "- Doosre apps ke upar ek chhota \"M\" bubble dikhta hai. Ungli se khiskakar kahin bhi rakh sakte hain.\n" +
                "- Bubble par tap karne se MAYA khulta hai. Mic apne aap shuru nahi hota.\n" +
                "- Bubble ko ~0.5 second dabakar rakhein: mic shuru ho jata hai aur chhodne ke baad bhi chalta rehta hai. Dobara dabakar rakhein ya notification ka \"Stop Listening\" dabayein: mic band.\n" +
                "- Bubble ka kinara hara = sun rahi hai, peela = soch rahi hai, jamuni = bol rahi hai.\n" +
                "- Mic ON hone par Android ka hara mic indicator dikhta hai. MAYA ise chhupa nahi sakti.\n" +
                "- Jo bhi mic sunta hai wo text banakar Gemini ko jata hai. Koi audio ya baat phone mein save nahi hoti.\n" +
                "- Confirm wale kaam (call, message, typing) bubble se nahi chalte; unke liye MAYA app mein bolein.\n" +
                "- Mic ke liye pehle mic permission dein, phir \"Enable bubble\". Permission baad mein di ho to \"Stop bubble\" aur dobara \"Enable bubble\" dabayein.\n" +
                "- MAYA app screen par ho to bubble ka mic nahi chalta; app ka apna mic use hota hai.",
            fontSize = 14.sp,
        )
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Display over other apps chahiye") },
            text = {
                Text("Bubble doosre apps ke upar dikhane ke liye Android ki \"Display over other apps\" permission chahiye. Settings khulengi, wahan MAYA AI ko ON karein, phir wapas aakar \"Enable bubble\" dabayein.")
            },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (_: Exception) {
                        note = "Android Settings nahi khul payi. Settings > Apps > MAYA AI mein jaakar \"Display over other apps\" ON karein."
                    }
                }) { Text("Settings kholo") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }
}

private fun hasPermission(ctx: android.content.Context, permission: String) =
    ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
