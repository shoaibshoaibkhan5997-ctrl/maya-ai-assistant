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
import com.maya.ai.overlay.FloatingBubbleService
import com.maya.ai.ui.settings.SettingsScaffold

@Composable
fun FloatingBubbleScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val running by FloatingBubbleService.running.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
    var explain by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    // Coming back from Android Settings: re-check the real permission state.
    LifecycleResumeEffect(Unit) {
        granted = Settings.canDrawOverlays(ctx)
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

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startBubble()   // bubble works even if the notification is hidden by the user
    }

    fun enable() {
        if (!Settings.canDrawOverlays(ctx)) {
            explain = true
            return
        }
        val needsNotifPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotifPermission) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) else startBubble()
    }

    SettingsScaffold("Floating Bubble", onBack) {
        Text("Floating bubble: ${if (running) "ON" else "OFF"}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Display over other apps permission: " + if (granted) "diya gaya hai" else "nahi diya gaya",
            fontSize = 15.sp,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::enable, enabled = !running) { Text("Enable bubble") }
            OutlinedButton(onClick = { FloatingBubbleService.stop(ctx) }, enabled = running) { Text("Stop bubble") }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }

        HorizontalDivider()
        Text("Yeh kaise kaam karta hai", style = MaterialTheme.typography.titleSmall)
        Text(
            "- Doosre apps ke upar ek chhota \"M\" bubble dikhta hai. Ungli se khiskakar kahin bhi rakh sakte hain.\n" +
                "- Bubble par tap karne se MAYA khulta hai. Mic apne aap shuru nahi hota.\n" +
                "- Jab tak bubble ON hai, ek notification dikhti hai jisme \"Band karo\" button hai.\n" +
                "- Bubble sirf apni chhoti si jagah banata hai. Yeh doosre apps ki screen nahi padhta aur koi naya data kahin nahi bhejta.\n" +
                "- Bubble band karne ke liye yahan \"Stop bubble\" ya notification ka \"Band karo\" dabayein.\n" +
                "- Android Settings mein \"Display over other apps\" ko aap khud ON karte hain. MAYA ise apne aap ON nahi kar sakti.",
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
