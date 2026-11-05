package ru.mercedesdoctorai

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.UUID
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Owns RFCOMM and the single command queue while UI is in background. */
class ObdService : Service() {
    companion object {
        const val CONNECT = "obd.CONNECT"
        const val DISCONNECT = "obd.DISCONNECT"
        const val RECORD = "obd.RECORD"
        const val DTC = "obd.DTC"
        const val CLEAR_DTC = "obd.CLEAR_DTC"
        const val MED40 = "obd.MED40"
        const val DEVICE = "device"
        @Volatile var instance: ObdService? = null
            private set
    }
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val pids = linkedMapOf("010C" to "Обороты", "0105" to "ОЖ", "010F" to "Воздух",
        "010B" to "MAP", "0111" to "Дроссель", "0106" to "STFT", "0107" to "LTFT",
        "0142" to "Напряжение ECU", "010D" to "Скорость", "0104" to "Нагрузка")
    val values = ConcurrentHashMap<String, String>()
    @Volatile var status = "Ожидание ELM327"
    @Volatile var detail = "Данные ЭБУ пока не подтверждены"
    @Volatile var connected = false
    @Volatile var recording = false
    @Volatile var med40 = false
    @Volatile var samples = 0
    @Volatile private var running = false
    @Volatile private var needDtc = false
    @Volatile private var needClear = false
    @Volatile var dtcReport = "Считывание ошибок ещё не выполнялось"
        private set
    @Volatile var lastDtcScanAt = 0L
        private set
    private var lastSpeedAt = 0L
    private var lastRpmAt = 0L
    private var address: String? = null
    private var socket: BluetoothSocket? = null
    private var csvFile: File? = null
    private val traceFile by lazy { File(File(filesDir, "diagnostics").apply { mkdirs() }, "elm_trace.txt") }
    fun trace(): File? = traceFile.takeIf { it.exists() }
    private fun traceCommand(command: String, response: String) {
        try {
            if (traceFile.length() > 800_000L) traceFile.writeText("")
            traceFile.appendText(System.currentTimeMillis().toString() +
                " | " + command + " | " + response.replace("\r", " ").replace("\n", " ").take(220) + "\n")
        } catch (_: Exception) {}
    }
    private val permitted = setOf("04","07","0A","0101","ATRV","ATI","ATZ","ATE0","ATL0","ATS0","ATH0","ATH1",
        "ATAT1","ATSP0","ATDP","ATSH7E0","ATSH7DF","03","0100","0120") + pids.keys
    @Volatile private var medProbePending = true

