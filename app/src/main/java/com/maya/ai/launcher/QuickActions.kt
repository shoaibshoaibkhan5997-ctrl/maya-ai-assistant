package com.maya.ai.launcher

import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import com.maya.ai.domain.ActionResult

/** Real, simple launches used by the Home screen's quick buttons. Nothing here is simulated. */
object QuickActions {

    private val MUSIC_PACKAGES = listOf(
        "com.google.android.apps.youtube.music",
        "com.spotify.music",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.bsbportal.music",
        "com.amazon.mp3",
        "com.apple.android.music",
    )

    fun openMusic(context: Context): ActionResult {
        val pm = context.packageManager
        for (pkg in MUSIC_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            return start(context, intent, "Music app kholne ki request bheji gayi.")
        }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().contains("music", ignoreCase = true)
        }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent != null) return start(context, intent, "Music app kholne ki request bheji gayi.")
        return ActionResult(false, "Not configured: is phone par koi music app nahi mila.")
    }

    fun openCamera(context: Context): ActionResult =
        start(
            context,
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            "Camera kholne ki request bheji gayi. Photo aap khud kheechein.",
            failure = "Camera app nahi mila.",
        )

    private fun start(context: Context, intent: Intent, success: String, failure: String = "App nahi khul paya."): ActionResult =
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionResult(true, success)
        } catch (e: Exception) {
            ActionResult(false, failure)
        }
}
