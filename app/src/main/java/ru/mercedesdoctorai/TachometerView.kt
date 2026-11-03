package ru.mercedesdoctorai

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Drawing only. Never generates or guesses an ECU value. */
class TachometerView(context: Context) : View(context) {
    var rpm: Int? = null
        set(value) {
            field = value?.coerceIn(0, 8000)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mint = Color.rgb(69, 218, 190)
    private val white = Color.WHITE
    private val muted = Color.rgb(151, 166, 185)
    private val edge = Color.rgb(41, 58, 76)
    private val danger = Color.rgb(249, 108, 97)
    private val bg = Color.rgb(18, 27, 40)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec)
        val height = (available * 0.60f).toInt().coerceIn(dp(165), dp(255))
        setMeasuredDimension(available, height)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.51f
        val radius = min(width * 0.40f, height * 0.46f)
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        paint.style = Paint.Style.FILL
        paint.color = bg
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), dp(16).toFloat(), dp(16).toFloat(), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = dp(9).toFloat()
        paint.color = edge
        canvas.drawArc(box, 150f, 240f, false, paint)
        paint.color = danger
        canvas.drawArc(box, 150f + (240f * 0.80f), 240f * 0.20f, false, paint)
        val value = rpm
        if (value != null) {
            paint.color = mint
            canvas.drawArc(box, 150f, 240f * (value / 8000f), false, paint)
        }

        for (i in 0..8) {
            val angle = Math.toRadians((150.0 + i * 30.0))
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            val from = radius * 0.80f
            val to = radius * 0.90f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = if (i >= 7) danger else muted
            canvas.drawLine(cx + dx * from, cy + dy * from, cx + dx * to, cy + dy * to, paint)
            paint.style = Paint.Style.FILL
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = android.graphics.Typeface.DEFAULT
            paint.textSize = dp(12).toFloat()
            val labelRad = radius * 0.66f
            canvas.drawText(i.toString(), cx + dx * labelRad, cy + dy * labelRad + dp(4), paint)
        }

        if (value != null) {
            val angle = Math.toRadians(150.0 + 240.0 * (value / 8000.0))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = mint
            canvas.drawLine(cx, cy,
                cx + cos(angle).toFloat() * radius * 0.57f,
                cy + sin(angle).toFloat() * radius * 0.57f, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, dp(5).toFloat(), paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = white
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(25).toFloat()
        canvas.drawText(value?.toString() ?: "—", cx, height * 0.84f, paint)
        paint.color = muted
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = dp(12).toFloat()
        canvas.drawText("ОБ/МИН  ·  ×1000", cx, height * 0.95f, paint)
    }
}
