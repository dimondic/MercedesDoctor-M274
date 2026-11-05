package ru.mercedesdoctorai

import java.util.Locale

/** Standard emission-related OBD-II DTCs only (03 stored, 07 pending, 0A permanent).
 * Incomplete CAN/ISO-TP replies are never interpreted as "no errors".
 */
object DtcDecoder {
    data class Result(val codes: List<String>, val valid: Boolean, val raw: String) {
        fun describe(label: String): String =
            when {
                !valid -> "$label: нет достоверного ответа ЭБУ; проверьте журнал обмена."
                codes.isEmpty() -> "$label: стандартные DTC не обнаружены."
                else -> "$label: " + codes.joinToString(", ")
            }
    }

    fun decode(raw: String, mode: Int): Result {
        val prefix = when (mode) {
            3 -> 0x43
            7 -> 0x47
            10 -> 0x4A
            else -> return Result(emptyList(), false, raw)
        }
        val normalized = raw.uppercase(Locale.US)
        if (listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "?").any { normalized.contains(it) })
            return Result(emptyList(), false, raw)
        // Handles spaced and unspaced responses, optional 7E8 CAN header,
        // optional CAN frame length byte, and '0:' ELM line prefix.
        val payloads = mutableListOf<List<Int>>()
        for (line in normalized.split('\n', '\r')) {
            var text = line.trim()
            if (text.isEmpty() || text.startsWith("SEARCHING") || text == "03" ||
                text == "07" || text == "0A") continue
            text = text.replace(Regex("^\\s*\\d+\\s*:\\s*"), "")
            val hex = text.replace(Regex("[^0-9A-F]"), "")
            if (hex.length % 2 != 0 || hex.isEmpty()) continue
            var bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (bytes.size >= 3 && bytes[0] in 0x7E8..0x7EF) {
                // Note: normal byte parsing of a 3-digit header isn't valid;
                // ELM normally emits 7E8 as a separate token, handled below.
                bytes = bytes.drop(1)
            }
            val headerMatch = Regex("^(7E[89A-F])").find(hex)
            if (headerMatch != null) {
                val withoutHeader = hex.substring(3)
                if (withoutHeader.length % 2 != 0) continue
                bytes = withoutHeader.chunked(2).mapNotNull { it.toIntOrNull(16) }
            }
            // ISO-TP single frame: 06 43 XX XX ...
            if (bytes.size > 1 && bytes[0] in 1..7 && bytes[1] == prefix) {
                bytes = bytes.drop(1).take(bytes[0])
            }
            val pos = bytes.indexOf(prefix)
            if (pos < 0) continue
            val body = bytes.drop(pos + 1)
            if (body.isEmpty()) continue
            payloads.add(body)
        }
        if (payloads.isEmpty()) return Result(emptyList(), false, raw)
        val codes = mutableListOf<String>()
        var sawZero = false
        for (body in payloads) {
            if (body.size % 2 != 0) return Result(emptyList(), false, raw)
            for (pair in body.chunked(2)) {
                val a = pair[0]; val b = pair[1]
                if (a == 0 && b == 0) { sawZero = true; continue }
                val system = "PCBU"[(a shr 6) and 3]
                codes.add("%c%01X%01X%02X".format(Locale.US, system,
                    (a shr 4) and 3, a and 0x0F, b))
            }
        }
        return Result(codes.distinct(), codes.isNotEmpty() || sawZero, raw)
    }
}
