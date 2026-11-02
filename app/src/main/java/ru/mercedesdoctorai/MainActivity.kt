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
    private val cards = mutableMapOf<Int, TextView>()
    private val commandLog = ArrayDeque<String>()
    private val background = Color.rgb(14, 20, 29)
    private val accent = Color.rgb(91, 221, 216)
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(22), dp(18), dp(24)); setBackgroundColor(this@MainActivity.background)
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    view.setPadding(dp(18) + bars.left, dp(22) + bars.top, dp(18) + bars.right, dp(24) + bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    view.setPadding(dp(18) + insets.systemWindowInsetLeft, dp(22) + insets.systemWindowInsetTop,
                        dp(18) + insets.systemWindowInsetRight, dp(24) + insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        layout.addView(text("MERCEDES DOCTOR", 25f, accent).apply { setTypeface(null, Typeface.BOLD) })
        layout.addView(text("E200 · W212 · 2014 · M274", 15f))
        status = text("ELM327 не подключён", 16f)
        layout.addView(status)
        layout.addView(button("Подключить ELM327 Bluetooth") { chooseAdapter() })
        layout.addView(button("Отключить") { disconnect("Соединение отключено") })
        val auto = Switch(this).apply {
            text = "Подключать выбранный адаптер и восстанавливать связь"
            setTextColor(Color.LTGRAY); isChecked = preferences.getBoolean("auto", false)
            setOnCheckedChangeListener { _, enabled ->
                preferences.edit().putBoolean("auto", enabled).apply()
                // Recreate the link so changing the setting also changes retry behavior immediately.
                if (connection != null) {
                    val address = preferences.getString("address", null)
                    disconnect("Настройки подключения изменены")
                    if (address != null) connect(address)
                } else if (enabled) connectSaved()
            }
        }
        layout.addView(auto)
        layout.addView(text("ЖИВЫЕ ПОКАЗАНИЯ", 16f, accent))
        layout.addView(text("Нажмите на параметр, чтобы открыть график. Пустое значение не означает ноль.", 13f))
        val grid = GridLayout(this).apply { columnCount = 2 }
        ObdProtocol.SENSORS.forEach { sensor ->
            val card = text("${sensor.label}\n— ${sensor.unit}\nНет связи", 16f).apply {
                setPadding(dp(12), dp(14), dp(10), dp(14)); setBackgroundColor(Color.rgb(29, 40, 53))
                minHeight = dp(128); setOnClickListener { showGraph(sensor) }
            }
            cards[sensor.pid] = card
            grid.addView(card, GridLayout.LayoutParams().apply {
                width = 0; height = GridLayout.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        layout.addView(grid)
        layout.addView(button("Настроить виджеты") { configureCards() })
        layout.addView(text("Данные — стандартный OBD-II. Расширенные параметры MED40 не заявлены. MAP — абсолютное давление, не наддув.", 13f))
        layout.addView(button("Считать ошибки двигателя · Mode 03") {
            if (!connected) toast("Сначала подключите адаптер") else connection?.readCodes()
        })
        record = button("Начать запись поездки") { toggleRecord() }
        layout.addView(record)
        layout.addView(button("Экспорт для ChatGPT · ZIP") { exportLatest() })
        layout.addView(button("Экспорт CSV последней записи") {
            val dir = reports.latest()
            if (dir == null) toast("Сначала запишите поездку") else exportCsv(dir)
        })
        layout.addView(button("История поездок") { showHistory() })
        layout.addView(button("Диагностика подключения / журнал") { showDiagnostics() })
        details = text("Запись и соединение работают при открытом приложении. При уходе с экрана поездка сохраняется, Bluetooth отключается.", 13f)
        layout.addView(details)
        setContentView(ScrollView(this).apply { addView(layout) })
        applyVisibility()
    }
    private fun text(value: String, size: Float, color: Int = Color.LTGRAY) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
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
                    if (!connected) stopRecording()
                }
                override fun metadata(version: String, protocol: String, support: Map<Int, ObdProtocol.State>) = ui {
                    adapterVersion = version; this@MainActivity.protocol = protocol
                    this@MainActivity.support.clear(); this@MainActivity.support.putAll(support)
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
            cards[sensor.pid]?.apply {
                text = getString(R.string.sensor_card, sensor.label, value, sensor.unit, label)
                setTextColor(if (valid) accent else Color.LTGRAY)
            }
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
