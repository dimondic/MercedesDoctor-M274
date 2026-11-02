#!/usr/bin/env bash
# Source in the prepared cloud snapshot; contains no credential values.
export JAVA_HOME=/workspace/android-tools/jdk-17.0.17+10
export ANDROID_HOME=/workspace/android-tools/sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME=/workspace/android-tools/gradle-home
if [[ ! -x "$JAVA_HOME/bin/java" || ! -x /workspace/android-tools/gradle-8.9/bin/gradle || ! -d "$ANDROID_HOME/platforms/android-35" ]]; then
    echo 'Prepared Android toolchain is absent; install JDK 17, Gradle 8.9 and Android SDK 35 before using this cloud helper.' >&2
    return 1
fi
export PATH="$JAVA_HOME/bin:/workspace/android-tools/gradle-8.9/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
python3 - <<'PY'
from pathlib import Path
from urllib.parse import urlsplit
import os, shutil, subprocess
root = Path('/workspace/android-tools')
trust = root / 'cacerts'
shutil.copy2(Path(os.environ['JAVA_HOME']) / 'lib/security/cacerts', trust)
cert = os.environ.get('CODEX_PROXY_CERT')
if cert and Path(cert).is_file():
    subprocess.run([str(Path(os.environ['JAVA_HOME']) / 'bin/keytool'), '-importcert', '-noprompt',
                    '-alias', 'cloud-session-proxy', '-file', cert, '-keystore', str(trust),
                    '-storepass', 'changeit'], check=True)
gradle = Path(os.environ['GRADLE_USER_HOME'])
gradle.mkdir(parents=True, exist_ok=True)
properties = ['org.gradle.workers.max=2', 'org.gradle.java.installations.paths=' + os.environ['JAVA_HOME']]
for scheme in ('http', 'https'):
    proxy = urlsplit(os.environ.get(scheme.upper() + '_PROXY', ''))
    if proxy.hostname:
        properties += [f'systemProp.{scheme}.proxyHost={proxy.hostname}', f'systemProp.{scheme}.proxyPort={proxy.port or 80}']
properties += ['systemProp.http.nonProxyHosts=localhost|127.*']
(gradle / 'gradle.properties').write_text('\n'.join(properties) + '\n')
PY
cloud_env_status=$?
if [[ "$cloud_env_status" != 0 ]]; then return "$cloud_env_status"; fi
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Djavax.net.ssl.trustStore=/workspace/android-tools/cacerts"
unset cloud_env_status
