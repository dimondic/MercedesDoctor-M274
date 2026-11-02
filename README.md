# Mercedes Doctor M274 · 0.4

Android 8+ (API 26), Mercedes-Benz E200 W212 2014 / M274, ELM327 **Bluetooth Classic** SPP. The debug APK compiles successfully with JDK 17 / Gradle 8.9. Six JVM tests pass (including 27 protocol checks); Android lint reports zero issues. Phone/vehicle operation has not been tested.

## Build

CI: `.github/workflows/build.yml` → **Run workflow**, or push a branch change to `main`. No GitHub write or workflow run is performed by these instructions.

Required: JDK 17, Gradle 8.9, Android SDK platform 35 and build-tools 35.0.0. The archive provides the root Gradle configuration (AGP 8.7.3 / Kotlin 2.0.21); the current `app/` directory is authoritative. Do not edit the generated `source/` directory.

```sh
python3 tools/prepare_project.py source
mkdir -p /tmp/obd-tests
javac -d /tmp/obd-tests app/src/main/java/ru/mercedesdoctorai/ObdProtocol.java app/src/test/java/ru/mercedesdoctorai/ObdProtocolCheck.java
java -cp /tmp/obd-tests ru.mercedesdoctorai.ObdProtocolCheck
gradle -p source --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

On a JDK image without the `javac` executable, use `java com.sun.tools.javac.Main` with the same arguments. Output: `source/app/build/outputs/apk/debug/app-debug.apk`. Debug signing supports installing over a previous APK only if its signing key is the same; CI keys may change. Export existing trips before reinstalling; uninstalling erases app data.

## Connection and measurements

1. Pair the ELM327 in Android Bluetooth settings; enable Bluetooth.
2. Open the app, select **Подключить ELM327 Bluetooth**, grant Nearby devices permission, and choose the paired adapter. No discovery scan/location permission is used.
3. The app initializes the ELM, records `ATI`, queries PID masks 0100 → 0120 → 0140 when advertised, and reads `ATDP` after the first ECU query. A reported ELM version does not establish authenticity.
4. Only advertised PIDs are polled. An unavailable support mask is **unknown**, not unsupported. Conflicting masks from different ECUs are deliberately not combined.
5. Each command completes before another starts, including Mode 03. Slow signals have independent intervals; errors back off up to 30 s. A command timeout closes the socket so delayed bytes cannot become another sensor's value. Initial ECU detection has a 12 s timeout. No concurrent command can overtake a pending ELM request.
6. Automatic connection/recovery is opt-in, applies to the selected paired address, and allows three retries, 5 s apart. Select Disconnect to cancel. All retries stop when the app leaves the foreground.

The ten standard PIDs are RPM, coolant/intake temperature, MAP, throttle, STFT/LTFT Bank 1, ECU voltage, calculated load and barometric pressure. MAP is **absolute manifold pressure**, not measured turbo boost. Fuel rail pressure, camshaft angles, target boost, gearbox data and ECU identity are not fabricated or inferred from the M274 label. No undocumented Mercedes-specific requests are sent.

The dashboard has configurable cards and per-sensor graphs (30–180 s window, min/max, up to 300 recent samples). Graphs are snapshots; reopen to refresh. Missing/error samples break the line; stale values become blank. No demo data are presented as measurements.

## Trips and reports

Recording appends individual timestamped samples to private storage rather than an unbounded in-memory list. CSV columns: `timestamp_ms,time_utc,pid,label,value,unit,status`. A missing value is blank with an explicit status. UTC timestamps include a timezone. Buffers flush every ten samples; a sudden process kill may lose the tail. An incomplete session remains identifiable by `complete:false`.

At most 30 new sessions are retained; 100000 samples ends a session automatically. v0.2 SharedPreferences history is preserved and still exports the original CSV. New sessions export a ZIP containing:

- `vehicle.json`: vehicle profile, self-reported adapter version, protocol and support masks; unknown ECU identity stays null.
- `sensors.csv`: measurements and error statuses, without substituting old readings.
- `dtc.json`: the session's Mode 03 result, raw reply and capture time; `NOT_REQUESTED` if not read during this session. Freeze frames are explicitly `NOT_IMPLEMENTED`.
- `elm_log.txt`: raw requests/replies with timestamps, durations and transport outcome; up to 10000 commands per trip, with an explicit truncation marker.
- `session.json`: schema/app version, times, completion state, sample count and limitations.

The connection diagnostic screen exports the last 500 commands independently, including setup before recording starts. ZIP export uses Android Share; it does not send data automatically or embed an API key. Describe measurement conditions when uploading the ZIP for analysis.

**Foreground workflow:** leaving the app (including opening a share target) closes the connection and saves the trip. Background recording/foreground service is not implemented. An export made while recording is a consistent partial snapshot (`complete:false`). Stop recording before exporting for a complete session. No automatic fault clearing, coding, adaptation or actuator control exists.

## Validation status and vehicle acceptance

Protocol regression checks can run without Android. They cover echo/spacing, byte bounds, voltage/RPM/trim formulas, support bit order, missing masks, conflicting ECU responses, malformed data and DTC decoding. Headered and multi-frame replies are rejected rather than guessed; consult the raw log if those occur.

Android build/lint and real ELM hardware checks are separate requirements. Before relying on a new APK, run CI, then test stationary with the actual phone/ELM: permission allow/deny, adapter unavailable, initial protocol search, unsupported PIDs, no-data, ignition changes, unplug/reconnect, repeated connection, rotation/background, recording, history and ZIP import. Compare sensor values against a known diagnostic instrument. Preserve the working APK and export trips before installation. This is standard OBD-II, not a full Xentry scan or a diagnosis of faulty parts.

## Prepared cloud environment

Toolchains and caches are retained under `/workspace/android-tools`. In this prepared snapshot, run `source tools/cloud_env.sh` from the repository before building. This refreshes Java CA trust and Gradle proxy settings from the current session without printing credentials. Use the existing checkout; do not create a Git worktree unless requested. Network downloads in this managed executor require an execution context with network access; offline builds can use `--offline` once dependencies are cached. No app server is needed.

## 0.4 dashboard and signed release

The main screen now has an RPM gauge, individual sensor cards, a visible app version, and four fixed navigation tabs. Trips, codes and adapter setup have separate pages. No readings are fabricated when disconnected. The supplied older white screen belongs to the pre-0.3 UI.

The signed release APK is published in `downloads/` on branch `codex/m274-v0.3-apk`. This is a locally signed prebuilt APK, not an APK signed by GitHub with a debug key. The private RSA release key and password stay outside the checkout in `/workspace/mercedesdoctor-signing/`; never commit or publish them. Keep a secure backup before deleting this environment. Losing this key prevents compatible updates under the same app identity.

After building `:app:assembleRelease`, run `python3 tools/sign_release.py /workspace/mercedesdoctor-v04/app/build/outputs/apk/release/app-release-unsigned.apk downloads/MercedesDoctor-M274-v0.4-release.apk` in the activated cloud environment. The signer verifies the output and rejects a debuggable APK. CI can build and test unsigned release sources, but does not have the private signing key. It uploads the explicitly committed signed APK separately. Updates must be signed with the same release key.

Android may refuse to install this release as an update over a debug-signed APK. Export old trips before changing installations. Do not uninstall an app with data you have not exported.
