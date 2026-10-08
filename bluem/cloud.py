"""Build-only automation. This process never receives production signing credentials."""
import argparse
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

import build
from recommended_policy import recommended, version

ROOT = Path(__file__).resolve().parent
REPO = 'SYLONG7708/Morphe'
MICROG_CERT = '0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7'
GOOGLE_CERTS = {
    '5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6',
    '3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a',
    '3257d599a49d2c961a471ca9843f59d341a405884583fc087df4237b733bbd6d',
}
ABIS = {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
KNOWN_GOOGLE = {'21.16.256': '724d2bf15d31dac98db00d82914db3876edf66e62fe2845001f204209ecf4c00'}


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def fetch(url, destination=None):
    if urllib.parse.urlparse(url).scheme != 'https':
        raise ValueError('HTTPS is required')
    request = urllib.request.Request(url, headers={'User-Agent': 'SyMorphe/1.1.104-local (Android 33)'})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                if destination is None:
                    return response.read(4 * 1024 * 1024)
                destination.parent.mkdir(parents=True, exist_ok=True)
                temporary = destination.with_suffix(destination.suffix + '.part')
                with temporary.open('wb') as out:
                    shutil.copyfileobj(response, out, 1024 * 1024)
                temporary.replace(destination)
                return destination
        except Exception:
            if attempt == 2:
                raise
            time.sleep(2 ** attempt)


def stable_release(repo):
    result = json.loads(fetch('https://api.github.com/repos/' + repo + '/releases/latest'))
    if result.get('draft') is not False or result.get('prerelease') is not False:
        raise ValueError('Stable published releases only')
    version(result['tag_name'].removeprefix('v'))
    return result


def asset(release, pattern):
    matches = [a for a in release['assets'] if re.fullmatch(pattern, a['name'])]
    if len(matches) != 1:
        raise ValueError('Ambiguous official release asset: ' + pattern)
    result = matches[0]
    if not re.fullmatch('sha256:[0-9a-f]{64}', result.get('digest') or ''):
        raise ValueError('Official release asset has no SHA-256 digest')
    return result


def download_asset(value):
    path = ROOT/'inputs'/value['name']
    expected = value['digest'].removeprefix('sha256:')
    if not path.exists() or build.digest(path) != expected:
        fetch(value['browser_download_url'], path)
    if build.digest(path) != expected or path.stat().st_size != value['size']:
        raise ValueError('Official asset digest/size mismatch')
    return path


def fingerprint(selection, assets):
    source = hashlib.sha256()
    files = list((ROOT/'src').rglob('*.java')) + [ROOT/name for name in
        ['build.py', 'cloud.py', 'publish.py', 'verify_apk.py', 'recommended_policy.py', 'tool-lock.json']]
    for path in sorted(files):
        source.update(path.relative_to(ROOT).as_posix().encode())
        source.update(path.read_bytes().replace(b'\r\n', b'\n'))
    data = {'source': source.hexdigest(), 'selection': selection,
            'assets': {key: value['digest'] for key, value in assets.items()}}
    return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()


def next_code(current):
    previous = max((p['version_code'] for p in current.get('packages', [])
                    if p['package'] == 'app.morphe.android.youtube'), default=0)
    result = max(2026100804, previous + 1, current.get('sequence', 0) + 1)
    if result > 2147483647:
        raise ValueError('Android versionCode exhausted')
    return result


def probe(force=False):
    releases = {key: stable_release(repo) for key, repo in {
        'patches': 'MorpheApp/morphe-patches', 'desktop': 'MorpheApp/morphe-desktop',
        'microg': 'MorpheApp/MicroG-RE'}.items()}
    tag = releases['patches']['tag_name']
    metadata = json.loads(fetch(f'https://raw.githubusercontent.com/MorpheApp/morphe-patches/{tag}/patches-list.json'))
    selection = recommended(releases['patches'], metadata)
    assets = {key: asset(releases[key], pattern) for key, pattern in {
        'patches': r'.+\.mpp', 'desktop': r'morphe-desktop-.+-all\.jar', 'microg': r'microg-[0-9.]+\.apk'}.items()}
    current = json.loads(fetch(build.CHANNEL_URL))
    digest = fingerprint(selection, assets)
    changed = force or current.get('client', {}).get('fingerprint') != digest
    plan = {'changed': changed, 'fingerprint': digest, 'selection': selection, 'assets': assets,
            'previous_sequence': current['sequence'], 'code': next_code(current),
            'source_commit': os.environ.get('GITHUB_SHA', 'local-verified'),
            'microg_version': releases['microg']['tag_name'].removeprefix('v')}
    plan['tag'] = 'blue-m-' + str(plan['code'])
    write(ROOT/'build/plan.json', plan)
    write(ROOT/'selection.json', selection)
    write(ROOT/'inputs/patches-list.json', metadata)
    for key, release in releases.items(): write(ROOT/'evidence'/(key+'-release.json'), release)
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a') as out:
            out.write('changed=' + str(changed).lower() + '\n')
    print(json.dumps({'changed': changed, 'code': plan['code'], 'selection': selection}))
    return plan


def tools():
    for name, item in json.loads((ROOT/'tool-lock.json').read_text()).items():
        path = ROOT/'tools'/name
        if not path.exists() or build.digest(path) != item['sha256']: fetch(item['url'], path)
        if build.digest(path) != item['sha256']: raise ValueError('Build dependency digest mismatch')


def variants(page, base):
    result = []
    for row in page.split('<div class="table-row headerFont">')[1:]:
        # Only monolithic universal APKs; bundles/splits are intentionally rejected.
        if 'BUNDLE</span>' in row or '>APK</span>' not in row or '>universal</div>' not in row:
            continue
        match = re.search(r'href="([^"]*android-apk-download/)"', row)
        if match: result.append(urllib.parse.urljoin(base, html.unescape(match[1])))
    return list(dict.fromkeys(result))


def google_apk(selected):
    path = ROOT/'inputs/youtube-google.apk'
    expected = KNOWN_GOOGLE.get(selected)
    # Cached inputs are still verified by apksigner below, including unknown new versions.
    if path.exists() and expected and build.digest(path) == expected:
        return path
    base = 'https://www.apkmirror.com/apk/google-inc/youtube/youtube-' + selected.replace('.', '-') + '-release/'
    candidates = variants(fetch(base).decode(), base)
    if len(candidates) != 1:
        raise ValueError('No unambiguous universal APK for official recommended version; keep current release')
    variant = candidates[0]
    page = fetch(variant).decode()
    match = re.search(r'<a[^>]*class="[^"]*\bdownloadButton\b[^"]*"[^>]*href="([^"]+)"', page)
    if not match: raise ValueError('APK source unavailable; keep current release')
    page = fetch(urllib.parse.urljoin(variant, html.unescape(match[1]))).decode()
    links = re.findall(r'href="([^"]*download\.php[^\"]*)"', page)
    if not links: raise ValueError('APK source unavailable; keep current release')
    fetch(urllib.parse.urljoin(variant, html.unescape(links[0])), path)
    if expected and build.digest(path) != expected: raise ValueError('Audited Google APK digest changed')
    write(ROOT/'evidence/google-source.json', {'url': variant, 'sha256': build.digest(path)})
    return path


def identity(apk, log):
    folder = ROOT/'evidence/identity'
    build.run([sys.executable, ROOT/'verify_apk.py', apk, folder], log)
    return json.loads((folder/(apk.stem+'-identity.json')).read_text())


def validate_source(info, selected):
    if info['package'] != 'com.google.android.youtube' or info['version_name'] != selected:
        raise ValueError('Incorrect Google APK identity')
    if not info['signature_verified'] or not info['signer_sha256'] or not set(info['signer_sha256']).issubset(GOOGLE_CERTS):
        raise ValueError('Untrusted Google APK signer')
    if set(info['abis']) != ABIS or int(info['min_sdk']) > 28:
        raise ValueError('APK excludes supported head units')


def assemble():
    plan = json.loads((ROOT/'build/plan.json').read_text())
    if not plan['changed']: return
    tools()
    paths = {key: download_asset(value) for key, value in plan['assets'].items()}
    source = google_apk(plan['selection']['youtube_version'])
    clean_env = os.environ.copy()
    # No credentials/tokens inherited by upstream patch tooling.
    for key in list(clean_env):
        if any(word in key.upper() for word in ['TOKEN', 'SECRET', 'PASSWORD', 'KEYSTORE', 'SIGNING']):
            clean_env.pop(key)
    clean_env['MORPHE_DATA_DIR'] = str(ROOT/'desktop-data')
    with (ROOT/'build/assembly.log').open('wb') as log:
        validate_source(identity(source, log), plan['selection']['youtube_version'])
        microg = identity(paths['microg'], log)
        if microg['package'] != 'app.revanced.android.gms' or microg['signer_sha256'] != [MICROG_CERT] or microg['version_name'] != plan['microg_version']:
            raise ValueError('Official MicroG identity changed')
        patched = ROOT/'inputs/youtube-recommended-patched.apk'
        build.run([build.jtool('java'), '-Xmx4g', '-jar', paths['desktop'], 'patch', source,
                   '-p', paths['patches'], '--unsigned', '--bytecode-mode=FULL', '-o', patched,
                   '-r', ROOT/'evidence/patch-result.json'], log, clean_env)
        result = json.loads((ROOT/'evidence/patch-result.json').read_text())
        if result['failedPatches'] or not all(s['success'] for s in result['patchingSteps']):
            raise ValueError('Official patching failed; preserve published release')
        if result['packageVersion'] != plan['selection']['youtube_version'] or len(result['appliedPatches']) < plan['selection']['default_patch_support_count']:
            raise ValueError('Incomplete patch selection')
        (ROOT/'inputs/recommended-base-sha256.txt').write_text(build.digest(patched))
        build.run([sys.executable, ROOT/'run_tests.py'], log)
        build.run([sys.executable, '-m', 'unittest', 'test_cloud', 'test_recommended_policy'], log)
        build.run([sys.executable, ROOT/'build.py', 'release', '--unsigned', '--code', plan['code']], log, clean_env)
    out = ROOT/'dist'; out.mkdir(exist_ok=True)
    shutil.copyfile(ROOT/'build/release/aligned.apk', out/'BlueM-unsigned.apk')
    shutil.copyfile(paths['microg'], out/'MicroG.apk')
    write(out/'plan.json', plan)
    write(out/'checksums.json', {name: build.digest(out/name) for name in ['BlueM-unsigned.apk', 'MicroG.apk']})
    for path in [ROOT/'build/assembly.log', ROOT/'build/release/build.log', ROOT/'evidence/patch-result.json']:
        shutil.copyfile(path, out/path.name)
    print('Verified unsigned release is ready for the separate signing job.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['probe', 'tools', 'assemble'])
    parser.add_argument('--force', action='store_true')
    args = parser.parse_args()
    if args.action == 'probe': probe(args.force)
    elif args.action == 'tools': tools()
    else: assemble()
