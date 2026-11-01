package ru.mercedesdoctorai

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val columns = listOf("Обороты", "ОЖ", "Впускной воздух", "MAP", "Дроссель", "STFT B1", "LTFT B1", "Напряжение ECU")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val values = linkedMapOf<String, String>()
    private val tripRows = mutableListOf<List<String>>()
    private val history = mutableListOf<Trip>()
    private lateinit var status: TextView
    private lateinit var data: TextView
    private lateinit var log: TextView
    private lateinit var connectBtn: Button
    private lateinit var recordBtn: Button
    private var polling = false
    private var recording = false
    private var tripStarted = 0L

    private data class Trip(val started: Long, val ended: Long, val csv: String, val count: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadHistory()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 20) }
        status = TextView(this).apply { textSize = 18f; text = "Mercedes E200 W212 • M274\nАдаптер не подключён" }
        data = TextView(this).apply { textSize = 16f; text = "Нет данных" }
        log = TextView(this).apply { textSize = 13f }
        connectBtn = Button(this).apply { text = "Подключить Bluetooth ELM327"; setOnClickListener { connect() } }
        val scanBtn = Button(this).apply { text = "Считать ошибки двигателя"; setOnClickListener { readCodes() } }
        recordBtn = Button(this).apply { text = "Начать запись поездки"; setOnClickListener { toggleRecord() } }
        val historyBtn = Button(this).apply { text = "История поездок (${history.size})"; setOnClickListener { showHistory() } }
        val exportBtn = Button(this).apply { text = "Экспорт CSV текущей записи"; setOnClickListener { exportCurrentCsv() } }
        val shareBtn = Button(this).apply { text = "Поделиться отчётом / CSV"; setOnClickListener { shareReport() } }
        layout.addView(status); layout.addView(connectBtn); layout.addView(data)
        layout.addView(scanBtn); layout.addView(recordBtn); layout.addView(exportBtn); layout.addView(historyBtn); layout.addView(shareBtn); layout.addView(log)
        setContentView(ScrollView(this).apply { addView(layout) })
        requestBtPermission()
    }

    private fun requestBtPermission() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 22)
    }

    private fun connect() {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null || !adapter.isEnabled) { toast("Включи Bluetooth"); return }
            val devices = adapter.bondedDevices.toList()
            if (devices.isEmpty()) { toast("Сначала сопряги ELM327 в настройках Bluetooth Android"); return }
            val names = devices.map { "${it.name ?: "Bluetooth"} • ${it.address}" }.toTypedArray()
            android.app.AlertDialog.Builder(this).setTitle("Выбери ELM327")
                .setItems(names) { _, which -> connectDevice(devices[which]) }.show()
        } catch (_: SecurityException) { toast("Разреши доступ к устройствам поблизости") }
    }

    private fun connectDevice(device: BluetoothDevice) {
        status.text = "Подключение к ${device.name}…"
        thread {
            try {
                val s = device.createRfcommSocketToServiceRecord(spp); s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command(it) }
                runOnUiThread { status.text = "Подключено: ${device.name}\nELM327 • профиль OBD-II"; connectBtn.text = "Переподключить" }
                startPolling()
            } catch (e: Exception) { runOnUiThread { status.text = "Ошибка подключения: ${e.message}" } }
        }
    }

    @Synchronized private fun command(cmd: String): String {
        val out = output ?: return ""; val inp = input ?: return ""
        out.write((cmd + "\r").toByteArray()); out.flush()
        val result = StringBuilder(); val deadline = System.currentTimeMillis() + 1800
        while (System.currentTimeMillis() < deadline) {
            if (inp.available() > 0) { val b = inp.read(); if (b == 62) break; if (b >= 0) result.append(b.toChar()) }
            else Thread.sleep(20)
        }
        return result.toString().replace("\r", " ").replace("\n", " ").trim()
    }

    private fun pid(cmd: String, label: String, decode: (String) -> String) {
        val raw = command(cmd).replace(" ", ""); val marker = "41" + cmd.takeLast(2).uppercase(Locale.US)
        val idx = raw.indexOf(marker)
        if (idx >= 0 && raw.length >= idx + 6) {
            val a = raw.substring(idx + 4, idx + 6).toIntOrNull(16)
            val b = raw.substring(idx + 6, idx + 8).toIntOrNull(16)
            if (a != null) values[label] = decode(if (b != null) "%02X%02X".format(a, b) else "%02X".format(a))
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        thread {
            while (polling && socket?.isConnected == true) {
                try {
                    pid("010C", "Обороты") { (it.toInt(16) / 4).toString() + " об/мин" }
                    pid("0105", "ОЖ") { (it.take(2).toInt(16) - 40).toString() + " °C" }
                    pid("010F", "Впускной воздух") { (it.take(2).toInt(16) - 40).toString() + " °C" }
                    pid("010B", "MAP") { it.take(2).toInt(16).toString() + " кПа" }
                    pid("0111", "Дроссель") { "%.1f%%".format(it.take(2).toInt(16) * 100.0 / 255.0) }
                    pid("0106", "STFT B1") { "%.1f%%".format((it.take(2).toInt(16) - 128) * 100.0 / 128.0) }
                    pid("0107", "LTFT B1") { "%.1f%%".format((it.take(2).toInt(16) - 128) * 100.0 / 128.0) }
                    pid("0142", "Напряжение ECU") { "%.2f В".format(it.toInt(16) / 1000.0) }
                    val snapshot = values.toMap()
                    if (recording) tripRows.add(listOf(System.currentTimeMillis().toString()) + columns.map { snapshot[it] ?: "" })
                    runOnUiThread { data.text = snapshot.entries.joinToString("\n") { "${it.key}: ${it.value}" } }
                    Thread.sleep(1000)
                } catch (e: Exception) { runOnUiThread { status.text = "Связь прервана: ${e.message}" }; polling = false }
            }
        }
    }

    private fun readCodes() {
        thread { val codes = command("03"); runOnUiThread { log.text = "Ответ ЭБУ (Mode 03): $codes\nNO DATA не подтверждает отсутствие фирменных ошибок Mercedes." } }
    }

    private fun toggleRecord() {
        if (!recording) {
            tripRows.clear(); tripStarted = System.currentTimeMillis(); recording = true
            recordBtn.text = "Остановить и сохранить поездку"; toast("Запись начата")
        } else {
            recording = false
            val csv = buildCsv(tripRows)
            if (tripRows.isNotEmpty()) {
                history.add(0, Trip(tripStarted, System.currentTimeMillis(), csv, tripRows.size)); saveHistory()
                toast("Поездка сохранена • ${tripRows.size} измерений")
            } else toast("Нет измерений для сохранения")
            recordBtn.text = "Начать запись поездки"
        }
    }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun buildCsv(rows: List<List<String>>): String = buildString {
        appendLine((listOf("timestamp_ms", "time_iso") + columns).joinToString(",") { csvCell(it) })
        rows.forEach { row ->
            val ts = row.firstOrNull()?.toLongOrNull() ?: 0L
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(ts))
            appendLine((listOf(ts.toString(), iso) + row.drop(1)).joinToString(",") { csvCell(it) })
        }
    }

    private fun exportCurrentCsv() {
        if (tripRows.isEmpty()) { toast("Пока нет данных записи. Начни и останови запись поездки."); return }
        shareCsv(buildCsv(tripRows), "MercedesDoctor-current.csv")
    }

    private fun showHistory() {
        if (history.isEmpty()) { toast("Сохранённых поездок пока нет"); return }
        val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val labels = history.mapIndexed { i, t -> "${i + 1}. ${fmt.format(Date(t.started))} • ${t.count} строк" }.toTypedArray()
        android.app.AlertDialog.Builder(this).setTitle("История поездок")
            .setItems(labels) { _, which -> showTripActions(which) }
            .setNegativeButton("Закрыть", null).show()
    }

    private fun showTripActions(index: Int) {
        val trip = history[index]
        android.app.AlertDialog.Builder(this).setTitle("Поездка • ${trip.count} измерений")
            .setItems(arrayOf("Экспортировать CSV", "Поделиться отчётом и CSV")) { _, action ->
                if (action == 0) shareCsv(trip.csv, "MercedesDoctor-trip-${trip.started}.csv") else shareTripReport(trip)
            }.show()
    }

    private fun saveHistory() {
        val arr = JSONArray()
        history.take(30).forEach { t -> arr.put(JSONObject().put("started", t.started).put("ended", t.ended).put("csv", t.csv).put("count", t.count)) }
        getSharedPreferences("trip_history", MODE_PRIVATE).edit().putString("items", arr.toString()).apply()
    }

    private fun loadHistory() {
        try {
            val arr = JSONArray(getSharedPreferences("trip_history", MODE_PRIVATE).getString("items", "[]"))
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); history.add(Trip(o.getLong("started"), o.getLong("ended"), o.getString("csv"), o.getInt("count"))) }
        } catch (_: Exception) { history.clear() }
    }

    private fun shareCsv(csv: String, name: String) {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }; val file = File(dir, name)
            file.writeText("\uFEFF" + csv, Charsets.UTF_8)
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor — CSV поездки")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Экспорт CSV / отправить в ChatGPT"))
        } catch (e: Exception) { toast("Не удалось экспортировать CSV: ${e.message}") }
    }

    private fun shareTripReport(trip: Trip) {
        try {
            val dir = File(cacheDir, "reports").apply { mkdirs() }; val file = File(dir, "MercedesDoctor-trip-${trip.started}.csv")
            file.writeText("\uFEFF" + trip.csv, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val report = "Mercedes-Benz E200 W212 2014, M274\nСтандартный OBD-II через ELM327.\n" +
                "Начало: ${Date(trip.started)}\nОкончание: ${Date(trip.ended)}\nИзмерений: ${trip.count}\n" +
                "Проанализируй тренды и качество данных. Не делай вывод о неисправности без подтверждения. Это не полный Xentry-скан."
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor — отчёт M274")
                putExtra(Intent.EXTRA_TEXT, report); putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Передать отчёт и CSV"))
        } catch (e: Exception) { toast("Ошибка подготовки отчёта: ${e.message}") }
    }

    private fun shareReport() {
        val report = buildString {
            appendLine("Mercedes-Benz E200 W212 2014, двигатель M274")
            appendLine("Адаптер: Bluetooth ELM327; данные стандартного OBD-II")
            appendLine("Дата: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("Текущие параметры:"); values.forEach { (k, v) -> appendLine("$k: $v") }
            appendLine("\nЗапись поездки: ${tripRows.size} измерений")
            appendLine("Важно: это универсальные OBD-II данные, не полный Xentry-скан.")
        }
        if (tripRows.isNotEmpty()) {
            val dir = File(cacheDir, "reports").apply { mkdirs() }; val file = File(dir, "MercedesDoctor-current.csv")
            file.writeText("\uFEFF" + buildCsv(tripRows), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor — отчёт M274"); putExtra(Intent.EXTRA_TEXT, report); putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            startActivity(Intent.createChooser(intent, "Поделиться отчётом и CSV"))
        } else {
            val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Mercedes Doctor — отчёт M274"); putExtra(Intent.EXTRA_TEXT, report) }
            startActivity(Intent.createChooser(intent, "Поделиться отчётом"))
        }
    }

    override fun onDestroy() { polling = false; try { socket?.close() } catch (_: Exception) {}; super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
