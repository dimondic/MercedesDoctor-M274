package ru.mercedesdoctorai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

class SensorGraph @JvmOverloads constructor(context: Context, private val samples: List<ElmConnection.Sample> = emptyList(), private val unit: String = "") : View(context) {
    var windowSeconds = 180
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val end = samples.lastOrNull()?.time ?: return
        val start = end - windowSeconds * 1000L
        val visible = samples.filter { it.time >= start }
        val points = visible.filter { it.state == ObdProtocol.State.OK && it.value != null }
        if (points.isEmpty()) return
        val min = points.minOf { it.value!! }
        val max = points.maxOf { it.value!! }
        val range = (max - min).coerceAtLeast(1.0)
        val left = 20f; val top = 60f; val bottom = height - 45f; val right = width - 20f
        paint.color = Color.LTGRAY; paint.textSize = 28f
        canvas.drawText(String.format(Locale.getDefault(), "min %.2f · max %.2f %s", min, max, unit), left, 32f, paint)
        paint.color = Color.DKGRAY; paint.strokeWidth = 2f
        canvas.drawLine(left, bottom, right, bottom, paint)
        paint.color = Color.CYAN; paint.strokeWidth = 4f; paint.style = Paint.Style.STROKE
        path.reset(); var previous: Long? = null
        visible.forEach { point ->
            val value = point.value
            if (point.state != ObdProtocol.State.OK || value == null) { previous = null; return@forEach }
            val x = left + (right - left) * (point.time - start).toFloat() / (windowSeconds * 1000f)
            val y = bottom - ((value - min) / range).toFloat() * (bottom - top)
            if (previous == null || point.time - previous!! > 15000) path.moveTo(x, y) else path.lineTo(x, y)
            previous = point.time
        }
        canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
        points.forEach {
            val x = left + (right - left) * (it.time - start).toFloat() / (windowSeconds * 1000f)
            val y = bottom - ((it.value!! - min) / range).toFloat() * (bottom - top)
            canvas.drawCircle(x, y, 4f, paint)
        }
        paint.color = Color.LTGRAY; paint.textSize = 24f
        canvas.drawText("−${windowSeconds} с                                   Последнее измерение", left, height - 12f, paint)
    }
}
