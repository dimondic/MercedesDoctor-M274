package ru.mercedesdoctorai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.content.res.ColorStateList
import android.view.Gravity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val preferences by lazy { getSharedPreferences("connection", MODE_PRIVATE) }
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var record: Button
    private lateinit var reports: SessionReports
    private var connection: ElmConnection? = null
    private var generation = 0
    private var connected = false
    private var foreground = false
    private var adapterVersion = "Не определена"
    private var protocol = "Не определён"
    private val support = mutableMapOf<Int, ObdProtocol.State>()
    private val latest = mutableMapOf<Int, ElmConnection.Sample>()
    private val points = mutableMapOf<Int, MutableList<ElmConnection.Sample>>()
    private val cards = mutableMapOf<Int, SensorCard>()
    private lateinit var gauge: RpmGauge
    private lateinit var connectionSummary: TextView
    private lateinit var tripSummary: TextView
    private lateinit var codeSummary: TextView
    private val pages = mutableListOf<View>()
    private val navigation = mutableListOf<Button>()
    private val commandLog = ArrayDeque<String>()
    private val background = DashboardStyle.background
    private val accent = DashboardStyle.accent
    private val ticker = object : Runnable {
        override fun run() {
            if (!foreground) return
            refreshCards()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reports = SessionReports(File(filesDir, "sessions"))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(this@MainActivity.background)
            setPadding(0, dp(24), 0, dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(bars.left, bars.top + dp(8), bars.right, bars.bottom + dp(8))
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop + dp(8),
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom + dp(8))
                }
                insets
            }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(8))
        }
        val branding = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        branding.addView(text("MERCEDES DOCTOR", 20f, Color.WHITE).apply { setTypeface(null, Typeface.BOLD) })
        branding.addView(text("E200  /  W212  /  M274", 12f))
        header.addView(branding, LinearLayout.LayoutParams(0, -2, 1f))
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        header.addView(text(version ?: "", 14f, accent).apply {
            tag = "app-version"; setPadding(dp(12),dp(8),dp(12),dp(8)); background = DashboardStyle.panel(this@MainActivity)
        })
        root.addView(header)
        status = text("●  ELM327 не подключён", 13f).apply { setPadding(dp(20),dp(6),dp(20),dp(10)) }
        root.addView(status)
        val container = FrameLayout(this)
        root.addView(container, LinearLayout.LayoutParams(-1,0,1f))
        fun page(title: String, subtitle: String): LinearLayout {
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(18))
            }
            content.addView(text(title, 26f, Color.WHITE).apply { setTypeface(null, Typeface.BOLD) })
            content.addView(text(subtitle, 13f))
            val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
            pages.add(scroll); container.addView(scroll, FrameLayout.LayoutParams(-1,-1))
            return content
        }
        val dashboard = page("Панель двигателя", "Стандартный OBD-II · живые измерения")
        gauge = RpmGauge(this).apply {
            background = DashboardStyle.panel(this@MainActivity)
            setOnClickListener { showGraph(ObdProtocol.SENSORS.first { it.pid == 0x0C }) }
        }
        dashboard.addView(gauge, LinearLayout.LayoutParams(-1,dp(280)).apply { setMargins(0,dp(12),0,dp(12)) })
        val grid = GridLayout(this).apply { columnCount = 2 }
        ObdProtocol.SENSORS.forEach { sensor ->
            val card = SensorCard(this).apply {
                bind(sensor,"—","Нет связи",false)
                setOnClickListener { showGraph(sensor) }
            }
            cards[sensor.pid] = card
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4),dp(4),dp(4),dp(4))
            })
        }
        dashboard.addView(grid)
        dashboard.addView(button("Настроить виджеты") { configureCards() })
        dashboard.addView(text("Нажмите на прибор для графика. MAP — абсолютное давление, не давление турбины.", 12f))

        val trips = page("Поездки", "Запись измерений и отчёты для анализа")
        tripSummary = text("Запись остановлена", 21f, accent).apply { tag = "trip-summary" }
        trips.addView(tripSummary)
        trips.addView(text("Каждое измерение сохраняется со временем, единицами и статусом ответа.",14f))
        record = button("Начать запись поездки") { toggleRecord() }
        trips.addView(record)
        trips.addView(button("История поездок") { showHistory() })
        trips.addView(text("ЭКСПОРТ",14f,accent))
        trips.addView(button("ZIP-отчёт для ChatGPT") { exportLatest() })
        trips.addView(button("Таблица измерений · CSV") {
            val dir = reports.latest()
            if (dir == null) toast("Сначала запишите поездку") else exportCsv(dir)
        })
        trips.addView(text("Перед экспортом остановите запись для получения полного отчёта.",13f))
        details = text("Запись работает при открытом приложении. При сворачивании поездка сохраняется, Bluetooth отключается.",13f)
        trips.addView(details)

        val errors = page("Ошибки двигателя", "Стандартные коды неисправностей · Mode 03")
        codeSummary = text("Чтение ещё не выполнялось",21f,accent).apply { tag = "dtc-summary" }
        errors.addView(codeSummary)
        errors.addView(text("Сначала подключите адаптер и включите зажигание. Ответ ЭБУ появится здесь.",14f))
        errors.addView(button("Считать ошибки двигателя") {
            if (!connected) toast("Сначала подключите адаптер") else connection?.readCodes()
        })
        errors.addView(text("Расширенные блоки Mercedes и стоп-кадры пока не поддерживаются. NO DATA не означает отсутствие ошибок.",13f))
        errors.addView(text("Только чтение. Очистка ошибок и адаптации не выполняются.",13f))

        val elm = page("Подключение", "ELM327 · Bluetooth Classic / SPP")
        connectionSummary = text("Адаптер не выбран",17f,accent).apply { tag = "connection-summary" }
        elm.addView(connectionSummary)
        elm.addView(button("Выбрать и подключить ELM327") { chooseAdapter() })
        elm.addView(button("Отключить адаптер") { disconnect("Соединение отключено") })
        val auto = Switch(this).apply {
            text = "Автоподключение и восстановление связи"
            setTextColor(Color.LTGRAY); isChecked = preferences.getBoolean("auto", false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("auto", enabled).apply()
                if (connection != null) {
                    val address = preferences.getString("address", null)
                    disconnect("Настройки подключения изменены")
                    if (address != null) connect(address)
                } else if (enabled) connectSaved()
            }
        }
        elm.addView(auto)
        elm.addView(button("Диагностика и экспорт журнала") { showDiagnostics() })
        elm.addView(text("Сопрягите ELM327 в настройках Android. Разрешите доступ к устройствам поблизости. Поддержка PID проверяется при подключении.",14f))
        elm.addView(text("Профиль: E200 W212 · 2014 · M274. Фирменные функции MED40 не заявлены.",13f))
        val tabs = LinearLayout(this).apply { setPadding(dp(8),dp(4),dp(8),0) }
        listOf("Панель", "Поездки", "Ошибки", "ELM327").forEachIndexed { index, name ->
            val tab = button(name) { showPage(index) }.apply { tag = "tab-$index"; textSize = 11f; minWidth = 0; minimumWidth = 0 }
            navigation.add(tab); tabs.addView(tab, LinearLayout.LayoutParams(0,dp(52),1f))
        }
        root.addView(tabs)
        setContentView(root)
        showPage(savedInstanceState?.getInt("page",0) ?: 0)
        applyVisibility()
    }
    private var currentPage = 0
    private fun showPage(index: Int) {
        currentPage = index.coerceIn(0,3)
        pages.forEachIndexed { i, page -> page.visibility = if (i == currentPage) View.VISIBLE else View.GONE }
        navigation.forEachIndexed { i, tab ->
            tab.isSelected = i == currentPage
            tab.setTextColor(if (i == currentPage) Color.BLACK else DashboardStyle.muted)
            tab.backgroundTintList = ColorStateList.valueOf(if (i == currentPage) accent else DashboardStyle.surface)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("page",currentPage); super.onSaveInstanceState(outState)
    }
    private fun text(value: String, size: Float, color: Int = DashboardStyle.muted) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minHeight = dp(48)
        backgroundTintList = ColorStateList.valueOf(DashboardStyle.surface)
        setTextColor(accent); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun permission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun chooseAdapter() {
        if (!permission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 22); return
        }
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) { toast("Включите Bluetooth в настройках телефона"); return }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) { toast("Сначала сопрягите ELM327 в настройках Bluetooth Android"); return }
            AlertDialog.Builder(this).setTitle("Выберите свой ELM327")
                .setItems(devices.map { "${it.name ?: "Bluetooth"} · ${it.address}" }.toTypedArray()) { _, which ->
                    val address = devices[which].address
                    preferences.edit().putString("address", address).apply(); connect(address)
                }.setNegativeButton("Отмена", null).show()
        } catch (_: SecurityException) { toast("Нужно разрешение на подключение к устройствам поблизости") }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == 22 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) chooseAdapter()
    }
    private fun connectSaved() {
        if (!foreground || !permission()) return
        preferences.getString("address", null)?.let { connect(it) }
    }
    private fun connect(address: String) {
        if (!permission()) { toast("Разрешите подключение к устройствам поблизости"); return }
        disconnect("Подготовка подключения…")
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) { status.setText(R.string.enable_bluetooth); return }
            val device = adapter.getRemoteDevice(address)
            adapterVersion = "Не определена"; protocol = "Не определён"; support.clear(); latest.clear(); points.clear()
            reports.metadata(adapterVersion, protocol, emptyMap())
            val id = ++generation
            fun ui(action: () -> Unit) { handler.post { if (id == generation && foreground && !isFinishing) action() } }
            val listener = object : ElmConnection.Listener {
                override fun status(text: String, connected: Boolean) = ui {
                    this@MainActivity.connected = connected
                    status.text = text
                    connectionSummary.text = text
                    if (!connected) stopRecording()
                }
                override fun metadata(version: String, protocol: String, support: Map<Int, ObdProtocol.State>) = ui {
                    adapterVersion = version; this@MainActivity.protocol = protocol
                    this@MainActivity.support.clear(); this@MainActivity.support.putAll(support)
                    connectionSummary.text = getString(R.string.adapter_summary, version, protocol)
                    storage { reports.metadata(version, protocol, support) }
                }
                override fun sample(sample: ElmConnection.Sample) = ui {
                    latest[sample.pid] = sample
                    points.getOrPut(sample.pid) { mutableListOf() }.apply {
                        add(sample); while (size > 300) removeAt(0)
                    }
                    storage {
                        if (reports.sample(sample)) { record.text = "Начать запись поездки"; toast("Достигнут лимит 100000 измерений; поездка сохранена") }
                    }
                    refreshCards()
                }
                override fun codes(time: Long, state: String, codes: List<String>?, raw: String) = ui {
                    storage { reports.codes(time, state, codes, raw) }
                    val message = when {
                        codes == null -> "Результат: $state. Отсутствие ошибок не подтверждено."
                        codes.isEmpty() -> "Стандартные DTC Mode 03 не возвращены."
                        else -> codes.joinToString("\n")
                    }
                    codeSummary.text = message
                    showPage(2)
                    AlertDialog.Builder(this@MainActivity).setTitle("Ошибки OBD-II")
                        .setMessage("$message\n\nЭто не полный скан блоков Mercedes.\nОтвет ELM327:\n$raw")
                        .setPositiveButton("Закрыть", null).show()
                }
                override fun command(time: Long, command: String, raw: String, outcome: String, duration: Long) = ui {
                    val line = JSONObject().put("timestamp_ms", time).put("command", command).put("raw", raw)
                        .put("transport_status", outcome).put("duration_ms", duration).toString()
                    commandLog.addLast(line); while (commandLog.size > 500) commandLog.removeFirst()
                    storage { reports.command(line) }
                }
            }
            connection = ElmConnection(device, listener, preferences.getBoolean("auto", false)).also { it.start() }
        } catch (e: Exception) { status.text = getString(R.string.connection_failed, e.message) }
    }
    private fun disconnect(message: String) {
        generation++
        connection?.close(); connection = null; connected = false
        if (::reports.isInitialized) stopRecording()
        latest.replaceAll { _, sample -> sample.copy(time = System.currentTimeMillis(), state = ObdProtocol.State.DISCONNECTED, value = null) }
        if (::status.isInitialized) status.text = message
        refreshCards()
    }
    private fun stateLabel(state: ObdProtocol.State) = when (state) {
        ObdProtocol.State.OK -> "Получено"
        ObdProtocol.State.NO_DATA -> "Нет данных"
        ObdProtocol.State.UNSUPPORTED -> "Не поддерживается ЭБУ"
        ObdProtocol.State.UNKNOWN_SUPPORT -> "Поддержка не определена"
        ObdProtocol.State.INVALID -> "Некорректный / неоднозначный ответ"
        ObdProtocol.State.ADAPTER_ERROR -> "Ошибка ELM327"
        ObdProtocol.State.DISCONNECTED -> "Нет связи"
    }
    private fun refreshCards() {
        val now = System.currentTimeMillis()
        if (::tripSummary.isInitialized) tripSummary.text = getString(R.string.trip_summary, if (reports.active) "Идёт запись" else "Запись остановлена", reports.rows)
        ObdProtocol.SENSORS.forEach { sensor ->
            val sample = latest[sensor.pid]
            val stale = sample != null && now - sample.time > sensor.intervalMs * 2 + 5000
            val valid = sample?.state == ObdProtocol.State.OK && !stale && connected
            val value = if (valid) String.format(Locale.getDefault(), "%.2f", sample?.value) else "—"
            val label = when {
                !connected -> "Нет связи"
                stale && sample?.state == ObdProtocol.State.OK -> "Данные устарели"
                sample != null -> stateLabel(sample.state)
                else -> "Ожидание"
            }
            cards[sensor.pid]?.bind(sensor, value, label, valid)
            if (sensor.pid == 0x0C && ::gauge.isInitialized) gauge.bind(if (valid) sample?.value else null, label)
        }
    }
    private fun configureCards() {
        val sensors = ObdProtocol.SENSORS
        val checked = sensors.map { preferences.getBoolean("show-${it.pid}", true) }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Параметры на панели")
            .setMultiChoiceItems(sensors.map { it.label }.toTypedArray(), checked) { _, index, selected -> checked[index] = selected }
            .setPositiveButton("Сохранить") { _, _ ->
                preferences.edit().apply { sensors.forEachIndexed { i, s -> putBoolean("show-${s.pid}", checked[i]) } }.apply()
                applyVisibility()
            }.setNegativeButton("Отмена", null).show()
    }
    private fun applyVisibility() {
        cards.forEach { (pid, view) -> view.visibility = if (preferences.getBoolean("show-$pid", true)) View.VISIBLE else View.GONE }
    }
    private fun showGraph(sensor: ObdProtocol.Sensor) {
        val samples = points[sensor.pid]?.toList() ?: emptyList()
        if (samples.none { it.value != null }) { toast("Пока нет измерений для графика"); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(this@MainActivity.background) }
        layout.addView(text("История текущего подключения. Разрывы не соединяются линией. Закройте и откройте график для обновления.", 13f))
        val graph = SensorGraph(this, samples, sensor.unit)
        layout.addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)))
        val range = text("Окно: 180 секунд", 14f); layout.addView(range)
        layout.addView(SeekBar(this).apply {
            max = 150; progress = 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, user: Boolean) {
                    graph.windowSeconds = progress + 30; range.text = getString(R.string.graph_window, progress + 30)
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })
        AlertDialog.Builder(this).setTitle(sensor.label).setView(layout).setPositiveButton("Закрыть", null).show()
    }
    private fun toggleRecord() {
        if (reports.active) { stopRecording(); return }
        if (!connected) { toast("Сначала подключите ELM327"); return }
        if (support.values.none { it == ObdProtocol.State.OK }) {
            toast("Нет подтверждённых PID. Проверьте зажигание и журнал подключения."); return
        }
        storage {
            reports.start()
            record.text = "Остановить и сохранить поездку"
            toast("Запись начата. Оставьте приложение открытым.")
        }
    }
    private fun stopRecording() {
        if (!reports.active) return
        storage { reports.stop() }
        record.text = "Начать запись поездки"
    }
    private fun storage(action: () -> Unit) {
        try { action() } catch (e: Exception) {
            try { reports.stop() } catch (_: Exception) {}
            record.text = "Начать запись поездки"
            toast("Ошибка записи / экспорта: ${e.message}")
        }
    }
    private fun share(file: File, type: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor · M274")
            putExtra(Intent.EXTRA_TEXT, "Проанализируй качество данных и тренды M274. Условия поездки: [опишите]. Это стандартный OBD-II, не полный скан Mercedes. Не делай вывод о неисправности без подтверждения.")
            clipData = ClipData.newRawUri("Отчёт M274", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Передать отчёт, например в ChatGPT"))
    }
    private fun reportFile(name: String): File = File(File(cacheDir, "reports").apply { mkdirs() }, name)
    private fun exportLatest() {
        val dir = reports.latest() ?: run { toast("Сначала запишите поездку"); return }
        exportSession(dir)
    }
    private fun exportSession(dir: File) = storage {
        val file = reportFile("MercedesDoctor-${dir.name}.zip")
        reports.export(dir, file); share(file, "application/zip")
    }
    private fun exportCsv(dir: File) = storage {
        val file = reportFile("MercedesDoctor-${dir.name}.csv")
        reports.exportCsv(dir, file); share(file, "text/csv")
    }
    private fun showHistory() {
        val sessions = reports.sessions()
        // Keep v0.2 history intact and offer its original CSV alongside new session archives.
        val legacy = try { JSONArray(getSharedPreferences("trip_history", MODE_PRIVATE).getString("items", "[]")) } catch (_: Exception) { JSONArray() }
        val labels = sessions.map {
            val date = it.name.removePrefix("session-").toLongOrNull() ?: 0L
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(date)) + " · ZIP"
        } + (0 until legacy.length()).map { "Поездка v0.2 · ${it + 1} · CSV" }
        if (labels.isEmpty()) { toast("Сохранённых поездок пока нет"); return }
        AlertDialog.Builder(this).setTitle("Поездки · выбрать для экспорта")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index < sessions.size) AlertDialog.Builder(this).setTitle("Экспорт поездки")
                    .setItems(arrayOf("Отчёт ZIP для ChatGPT", "Измерения CSV")) { _, action ->
                        if (action == 0) exportSession(sessions[index]) else exportCsv(sessions[index])
                    }.show()
                else storage {
                    val trip = legacy.getJSONObject(index - sessions.size)
                    val file = reportFile("MercedesDoctor-legacy-${trip.getLong("started")}.csv")
                    file.writeText("\uFEFF" + trip.getString("csv")); share(file, "text/csv")
                }
            }.setNegativeButton("Закрыть", null).show()
    }
    private fun showDiagnostics() {
        val diagnostic = buildString {
            appendLine("ELM327 (заявлено адаптером): $adapterVersion")
            appendLine("Протокол: $protocol")
            appendLine("Строка версии не подтверждает подлинность адаптера.")
            ObdProtocol.SENSORS.forEach { appendLine("${it.command()}: ${stateLabel(support[it.pid] ?: ObdProtocol.State.UNKNOWN_SUPPORT)}") }
            appendLine("\nПоследние команды (не более 500):")
            commandLog.toList().takeLast(15).forEach { appendLine(it) }
        }
        AlertDialog.Builder(this).setTitle("Диагностика ELM327")
            .setMessage(diagnostic).setPositiveButton("Закрыть", null)
            .setNeutralButton("Экспорт журнала") { _, _ -> storage {
                val file = reportFile("MercedesDoctor-elm-log.txt")
                file.writeText(commandLog.joinToString("\n")); share(file, "text/plain")
            } }.show()
    }
    override fun onStart() {
        super.onStart(); foreground = true; handler.post(ticker)
        if (preferences.getBoolean("auto", false) && connection == null) connectSaved()
    }
    override fun onStop() {
        foreground = false; handler.removeCallbacks(ticker)
        disconnect("Приложение было свёрнуто · подключитесь снова")
        super.onStop()
    }
    override fun onDestroy() { connection?.close(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