    override fun onCreate() {
        super.onCreate()
        instance = this
        med40 = getSharedPreferences("elm", MODE_PRIVATE).getBoolean("med40", false)
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel("obd", "Диагностика Mercedes",
            NotificationManager.IMPORTANCE_LOW))
        startForeground(14, notification())
    }
    private fun notification(): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "obd")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Mercedes Doctor · ELM327")
            .setContentText(if (recording) "Фоновая запись поездки" else "Диагностика ЭБУ")
            .setContentIntent(pi).setOngoing(true).build()
    }
    private fun refreshNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(14, notification())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            CONNECT -> {
                val mac = intent.getStringExtra(DEVICE)
                    ?: getSharedPreferences("elm", MODE_PRIVATE).getString("mac", null)
                if (mac != null) {
                    address = mac
                    getSharedPreferences("elm", MODE_PRIVATE).edit().putString("mac", mac).apply()
                    if (!running) connectLoop()
                } else status = "Выберите сопряжённый ELM327"
            }
            DISCONNECT -> {
                running = false
                close()
                recording = false
                connected = false
                stopSelf()
            }
            RECORD -> {
                if (recording) recording = false else {
                    val dir = File(filesDir, "trips").apply { mkdirs() }
                    csvFile = File(dir, "trip-" + System.currentTimeMillis() + ".csv")
                    csvFile!!.writeText("timestamp_ms," + pids.values.joinToString(",") + "\n")
                    samples = 0
                    recording = true
                }
                refreshNotification()
            }
            DTC -> needDtc = true
            CLEAR_DTC -> {
                if (connected && lastDtcScanAt > 0L &&
                    System.currentTimeMillis() - lastDtcScanAt < 10 * 60_000L) needClear = true
                else dtcReport = "Сначала выполните свежий скан ошибок при подключённом ЭБУ."
            }
            MED40 -> {
                med40 = !med40
                medProbePending = med40
                getSharedPreferences("elm", MODE_PRIVATE).edit().putBoolean("med40", med40).apply()
                detail = if (med40) "MED40 проба: только чтение OBD-II, не Xentry"
                    else "MED40 проба выключена"
            }
        }
        return START_STICKY
    }
    private fun connectLoop() {
        running = true
        thread(name = "ELM327-serial") {
            var failures = 0
            while (running) {
                try {
                    if (Build.VERSION.SDK_INT >= 31 &&
                        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Нет разрешения Bluetooth")
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                        ?: throw IllegalStateException("Bluetooth отсутствует")
                    if (!adapter.isEnabled) throw IllegalStateException("Bluetooth выключен")
                    status = "Соединение Bluetooth..."
                    try { adapter.cancelDiscovery() } catch (_: Exception) {}
                    val target = adapter.getRemoteDevice(address ?: break)
                    socket = target.createRfcommSocketToServiceRecord(spp)
                    socket!!.connect()
                    status = "ELM327 на связи. Проверяю ЭБУ..."
                    val identification = send("ATI", 3500)
                    send("ATZ", 4000)
                    send("ATE0"); send("ATL0"); send("ATS0"); send("ATH0")
                    send("ATAT1"); send("ATSP0", 4500)
                    val protocol = send("ATDP")
                    val supportRaw = send("0100", 5200)
                    val support = response(supportRaw, "4100")
                    if (support == null || support.size < 4) {
                        detail = "ELM327: " + identification + "; протокол: " + protocol +
                            "; ECU 0100: " + supportRaw + ". Проверьте зажигание."
                        throw IllegalStateException("ЭБУ не подтвердил PID 0100")
                    }
                    val upperRaw = send("0120", 4500)
                    val upper = response(upperRaw, "4120")
                    val enabled = pids.filterKeys { cmd ->
                        val n = cmd.substring(2).toInt(16)
                        when {
                            n in 1..32 -> (support[(n - 1) / 8] and (128 shr ((n - 1) % 8))) != 0
                            n in 33..64 && upper != null && upper.size >= 4 ->
                                (upper[(n - 33) / 8] and (128 shr ((n - 33) % 8))) != 0
                            else -> false
                        }
                    }
                    connected = true
                    failures = 0
                    status = "ЭБУ отвечает, " + enabled.size + " PID · " + protocol
                    detail = "Протокол: " + protocol + "; запрошено: " + enabled.keys.joinToString()
                    while (running && socket?.isConnected == true) {
                        if (needDtc) {
                            needDtc = false
                            detail = parseDtc(send("03", 6500))
                        }
                        if (med40 && medProbePending) {
                            medProbePending = false
                            // Safe read-only probe, not proprietary MED40 identification.
                            send("ATH1")
                            send("ATSH7E0")
                            val probe = send("0100", 5000)
                            detail = "MED40 проба 7E0/0100: " + probe
                            send("ATSH7DF")
                            send("ATH0")
                        }
                        for ((pid, label) in enabled) {
                            if (!running) break
                            val bytes = response(send(pid), "41" + pid.substring(2))
                            if (bytes != null) decode(pid, bytes)?.let { values[label] = it }
                        }
                        if (recording && values.isNotEmpty()) appendSample()
                        Thread.sleep(700)
                    }
                } catch (e: Exception) {
                    if (running) status = "Нет данных ЭБУ: " + e.message
                } finally {
                    connected = false
                    close()
                }
                if (!running) break
                failures++
                status += " · повтор #" + failures
                try { Thread.sleep(2000L * failures.coerceAtMost(5)) }
                catch (_: InterruptedException) { break }
            }
            running = false
        }
    }
    /** Serial prompt-framed commands. Both screen and logger use this one thread. */
    private fun send(cmd: String, timeout: Long = 3200): String {
        require(cmd in permitted) { "Запрещённая команда OBD/ELM: " + cmd }
        val s = socket ?: throw IllegalStateException("Потеряна связь")
        val input = s.inputStream
        while (input.available() > 0) input.read()
        s.outputStream.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        s.outputStream.flush()
        val result = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (input.available() > 0) {
                val v = input.read()
                if (v < 0) throw IllegalStateException("Bluetooth EOF")
                if (v == 62) {
                    val response = result.toString().trim()
                    traceCommand(cmd, response)
                    return response
                }
                result.append(v.toChar())
                if (result.length > 4096) throw IllegalStateException("Слишком длинный ответ")
            } else Thread.sleep(15)
        }
        traceCommand(cmd, "TIMEOUT: " + result.take(120))
        throw IllegalStateException("Тайм-аут " + cmd + ": " + result.take(80))
    }
    private fun response(raw: String, prefix: String): List<Int>? {
        val upper = raw.uppercase(Locale.US)
        if (listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "?").any { upper.contains(it) })
            return null
        val hex = upper.replace(Regex("[^0-9A-F]"), "")
        val pos = hex.indexOf(prefix)
        if (pos < 0) return null
        return hex.substring(pos + prefix.length).chunked(2)
            .filter { it.length == 2 }.mapNotNull { it.toIntOrNull(16) }
    }
    private fun decode(pid: String, data: List<Int>): String? {
        if (data.isEmpty()) return null
        val a = data[0]
        return when (pid) {
            "010C" -> if (data.size >= 2) ((a * 256 + data[1]) / 4).toString() + " об/мин" else null
            "0105", "010F" -> (a - 40).toString() + " °C"
            "010B" -> a.toString() + " кПа"
            "010D" -> a.toString() + " км/ч"
            "0111", "0104" -> "%.1f %%".format(Locale.US, a * 100.0 / 255)
            "0106", "0107" -> "%.1f %%".format(Locale.US, (a - 128) * 100.0 / 128)
            "0142" -> if (data.size >= 2)
                "%.2f В".format(Locale.US, (a * 256 + data[1]) / 1000.0) else null
            else -> null
        }
    }
    private fun parseDtc(raw: String): String {
        val hex = raw.uppercase(Locale.US).replace(Regex("[^0-9A-F]"), "")
        val start = hex.indexOf("43")
        if (start < 0) return "Mode 03: нет корректного ответа 43; " + raw
        val rest = hex.substring(start + 2)
        if (rest.length < 4 || rest.length % 2 != 0 || rest.contains("43"))
            return "Mode 03: неполный/неоднозначный ответ (" + raw + ")."
        val codes = mutableListOf<String>()
        for (pair in rest.chunked(4)) {
            if (pair.length < 4) break
            val a = pair.substring(0, 2).toIntOrNull(16) ?: break
            val b = pair.substring(2, 4).toIntOrNull(16) ?: break
            if (a == 0 && b == 0) continue
            codes.add("%c%01X%01X%02X".format("PCBU"[(a shr 6) and 3], (a shr 4) and 3, a and 15, b))
        }
        return if (codes.isEmpty()) "Стандартные DTC Mode 03 не обнаружены. Это не скан Xentry."
            else "Mode 03: " + codes.joinToString(", ")
    }
    @Synchronized private fun appendSample() {
        try {
            val row = listOf(System.currentTimeMillis().toString()) +
                pids.values.map { values[it] ?: "" }
            csvFile?.appendText(row.joinToString(",") {
                "\"" + it.replace("\"", "\"\"") + "\""
            } + "\n")
            samples++
        } catch (e: Exception) {
            recording = false
            detail = "Ошибка CSV: " + e.message
        }
    }
    fun history(): List<File> = File(filesDir, "trips").listFiles()
        ?.filter { it.extension == "csv" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    private fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        running = false
        close()
        recording = false
        instance = null
        super.onDestroy()
    }
}
