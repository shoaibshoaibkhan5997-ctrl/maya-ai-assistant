package com.maya.ai.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.PI
import kotlin.math.sin

/** Real assistant state, driven by the existing voice system. */
enum class AvatarState { IDLE, LISTENING, THINKING, SPEAKING }

private val Neon = Color(0xFF3EA6FF)
private val NeonSoft = Color(0xFF6CB4FF)
private const val TWO_PI = (2.0 * PI).toFloat()

/**
 * Lightweight vector avatar. [phase] is a 0..1 looping timer; it is only read while drawing,
 * so animating it does not recompose anything. Replace this composable (see the `avatar`
 * parameter of HomeScreen) with a real 3D view later without touching the rest of the screen.
 */
@Composable
fun MayaAvatar(state: AvatarState, phase: State<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        drawAvatar(state, phase.value)
    }
}

private fun DrawScope.drawAvatar(state: AvatarState, p: Float) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height / 2f
    val k = when (state) {
        AvatarState.LISTENING -> 3
        AvatarState.SPEAKING -> 5
        AvatarState.THINKING -> 2
        AvatarState.IDLE -> 1
    }
    val pulse = 0.5f + 0.5f * sin(p * TWO_PI * k)

    drawHalo(s, cx, cy, p, state, pulse)

    val clip = Path().apply { addOval(Rect(Offset(cx, cy), s * 0.43f)) }
    val bob = sin(p * TWO_PI) * s * 0.010f
    clipPath(clip) {
        translate(top = bob) { drawCharacter(s, cx, cy, p, state) }
    }
}

private fun DrawScope.drawHalo(s: Float, cx: Float, cy: Float, p: Float, state: AvatarState, pulse: Float) {
    val r = s * 0.44f
    val c = Offset(cx, cy)
    val glow = if (state == AvatarState.IDLE) 0.55f else 0.7f + 0.3f * pulse

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Neon.copy(alpha = 0.22f * glow), Color.Transparent),
            center = c,
            radius = r * 1.15f,
        ),
        radius = r * 1.15f,
        center = c,
    )
    drawCircle(Neon.copy(alpha = 0.10f * glow), r + s * 0.030f, c, style = Stroke(s * 0.040f))
    drawCircle(Neon.copy(alpha = 0.18f * glow), r + s * 0.012f, c, style = Stroke(s * 0.024f))
    drawCircle(NeonSoft.copy(alpha = 0.95f), r, c, style = Stroke(s * 0.010f))
    drawCircle(Neon.copy(alpha = 0.35f), r * 0.93f, c, style = Stroke(s * 0.004f))

    val spin = if (state == AvatarState.THINKING) 3f else 1f
    drawArc(
        color = Color.White.copy(alpha = 0.85f),
        startAngle = p * 360f * spin,
        sweepAngle = 70f,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = Size(2f * r, 2f * r),
        style = Stroke(s * 0.014f, cap = StrokeCap.Round),
    )
}

