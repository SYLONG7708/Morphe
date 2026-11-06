"""Build a portable, prepatched two-APK release. Private keys stay in the secure directory."""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SDK = Path(os.environ.get('ANDROID_HOME', 'C:/Users/Long/AppData/Local/Android/Sdk'))
JAVA = Path(os.environ.get('BLUE_M_JAVA_HOME', os.environ.get('JAVA_HOME', 'C:/Users/Long/Temp/tools/jdk21/jdk-21.0.11+10'))) / 'bin'
CACHE = Path('C:/Users/Long/.gradle/caches/modules-2/files-2.1')
SECURE = Path('C:/Users/Long/.secure/symorphe-production')
BASE_HASH_FILE = ROOT / 'inputs/recommended-base-sha256.txt'
CERT = '7cee829c140e3ba32767e541e98d99a87214b80c7b3095692549131a2d6ebf02'
CHANNEL_URL = 'https://raw.githubusercontent.com/SYLONG7708/Morphe/main/channels/blue-m-stable.json'

def digest(path):
    return hashlib.file_digest(open(path, 'rb'), 'sha256').hexdigest()

def cached(pattern):
    matches = list(CACHE.glob(pattern))
    assert len(matches) == 1, (pattern, len(matches))
    return matches[0]

def jtool(name):
    return JAVA / (name + ('.exe' if os.name == 'nt' else ''))

def atool(name):
    suffix = ('.bat' if name in ('apksigner', 'd8') else '.exe') if os.name == 'nt' else ''
    return SDK / 'build-tools/36.0.0' / (name + suffix)

def tool_classpath():
    jars = sorted((ROOT/'inputs').glob('morphe-desktop-*-all.jar'))
    if not jars: raise RuntimeError('Verified desktop CLI missing')
    return os.pathsep.join([str(ROOT/'build/tools'), str(jars[-1])])

def signing_env(update=False):
    config = json.loads((SECURE/'production-secrets.json').read_text(encoding='utf-8-sig')) if os.name == 'nt' else dict(os.environ)
    names = (['UPDATE_SIGNING_P12_FILE', 'UPDATE_SIGNING_STORE_PASSWORD', 'UPDATE_SIGNING_KEY_ALIAS', 'UPDATE_SIGNING_KEY_PASSWORD']
             if update else ['SYMORPHE_KEYSTORE_FILE', 'KEYSTORE_PASSWORD', 'KEYSTORE_ENTRY_ALIAS', 'KEYSTORE_ENTRY_PASSWORD'])
    env = os.environ.copy()
    for name in names:
        assert config.get(name), 'Incomplete protected signing configuration'
        env[name] = config[name]
    if not update and os.name == 'nt':
        assert Path(config['SYMORPHE_KEYSTORE_FILE']).resolve() == (SECURE/'symorphe-release.jks').resolve()
        assert config['SYMORPHE_EXPECTED_SIGNING_CERT_SHA256'].replace(':', '').lower() == CERT
    elif update and os.name == 'nt':
        assert Path(config['UPDATE_SIGNING_P12_FILE']).resolve() == (SECURE/'morphe-update-signing.p12').resolve()
    config.clear()
    env['JAVA_HOME'] = str(JAVA.parent)
    return env

def run(args, log, env=None):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=0x08000000 if os.name == 'nt' else 0)
    if result.returncode:
        raise RuntimeError('Build step failed; inspect build log (' + str(result.returncode) + ')')

def compile_tools(log):
    dest = ROOT/'build/tools'
    dest.mkdir(parents=True, exist_ok=True)
    run([jtool('javac'), '-encoding', 'UTF-8', '-cp', tool_classpath()+os.pathsep+str(SDK/'platforms/android-37.0/android.jar'),
         '-d', dest, *sorted((ROOT/'src/tools').glob('*.java')), ROOT/'src/tests/DexPartitionWriterTest.java'], log)
    run([jtool('java'), '-Xmx1g', '-cp', tool_classpath(), 'DexPartitionWriterTest', ROOT/'build/dex-tests'], log)

def sign_manifest(source, destination, log):
    run([jtool('java'), '-cp', ROOT/'build/tools', 'SignManifest', source, destination, ROOT/'assets/release-cert.der'], log, signing_env(True))

def package_unsigned(source, patched, client_dex, certificate, destination):
    rewritten = {p.name: p for p in patched.iterdir() if p.is_file()}
    with zipfile.ZipFile(source) as original:
        original_names = set(original.namelist())
        replaced = set(rewritten) & original_names
        added = set(rewritten) - original_names
        if (not 2 <= len(replaced) <= 5 or 'AndroidManifest.xml' not in replaced
                or any(not re.fullmatch(r'classes(\d*)\.dex', name)
                       for name in set(rewritten) - {'AndroidManifest.xml'})):
            raise ValueError('Unexpected patched APK entries')
        last_dex = max(int(m[1] or '1') for name in original_names
                       if (m := re.fullmatch(r'classes(\d*)\.dex', name)))
        extra_dex = ['classes'+str(last_dex+i+1)+'.dex' for i in range(len(added))]
        if added != set(extra_dex):
            raise ValueError('Non-contiguous overflow DEX entries')
        with zipfile.ZipFile(destination, 'w', allowZip64=False) as target:
            for info in original.infolist():
                if info.filename == 'stamp-cert-sha256' or re.fullmatch(r'META-INF/(?:[^/]+\.(?:SF|RSA|DSA|EC)|MANIFEST\.MF)', info.filename, re.I):
                    continue
                content = rewritten[info.filename].read_bytes() if info.filename in rewritten else original.read(info.filename)
                info.extra = b''
                target.writestr(info, content)
            for name in extra_dex:
                target.writestr(name, rewritten[name].read_bytes(), compress_type=zipfile.ZIP_STORED)
            target.writestr('classes'+str(last_dex+len(extra_dex)+1)+'.dex', client_dex.read_bytes(), compress_type=zipfile.ZIP_STORED)
            target.writestr('assets/bluem-updates/release-cert.der', certificate.read_bytes())
    return rewritten

