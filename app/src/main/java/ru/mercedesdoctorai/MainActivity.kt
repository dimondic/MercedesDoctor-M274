package ru.mercedesdoctorai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

/**
 * 0.4-style user interface (dark navy, mint controls, persistent four-tab navigation).
 * RFCOMM and all OBD interactions live exclusively in ObdService.
 */
class MainActivity : Activity() {
    private val bg = Color.rgb(9, 14, 24)
    private val surface = Color.rgb(23, 30, 42)
    private val muted = Color.rgb(153, 161, 178)
    private val accent = Color.rgb(69, 218, 190)
    private val white = Color.WHITE
    private val handler = Handler(Looper.getMainLooper())
    private var page = 3  // 0 Panel, 1 Trips, 2 Errors, 3 ELM327
    private lateinit var headerStatus: TextView
    private lateinit var versionTag: TextView
    private lateinit var body: LinearLayout
    private lateinit var menu: LinearLayout
    private var lastUiKey = ""
    private val refresh = object : Runnable {
        override fun run() {
            val s = ObdService.instance
            val confirmed = if (s?.connected == true) s.values.size else 0
            headerStatus.text = (s?.status ?: "ELM327 не подключён") +
                "\nПодтверждено параметров: " + confirmed + " из 10"
            val key = listOf(page, s?.status, s?.detail, s?.connected, s?.recording,
                s?.samples, s?.med40, s?.values?.entries?.sortedBy { it.key }?.toString()).joinToString("|")
            if (lastUiKey != key) {
                lastUiKey = key
                renderPage(s)
            }
            handler.postDelayed(this, 1200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        window.decorView.setBackgroundColor(bg)
        requestBtPermission()
        buildLayout()
        handler.post(refresh)
    }

    private fun background(color: Int, radius: Float = 10f, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (border != null) setStroke(dp(1), border)
        }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun text(value: String, sp: Float, color: Int = white, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = value
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    private fun space(parent: LinearLayout, h: Int) =
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h)))

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(18), dp(22), dp(18), dp(12))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("MERCEDES DOCTOR", 23f, white, true))
        space(names, 9)
        names.addView(text("E200  /  W212  /  M274", 13f, muted))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        versionTag = text("0.5", 16f, accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = background(surface, 24f, Color.rgb(54, 70, 86))
        }
        top.addView(versionTag)
        root.addView(top)
        space(root, 19)
        headerStatus = text("Проверка состояния ELM327...", 13f, muted)
        root.addView(headerStatus)
        space(root, 24)
        val scroll = ScrollView(this).apply {
            isFillViewport = false
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        root.addView(menu)
        setContentView(root)
        renderMenu()
        renderPage(ObdService.instance)
    }

    private fun renderMenu() {
        menu.removeAllViews()
        listOf("Панель", "Поездки", "Ошибки", "ELM327").forEachIndexed { i, label ->
            val selected = page == i
            val b = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12.5f
                setTextColor(if (selected) bg else muted)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                background = background(if (selected) accent else surface, 4f)
                setOnClickListener {
                    page = i
                    lastUiKey = ""
                    renderMenu()
                    renderPage(ObdService.instance)
                }
            }
            val margins = LinearLayout.LayoutParams(0, dp(55), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            }
            menu.addView(b, margins)
        }
    }

    private fun section(title: String, subtitle: String? = null) {
        body.addView(text(title, 26f, white, true))
        if (subtitle != null) {
            space(body, 10)
            body.addView(text(subtitle, 15f, muted))
        }
        space(body, 23)
    }
    private fun note(value: String, color: Int = muted, fontSize: Float = 15f) {
        body.addView(text(value, fontSize, color))
        space(body, 16)
    }
    private fun action(label: String, operation: () -> Unit) {
        val button = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(52)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = background(surface, 4f)
            setOnClickListener { operation() }
        }
        body.addView(button, LinearLayout.LayoutParams(-1, dp(54)))
        space(body, 13)
    }
    private fun chipRow(label: String, value: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = background(surface, 6f)
            setPadding(dp(12), dp(14), dp(12), dp(14))
        }
        line.addView(text(label, 14f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(value, 15f, accent, true))
        body.addView(line)
        space(body, 9)
    }

    private fun renderPage(service: ObdService?) {
        if (!::body.isInitialized) return
        body.removeAllViews()
        when (page) {
            0 -> {
                section("Панель приборов", "Данные двигателя в реальном времени")
                if (service?.connected != true) note("Нет подтверждённого обмена с ЭБУ.\nПодключите ELM327 на вкладке «ELM327».")
                else if (service.values.isEmpty()) note("ЭБУ отвечает, но параметры ещё не получены.", accent)
                val labels = listOf("Обороты", "ОЖ", "Воздух", "MAP", "Дроссель",
                    "STFT", "LTFT", "Напряжение ECU", "Скорость", "Нагрузка")
                labels.forEach { label ->
                    chipRow(label, service?.values?.get(label) ?: "—")
                }
                note("Показываются только полученные ответы OBD-II. Отсутствующие значения не подменяются нулями.", muted, 13f)
            }
            1 -> {
                section("Поездки", "Запись измерений и отчёты для анализа")
                val running = service?.recording == true
                note(if (running) "Запись выполняется · измерений: " + (service?.samples ?: 0)
                else "Запись остановлена · измерений: " + (service?.samples ?: 0), accent, 22f)
                note("Каждое измерение сохраняется со временем и доступными показателями двигателя.")
                action(if (running) "Остановить запись поездки" else "Начать запись поездки") {
                    if (ObdService.instance?.connected != true) {
                        toast("Сначала подтвердите связь с ЭБУ")
                    } else send(ObdService.RECORD)
                }
                action("История поездок") { showHistory() }
                note("ЭКСПОРТ", accent, 14f)
                action("Таблица измерений · CSV") { showHistory() }
                action("ZIP-отчёт для ChatGPT · доступен CSV") { showHistory() }
                note("Запись через службу Android продолжается при сворачивании приложения; не завершайте службу принудительно.", muted, 13f)
            }
            2 -> {
                section("Ошибки двигателя", "Стандартные коды неисправностей · Mode 03")
                note(service?.detail ?: "Данных диагностического обмена пока нет", accent, 18f)
                action("Прочитать ошибки OBD-II") {
                    if (service?.connected == true) {
                        send(ObdService.DTC)
                        toast("Запрос ошибок отправлен в очередь")
                    } else toast("Сначала подключите ЭБУ")
                }
                action("Экспорт журнала диагностики") { shareTrace() }
                note("Это только стандартный OBD-II, а не полный скан блоков Mercedes.\nОчистка ошибок и изменение настроек автомобиля заблокированы.", muted, 14f)
            }
            else -> {
                section("Подключение", "ELM327 · Bluetooth Classic / SPP")
                val info = service?.status ?: "Адаптер не подключён"
                note(info + "\nПодтверждено параметров: " +
                    (if (service?.connected == true) service.values.size else 0) + " из 10", accent, 19f)
                action("Выбрать и подключить ELM327") { chooseDevice() }
                action("Подключить сохранённый адаптер") { send(ObdService.CONNECT) }
                action("Отключить адаптер") { send(ObdService.DISCONNECT) }
                val remembered = getSharedPreferences("elm", MODE_PRIVATE).getString("mac", null)
                if (remembered != null) note("Сопряжённый адаптер сохранён: " + remembered, muted, 12f)
                val switch = Switch(this).apply {
                    text = "Автоподключение и восстановление связи"
                    textSize = 14f
                    setTextColor(white)
                    isChecked = getSharedPreferences("elm", MODE_PRIVATE).getBoolean("auto", true)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("elm", MODE_PRIVATE).edit().putBoolean("auto", checked).apply()
                    }
                }
                body.addView(switch)
                space(body, 15)
                action("Диагностика и экспорт журнала") { shareTrace() }
                note("MED40 · ПРОБНЫЙ РЕЖИМ ТОЛЬКО ЧТЕНИЯ", accent, 14f)
                action(if (service?.med40 == true) "Выключить пробный MED40" else "Включить пробный MED40") {
                    if (service?.connected == true) send(ObdService.MED40)
                    else toast("Сначала подтвердите связь с ЭБУ")
                }
                note("MED40 не предоставляет кодирование, адаптации или запись в ЭБУ.\n" +
                    "Сопрягите ELM327 в настройках Android и разрешите доступ к устройствам поблизости.", muted, 13f)
            }
        }
    }

    private fun send(action: String, mac: String? = null) {
        val intent = Intent(this, ObdService::class.java).apply {
            this.action = action
            if (mac != null) putExtra(ObdService.DEVICE, mac)
        }
        if (action == ObdService.CONNECT) startForegroundService(intent)
        else if (ObdService.instance != null) startService(intent)
        else toast("Сначала подключите ELM327")
    }
    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 101)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
        }
    }
    private fun chooseDevice() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) {
                toast("Включите Bluetooth")
                return
            }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) {
                toast("Сначала сопрягите ELM327 в настройках Bluetooth Android")
                return
            }
            AlertDialog.Builder(this).setTitle("Сопряжённые Bluetooth-устройства")
                .setItems(devices.map { (it.name ?: "Bluetooth") + "  " + it.address }.toTypedArray()) {
                    _, index -> send(ObdService.CONNECT, devices[index].address)
                }.show()
        } catch (_: SecurityException) {
            requestBtPermission()
            toast("Разрешите доступ к Bluetooth")
        }
    }
    private fun showHistory() {
        val files = ObdService.instance?.history() ?: emptyList()
        if (files.isEmpty()) {
            toast("Записей пока нет")
            return
        }
        AlertDialog.Builder(this).setTitle("История поездок")
            .setItems(files.map { it.name + " · " + (it.length() / 1024) + " КБ" }.toTypedArray()) {
                _, i -> share(files[i])
            }.show()
    }
    private fun shareTrace() {
        val file = ObdService.instance?.trace()
        if (file != null) share(file) else toast("Журнал пока пуст")
    }
    private fun share(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(this,
                packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.extension == "txt") "text/plain" else "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Отправить журнал / CSV"))
        } catch (e: Exception) { toast("Ошибка экспорта: " + e.message) }
    }
    private fun toast(value: String) =
        Toast.makeText(this, value, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }
}
