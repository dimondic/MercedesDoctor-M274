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
import android.view.WindowInsets
import android.view.WindowManager
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import android.content.ClipData

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
    private var page = 0  // 0 Panel, 1 Trips, 2 Errors, 3 ELM327
    private lateinit var headerStatus: TextView
    private lateinit var versionTag: TextView
    private lateinit var body: LinearLayout
    private lateinit var menu: LinearLayout
    private var lastUiKey = ""
    private var rpmGauge: TachometerView? = null
    private val liveValueViews = mutableMapOf<String, TextView>()
    private var connectionLabel: TextView? = null
    private var tripLabel: TextView? = null
    private var errorsLabel: TextView? = null
    private lateinit var scrollView: ScrollView
    private val refresh = object : Runnable {
        override fun run() {
            val service = ObdService.instance
            val confirmed = if (service?.connected == true) service.values.size else 0
            headerStatus.text = (service?.status ?: "ELM327 не подключён") +
                "  ·  PID: " + confirmed + "/10"
            val key = listOf(page, service?.connected, service?.recording,
                service?.med40, service?.status?.startsWith("Нет данных ЭБУ")).joinToString("|")
            if (key != lastUiKey) {
                lastUiKey = key
                renderPage(service)
            }
            updateLive(service)
            handler.postDelayed(this, 900)
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
        applyScreenMode()
        buildLayout()
        if (ObdService.instance == null &&
            getSharedPreferences("elm", MODE_PRIVATE).getBoolean("auto", true) &&
            getSharedPreferences("elm", MODE_PRIVATE).contains("mac")) {
            send(ObdService.CONNECT)
        }
        handler.post(refresh)
    }

    private fun background(color: Int, radius: Float = 10f, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * resources.displayMetrics.density
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
            setPadding(dp(14), dp(10), dp(14), dp(8))
        }
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(14), dp(10) + bars.top, dp(14),
                    dp(8) + bars.bottom)
                insets
            }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text("MERCEDES DOCTOR", 20f, white, true))
        space(names, 3)
        names.addView(text("E200  /  W212  /  M274", 13f, muted))
        top.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        versionTag = text("0.6", 14f, accent).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = background(surface, 24f, Color.rgb(54, 70, 86))
        }
        top.addView(versionTag)
        root.addView(top)
        space(root, 10)
        headerStatus = text("Проверка состояния ELM327...", 13f, muted)
        root.addView(headerStatus)
        space(root, 8)
        scrollView = ScrollView(this).apply {
            isFillViewport = false
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        scrollView.addView(body)
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
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
            val margins = LinearLayout.LayoutParams(0, dp(49), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            }
            menu.addView(b, margins)
        }
    }

    private fun section(title: String, subtitle: String? = null) {
        body.addView(text(title, 23f, white, true))
        if (subtitle != null) {
            space(body, 3)
            body.addView(text(subtitle, 13f, muted))
        }
        space(body, 12)
    }
    private fun note(value: String, color: Int = muted, fontSize: Float = 15f) {
        body.addView(text(value, fontSize, color))
        space(body, 10)
    }
    private fun action(label: String, operation: () -> Unit) {
        val button = TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(accent)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(42)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = background(surface, 4f)
            setOnClickListener { operation() }
        }
        body.addView(button, LinearLayout.LayoutParams(-1, dp(44)))
        space(body, 7)
    }
    private fun twoActions(left: String, leftOp: () -> Unit,
                           right: String, rightOp: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(label: String, op: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(5), dp(5), dp(5), dp(5))
                background = background(surface, 7f)
                setOnClickListener { op() }
            }
            row.addView(button, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(3); marginEnd = dp(3)
            })
        }
        add(left, leftOp); add(right, rightOp)
        body.addView(row)
        space(body, 7)
    }

    private fun dataGrid() {
        val keys = listOf("ОЖ","Скорость","Дроссель","MAP","Нагрузка",
            "Напряжение ECU","STFT","LTFT","Воздух")
        for (chunk in keys.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (key in chunk) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(10), dp(10))
                    background = background(surface, 10f)
                }
                card.addView(text(key, 12f, muted))
                val value = text("—", 20f, accent, true)
                liveValueViews[key] = value
                card.addView(value)
                row.addView(card, LinearLayout.LayoutParams(0, dp(72), 1f).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                })
            }
            body.addView(row)
            space(body, 7)
        }
    }

    private fun updateLive(service: ObdService?) {
        val connected = service?.connected == true
        val rpmString = if (connected) service.values["Обороты"] else null
        rpmGauge?.rpm = rpmString?.substringBefore(" ")?.toIntOrNull()
        for ((key, view) in liveValueViews) {
            view.text = if (connected) service.values[key] ?: "—" else "—"
        }
        connectionLabel?.text = (service?.status ?: "Адаптер не подключён") +
            "\n" + "PID: " + (if (connected) service.values.size else 0) + "/10"
        tripLabel?.text = if (service?.recording == true)
            "Запись: " + service.samples + " измерений" else "Запись остановлена"
        errorsLabel?.text = service?.dtcReport ?: "Нет данных диагностики"
    }

    private fun renderPage(service: ObdService?) {
        if (!::body.isInitialized) return
        body.removeAllViews()
        rpmGauge = null
        liveValueViews.clear()
        connectionLabel = null
        tripLabel = null
        errorsLabel = null
        when (page) {
            0 -> {
                section("Панель приборов", "M274 · данные ЭБУ в реальном времени")
                rpmGauge = TachometerView(this)
                body.addView(rpmGauge, LinearLayout.LayoutParams(-1, -2))
                space(body, 9)
                if (service?.connected != true)
                    note("Нет подтверждённых данных ЭБУ. Нажмите ELM327 для подключения.", muted, 13f)
                dataGrid()
                note("Пустые показатели означают отсутствие подтверждённого ответа, а не ноль.", muted, 12f)
            }
            1 -> {
                section("Поездки", "Запись измерений и отчёты для анализа")
                val running = service?.recording == true
                tripLabel = text("Запись остановлена", 19f, accent, true)
                body.addView(tripLabel)
                space(body, 12)

                action(if (running) "Остановить запись поездки" else "Начать запись поездки") {
                    if (ObdService.instance?.connected != true) {
                        toast("Сначала подтвердите связь с ЭБУ")
                    } else send(ObdService.RECORD)
                }
                action("История поездок") { showHistory() }
                action("Отчёт поездки → ChatGPT") { shareDiagnosticReport(latestTrip()) }
                action("Экспорт ZIP (поездка + DTC)") { shareZip(latestTrip()) }
                note("Запись через службу Android продолжается при сворачивании приложения; не завершайте службу принудительно.", muted, 13f)
            }
            2 -> {
                section("Ошибки двигателя", "Сохранённые 03 · ожидающие 07 · постоянные 0A")
                errorsLabel = text(service?.dtcReport ?: "Нет данных диагностики", 15f, accent)
                body.addView(errorsLabel)
                space(body, 12)
                action("Сканировать ошибки 03 / 07 / 0A") {
                    if (service?.connected == true) {
                        send(ObdService.DTC)
                        toast("Запрос ошибок отправлен в очередь")
                    } else toast("Сначала подключите ЭБУ")
                }
                action("Сбросить ошибки OBD-II (подтверждение)") { requestClearDtc() }
                action("Отправить отчёт в ChatGPT") { shareDiagnosticReport() }
                action("Экспорт журнала диагностики") { shareTrace() }
                note("Mode 04 очищает данные OBD-II, стоп-кадры и может сбросить readiness. " +
                    "Не удаляет все фирменные ошибки MED40. Перед очисткой сохраните отчёт.",
                    muted, 12f)
            }
            else -> {
                section("Подключение", "ELM327 · Bluetooth Classic / SPP")
                connectionLabel = text("Адаптер не подключён", 16f, accent, true)
                body.addView(connectionLabel)
                space(body, 9)
                action("Выбрать и подключить ELM327") { chooseDevice() }
                twoActions("Подключить сохранённый", { send(ObdService.CONNECT) },
                    "Отключить", { send(ObdService.DISCONNECT) })
                val remembered = getSharedPreferences("elm", MODE_PRIVATE).getString("mac", null)
                if (remembered != null) note("Сохранён: " + remembered, muted, 12f)
                val sw = Switch(this).apply {
                    text = "Автоподключение"
                    textSize = 14f
                    setTextColor(white)
                    isChecked = getSharedPreferences("elm", MODE_PRIVATE).getBoolean("auto", true)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("elm", MODE_PRIVATE).edit().putBoolean("auto", checked).apply()
                    }
                }
                body.addView(sw)
                val keep = Switch(this).apply {
                    text = "Не гасить экран, пока приложение открыто"
                    textSize = 13f
                    setTextColor(white)
                    isChecked = getSharedPreferences("elm", MODE_PRIVATE).getBoolean("keep_screen", true)
                    setOnCheckedChangeListener { _, checked ->
                        getSharedPreferences("elm", MODE_PRIVATE)
                            .edit().putBoolean("keep_screen", checked).apply()
                        applyScreenMode()
                    }
                }
                body.addView(keep)
                space(body, 10)
                twoActions("Журнал ELM327", { shareTrace() },
                    if (service?.med40 == true) "MED40: выключить" else "MED40: проба", {
                        if (ObdService.instance?.connected == true) send(ObdService.MED40)
                        else toast("Сначала подключите ЭБУ")
                    })
                note("MED40: пробное чтение. Полный скан доступен только с совместимой диагностикой.",
                    muted, 12f)
                action("Завершить приложение и отключить ELM327") { exitApplication() }
            }

        }
        scrollView.scrollTo(0, 0)
        updateLive(service)
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
    private fun requestClearDtc() {
        val service = ObdService.instance
        if (service?.connected != true) { toast("Сначала подключите ЭБУ"); return }
        if (service.lastDtcScanAt == 0L ||
            System.currentTimeMillis() - service.lastDtcScanAt > 10 * 60_000L) {
            toast("Сначала выполните свежий скан кодов"); return
        }
        AlertDialog.Builder(this)
            .setTitle("Очистка DTC меняет состояние ЭБУ")
            .setMessage("ВНИМАНИЕ: Mode 04 удаляет подтверждённые коды и стоп-кадры, " +
                "может сбросить readiness/мониторы токсичности. " +
                "Это НЕ ремонт и не очищает все Mercedes-коды. " +
                "Автомобиль должен стоять, двигатель должен быть заглушен, зажигание включено.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Продолжить") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Подтвердить Mode 04?")
                    .setMessage("Вы согласны очистить стандартные OBD-II коды " +
                        "и потерять часть диагностических данных? " +
                        "Другие команды управления автомобилем недоступны.")
                    .setNegativeButton("Не очищать", null)
                    .setPositiveButton("ДА, ОЧИСТИТЬ") { _, _ ->
                        send(ObdService.CLEAR_DTC)
                        toast("Команда в очереди: приложение проверит скорость и обороты")
                    }.show()
            }.show()
    }

    private fun applyScreenMode() {
        val keep = getSharedPreferences("elm", MODE_PRIVATE).getBoolean("keep_screen", true)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun exitApplication() {
        AlertDialog.Builder(this).setTitle("Завершить Mercedes Doctor?")
            .setMessage("Bluetooth отключится, фоновая запись остановится. " +
                "Сохранённые CSV останутся на телефоне.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Завершить") { _, _ ->
                if (ObdService.instance != null) send(ObdService.DISCONNECT)
                finishAndRemoveTask()
            }.show()
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
    private fun latestTrip(): File? =
        File(filesDir, "trips").listFiles()?.filter { it.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }

    private fun reportText(trip: File?): String = buildString {
        appendLine("MERCEDES DOCTOR — ОТЧЁТ ДЛЯ CHATGPT")
        appendLine("Модель: Mercedes-Benz E200 W212, двигатель M274, 2014")
        appendLine("Источник: универсальный OBD-II ELM327; не Xentry и не полный MED40-скан")
        appendLine("Дата экспорта (epoch ms): " + System.currentTimeMillis())
        appendLine("Двигатель: бензиновый турбо M274; данные без подтверждения VIN/комплектации")
        appendLine("Соединение ЭБУ: " + (ObdService.instance?.status ?: "офлайн"))
        appendLine("Последняя поездка: " + (trip?.name ?: "не выбрана"))
        if (trip != null) {
            appendLine("Размер CSV: " + trip.length() + " байт")
            appendLine("Строк (с заголовком): " + trip.useLines { it.count() })
        }
        val live = ObdService.instance?.values ?: emptyMap()
        appendLine("Последние значения:")
        live.entries.sortedBy { it.key }.forEach { appendLine(it.key + ": " + it.value) }
        appendLine("Последний скан DTC:")
        appendLine(ObdService.instance?.dtcReport
            ?: File(File(filesDir,"diagnostics"),"dtc-last.txt")
                .takeIf { it.exists() }?.readText() ?: "Нет скана")
        appendLine("Не трактуйте отсутствие Mode 03 как отсутствие фирменных ошибок Mercedes.")
        appendLine("Задача ChatGPT: проанализировать динамику, пропуски и гипотезы.")
    }

    private fun makeReportZip(trip: File?): File {
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val archive = File(dir, "MercedesDoctor-M274-" + System.currentTimeMillis() + ".zip")
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            fun addBytes(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            addBytes("README.txt", reportText(trip).toByteArray(Charsets.UTF_8))
            if (trip != null && trip.exists()) addBytes(trip.name, trip.readBytes())
            val dtc = File(File(filesDir,"diagnostics"), "dtc-last.txt")
            if (dtc.exists()) addBytes("dtc-last.txt", dtc.readBytes())
            val trace = File(File(filesDir,"diagnostics"), "elm_trace.txt")
            if (trace.exists()) addBytes("elm_trace.txt", trace.readBytes())
        }
        return archive
    }

    private fun shareDiagnosticReport(trip: File? = null) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor M274: поездка и DTC")
                putExtra(Intent.EXTRA_TEXT, reportText(trip))
                if (trip != null && trip.exists()) {
                    val uri = FileProvider.getUriForFile(this@MainActivity,
                        packageName + ".fileprovider", trip)
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, "CSV OBD-II", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            startActivity(Intent.createChooser(intent, "Передать отчёт в ChatGPT или другое приложение"))
        } catch (e: Exception) { toast("Отправка отчёта: " + e.message) }
    }

    private fun shareZip(trip: File?) {
        try {
            val file = makeReportZip(trip)
            val uri = FileProvider.getUriForFile(this,
                packageName + ".fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "OBD ZIP", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Сохранить или отправить ZIP-отчёт"))
        } catch (e: Exception) { toast("Создание ZIP: " + e.message) }
    }

    private fun showHistory() {
        val files = File(filesDir, "trips").listFiles()?.filter { it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (files.isEmpty()) {
            toast("Записей пока нет")
            return
        }
        AlertDialog.Builder(this).setTitle("История поездок")
            .setItems(files.map { it.name + " · " + (it.length() / 1024) + " КБ" }.toTypedArray()) {
                _, i ->
                    AlertDialog.Builder(this).setTitle(files[i].name)
                        .setItems(arrayOf("CSV — сохранить или отправить",
                            "Отправить отчёт в ChatGPT", "ZIP: CSV + ошибки + журнал")) { _, action ->
                            when (action) {
                                0 -> share(files[i])
                                1 -> shareDiagnosticReport(files[i])
                                else -> shareZip(files[i])
                            }
                        }.show()
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
