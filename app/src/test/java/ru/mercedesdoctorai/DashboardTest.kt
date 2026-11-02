package ru.mercedesdoctorai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardTest {
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    @Test fun dashboardLaunchesWithoutHardwareAndTabsSwitchPages() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val views = all(activity.window.decorView)
            assertEquals("0.4", (views.first { it.tag == "app-version" } as TextView).text.toString())
            assertEquals(10, views.filterIsInstance<SensorCard>().size)
            assertTrue(views.filterIsInstance<RpmGauge>().single().isShown)
            for (index in 1..3) {
                views.first { it.tag == "tab-$index" }.performClick()
                assertTrue(views.first { it.tag == "tab-$index" }.isSelected)
                assertFalse(views.filterIsInstance<RpmGauge>().single().isShown)
            }
            views.first { it.tag == "tab-0" }.performClick()
            assertTrue(views.filterIsInstance<RpmGauge>().single().isShown)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Нет связи" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun renderRealDashboardAtPhoneSize() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val view = controller.get().window.decorView
            view.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY))
            view.layout(0,0,432,936)
            val image = Bitmap.createBitmap(432,936,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val target = File("build/reports/dashboard-v0.4.png")
            target.parentFile?.mkdirs()
            target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            assertTrue(target.length() > 0)
        } finally { controller.pause().stop().destroy() }
    }
}
