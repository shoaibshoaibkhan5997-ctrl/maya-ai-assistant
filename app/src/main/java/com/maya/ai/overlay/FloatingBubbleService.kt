package com.maya.ai.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.maya.ai.MainActivity
import com.maya.ai.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot

/**
 * Shows a small draggable "M" bubble above other apps. It only draws its own tiny window:
 * it never reads other apps' screens. Tapping it brings the existing MAYA screen to the front.
 * It runs as a foreground service (with a notification and a stop button) and only after the
 * user enables it and has granted "Display over other apps".
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private var bubble: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var sizePx = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sizePx = dp(52)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubble == null) addBubble()   // never a second bubble, even if started twice
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val p = lp ?: return
        place(p.x, p.y)                   // rotation: keep the bubble inside the new screen
        savePosition()
    }

    override fun onDestroy() {
        removeBubble()
        _running.value = false
        super.onDestroy()
    }

    // ---------- bubble ----------

    private fun addBubble() {
        val view = TextView(this).apply {
            text = "M"
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE6E0FF.toInt())
            contentDescription = "MAYA floating bubble. Tap to open MAYA."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2A2370.toInt())
                setStroke(dp(2), 0xFF6CB4FF.toInt())
            }
            setOnClickListener { openMaya() }
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val area = area()
        val savedX = prefs.getInt(KEY_X, Int.MIN_VALUE)
        val savedY = prefs.getInt(KEY_Y, Int.MIN_VALUE)
        params.x = if (savedX == Int.MIN_VALUE) area.right - sizePx - dp(8) else savedX
        params.y = if (savedY == Int.MIN_VALUE) area.top + (area.height() * 0.4f).toInt() else savedY
        clamp(params)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) place(startX + dx.toInt(), startY + dy.toInt())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) savePosition() else v.performClick()   // a drag never counts as a tap
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) savePosition()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(view, params)
            bubble = view
            lp = params
            _running.value = true
        } catch (e: Exception) {
            // Permission revoked or window refused: stop cleanly instead of crashing.
            stopSelf()
        }
    }

    private fun removeBubble() {
        bubble?.let {
            it.setOnTouchListener(null)
            it.setOnClickListener(null)
            try { wm.removeViewImmediate(it) } catch (_: Exception) { }
        }
        bubble = null
        lp = null
    }

    private fun place(x: Int, y: Int) {
        val p = lp ?: return
        val v = bubble ?: return
        p.x = x
        p.y = y
        clamp(p)
        try { wm.updateViewLayout(v, p) } catch (_: Exception) { }
    }

    /** Keeps the bubble inside the screen, away from status bar, navigation bar and cutouts. */
    private fun clamp(p: WindowManager.LayoutParams) {
        val a = area()
        p.x = p.x.coerceIn(a.left, maxOf(a.left, a.right - sizePx))
        p.y = p.y.coerceIn(a.top, maxOf(a.top, a.bottom - sizePx))
    }

    private fun area(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val m = wm.currentWindowMetrics
            val ins = m.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val b = m.bounds
            return Rect(b.left + ins.left, b.top + ins.top, b.right - ins.right, b.bottom - ins.bottom)
        }
        val dm = resources.displayMetrics
        return Rect(0, dp(24), dm.widthPixels, dm.heightPixels)
    }

    private fun savePosition() {
        val p = lp ?: return
        prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
    }

    private fun openMaya() {
        // Reuses the existing MAYA screen (same chat, voice and Gemini). It does not start the mic.
        val intent = Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        try { startActivity(intent) } catch (_: Exception) { }
    }

    // ---------- foreground notification ----------

    private fun startInForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MAYA floating bubble", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the MAYA floating bubble is on"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_maya_foreground)
            .setContentTitle("MAYA floating bubble ON")
            .setContentText("Bubble band karne ke liye \"Band karo\" dabayein.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Band karo", stop)
            .build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "maya_floating_bubble"
        private const val NOTIF_ID = 4101
        private const val ACTION_STOP = "com.maya.ai.overlay.STOP"
        private const val PREFS = "maya_bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        private val _running = MutableStateFlow(false)
        /** True while the bubble is on screen. */
        val running: StateFlow<Boolean> = _running

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
