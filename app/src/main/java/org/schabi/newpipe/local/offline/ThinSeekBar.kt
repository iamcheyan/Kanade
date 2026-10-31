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

    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }
    private val bufferedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4038BDF8.toInt() }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF38BDF8.toInt() }

    private val density = resources.displayMetrics.density
    private val barHeight = 3f * density
    private val thumbRadius = 5f * density

    /** True while the user is dragging the thumb. */
    val isDragging: Boolean get() = dragging

    private var dragging = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The expanded player asks for the full 32 dp touch target; the mini
        // bar passes an exact height so the strip stays visually thin.
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            MeasureSpec.getSize(heightMeasureSpec)
        } else {
            (32 * density).roundToInt()
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
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
