package org.schabi.newpipe.local.offline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * A donut chart of storage per source.
 *
 * Drawn directly instead of using a chart library: the app already ships no
 * charting dependency, and two or three slices need only arcs and a legend
 * that the surrounding screen supplies.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** One slice: a label, a byte count, and a colour. */
    data class Slice(val label: String, val bytes: Long, val color: Int)

    private var slices: List<Slice> = emptyList()
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private val density = resources.displayMetrics.density
    private val stroke = 34f * density
    private val holeColor = 0x00000000

    fun submit(next: List<Slice>) {
        slices = next
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = slices.sumOf { it.bytes }
        if (total <= 0L) return

        val size = min(width, height).toFloat()
        val pad = stroke / 2 + 2 * density
        val box = RectF(
            (width - size) / 2 + pad,
            (height - size) / 2 + pad,
            (width + size) / 2 - pad,
            (height + size) / 2 - pad
        )

        var start = -90f
        for (slice in slices) {
            val sweep = slice.bytes.toFloat() / total * 360f
            arcPaint.color = slice.color
            canvas.drawArc(
                box,
                start,
                sweep,
                false,
                arcPaint.apply {
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                }
            )
            start += sweep
        }

        // Centre label: total size, so the chart is readable on its own.
        labelPaint.color = 0xFFFFFFFF.toInt()
        labelPaint.textSize = 20f * density
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText(formatBytes(total), cx, cy + labelPaint.textSize / 3, labelPaint)
        labelPaint.color = 0xFF999999.toInt()
        labelPaint.textSize = 11f * density
        canvas.drawText("total", cx, cy + 22f * density, labelPaint)
    }
}
