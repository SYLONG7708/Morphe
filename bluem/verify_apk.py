import hashlib, json, re, subprocess, sys, zipfile, os
from pathlib import Path
sys.stdout.reconfigure(encoding='utf-8')
apk = Path(sys.argv[1]).resolve()
dest = Path(sys.argv[2]).resolve()
dest.mkdir(parents=True, exist_ok=True)
bt = Path(os.environ.get('ANDROID_HOME', 'C:/Users/Long/AppData/Local/Android/Sdk'))/'build-tools/36.0.0'
def tool(name):
    return bt/(name + (('.bat' if name=='apksigner' else '.exe') if os.name=='nt' else ''))
def run(args):
    r = subprocess.run([str(x) for x in args], capture_output=True, timeout=90, creationflags=0x08000000 if os.name == 'nt' else 0)
    assert r.returncode == 0, (r.stdout+r.stderr).decode('utf-8', errors='replace')
    return r.stdout.decode('utf-8', errors='replace')
badging = run([tool('aapt'), 'dump', 'badging', apk])
signing = run([tool('apksigner'), 'verify', '--verbose', '--print-certs', apk])
run([tool('zipalign'), '-c', '-P', '16', '4', apk])
match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
with zipfile.ZipFile(apk) as archive:
    # aapt may split supported architectures between native-code and alt-native-code.
    abis = sorted({name.split('/')[1] for name in archive.namelist()
                   if name.startswith('lib/') and name.endswith('.so')})
result = dict(file=apk.name, size=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
              package=match[1], version_code=int(match[2]), version_name=match[3],
              signer_sha256=re.findall(r'certificate SHA-256 digest: (\w+)', signing),
              min_sdk=re.search(r"sdkVersion:'(\d+)'", badging)[1],
              abis=abis, signature_verified=True, zipalign16k=True)
(dest/(apk.stem+'-identity.json')).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
(dest/(apk.stem+'-signing.txt')).write_text(signing, encoding='utf-8')
print(json.dumps(result, ensure_ascii=False, indent=2))
