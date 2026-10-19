package com.maya.ai.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.maya.ai.MainActivity
import com.maya.ai.MayaApp
import com.maya.ai.R
import com.maya.ai.data.GeminiException
import com.maya.ai.data.MayaSettings
import com.maya.ai.data.settingsSnapshot
import com.maya.ai.domain.ActionResult
import com.maya.ai.domain.AssistantCommand
import com.maya.ai.domain.ChatTurn
import com.maya.ai.domain.CommandParser
import com.maya.ai.launcher.AppLauncher
import com.maya.ai.service.MayaAccessibilityService
import com.maya.ai.speech.SpeechRecognitionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** What the floating bubble's microphone is doing right now. */
enum class BubbleVoiceState { STOPPED, LISTENING, THINKING, SPEAKING }

/**
 * Floating "M" bubble over other apps, plus an optional always-on voice session.
 * - Tap: brings the existing MAYA screen to the front.
 * - Long press: starts listening; long press again (or the notification's Stop Listening) stops it.
 * Listening uses the existing SpeechRecognitionManager, Gemini, AppLauncher and speech output.
 * It never reads other apps' screens and stores no audio or conversation on disk.
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var speech: SpeechRecognitionManager
    private val maya get() = application as MayaApp

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var loopJob: Job? = null

    private var bubble: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var sizePx = 0

    @Volatile private var listening = false
    private var micTypeActive = false
    @Volatile private var heard: String? = null
    private val history = ArrayList<ChatTurn>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        sizePx = dp(52)
        speech = SpeechRecognitionManager(this)
        speech.onFinal = { heard = it }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_LISTEN -> {
                if (listening) stopListening("Mic band kiya gaya.")
                if (bubble == null) stopSelf()
                return START_NOT_STICKY
            }
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
        stopListening(null)
        mainHandler.removeCallbacksAndMessages(null)
        removeBubble()
        speech.destroy()
        scope.cancel()
        _running.value = false
        _voiceState.value = BubbleVoiceState.STOPPED
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
            contentDescription = "MAYA floating bubble. Tap to open MAYA. Long press to start or stop listening."
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF2A2370.toInt())
                setStroke(dp(2), strokeFor(_voiceState.value))
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
        var longPressed = false
        val longPress = Runnable {
            if (!dragging) {
                longPressed = true
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                toggleListening()
            }
        }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    longPressed = false
                    mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (dragging) place(startX + dx.toInt(), startY + dy.toInt())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    // a drag or a long press never counts as a tap
                    if (dragging) savePosition() else if (!longPressed) v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
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

    // ---------- always-on listening ----------

    private fun toggleListening() {
        if (listening) stopListening("Mic band kiya gaya.") else startListening()
    }

    private fun startListening() {
        if (listening) return
        val problem = when {
            appVisible -> "MAYA app khula hai. App ka apna mic button use karein."
            !hasMicPermission() -> "Mic permission nahi hai. MAYA kholkar Floating Bubble screen se permission dein."
            !micTypeActive -> "Mic ke liye bubble dobara Enable karein: MAYA kholkar Floating Bubble > Stop bubble, phir Enable."
            !maya.keys.configured.value -> "Gemini API key set nahi hai."
            !speech.isAvailable() -> "Is phone par speech recognition service nahi mili."
            else -> null
        }
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            return
        }
        listening = true
        history.clear()
        BubbleChatBus.post(BubbleChatBus.Kind.STATUS, "Bubble mic ON. Sun rahi hoon. Band karne ke liye bubble ko dobara dabakar rakhein.")
        setState(BubbleVoiceState.LISTENING)
        loopJob = scope.launch { runLoop() }
    }

    /** Stops the microphone for good. Nothing restarts it until the user long-presses again. */
    private fun stopListening(note: String?) {
        val was = listening
        listening = false
        loopJob?.cancel()
        loopJob = null
        speech.cancel()
        if (was) maya.stopSpeech()
        setState(BubbleVoiceState.STOPPED)
        if (was && note != null) BubbleChatBus.post(BubbleChatBus.Kind.STATUS, note)
    }

    private suspend fun runLoop() {
        var failed = 0
        var lastHeardAt = SystemClock.elapsedRealtime()
        while (listening) {
            waitIdle()
            if (!listening) return
            if (AUTO_STOP_SILENT_MS > 0 && SystemClock.elapsedRealtime() - lastHeardAt > AUTO_STOP_SILENT_MS) {
                stopListening("Kaafi der se kuch nahi suna, isliye mic band kiya gaya (battery bachane ke liye).")
                return
            }

            heard = null
            setState(BubbleVoiceState.LISTENING)
            speech.clearError()
            speech.start(maya.prefs.settingsSnapshot().inputLang)

            var waited = 0
            while (listening && speech.state.value is SpeechRecognitionManager.State.Listening) {
                delay(100)
                waited += 100
                if (waited == MAX_LISTEN_MS) speech.stop()
                if (waited > MAX_LISTEN_MS + 5_000) { speech.cancel(); break }
            }
            if (!listening) return

            val text = heard
            val st = speech.state.value
            when {
                text != null -> {
                    failed = 0
                    lastHeardAt = SystemClock.elapsedRealtime()
                    handleHeard(text)
                }
                st is SpeechRecognitionManager.State.Error && isSilence(st.message) -> delay(700)
                st is SpeechRecognitionManager.State.Error -> {
                    failed++
                    if (failed >= MAX_FAILED_ROUNDS) {
                        stopListening("Mic band kiya gaya: ${st.message}")
                        return
                    }
                    delay((failed * 1_000L).coerceAtMost(5_000L))
                }
                else -> delay(300)
            }
        }
    }

    private fun isSilence(message: String) =
        message.contains("timeout", ignoreCase = true) || message.contains("samajh", ignoreCase = true)

    /** Never listens while MAYA is speaking (plus a short guard), so she cannot hear herself. */
    private suspend fun waitIdle() {
        var quiet = 0
        while (listening) {
            if (maya.speaking.value) {
                quiet = 0
                setState(BubbleVoiceState.SPEAKING)
            } else {
                quiet += 100
            }
            if (quiet >= QUIET_MS) return
            delay(100)
        }
    }

    private suspend fun handleHeard(text: String) {
        setState(BubbleVoiceState.THINKING)
        BubbleChatBus.post(BubbleChatBus.Kind.USER, text)
        val s = maya.prefs.settingsSnapshot()

        // Plain "open <app>" commands are handled on the phone, exactly like in the chat screen.
        val launched = AppLauncher.handle(maya, text)
        if (launched != null) {
            BubbleChatBus.post(BubbleChatBus.Kind.STATUS, launched.message, launched.success)
            if (!launched.success) say(launched.message, s)
            return
        }

        history.add(ChatTurn(true, text))
        try {
            val raw = maya.gemini.interpret(history.takeLast(MAX_HISTORY).dropWhile { !it.fromUser })
            respond(CommandParser.parse(raw), s)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeminiException) {
            BubbleChatBus.post(BubbleChatBus.Kind.STATUS, e.message ?: "Unknown error", false)
        }
    }

    private suspend fun respond(cmd: AssistantCommand, s: MayaSettings) {
        when (cmd) {
            is AssistantCommand.AnswerQuestion -> sayMaya(cmd.reply, s)
            is AssistantCommand.Clarify -> sayMaya(cmd.question, s)
            is AssistantCommand.Invalid -> BubbleChatBus.post(BubbleChatBus.Kind.STATUS, cmd.reason, false)
            is AssistantCommand.StopActions -> {
                maya.disableScreenControl()
                BubbleChatBus.post(BubbleChatBus.Kind.STATUS, "Screen Control band kar diya.")
            }
            else -> runSimpleAction(cmd, s)
        }
    }

    /** Only single, non-confirmation actions run from the bubble; everything else needs the app. */
    private suspend fun runSimpleAction(cmd: AssistantCommand, s: MayaSettings) {
        if (cmd.requiresConfirmation || cmd is AssistantCommand.Plan) {
            val msg = "Yeh kaam confirm karke chalana padta hai. MAYA app kholkar bolein."
            BubbleChatBus.post(BubbleChatBus.Kind.STATUS, msg, false)
            say(msg, s)
            return
        }
        if (!s.screenControlEnabled || !MayaAccessibilityService.connected.value) {
            BubbleChatBus.post(BubbleChatBus.Kind.STATUS, "Screen Control OFF hai, isliye yeh action nahi chalaya.", false)
            return
        }
        val result = MayaAccessibilityService.instance?.execute(cmd)
            ?: ActionResult(false, "Accessibility service connected nahi hai.")
        BubbleChatBus.post(BubbleChatBus.Kind.STATUS, result.message, result.success)
    }

    private suspend fun sayMaya(text: String, s: MayaSettings) {
        BubbleChatBus.post(BubbleChatBus.Kind.MAYA, text)
        history.add(ChatTurn(false, text))
        say(text, s)
    }

    private suspend fun say(text: String, s: MayaSettings) {
        if (!s.speakEnabled) return
        setState(BubbleVoiceState.SPEAKING)
        maya.speakText(text, s)
        delay(600)                                   // let the voice engine start
        while (listening && maya.speaking.value) delay(100)
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- state, bubble ring colour, notification ----------

    private fun setState(state: BubbleVoiceState) {
        if (_voiceState.value == state) return
        _voiceState.value = state
        render()
    }

    private fun render() {
        (bubble?.background as? GradientDrawable)?.setStroke(dp(2), strokeFor(_voiceState.value))
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) { }
    }

    private fun strokeFor(state: BubbleVoiceState): Int = when (state) {
        BubbleVoiceState.STOPPED -> 0xFF6CB4FF.toInt()
        BubbleVoiceState.LISTENING -> 0xFF3DDC97.toInt()
        BubbleVoiceState.THINKING -> 0xFFFFC857.toInt()
        BubbleVoiceState.SPEAKING -> 0xFFA99BFF.toInt()
    }

    // ---------- foreground notification ----------

    private fun startInForeground() {
        val micOk = hasMicPermission()
        val n = buildNotification()
        micTypeActive = false
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                var type = 0
                if (Build.VERSION.SDK_INT >= 34) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (micOk) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (type != 0) startForeground(NOTIF_ID, n, type) else startForeground(NOTIF_ID, n)
                micTypeActive = micOk
            } else {
                startForeground(NOTIF_ID, n)
                micTypeActive = micOk
            }
        } catch (e: Exception) {
            // The system refused the microphone type: keep the bubble working without the mic.
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, n)
                }
            } catch (e2: Exception) {
                stopSelf()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "MAYA floating bubble", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the MAYA floating bubble or its microphone is on"
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
        val stopListen = PendingIntent.getService(
            this, 2,
            Intent(this, FloatingBubbleService::class.java).setAction(ACTION_STOP_LISTEN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val state = _voiceState.value
        val (title, text) = when (state) {
            BubbleVoiceState.STOPPED -> "MAYA floating bubble ON" to "Mic band hai. Mic shuru karne ke liye bubble ko dabakar rakhein."
            BubbleVoiceState.LISTENING -> "MAYA mic ON" to "Sun rahi hoon..."
            BubbleVoiceState.THINKING -> "MAYA mic ON" to "MAYA soch rahi hai..."
            BubbleVoiceState.SPEAKING -> "MAYA mic ON" to "MAYA bol rahi hai..."
        }
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_maya_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state != BubbleVoiceState.STOPPED) b.addAction(0, "Stop Listening", stopListen)
        b.addAction(0, "Band karo", stop)
        return b.build()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "maya_floating_bubble"
        private const val NOTIF_ID = 4101
        private const val ACTION_STOP = "com.maya.ai.overlay.STOP"
        private const val ACTION_STOP_LISTEN = "com.maya.ai.overlay.STOP_LISTEN"
        private const val PREFS = "maya_bubble"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        private const val MAX_HISTORY = 12
        private const val MAX_LISTEN_MS = 60_000
        private const val MAX_FAILED_ROUNDS = 5
        private const val QUIET_MS = 1_000
        /** Battery safety net: stops after this long without hearing anything. 0 = never. */
        private const val AUTO_STOP_SILENT_MS = 30 * 60_000L

        private val _running = MutableStateFlow(false)
        /** True while the bubble is on screen. */
        val running: StateFlow<Boolean> = _running

        private val _voiceState = MutableStateFlow(BubbleVoiceState.STOPPED)
        /** Real state of the bubble's microphone session. */
        val voiceState: StateFlow<BubbleVoiceState> = _voiceState

        /** Set by MainActivity: while the MAYA screen is visible, the app owns the microphone. */
        @Volatile var appVisible: Boolean = false

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }

        /** Stops only the microphone session; the bubble stays. */
        fun stopListening(context: Context) {
            if (_running.value) {
                context.startService(
                    Intent(context, FloatingBubbleService::class.java).setAction(ACTION_STOP_LISTEN)
                )
            }
        }
    }
}
