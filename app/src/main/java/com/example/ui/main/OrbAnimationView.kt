package com.example.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class OrbAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class OrbState {
        IDLE,
        LISTENING,
        SPEAKING,
        THINKING,
        ACTIVE
    }

    private var state: OrbState = OrbState.IDLE
    private var pulseScale = 1f
    private var glowAlpha = 140
    private var rotationAngle = 0f
    private var waveOffset = 0f
    private var thinkingAngle = 0f
    private var amplitude = 0f
    private var smoothedAmplitude = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val thinkingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val wavePath = Path()
    private val arcRect = RectF()

    private val dash1 = DashPathEffect(floatArrayOf(28f, 18f), 0f)
    private val dash2 = DashPathEffect(floatArrayOf(18f, 22f, 8f, 16f), 0f)
    private val dash3 = DashPathEffect(floatArrayOf(38f, 24f), 0f)

    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { anim ->
            val v = anim.animatedValue as Float
            pulseScale = 1f + v * 0.15f
            glowAlpha = (120 + (v * 100)).toInt()
            invalidate()
        }
    }

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            rotationAngle = anim.animatedValue as Float
            smoothedAmplitude += (amplitude - smoothedAmplitude) * 0.25f
            val waveStep = if (state == OrbState.SPEAKING) 0.22f else 0.12f
            waveOffset = (waveOffset + waveStep) % (2f * PI.toFloat())
            invalidate()
        }
    }

    private val thinkingAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            thinkingAngle = anim.animatedValue as Float
            invalidate()
        }
    }

    init {
        startAnimatorsForState()
    }

    fun setOrbState(newState: OrbState) {
        if (state == newState) return
        state = newState
        startAnimatorsForState()
        invalidate()
    }

    fun getOrbState(): OrbState = state

    fun setAmplitude(rms: Float) {
        amplitude = rms.coerceIn(0f, 1f)
    }

    private fun startAnimatorsForState() {
        if (!pulseAnimator.isRunning) pulseAnimator.start()
        if (!rotationAnimator.isRunning) rotationAnimator.start()
        if (state == OrbState.THINKING) {
            if (!thinkingAnimator.isRunning) thinkingAnimator.start()
        } else {
            if (thinkingAnimator.isRunning) thinkingAnimator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimatorsForState()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator.cancel()
        rotationAnimator.cancel()
        thinkingAnimator.cancel()
    }

    private fun stateColors(): Pair<Int, Int> {
        return when (state) {
            OrbState.IDLE -> Color.parseColor("#B71C1C") to Color.parseColor("#880E4F")
            OrbState.LISTENING, OrbState.ACTIVE ->
                Color.parseColor("#FF1744") to Color.parseColor("#D500F9")
            OrbState.SPEAKING ->
                Color.parseColor("#E040FB") to Color.parseColor("#FF1744")
            OrbState.THINKING ->
                Color.parseColor("#40C4FF") to Color.parseColor("#00B0FF")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val minDim = min(width, height).toFloat()
        if (minDim <= 0f) return

        val baseRadius = minDim * 0.27f
        val dynamicBoost = 1f + smoothedAmplitude * 0.18f
        val coreRadius = baseRadius * pulseScale * dynamicBoost
        val (primaryColor, secondaryColor) = stateColors()

        // 1. Radial glow (1.6x radius)
        val glowRadius = coreRadius * 1.6f
        glowPaint.shader = RadialGradient(
            cx,
            cy,
            glowRadius,
            intArrayOf(
                applyAlpha(primaryColor, glowAlpha),
                applyAlpha(secondaryColor, glowAlpha / 3),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        // 2. Core orb (RadialGradient sphere illusion)
        corePaint.shader = RadialGradient(
            cx - coreRadius * 0.25f,
            cy - coreRadius * 0.25f,
            coreRadius * 1.25f,
            intArrayOf(primaryColor, secondaryColor, Color.parseColor("#16020A")),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreRadius, corePaint)

        // 3. 3 rotating dashed rings
        val ringsActive = state != OrbState.IDLE
        val ringAlpha = if (ringsActive) 200 else 75
        drawDashedRing(canvas, cx, cy, coreRadius * 1.16f, rotationAngle, 3.2f, dash1, applyAlpha(primaryColor, ringAlpha))
        drawDashedRing(canvas, cx, cy, coreRadius * 1.30f, -rotationAngle * 1.35f, 2.4f, dash2, applyAlpha(secondaryColor, (ringAlpha * 0.85f).toInt()))
        drawDashedRing(canvas, cx, cy, coreRadius * 1.44f, rotationAngle * 0.7f, 2.0f, dash3, applyAlpha(primaryColor, (ringAlpha * 0.65f).toInt()))

        // 4. Wave rings (sine waves, amplitude-reactive)
        if (state == OrbState.LISTENING || state == OrbState.SPEAKING || state == OrbState.ACTIVE) {
            drawWaveRing(canvas, cx, cy, coreRadius * 1.09f, primaryColor, 6, 1.0f)
            drawWaveRing(canvas, cx, cy, coreRadius * 1.22f, secondaryColor, 9, -1.2f)
        }

        // 5. Thinking arc (2 spinning arcs, only in THINKING state)
        if (state == OrbState.THINKING) {
            val arcR = coreRadius * 1.28f
            arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
            thinkingPaint.color = Color.parseColor("#40C4FF")
            canvas.drawArc(arcRect, thinkingAngle, 95f, false, thinkingPaint)
            thinkingPaint.color = Color.parseColor("#00B0FF")
            canvas.drawArc(arcRect, thinkingAngle + 180f, 95f, false, thinkingPaint)
        }

        // 6. Particles (12 dots orbiting, only when active/speaking/listening)
        if (state == OrbState.ACTIVE || state == OrbState.SPEAKING || state == OrbState.LISTENING) {
            val particleCount = 12
            val orbitBase = coreRadius * (1.34f + smoothedAmplitude * 0.22f)
            for (i in 0 until particleCount) {
                val angleDeg = rotationAngle * (if (state == OrbState.SPEAKING) 1.8f else 1.1f) + i * (360f / particleCount)
                val rad = Math.toRadians(angleDeg.toDouble())
                val wobble = sin(waveOffset * 2.0 + i).toFloat() * (6f + smoothedAmplitude * 14f)
                val px = cx + (orbitBase + wobble) * cos(rad).toFloat()
                val py = cy + (orbitBase + wobble) * sin(rad).toFloat()
                val pRadius = (3.5f + smoothedAmplitude * 4f) * (if (i % 2 == 0) 1f else 0.7f)
                particlePaint.color = applyAlpha(if (i % 2 == 0) primaryColor else secondaryColor, 220)
                canvas.drawCircle(px, py, pRadius, particlePaint)
            }
        }

        // 7. Inner highlight (white radial gradient, top-left position)
        val hlX = cx - coreRadius * 0.34f
        val hlY = cy - coreRadius * 0.34f
        val hlR = coreRadius * 0.55f
        highlightPaint.shader = RadialGradient(
            hlX,
            hlY,
            hlR,
            intArrayOf(Color.argb(150, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(hlX, hlY, hlR, highlightPaint)
    }

    private fun drawDashedRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angle: Float,
        stroke: Float,
        effect: DashPathEffect,
        color: Int
    ) {
        canvas.save()
        canvas.rotate(angle, cx, cy)
        ringPaint.color = color
        ringPaint.strokeWidth = stroke
        ringPaint.pathEffect = effect
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.restore()
    }

    private fun drawWaveRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        lobes: Int,
        phaseDir: Float
    ) {
        wavePath.reset()
        val steps = 72
        val waveAmp = 5f + smoothedAmplitude * (if (state == OrbState.SPEAKING) 26f else 16f)
        for (i in 0..steps) {
            val theta = (2.0 * PI * i / steps).toFloat()
            val r = radius + sin(theta * lobes + waveOffset * phaseDir) * waveAmp
            val x = cx + r * cos(theta)
            val y = cy + r * sin(theta)
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        wavePath.close()
        wavePaint.color = applyAlpha(color, 185)
        canvas.drawPath(wavePath, wavePaint)
    }

    private fun applyAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }
}
