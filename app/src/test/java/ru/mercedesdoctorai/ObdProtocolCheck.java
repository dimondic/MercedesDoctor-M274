package ru.mercedesdoctorai;

import java.util.*;

/** Dependency-free regression runner, also run by CI before APK build. */
public class ObdProtocolCheck {
    static int checks = 0;
    static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
    static ObdProtocol.Sensor sensor(int pid) {
        return ObdProtocol.SENSORS.stream().filter(s -> s.pid == pid).findFirst().get();
    }
    static Double value(int pid, String raw) {
        ObdProtocol.Sensor s = sensor(pid);
        return ObdProtocol.value(s, ObdProtocol.parse(s.command(), raw, 0x41, pid, s.bytes));
    }
    public static void main(String[] args) {
        check(value(0x0C, "010C\rSEARCHING...\r41 0C 0C 30\r>") == 780.0, "echo, spaces, searching, RPM");
        check(value(0x42, "41423778\r>") == 14.2, "two-byte ECU voltage");
        check(value(0x05, "41055A\r>") == 50.0, "single-byte temperature does not read past end");
        check(value(0x06, "410680\r>") == 0.0, "neutral fuel trim");
        check(value(0x07, "410700\r>") == -100.0, "negative fuel trim");
        check(value(0x42, "414237\r>") == null, "reject truncated voltage");
        check(value(0x0C, "410C0C\r30\r>") == null, "never combine incomplete frames");
        check(value(0x0C, "7E804410C0C30\r>") == null, "reject unexpected CAN headers");
        check(value(0x0C, "410C0C30\r410C0FA0\r>") == null, "conflicting ECUs");
        check(value(0x0C, "410C0C30\r410C0C30\r>") == 780.0, "identical ECU replies");
        check(value(0x0C, "410C0C30\rNO DATA\r>") == null, "mixed error and value");
        check(ObdProtocol.parse("010C", "NO DATA\r>", 65, 12, 2).state == ObdProtocol.State.NO_DATA, "no data distinct from zero");
        check(ObdProtocol.parse("010C", "CAN ERROR\r>", 65, 12, 2).state == ObdProtocol.State.ADAPTER_ERROR, "adapter error");
        check(value(0x0C, "410CZZZZ\r>") == null, "non hex rejected");
        Set<Integer> mask = ObdProtocol.supported(0, ObdProtocol.parse("0100", "410080000001\r>", 65, 0, 4));
        check(mask.equals(new HashSet<>(Arrays.asList(1, 32))), "support mask bit order and continuation");
        check(ObdProtocol.supported(0x40, ObdProtocol.parse("0140", "414040000000\r>", 65, 64, 4)).contains(0x42), "higher PID page");
        check(ObdProtocol.supported(0, ObdProtocol.parse("0100", "410080000001\r410000000000\r>",65,0,4)) == null, "conflicting support masks unknown");
        check(ObdProtocol.supported(0, ObdProtocol.parse("0100", "NO DATA\r>",65,0,4)) == null, "missing support unknown");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "430133C1230000\r>",67,-1,0), false).equals(Arrays.asList("P0133", "U0123")), "DTC decode and padding");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "43000000000000\r>",67,-1,0), false).isEmpty(), "confirmed empty standard DTC response");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "NO DATA\r>",67,-1,0), false) == null, "no data is not no codes");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "4301\r>",67,-1,0), false) == null, "truncated DTC");
        check(value(0x0B, "410B27\r>") == 39.0, "MAP remains absolute kPa");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "43010133000000\r>",67,-1,0), true).equals(Arrays.asList("P0133")), "CAN DTC count and padding");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "43000000000000\r>",67,-1,0), true).isEmpty(), "CAN zero count");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "43020133\r>",67,-1,0), true) == null, "CAN count exceeding payload");
        check(ObdProtocol.troubleCodes(ObdProtocol.parse("03", "430101330001\r>",67,-1,0), true) == null, "CAN nonzero trailing data rejected");
        System.out.println("Passed " + checks + " OBD protocol regression checks");
    }
}
