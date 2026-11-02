package ru.mercedesdoctorai

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Append-only measurements; every saved trip carries its own adapter metadata, DTCs and log. */
class SessionReports(private val root: File) {
    private var directory: File? = null
    private var csv: BufferedWriter? = null
    private var log: BufferedWriter? = null
    private var count = 0
    private var logCount = 0
    private var started = 0L
    private var vehicle = JSONObject()
    private var diagnostics = JSONObject().put("status", "NOT_REQUESTED").put("freeze_frames_status", "NOT_IMPLEMENTED")
    private var lastDirectory: File? = null
    val active get() = directory != null
    val rows get() = count

    fun metadata(version: String, protocol: String, support: Map<Int, ObdProtocol.State>) {
        val sensors = JSONObject()
        support.forEach { (pid, state) -> sensors.put("%02X".format(pid), state.name) }
        vehicle = JSONObject().put("vehicle", "Mercedes-Benz E200 W212").put("year", 2014)
            .put("engine", "M274").put("ecu_identity", JSONObject.NULL)
            .put("adapter", "ELM327 Bluetooth Classic").put("adapter_reported_version", version)
            .put("protocol", protocol).put("pid_support", sensors)
        directory?.let { File(it, "vehicle.json").writeText(vehicle.toString(2)) }
    }
    fun start() {
        check(!active)
        root.mkdirs()
        started = System.currentTimeMillis(); count = 0; logCount = 0
        val dir = File(root, "session-$started").apply { check(mkdir()) { "Не удалось создать запись" } }
        try {
            csv = File(dir, "sensors.csv").bufferedWriter().apply {
                appendLine("timestamp_ms,time_utc,pid,label,value,unit,status")
            }
            log = File(dir, "elm_log.txt").bufferedWriter()
            directory = dir
            File(dir, "vehicle.json").writeText(vehicle.toString(2))
            diagnostics = JSONObject().put("status", "NOT_REQUESTED").put("freeze_frames_status", "NOT_IMPLEMENTED")
            File(dir, "dtc.json").writeText(diagnostics.toString(2))
            saveSession(dir, false)
        } catch (e: Exception) {
            try { csv?.close() } catch (_: Exception) {}
            try { log?.close() } catch (_: Exception) {}
            csv = null; log = null; directory = null
            dir.deleteRecursively()
            throw e
        }
    }
    fun sample(sample: ElmConnection.Sample): Boolean {
        if (!active) return false
        val sensor = ObdProtocol.SENSORS.first { it.pid == sample.pid }
        csv?.appendLine(listOf(sample.time.toString(), Instant.ofEpochMilli(sample.time).toString(),
            "%02X".format(sample.pid), sensor.label, sample.value?.toString() ?: "", sensor.unit, sample.state.name)
            .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
        count++
        // Flush regularly so process death loses at most a small tail of samples.
        if (count % 10 == 0) csv?.flush()
        if (count >= 100000) { stop(); return true }
        return false
    }
    fun command(line: String) {
        if (!active) return
        if (logCount < 10000) {
            log?.appendLine(line)
            if (logCount % 10 == 0) log?.flush()
        } else if (logCount == 10000) log?.appendLine("LOG_TRUNCATED: 10000 command limit")
        logCount++
    }
    fun codes(time: Long, state: String, codes: List<String>?, raw: String) {
        diagnostics = JSONObject().put("timestamp_ms", time).put("status", state)
            .put("codes", codes?.let { JSONArray(it) } ?: JSONObject.NULL).put("raw", raw)
            .put("scope", "standard OBD-II Mode 03 only").put("freeze_frames_status", "NOT_IMPLEMENTED")
        directory?.let { File(it, "dtc.json").writeText(diagnostics.toString(2)) }
    }
    private fun saveSession(dir: File, complete: Boolean) {
        File(dir, "session.json").writeText(JSONObject().put("schema_version", 1).put("app_version", "0.3")
            .put("started_ms", started).put("ended_ms", if (complete) System.currentTimeMillis() else JSONObject.NULL)
            .put("sample_count", count).put("complete", complete).put("elm_log_truncated", logCount > 10000)
            .put("conditions", "User must describe engine temperature, load and driving conditions when sharing")
            .put("diagnostic_scope", "Standard OBD-II read only; no Mercedes-specific ECU functions")
            .toString(2))
    }
    fun stop() {
        val dir = directory ?: return
        // Reset active state even when storage is full; never keep writing to a broken session.
        directory = null
        try {
            try { csv?.close() } finally { log?.close() }
            saveSession(dir, true)
            lastDirectory = dir
        } finally { csv = null; log = null }
        sessions().drop(30).forEach { it.deleteRecursively() }
    }
    fun sessions(): List<File> = root.listFiles()?.filter {
        it.isDirectory && it.name.matches(Regex("session-[0-9]+")) && File(it, "sensors.csv").exists()
    }?.sortedByDescending { it.name } ?: emptyList()
    fun latest(): File? = directory ?: lastDirectory ?: sessions().firstOrNull()
    fun exportCsv(dir: File, target: File) {
        csv?.flush()
        File(dir, "sensors.csv").copyTo(target, overwrite = true)
    }
    fun export(dir: File, target: File) {
        csv?.flush(); log?.flush()
        if (dir == directory) saveSession(dir, false)
        ZipOutputStream(target.outputStream()).use { zip ->
            listOf("vehicle.json", "sensors.csv", "dtc.json", "elm_log.txt", "session.json").forEach { name ->
                val file = File(dir, name)
                check(file.isFile) { "Неполная запись: $name" }
                zip.putNextEntry(ZipEntry(name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
