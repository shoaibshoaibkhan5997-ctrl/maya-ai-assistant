package com.maya.ai.ui.screencontrol

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maya.ai.ui.settings.SettingsScaffold
import com.maya.ai.viewmodel.SettingsViewModel

@Composable
fun ScreenControlScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsStateWithLifecycle()
    val svcOn by vm.serviceConnected.collectAsStateWithLifecycle()
    var explain by remember { mutableStateOf(false) }
    var disabledNote by remember { mutableStateOf(false) }
    val on = svcOn && s.screenControlEnabled

    fun openAccessibilitySettings() {
        try {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { /* very unlikely */ }
    }

    SettingsScaffold("Screen Control", onBack) {
        Text("Screen Control: ${if (on) "ON" else "OFF"}", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Android Accessibility permission: " + if (svcOn) "diya gaya hai (service chal rahi hai)" else "nahi diya gaya",
            fontSize = 15.sp,
        )
        if (svcOn && !s.screenControlEnabled) {
            Text("Android permission ON hai, lekin MAYA ke andar Screen Control OFF hai, isliye koi action nahi chalega.", fontSize = 14.sp)
        }
        if (!svcOn && s.screenControlEnabled) {
            Text("Aapne MAYA mein ON kiya hai, par Android Accessibility settings mein MAYA Screen Control abhi enable nahi hai.", fontSize = 14.sp)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (svcOn) vm.enableScreenControl() else explain = true
            }, enabled = !on) { Text("Enable Screen Control") }
            OutlinedButton(onClick = { vm.disableScreenControl(); disabledNote = true }, enabled = on || s.screenControlEnabled || svcOn) {
                Text("Disable Screen Control")
            }
        }
        if (disabledNote) {
            Text(
                "MAYA ne naye commands lena band kar diya aur service ko disable karne ki request di. Agar Android settings mein MAYA abhi bhi ON dikhe, to Settings > Accessibility mein jaakar use manually OFF karein.",
                fontSize = 14.sp,
            )
        }

        HorizontalDivider()
        Text("MAYA kya kar sakti hai (aur sirf aapke command par)", style = MaterialTheme.typography.titleSmall)
        Text(
            "- Installed app kholna\n- Back dabana\n- Screen par pehla scrollable area upar/neeche scroll karna\n- Aapke focused text box mein text likhna (har baar aapki confirmation ke baad; password fields mein kabhi nahi)\n\nMAYA yeh nahi karti: passwords, payments, purchases ya security settings automate karna; screen ki contents Gemini ko bhejna; background mein khud se action chalana.\n\nAccessibility ek sensitive Android permission hai, normal permission nahi. Isliye Android aapko ise Settings mein khud enable karne deta hai; MAYA ise automatically on nahi kar sakti.",
            fontSize = 14.sp,
        )
    }

    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text("Accessibility permission chahiye") },
            text = {
                Text("Android Accessibility Settings khulengi. Wahan \"MAYA Screen Control\" chunke use ON karein. Yeh powerful permission hai: service doosre apps ki screen par actions kar sakti hai. MAYA ise sirf tab use karti hai jab aap command dete hain. Wapas aakar yahan status check karein.")
            },
            confirmButton = {
                TextButton(onClick = { explain = false; vm.enableScreenControl(); openAccessibilitySettings() }) { Text("Settings kholo") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Cancel") } },
        )
    }
}
