package com.maya.ai.launcher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.maya.ai.domain.ActionResult

/**
 * Opens a real installed app through Android's PackageManager and a normal launch Intent.
 * It reports "not installed" honestly, and says only that the launch request was sent
 * (Android does not confirm that the app reached the foreground).
 */
object AppLauncher {

    private val PACKAGES: Map<AppTarget, List<String>> = mapOf(
        AppTarget.YOUTUBE to listOf("com.google.android.youtube"),
        AppTarget.WHATSAPP to listOf("com.whatsapp", "com.whatsapp.w4b"),
        AppTarget.INSTAGRAM to listOf("com.instagram.android"),
        AppTarget.CHROME to listOf("com.android.chrome"),
        AppTarget.GOOGLE to listOf("com.google.android.googlequicksearchbox"),
        AppTarget.GALLERY to listOf(
            "com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery",
            "com.android.gallery3d", "com.oneplus.gallery", "com.coloros.gallery3d",
            "com.vivo.gallery", "com.motorola.gallery",
        ),
        AppTarget.PLAY_STORE to listOf("com.android.vending"),
    )

    private val LABELS: Map<AppTarget, List<String>> = mapOf(
        AppTarget.GALLERY to listOf("Gallery", "Photos"),
    )

    /** Returns a result if [text] is a plain "open <app>" command, otherwise null (not handled). */
    fun handle(context: Context, text: String): ActionResult? =
        AppCommandMatcher.match(text)?.let { launch(context, it) }

    fun launch(context: Context, target: AppTarget): ActionResult {
        val app = context.applicationContext
        if (target == AppTarget.SETTINGS) return start(app, Intent(Settings.ACTION_SETTINGS), target)

        val pm = app.packageManager

        for (pkg in PACKAGES[target].orEmpty()) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) return start(app, intent, target)
        }

        // Fallback: match by the name shown in the launcher (needs the <queries> in the manifest).
        val wanted = LABELS[target] ?: listOf(target.label)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, android.content.pm.PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }.firstOrNull { ri ->
            val label = ri.loadLabel(pm).toString()
            wanted.any { it.equals(label, ignoreCase = true) }
        }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            if (intent != null) return start(app, intent, target)
        }

        if (target == AppTarget.GALLERY) {
            val gallery = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
            if (gallery.resolveActivity(pm) != null) return start(app, gallery, target)
        }

        return ActionResult(false, "${target.label} is phone par installed nahi hai.")
    }

    private fun start(context: Context, intent: Intent, target: AppTarget): ActionResult = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        ActionResult(true, "${target.label} kholne ki request bheji gayi.")
    } catch (e: Exception) {
        ActionResult(false, "${target.label} nahi khul paya.")
    }
}