def build(variant, code, name, unsigned_only=False):
    out = ROOT/'build'/variant
    out.mkdir(parents=True, exist_ok=True)
    qa = variant.startswith('qa')
    source = ROOT/'inputs/youtube-recommended-patched.apk'
    assert digest(source) == BASE_HASH_FILE.read_text().strip(), 'Unexpected audited recommended base'
    selection = json.loads((ROOT/'selection.json').read_text())
    with (out/'build.log').open('wb') as log:
        compile_tools(log)
        run([jtool('java'), '-Xmx2g', '-cp', tool_classpath(), 'CheckRecoveryContract', source], log)
        generated = out/'generated/com/sylong/bluem/update/BuildConfig.java'
        generated.parent.mkdir(parents=True, exist_ok=True)
        url = 'http://127.0.0.1:8873/blue-m-stable.json' if qa else CHANNEL_URL
        upstream_url = 'http://127.0.0.1:8873/patches-list.json' if qa else 'https://raw.githubusercontent.com/MorpheApp/morphe-patches/main/patches-list.json'
        generated.write_text('package com.sylong.bluem.update;\npublic final class BuildConfig {\n' +
            f' public static final boolean QA = {str(qa).lower()};\n public static final String CHANNEL_URL = "{url}";\n' +
            f' public static final String UPSTREAM_URL = "{upstream_url}";\n' +
            f' public static final String PATCHES_VERSION = "{selection["patches_version"]}";\n' +
            f' public static final String YOUTUBE_VERSION = "{selection["youtube_version"]}";\n}}\n', encoding='utf-8')
        classes = out/'classes'
        classes.mkdir(exist_ok=True)
        android_jar = SDK/'platforms/android-37.0/android.jar'
        apksig = ROOT/'tools/apksig.jar'
        assert digest(apksig) == 'c070ed1394629d74641aa0906f60b2ffa1ee77e6366a1f93437f59717b1aeb89'
        run([jtool('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-Xlint:unchecked', '-cp', str(android_jar)+os.pathsep+str(apksig),
             '-d', classes, *sorted((ROOT/'src/client').rglob('*.java')), generated], log)
        jar = out/'client.jar'
        run([jtool('jar'), 'cf', jar, '-C', classes, '.'], log)
        dex = out/'dex'
        dex.mkdir(exist_ok=True)
        run([atool('d8'), '--release', '--min-api', '28', '--lib', android_jar, '--output', dex, jar, apksig], log)
        unsigned = out/'unsigned.apk'
        # Each run needs fresh staging: stale overflow DEX files duplicate classes.
        with tempfile.TemporaryDirectory(prefix='patched-', dir=out) as directory:
            patched = Path(directory)
            run([jtool('java'), '-Xmx2g', '-cp', tool_classpath(), 'PatchApk', source, patched, str(code), name, str(qa).lower()], log)
            rewritten = package_unsigned(source, patched, dex/'classes.dex', ROOT/'assets/release-cert.der', unsigned)
        aligned = out/'aligned.apk'
        run([atool('zipalign'), '-f', '-P', '16', '4', unsigned, aligned], log)
        if unsigned_only:
            run([jtool('java'), '-Xmx2g', '-cp', tool_classpath(), 'CheckUpdateContract', aligned, str(qa).lower()], log)
            print(json.dumps({'unsigned': str(aligned), 'version_code': code, 'version_name': name}))
            return
        signed = out/'BlueM.apk'
        env = signing_env()
        # Alias is public metadata; passwords are read from the child environment only.
        run([atool('apksigner'), 'sign', '--ks', env['SYMORPHE_KEYSTORE_FILE'], '--ks-key-alias', env['KEYSTORE_ENTRY_ALIAS'],
             '--ks-pass', 'env:KEYSTORE_PASSWORD', '--key-pass', 'env:KEYSTORE_ENTRY_PASSWORD', '--out', signed, aligned], log, env)
        run([sys.executable, ROOT/'verify_apk.py', signed, out/'verification'], log)
        run([jtool('java'), '-Xmx2g', '-cp', tool_classpath(), 'CheckUpdateContract', signed, str(qa).lower()], log)
        with zipfile.ZipFile(source) as before, zipfile.ZipFile(signed) as after:
            changed_original_entries = sorted(set(rewritten) & set(before.namelist()))
            unchanged = 0
            for entry in before.infolist():
                if entry.filename in rewritten or entry.filename.startswith('META-INF/') or entry.filename == 'stamp-cert-sha256':
                    continue
                assert before.read(entry.filename) == after.read(entry.filename), 'Unexpected original entry change: '+entry.filename
                unchanged += 1
        result = dict(variant=variant, version_code=code, version_name=name, sha256=digest(signed), size=signed.stat().st_size,
                      original_entries_byte_identical=unchanged, changed_original_entries=changed_original_entries, channel=url,
                      qa=qa, production_signer=CERT)
        (out/'build-result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('variant', choices=['qa-bootstrap', 'qa-update', 'release'])
    parser.add_argument('--code', type=int)
    parser.add_argument('--unsigned', action='store_true')
    args = parser.parse_args()
    build(args.variant, args.code or (2026100803 if args.variant == 'release' else 2026100802),
          json.loads((ROOT/'selection.json').read_text())['youtube_version'], args.unsigned)
