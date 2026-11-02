package ru.mercedesdoctorai;

import java.util.*;

/** Headerless, single-frame OBD responses only. Never splice replies from different ECUs. */
public final class ObdProtocol {
    private ObdProtocol() {}
    public enum State { OK, NO_DATA, UNSUPPORTED, UNKNOWN_SUPPORT, INVALID, ADAPTER_ERROR, DISCONNECTED }
    public static final class Sensor {
        public final int pid, bytes;
        public final String label, unit;
        public final long intervalMs;
        Sensor(int pid, String label, String unit, int bytes, long intervalMs) {
            this.pid = pid; this.label = label; this.unit = unit; this.bytes = bytes; this.intervalMs = intervalMs;
        }
        public String command() { return String.format(Locale.US, "01%02X", pid); }
        public double decode(int[] b) {
            switch (pid) {
                case 0x0C: return (b[0] * 256 + b[1]) / 4.0;
                case 0x05: case 0x0F: return b[0] - 40;
                case 0x04: case 0x11: return b[0] * 100.0 / 255;
                case 0x06: case 0x07: return (b[0] - 128) * 100.0 / 128;
                case 0x42: return (b[0] * 256 + b[1]) / 1000.0;
                default: return b[0];
            }
        }
    }
    public static final List<Sensor> SENSORS = Collections.unmodifiableList(Arrays.asList(
        new Sensor(0x0C, "Обороты", "об/мин", 2, 500),
        new Sensor(0x05, "Охлаждающая жидкость", "°C", 1, 3000),
        new Sensor(0x0F, "Воздух на впуске", "°C", 1, 3000),
        new Sensor(0x0B, "MAP · абсолютное", "кПа абс.", 1, 750),
        new Sensor(0x11, "Дроссель", "%", 1, 750),
        new Sensor(0x06, "STFT · Bank 1", "%", 1, 1500),
        new Sensor(0x07, "LTFT · Bank 1", "%", 1, 3000),
        new Sensor(0x42, "Напряжение ЭБУ", "В", 2, 3000),
        new Sensor(0x04, "Нагрузка двигателя", "%", 1, 1500),
        new Sensor(0x33, "Атмосферное давление", "кПа", 1, 10000)
    ));
    public static final class Reply {
        public final State state;
        public final List<int[]> frames;
        Reply(State state, List<int[]> frames) { this.state = state; this.frames = frames; }
    }
    public static Reply parse(String command, String raw, int service, int pid, int minimumBytes) {
        List<int[]> frames = new ArrayList<>();
        boolean malformed = false;
        State error = null;
        for (String line : raw.toUpperCase(Locale.US).split("[\\r\\n]+")) {
            String s = line.replaceAll("\\s", "").replace(">", "");
            if (s.isEmpty() || s.equals(command.toUpperCase(Locale.US))) continue;
            if (s.startsWith("SEARCHING...")) s = s.substring("SEARCHING...".length());
            if (s.isEmpty()) continue;
            if (s.equals("NODATA")) { error = State.NO_DATA; continue; }
            if (s.equals("?") || s.contains("ERROR") || s.contains("UNABLETOCONNECT") ||
                    s.contains("STOPPED") || s.contains("BUSINIT") || s.contains("BUFFERFULL")) {
                error = State.ADAPTER_ERROR; continue;
            }
            if (!s.matches("[0-9A-F]+") || s.length() % 2 != 0) { malformed = true; continue; }
            int[] bytes = new int[s.length() / 2];
            for (int i = 0; i < bytes.length; i++) bytes[i] = Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
            int offset = pid < 0 ? 1 : 2;
            if (bytes.length < offset + minimumBytes || bytes[0] != service || (pid >= 0 && bytes[1] != pid)) {
                malformed = true; continue;
            }
            frames.add(Arrays.copyOfRange(bytes, offset, bytes.length));
        }
        // A valid frame mixed with errors or malformed/multiframe data is not a trustworthy value.
        State state = malformed ? State.INVALID : error != null ? error : frames.isEmpty() ? State.INVALID : State.OK;
        return new Reply(state, Collections.unmodifiableList(frames));
    }
    /** Null for missing or conflicting ECU replies: do not silently select an arbitrary ECU. */
    public static Double value(Sensor sensor, Reply reply) {
        if (reply.state != State.OK) return null;
        Double value = null;
        for (int[] frame : reply.frames) {
            if (frame.length < sensor.bytes) return null;
            double next = sensor.decode(frame);
            if (value != null && Double.compare(value, next) != 0) return null;
            value = next;
        }
        return value;
    }
    /** Require identical support masks across ECUs to avoid mixing engine/transmission data. */
    public static Set<Integer> supported(int base, Reply reply) {
        if (reply.state != State.OK || reply.frames.isEmpty()) return null;
        int[] first = reply.frames.get(0);
        if (first.length < 4) return null;
        for (int[] frame : reply.frames) {
            if (frame.length < 4 || !Arrays.equals(Arrays.copyOf(first, 4), Arrays.copyOf(frame, 4))) return null;
        }
        Set<Integer> pids = new HashSet<>();
        for (int bit = 0; bit < 32; bit++)
            if ((first[bit / 8] & (1 << (7 - bit % 8))) != 0) pids.add(base + bit + 1);
        return pids;
    }
    public static List<String> troubleCodes(Reply reply, boolean can) {
        if (reply.state != State.OK) return null;
        Set<String> codes = new LinkedHashSet<>();
        for (int[] frame : reply.frames) {
            // ISO 15765 adds a DTC count byte; older protocols start with the first code.
            int offset = can ? 1 : 0;
            if (frame.length < offset) return null;
            int end = can ? 1 + frame[0] * 2 : frame.length;
            if (end > frame.length || (!can && frame.length % 2 != 0)) return null;
            for (int i = end; i < frame.length; i++) if (frame[i] != 0) return null;
            for (int i = offset; i < end; i += 2) {
                int a = frame[i], b = frame[i + 1];
                if (a == 0 && b == 0) { if (can) return null; else continue; }
                codes.add(String.format(Locale.US, "%c%01X%01X%02X", "PCBU".charAt(a >> 6), (a >> 4) & 3, a & 15, b));
            }
        }
        return new ArrayList<>(codes);
    }
}
