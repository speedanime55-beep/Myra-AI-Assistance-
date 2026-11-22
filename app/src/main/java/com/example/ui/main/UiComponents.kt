package com.example.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.R
import kotlin.math.PI
import kotlin.math.sin

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val BAR_COUNT = 20
        private const val LERP_FACTOR = 0.3f
    }

    private val barHeights = FloatArray(BAR_COUNT) { 0.12f }
    private var currentAmplitude = 0f
    private var phase = 0f
    private var isAnimating = false

    private val barRect = RectF()
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = (phase + 0.22f) % (2f * PI.toFloat())
            updateBars()
            invalidate()
        }
    }

    fun startAnimation() {
        isAnimating = true
        if (!animator.isRunning) {
            animator.start()
        }
    }

    fun stopAnimation() {
        isAnimating = false
        currentAmplitude = 0f
        if (animator.isRunning) {
            animator.cancel()
        }
        for (i in barHeights.indices) {
            barHeights[i] = 0.1f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
        if (!isAnimating && currentAmplitude > 0.02f) {
            startAnimation()
        }
    }

    private fun updateBars() {
        for (i in 0 until BAR_COUNT) {
            val centerDist = 1f - kotlin.math.abs((i - (BAR_COUNT - 1) / 2f) / (BAR_COUNT / 2f))
            val wave = ((sin(phase + i * 0.55f) + 1f) * 0.5f)
            val baseLevel = if (isAnimating) 0.12f + 0.08f * wave else 0.08f
            val target = (baseLevel + currentAmplitude * (0.35f + 0.65f * centerDist) * (0.45f + 0.55f * wave))
                .coerceIn(0.08f, 1f)
            barHeights[i] += (target - barHeights[i]) * LERP_FACTOR
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slotWidth = w / BAR_COUNT
        val barWidth = slotWidth * 0.52f
        val corner = barWidth / 2f
        val centerY = h / 2f

        for (i in 0 until BAR_COUNT) {
            val normalized = barHeights[i].coerceIn(0.08f, 1f)
            val barH = h * normalized
            val left = i * slotWidth + (slotWidth - barWidth) / 2f
            val top = centerY - barH / 2f
            val bottom = centerY + barH / 2f

            val alpha = (150 + (normalized * 105f)).toInt().coerceIn(150, 255)
            barPaint.color = Color.argb(alpha, 0xFF, 0x17, 0x44)

            barRect.set(left, top, left + barWidth, bottom)
            canvas.drawRoundRect(barRect, corner, corner, barPaint)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_MYRA = 2
    }

    private val messages = mutableListOf<ChatMessage>()

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val chatText: TextView = view.findViewById(R.id.chatText)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_MYRA
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layoutRes = if (viewType == VIEW_TYPE_USER) {
            R.layout.item_chat_user
        } else {
            R.layout.item_chat_myra
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        holder.chatText.text = messages[position].text
    }

    override fun getItemCount(): Int = messages.size

    fun lastMyraText(): String? {
        return messages.lastOrNull { !it.isUser }?.text
    }

    fun addMessage(message: ChatMessage): Boolean {
        val cleaned = message.text.trim()
        if (cleaned.isEmpty()) return false
        if (!message.isUser && lastMyraText() == cleaned) {
            return false
        }
        messages.add(message.copy(text = cleaned))
        notifyItemInserted(messages.size - 1)
        return true
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        if (size > 0) notifyItemRangeRemoved(0, size)
    }
}
