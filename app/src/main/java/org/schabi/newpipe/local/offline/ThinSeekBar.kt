package org.schabi.newpipe.local.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A seek bar drawn as one thin line with a small thumb.
 *
 * Android's SeekBar carries a large touch target and platform theming that
 * dominates a compact player bar. Drawing it directly keeps it to a few
 * pixels, which is what the reference player looks like, while still giving a
 * 32dp tall touch area for dragging.
 */
class ThinSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Buffered fraction, 0..1, drawn behind the played part. */
    var bufferedFraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Played fraction, 0..1. */
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            if (!dragging) invalidate()
        }

    /** Called while the user drags, with a 0..1 fraction. */
    var onSeek: ((Float) -> Unit)? = null

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0000.toInt() }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0000.toInt() }

    private val density = resources.displayMetrics.density
    private val barHeight = 3f * density
    private val thumbRadius = 5f * density
    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Fixed height so the bar never pushes the row taller, but enough to
        // touch comfortably.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            (32 * density).roundToInt()
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val startX = thumbRadius
        val endX = width - thumbRadius
        val span = max(endX - startX, 1f)

        canvas.drawRoundRect(
            startX,
            cy - barHeight / 2,
            endX,
            cy + barHeight / 2,
            barHeight / 2,
            barHeight / 2,
            trackPaint
        )
        if (bufferedFraction > 0f) {
            canvas.drawRoundRect(
                startX,
                cy - barHeight / 2,
                startX + span * bufferedFraction,
                cy + barHeight / 2,
                barHeight / 2,
                barHeight / 2,
                bufferedPaint
            )
        }
        if (fraction > 0f) {
            canvas.drawRoundRect(
                startX,
                cy - barHeight / 2,
                startX + span * fraction,
                cy + barHeight / 2,
                barHeight / 2,
                barHeight / 2,
                playedPaint
            )
        }
        canvas.drawCircle(startX + span * fraction, cy, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                fraction = fractionAt(event.x)
                onSeek?.invoke(fraction)
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun fractionAt(x: Float): Float {
        val startX = thumbRadius
        val span = max(width - 2 * thumbRadius, 1f)
        return ((x - startX) / span).coerceIn(0f, 1f)
    }
}
