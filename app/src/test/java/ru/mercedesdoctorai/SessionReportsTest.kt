package ru.mercedesdoctorai

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class SessionReportsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun recorder(): SessionReports = SessionReports(temp.newFolder()).apply {
        metadata("ELM327 v1.5", "ISO 15765-4 (CAN 11/500)", mapOf(0x0C to ObdProtocol.State.OK))
    }
    @Test fun protocolRegression() { ObdProtocolCheck.main(emptyArray()) }
    @Test fun recordingPreservesInvalidValuesAndUtcTime() {
        val r = recorder(); r.start()
        r.sample(ElmConnection.Sample(0x0C, 1700000000000L, ObdProtocol.State.OK, 780.0))
        r.sample(ElmConnection.Sample(0x0C, 1700000001000L, ObdProtocol.State.NO_DATA, null))
        val dir = r.latest()!!; r.stop()
        val lines = File(dir, "sensors.csv").readLines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("2023-11-14T22:13:20Z"))
        assertTrue(lines[1].contains("\"780.0\""))
        assertTrue(lines[2].contains("\"\",\"об/мин\",\"NO_DATA\""))
        assertFalse(lines[2].contains("780"))
        assertEquals(2, JSONObject(File(dir, "session.json").readText()).getInt("sample_count"))
        assertFalse(r.active)
    }
    @Test fun activeExportIsAnExplicitPartialSnapshot() {
        val r = recorder(); r.start()
        r.sample(ElmConnection.Sample(0x42, 1000L, ObdProtocol.State.OK, 14.2))
        val zip = File(temp.root, "active.zip")
        r.export(r.latest()!!, zip)
        ZipFile(zip).use { z ->
            assertEquals(setOf("vehicle.json", "sensors.csv", "dtc.json", "elm_log.txt", "session.json"), z.entries().asSequence().map { it.name }.toSet())
            val json = JSONObject(z.getInputStream(z.getEntry("session.json")).bufferedReader().readText())
            assertFalse(json.getBoolean("complete"))
            assertEquals(1, json.getInt("sample_count"))
            val dtc = JSONObject(z.getInputStream(z.getEntry("dtc.json")).bufferedReader().readText())
            assertEquals("NOT_REQUESTED", dtc.getString("status"))
        }
        r.stop()
    }
    @Test fun stoppedExportContainsSessionSpecificCodesAndLog() {
        val r = recorder(); r.start()
        r.command("raw ELM command response")
        r.codes(1234L, "OK", listOf("P0133"), "43010133")
        val dir = r.latest()!!; r.stop()
        val zip = File(temp.root, "complete.zip"); r.export(dir, zip)
        ZipFile(zip).use { z ->
            fun read(name: String) = z.getInputStream(z.getEntry(name)).bufferedReader().readText()
            assertTrue(JSONObject(read("session.json")).getBoolean("complete"))
            assertEquals("P0133", JSONObject(read("dtc.json")).getJSONArray("codes").getString(0))
            assertTrue(read("elm_log.txt").contains("raw ELM command response"))
            assertTrue(JSONObject(read("vehicle.json")).isNull("ecu_identity"))
        }
    }
    @Test fun reopeningStoreFindsPreviousSessionWithoutChangingIt() {
        val root = temp.newFolder(); val r = SessionReports(root)
        r.start(); val dir = r.latest()!!; r.stop()
        val before = File(dir, "session.json").readText()
        val reopened = SessionReports(root)
        assertEquals(dir, reopened.latest())
        assertEquals(1, reopened.sessions().size)
        assertEquals(before, File(dir, "session.json").readText())
    }
    @Test fun boundedLogMarksTruncation() {
        val r = recorder(); r.start()
        repeat(10002) { r.command("response-$it") }
        val dir = r.latest()!!; r.stop()
        assertTrue(JSONObject(File(dir, "session.json").readText()).getBoolean("elm_log_truncated"))
        val lines = File(dir, "elm_log.txt").readLines()
        assertEquals(10001, lines.size)
        assertTrue(lines.last().startsWith("LOG_TRUNCATED"))
        assertFalse(lines.any { it.contains("response-10001") })
    }
}
