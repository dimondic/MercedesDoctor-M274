#!/usr/bin/env python3
"""Sign an unsigned release APK with the private key retained outside the checkout."""
from pathlib import Path
import hashlib
import os
import subprocess
import sys

if len(sys.argv) != 3:
    raise SystemExit('Usage: python3 tools/sign_release.py UNSIGNED_APK OUTPUT_APK')
source = Path(sys.argv[1]).resolve()
output = Path(sys.argv[2]).resolve()
key_dir = Path('/workspace/mercedesdoctor-signing')
sdk = Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0'
assert (key_dir/'m274-release.p12').is_file(), 'Release key is required; never replace it to sign an update'
assert (key_dir/'storepass').is_file(), 'Release key password file is required'
assert source != output and source.is_file()
output.parent.mkdir(parents=True, exist_ok=True)
aligned = output.with_suffix('.aligned.apk')
try:
    subprocess.run([str(sdk/'zipalign'),'-f','-p','4',str(source),str(aligned)],check=True)
    subprocess.run([str(sdk/'apksigner'),'sign','--ks',str(key_dir/'m274-release.p12'),
                    '--ks-key-alias','mercedesdoctor','--ks-pass','file:'+str(key_dir/'storepass'),
                    '--out',str(output),str(aligned)],check=True)
    subprocess.run([str(sdk/'apksigner'),'verify','--verbose','--print-certs',str(output)],check=True)
    info = subprocess.check_output([str(sdk/'aapt'),'dump','badging',str(output)],text=True)
    assert 'application-debuggable' not in info, 'Refusing debuggable APK'
    output.with_suffix('.apk.sha256').write_text(hashlib.sha256(output.read_bytes()).hexdigest()+'  '+output.name+'\n')
finally:
    aligned.unlink(missing_ok=True)
    output.with_suffix('.apk.idsig').unlink(missing_ok=True)
