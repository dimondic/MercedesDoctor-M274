package ru.mercedesdoctorai

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One serial queue owns all adapter I/O, polling and Mode 03 requests. */
class ElmConnection(
    private val device: BluetoothDevice,
    private val listener: Listener,
    private val reconnect: Boolean
) {
    data class Sample(val pid: Int, val time: Long, val state: ObdProtocol.State, val value: Double?)
    interface Listener {
        fun status(text: String, connected: Boolean)
        fun metadata(version: String, protocol: String, support: Map<Int, ObdProtocol.State>)
        fun sample(sample: Sample)
        fun codes(time: Long, state: String, codes: List<String>?, raw: String)
        fun command(time: Long, command: String, raw: String, outcome: String, duration: Long)
    }
    private val queue = Executors.newSingleThreadScheduledExecutor()
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false
    @Volatile private var socket: BluetoothSocket? = null
    private var connected = false
    private var canDtc: Boolean? = null
    private var retries = 0
    private var poll: ScheduledFuture<*>? = null
    private val support = mutableMapOf<Int, ObdProtocol.State>()
    private val due = mutableMapOf<Int, Long>()
    private val failures = mutableMapOf<Int, Int>()

    fun start() { queue.execute { open() } }
    fun close() {
        closed = true
        connected = false
        try { socket?.close() } catch (_: IOException) {}
        queue.shutdownNow()
        watchdog.shutdownNow()
    }

    private fun open() {
        if (closed) return
        listener.status("Подключение к ELM327…", false)
        support.clear(); due.clear(); failures.clear()
        ObdProtocol.SENSORS.forEach {
            listener.sample(Sample(it.pid, System.currentTimeMillis(), ObdProtocol.State.DISCONNECTED, null))
        }
        try {
            val s = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            socket = s
            if (closed) { s.close(); return }
            val deadline = watchdog.schedule({ try { s.close() } catch (_: IOException) {} }, 12, TimeUnit.SECONDS)
            try { s.connect() } finally { deadline.cancel(false) }
            if (closed) return
            exchange("ATZ", 4000)
            listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { cmd ->
                val raw = exchange(cmd)
                if (raw.lineSequence().none { it.trim().equals("OK", true) })
                    throw IOException("ELM327 не подтвердил $cmd")
            }
            val version = exchange("ATI").replace(Regex("[\\r\\n>]+"), " ").trim()
            discover(0)
            // Protocol is meaningful after first ECU query, not directly after ATSP0.
            val protocol = exchange("ATDP").replace(Regex("[\\r\\n>]+"), " ").trim()
            canDtc = when {
                protocol.contains("15765", true) -> true
                listOf("9141", "14230", "J1850").any { protocol.contains(it, true) } -> false
                else -> null
            }
            listener.metadata(version, protocol, support.toMap())
            connected = true
            val available = support.values.count { it == ObdProtocol.State.OK }
            listener.status("ELM327 подключён · $protocol\nПодтверждено параметров: $available из ${ObdProtocol.SENSORS.size}", true)
            schedulePoll(0)
        } catch (e: SecurityException) {
            // Permission can be revoked after the Activity check; do not retry a denied permission.
            retries = 3
            failed(e)
        } catch (e: Exception) { failed(e) }
    }

    private fun discover(base: Int) {
        val command = String.format(Locale.US, "01%02X", base)
        val raw = exchange(command, if (base == 0) 12000 else 3000)
        val response = ObdProtocol.parse(command, raw, 0x41, base, 4)
        val supported = ObdProtocol.supported(base, response)
        ObdProtocol.SENSORS.filter { it.pid > base && it.pid <= base + 32 }.forEach {
            val state = when {
                supported == null -> ObdProtocol.State.UNKNOWN_SUPPORT
                it.pid in supported -> ObdProtocol.State.OK
                else -> ObdProtocol.State.UNSUPPORTED
            }
            support[it.pid] = state
            listener.sample(Sample(it.pid, System.currentTimeMillis(), if (state == ObdProtocol.State.OK) ObdProtocol.State.NO_DATA else state, null))
        }
        if (base >= 0x40) return
        if (supported?.contains(base + 32) == true) discover(base + 32)
        else ObdProtocol.SENSORS.filter { it.pid > base + 32 }.forEach {
            val state = if (supported == null) ObdProtocol.State.UNKNOWN_SUPPORT else ObdProtocol.State.UNSUPPORTED
            support[it.pid] = state
            listener.sample(Sample(it.pid, System.currentTimeMillis(), state, null))
        }
    }

    private fun schedulePoll(delay: Long) {
        if (!closed) poll = queue.schedule({ pollOne() }, delay, TimeUnit.MILLISECONDS)
    }
    private fun pollOne() {
        if (closed || !connected) return
        val now = SystemClock.elapsedRealtime()
        val available = ObdProtocol.SENSORS.filter { support[it.pid] == ObdProtocol.State.OK }
        val next = available.minByOrNull { due[it.pid] ?: 0L }
        if (next == null) { schedulePoll(1000); return }
        val wait = (due[next.pid] ?: 0L) - now
        if (wait > 0) { schedulePoll(wait.coerceAtMost(1000)); return }
        try {
            val raw = exchange(next.command())
            val reply = ObdProtocol.parse(next.command(), raw, 0x41, next.pid, next.bytes)
            val value = ObdProtocol.value(next, reply)
            val state = if (reply.state == ObdProtocol.State.OK && value == null) ObdProtocol.State.INVALID else reply.state
            listener.sample(Sample(next.pid, System.currentTimeMillis(), state, value))
            val count = if (state == ObdProtocol.State.OK) 0 else (failures[next.pid] ?: 0) + 1
            failures[next.pid] = count
            // A slow/unsupported-at-runtime PID backs off; fast signals keep their own schedule.
            due[next.pid] = SystemClock.elapsedRealtime() + if (count == 0) next.intervalMs else (5000L * count).coerceAtMost(30000)
            if (state == ObdProtocol.State.OK) retries = 0
            schedulePoll(40)
        } catch (e: Exception) { failed(e) }
    }

    fun readCodes() {
        if (closed) return
        queue.execute {
            if (!connected) {
                listener.codes(System.currentTimeMillis(), "DISCONNECTED", null, "")
                return@execute
            }
            try {
                val raw = exchange("03", 5000)
                val reply = ObdProtocol.parse("03", raw, 0x43, -1, 0)
                val codes = canDtc?.let { ObdProtocol.troubleCodes(reply, it) }
                val state = if (reply.state == ObdProtocol.State.OK && codes == null) "INVALID" else reply.state.name
                listener.codes(System.currentTimeMillis(), state, codes, raw)
            } catch (e: Exception) { failed(e) }
        }
    }

    /** A timeout invalidates the whole link, so a late response can never become the next PID. */
    private fun exchange(command: String, timeoutMs: Long = 2500): String {
        if (closed) throw IOException("Соединение закрыто")
        val s = socket ?: throw IOException("Нет Bluetooth-соединения")
        val start = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val raw = StringBuilder()
        var outcome = "IO_ERROR"
        val deadline = watchdog.schedule({ try { s.close() } catch (_: IOException) {} }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            val out = s.outputStream
            val input = s.inputStream
            out.write((command + "\r").toByteArray(Charsets.US_ASCII)); out.flush()
            while (!closed && SystemClock.elapsedRealtime() - start < timeoutMs) {
                if (input.available() == 0) { Thread.sleep(15); continue }
                val byte = input.read()
                if (byte < 0) throw IOException("Bluetooth закрыл поток")
                if (byte == 62) { outcome = "PROMPT"; return raw.toString() }
                if (raw.length >= 16384) throw IOException("Слишком длинный ответ ELM327")
                raw.append(byte.toChar())
            }
            outcome = if (closed) "CANCELLED" else "TIMEOUT"
            throw IOException(if (closed) "Соединение закрыто" else "Тайм-аут $command; соединение сброшено")
        } finally {
            deadline.cancel(false)
            listener.command(wall, command, raw.toString(), outcome, SystemClock.elapsedRealtime() - start)
            if (outcome != "PROMPT") try { s.close() } catch (_: IOException) {}
        }
    }

    private fun failed(error: Exception) {
        connected = false
        poll?.cancel(false)
        try { socket?.close() } catch (_: IOException) {}
        if (closed) return
        ObdProtocol.SENSORS.forEach {
            listener.sample(Sample(it.pid, System.currentTimeMillis(), ObdProtocol.State.DISCONNECTED, null))
        }
        if (reconnect && retries < 3) {
            retries++
            listener.status("${error.message} · повтор $retries/3 через 5 с", false)
            queue.schedule({ open() }, 5, TimeUnit.SECONDS)
        } else listener.status("Связь недоступна: ${error.message}. Подключитесь повторно.", false)
    }
}