private fun DrawScope.drawCharacter(s: Float, cx: Float, cy: Float, p: Float, state: AvatarState) {
    val hair = Color(0xFF150F22)
    val hairHi = Color(0xFF2B2145)
    val skin = Color(0xFFC98C64)
    val skinShade = Color(0xFFB27850)
    val outfit = Color(0xFF0C1022)
    val ink = Color(0xFF1B1216)

    // long hair behind the head
    drawRoundRect(hair, Offset(cx - 0.20f * s, cy - 0.12f * s), Size(0.40f * s, 0.46f * s), CornerRadius(0.14f * s))

    // shoulders / dark outfit
    val shoulders = Path().apply {
        moveTo(cx - 0.50f * s, cy + 0.50f * s)
        cubicTo(cx - 0.50f * s, cy + 0.26f * s, cx - 0.26f * s, cy + 0.24f * s, cx - 0.08f * s, cy + 0.20f * s)
        lineTo(cx + 0.08f * s, cy + 0.20f * s)
        cubicTo(cx + 0.26f * s, cy + 0.24f * s, cx + 0.50f * s, cy + 0.26f * s, cx + 0.50f * s, cy + 0.50f * s)
        close()
    }
    drawPath(shoulders, outfit)
    drawPath(shoulders, Neon.copy(alpha = 0.35f), style = Stroke(s * 0.006f))

    // glowing "M" on the chest
    val mx1 = cx - 0.065f * s
    val mx2 = cx + 0.065f * s
    val my1 = cy + 0.30f * s
    val my2 = cy + 0.395f * s
    val m = Path().apply {
        moveTo(mx1, my2)
        lineTo(mx1, my1)
        lineTo(cx, my1 + 0.055f * s)
        lineTo(mx2, my1)
        lineTo(mx2, my2)
    }
    drawPath(m, Neon.copy(alpha = 0.22f), style = Stroke(0.040f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(m, Neon.copy(alpha = 0.55f), style = Stroke(0.022f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(m, Color(0xFFDFF1FF), style = Stroke(0.009f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))

    // neck
    drawRoundRect(skinShade, Offset(cx - 0.045f * s, cy + 0.08f * s), Size(0.09f * s, 0.14f * s), CornerRadius(0.03f * s))

    // hair locks falling in front of the shoulders
    drawRoundRect(hair, Offset(cx - 0.215f * s, cy + 0.02f * s), Size(0.09f * s, 0.30f * s), CornerRadius(0.045f * s))
    drawRoundRect(hair, Offset(cx + 0.125f * s, cy + 0.02f * s), Size(0.09f * s, 0.30f * s), CornerRadius(0.045f * s))
    drawRoundRect(hairHi, Offset(cx - 0.19f * s, cy + 0.04f * s), Size(0.012f * s, 0.20f * s), CornerRadius(0.006f * s))
    drawRoundRect(hairHi, Offset(cx + 0.178f * s, cy + 0.04f * s), Size(0.012f * s, 0.20f * s), CornerRadius(0.006f * s))

    // head
    drawOval(skin, Offset(cx - 0.115f * s, cy - 0.18f * s), Size(0.23f * s, 0.30f * s))

    // futuristic earpieces
    for (side in listOf(-1f, 1f)) {
        val ex = cx + side * 0.118f * s
        drawCircle(Neon.copy(alpha = 0.30f), 0.026f * s, Offset(ex, cy - 0.03f * s))
        drawCircle(Neon, 0.014f * s, Offset(ex, cy - 0.03f * s))
        drawCircle(Color.White, 0.005f * s, Offset(ex, cy - 0.03f * s))
    }

    // front hair with a side sweep
    val fringe = Path().apply {
        moveTo(cx - 0.125f * s, cy - 0.02f * s)
        cubicTo(cx - 0.14f * s, cy - 0.20f * s, cx - 0.04f * s, cy - 0.215f * s, cx, cy - 0.20f * s)
        cubicTo(cx + 0.06f * s, cy - 0.215f * s, cx + 0.14f * s, cy - 0.20f * s, cx + 0.125f * s, cy - 0.02f * s)
        cubicTo(cx + 0.11f * s, cy - 0.10f * s, cx + 0.05f * s, cy - 0.145f * s, cx - 0.01f * s, cy - 0.14f * s)
        cubicTo(cx - 0.06f * s, cy - 0.13f * s, cx - 0.11f * s, cy - 0.09f * s, cx - 0.125f * s, cy - 0.02f * s)
        close()
    }
    drawPath(fringe, hair)

    // eyes (blink once per loop)
    val blink = if (p > 0.93f && p < 0.955f) 0.15f else 1f
    for (side in listOf(-1f, 1f)) {
        val ex = cx + side * 0.045f * s
        val ey = cy - 0.045f * s
        val w = 0.036f * s
        val h = 0.026f * s * blink
        drawOval(ink, Offset(ex - w / 2f, ey - h / 2f), Size(w, h))
        if (blink == 1f) drawCircle(Color.White.copy(alpha = 0.9f), 0.005f * s, Offset(ex + 0.006f * s, ey - 0.005f * s))
        drawArc(
            color = ink, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(ex - 0.026f * s, ey - 0.052f * s), size = Size(0.052f * s, 0.03f * s),
            style = Stroke(0.006f * s, cap = StrokeCap.Round),
        )
        drawCircle(Color(0xFFFF9AA8).copy(alpha = 0.12f), 0.02f * s, Offset(cx + side * 0.07f * s, cy + 0.005f * s))
    }

    // mouth: moves only while MAYA is actually speaking
    val mouthY = cy + 0.055f * s
    if (state == AvatarState.SPEAKING) {
        val open = 0.5f + 0.5f * sin(p * TWO_PI * 8f)
        val h = 0.006f * s + 0.016f * s * open
        drawOval(Color(0xFF5A1F2B), Offset(cx - 0.02f * s, mouthY - h / 2f), Size(0.04f * s, h))
    } else {
        drawArc(
            color = Color(0xFF7A2E3A), startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(cx - 0.028f * s, mouthY - 0.02f * s), size = Size(0.056f * s, 0.03f * s),
            style = Stroke(0.006f * s, cap = StrokeCap.Round),
        )
    }
}
