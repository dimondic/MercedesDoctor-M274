package ru.mercedesdoctorai

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

object DashboardStyle {
    val background = Color.rgb(10, 15, 22)
    val surface = Color.rgb(20, 29, 40)
    val accent = Color.rgb(75, 224, 192)
    val muted = Color.rgb(145, 163, 182)
    fun dp(context: Context, size: Int) = (size * context.resources.displayMetrics.density).toInt()
    fun panel(context: Context, fill: Int = surface) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(context, 18).toFloat(); setStroke(dp(context, 1), Color.rgb(39, 54, 69))
    }
}

class SensorCard @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    private val title = label(12f, DashboardStyle.muted)
    private val number = label(30f, Color.WHITE)
    private val unit = label(12f, DashboardStyle.muted)
    private val status = label(11f, DashboardStyle.muted)
    init {
        orientation = VERTICAL; minimumHeight = DashboardStyle.dp(context, 156)
        val p = DashboardStyle.dp(context, 14); setPadding(p,p,p,p)
        background = DashboardStyle.panel(context)
        addView(title); addView(number); addView(unit); addView(status)
        isClickable = true; isFocusable = true
    }
    private fun label(size: Float, color: Int) = TextView(context).apply {
        textSize = size; setTextColor(color); gravity = Gravity.START
        setPadding(0, DashboardStyle.dp(context, 3), 0, DashboardStyle.dp(context, 3))
    }
    fun bind(sensor: ObdProtocol.Sensor, value: String, state: String, valid: Boolean) {
        title.text = sensor.label; number.text = value; unit.text = sensor.unit; status.text = state
        number.setTypeface(null, Typeface.BOLD)
        number.setTextColor(if (valid) DashboardStyle.accent else Color.WHITE)
        contentDescription = listOf(sensor.label, value, sensor.unit, state).joinToString(", ")
    }
}

/** A real RPM gauge: unknown/disconnected readings have no value arc or fabricated needle. */
class RpmGauge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var rpm: Double? = null
    private var state = "Нет связи"
    fun bind(value: Double?, status: String) {
        rpm = value; state = status
        contentDescription = "Обороты двигателя: ${value?.toInt() ?: "нет данных"}, $status"
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val radius = (width.coerceAtMost(height) / 2f - 24 * density).coerceAtLeast(1f)
        val cx = width / 2f; val cy = height / 2f
        bounds.set(cx-radius,cy-radius,cx+radius,cy+radius)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 10*density; paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(37, 53, 66); canvas.drawArc(bounds,150f,240f,false,paint)
        paint.color = Color.rgb(209, 111, 82); canvas.drawArc(bounds,345f,45f,false,paint)
        rpm?.let {
            paint.color = DashboardStyle.accent
            canvas.drawArc(bounds,150f,(it/8000.0*240).toFloat().coerceIn(0f,240f),false,paint)
        }
        paint.style = Paint.Style.FILL; paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT,Typeface.NORMAL)
        paint.textSize = 11*density; paint.color = DashboardStyle.muted
        for (i in 0..8) {
            val angle = Math.toRadians(150.0+i*30.0)
            val r=radius-20*density
            canvas.drawText(i.toString(),cx+cos(angle).toFloat()*r,cy+sin(angle).toFloat()*r+4*density,paint)
        }
        paint.textSize = 11*density; canvas.drawText("ОБОРОТЫ ДВИГАТЕЛЯ",cx,cy-26*density,paint)
        paint.color = Color.WHITE; paint.textSize = 48*density
        paint.typeface = Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
        canvas.drawText(rpm?.let { String.format(Locale.getDefault(),"%.0f",it) } ?: "—",cx,cy+23*density,paint)
        paint.textSize = 12*density; paint.color = DashboardStyle.muted
        canvas.drawText("об/мин · ×1000 на шкале",cx,cy+46*density,paint)
        paint.textSize = 11*density; canvas.drawText(state,cx,cy+radius-4*density,paint)
    }
}
