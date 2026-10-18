package com.maya.ai.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.maya.ai.domain.ActionResult

/**
 * Opens the dialer or a message composer with everything pre-filled.
 * MAYA never places a call or presses Send: the user does that last tap.
 */
object MessagingActions {

    fun dial(ctx: Context, number: String): ActionResult = try {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        ActionResult(true, "Dialer $number ke saath kholne ki request bheji gayi. Call karne ke liye hara button aap khud dabayein.")
    } catch (e: Exception) {
        ActionResult(false, "Dialer nahi khul paya.")
    }

    fun whatsApp(ctx: Context, number: String, text: String): ActionResult {
        val digits = number.removePrefix("+")
        val uri = Uri.parse("https://wa.me/$digits?text=" + Uri.encode(text))
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return ActionResult(true, "WhatsApp chat $number ke saath message likha hua kholne ki request bheji gayi. Send aap khud dabayein.")
            } catch (e: ActivityNotFoundException) {
                // try the next WhatsApp package
            } catch (e: Exception) {
                return ActionResult(false, "WhatsApp nahi khul paya.")
            }
        }
        return ActionResult(false, "Is phone par WhatsApp installed nahi mila.")
    }

    fun sms(ctx: Context, number: String, text: String): ActionResult = try {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        ActionResult(true, "SMS $number ke saath message likha hua kholne ki request bheji gayi. Send aap khud dabayein.")
    } catch (e: Exception) {
        ActionResult(false, "SMS app nahi khul paya.")
    }
}
