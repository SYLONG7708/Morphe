"""Sign verified artifacts and atomically publish an immutable Blue M channel."""
import argparse
import base64
import datetime
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
import build
from cloud import ROOT, REPO, MICROG_CERT, ABIS, write, fingerprint

DIST = ROOT/'dist'
READY = ROOT/'build/publish-ready'
# Revision 4 validates these names before checking APK updates. Keep the wire
# policy compatible; revision 6 adds VisionOS locally according to JS capability.
PLAYBACK_POLICY = {'schema': 1, 'buffering_ms': 6000,
                   'clients': ['TV_SABR', 'ANDROID_CREATOR', 'TV_SIMPLY']}


def gh(*args, data=None, missing=False):
    command = ['gh', *map(str, args)]
    if data is not None: command += ['--input', '-']
    result = subprocess.run(command, input=json.dumps(data).encode() if data is not None else None,
        capture_output=True, timeout=600, creationflags=0x08000000 if os.name == 'nt' else 0)
    if result.returncode:
        if missing and b'404' in result.stderr: return None
        raise RuntimeError(result.stderr.decode(errors='replace'))
    return result.stdout


def api(path, data=None, method=None, missing=False):
    args = ['api', path]
    if method: args += ['--method', method]
    raw = gh(*args, data=data, missing=missing)
    return json.loads(raw) if raw else None


def identity(apk, log):
    build.run([sys.executable, ROOT/'verify_apk.py', apk, READY/'verification'], log)
    return json.loads((READY/'verification'/(apk.stem+'-identity.json')).read_text())


def prepare(local=False):
    READY.mkdir(parents=True, exist_ok=True)
    plan = json.loads((ROOT/'build/plan.json' if local else DIST/'plan.json').read_text())
    if fingerprint(plan['selection'], plan['assets']) != plan['fingerprint']:
        raise ValueError('Signing checkout differs from tested source')
    youtube = READY/('BlueM-' + plan['selection']['youtube_version'] + '-' + str(plan['code']) + '.apk')
    microg = READY/('MicroG-' + plan['microg_version'] + '.apk')
    with (READY/'signing.log').open('wb') as log:
        if local:
            shutil.copyfile(ROOT/'build/release/BlueM.apk', youtube)
            shutil.copyfile(ROOT/'inputs'/plan['assets']['microg']['name'], microg)
        else:
            sums = json.loads((DIST/'checksums.json').read_text())
            for name in ['BlueM-unsigned.apk', 'MicroG.apk']:
                if build.digest(DIST/name) != sums[name]: raise ValueError('Build artifact was damaged')
            env = build.signing_env()
            build.run([build.atool('apksigner'), 'sign', '--ks', env['SYMORPHE_KEYSTORE_FILE'],
                '--ks-key-alias', env['KEYSTORE_ENTRY_ALIAS'], '--ks-pass', 'env:KEYSTORE_PASSWORD',
                '--key-pass', 'env:KEYSTORE_ENTRY_PASSWORD', '--out', youtube, DIST/'BlueM-unsigned.apk'], log, env)
            shutil.copyfile(DIST/'MicroG.apk', microg)
        if 'sha256:' + build.digest(microg) != plan['assets']['microg']['digest']:
            raise ValueError('MicroG differs from official release')
        packages = []
        for apk, package, certificate in [(youtube, 'app.morphe.android.youtube', build.CERT),
                                         (microg, 'app.revanced.android.gms', MICROG_CERT)]:
            info = identity(apk, log)
            if info['package'] != package or info['signer_sha256'] != [certificate] or set(info['abis']) != ABIS:
                raise ValueError('Signed APK identity/ABI validation failed')
            if apk == youtube and (info['version_code'] != plan['code'] or info['version_name'] != plan['selection']['youtube_version']):
                raise ValueError('Signed APK version differs from plan')
            packages.append({key: info[key] for key in ['package','version_code','version_name','size','sha256','abis']}
                | {'min_sdk': int(info['min_sdk']), 'signer_sha256':certificate,
                   'url':f'https://github.com/{REPO}/releases/download/{plan["tag"]}/{apk.name}'})
        manifest = {'schema':1, 'channel':'blue-m-stable', 'sequence':plan['code'],
            'published_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'upstream':plan['selection'], 'packages':packages,
            'client':{'revision':7,'fingerprint':plan['fingerprint'],'source_commit':plan['source_commit']},
            'playback':PLAYBACK_POLICY}
        write(READY/'blue-m-stable.json', manifest)
        # Compile only our small standard-library signer in the secret-bearing job.
        (ROOT/'build/tools').mkdir(parents=True, exist_ok=True)
        build.run([build.jtool('javac'), '-d', ROOT/'build/tools', ROOT/'src/tools/SignManifest.java'], log)
        build.sign_manifest(READY/'blue-m-stable.json', READY/'blue-m-stable.json.sig', log)
    write(READY/'plan.json', plan)
    notes = f'''藍色 M 程式內更新與首次啟動修正

YouTube {plan['selection']['youtube_version']} / Morphe patches {plan['selection']['patches_version']} / 官方 MicroG {plan['microg_version']}。

- MicroG 與藍色 M 更新在程式內下載，顯示百分比及已下載 MB；支援取消、背景返回與中斷後重試。
- 下載完成驗證 SHA-256、簽章、套件與版本；由使用者按「安裝更新」接續 Android 安裝程序。
- 缺少 MicroG 時顯示原生初次設定頁，取代開啟網頁並結束程式的舊流程。
- 修正缺少 MicroG 時 HTTPS 被全域 SSL guard 阻擋，下載仍保留平台憑證與主機名驗證。
- 安裝完成後等待系統交易完成再進入 YouTube；車機缺少電池設定入口時提供替代設定頁。
- 修正首頁預載資訊誤判成正在播放、導致更新一直等待的問題；真正播放中仍保護播放。
- 已驗證 APK 可離線接續安裝；保留帳號與設定，沿用正式簽章。
- 後續 YouTube 更新必須包含 revision 7 以上，避免舊更新器覆蓋本次修正。

保留既有播放錯誤恢復。Android 首次安裝來源授權及必要的系統安裝確認仍須操作；系統替換套件時可能關閉 App，完成後可重新開啟。
相容 Android 9 以上及四種 ABI；實際設備驗收範圍請參照交付紀錄。
'''
    (READY/'release-notes.txt').write_text(notes, encoding='utf-8')
    print(json.dumps({'prepared':plan['tag'],'sequence':plan['code'],'packages':packages}, ensure_ascii=False))


def publish():
    plan = json.loads((READY/'plan.json').read_text())
    manifest = json.loads((READY/'blue-m-stable.json').read_text())
    path = 'repos/'+REPO
    previous = api(path+'/contents/channels/blue-m-stable.json')
    prior_manifest = json.loads(base64.b64decode(previous['content']))
    if prior_manifest['sequence'] != plan['previous_sequence']:
        raise ValueError('Channel moved; rerun against the current release')
    if manifest['sequence'] <= prior_manifest['sequence']: raise ValueError('Channel rollback')
    tag = plan['tag']
    base = api(path+'/git/ref/heads/main')['object']['sha']
    assets = [READY/p['url'].rsplit('/',1)[-1] for p in manifest['packages']]
    for item, file in zip(manifest['packages'], assets):
        if build.digest(file) != item['sha256']: raise ValueError('Prepared APK changed')
    release = api(path+'/releases/tags/'+tag, missing=True)
    if release is None:
        gh('release','create',tag,'--repo',REPO,'--target',plan['source_commit'] if plan['source_commit'] != 'local-verified' else base,
           '--title','Blue M 自動恢復 '+str(plan['code']),'--notes-file',READY/'release-notes.txt',
           '--draft','--latest=false',*assets,READY/'blue-m-stable.json',READY/'blue-m-stable.json.sig')
        gh('release','edit',tag,'--repo',REPO,'--draft=false','--latest=false')
    # A retry never overwrites immutable release assets. Verify public bytes before pointing the channel at them.
    for item in manifest['packages']:
        downloaded = READY/('download-'+item['url'].rsplit('/',1)[-1])
        for attempt in range(4):
            try:
                with urllib.request.urlopen(urllib.request.Request(item['url'],headers={'User-Agent':'BlueM-publication-verifier'}), timeout=90) as response, downloaded.open('wb') as out:
                    shutil.copyfileobj(response,out,1024*1024)
                break
            except Exception:
                if attempt == 3: raise
                time.sleep(2 ** attempt)
        if build.digest(downloaded) != item['sha256'] or downloaded.stat().st_size != item['size']:
            raise ValueError('Public release APK differs from prepared artifact')
    # Both JSON and detached signature move in one commit. Never force-push over concurrent work.
    for attempt in range(4):
        current = api(path+'/git/ref/heads/main')['object']['sha']
        if api(path+'/contents/channels/blue-m-stable.json')['sha'] != previous['sha']:
            raise ValueError('Concurrent channel publication; preserve it')
        parent = api(path+'/git/commits/'+current)
        entries=[]
        for name in ['blue-m-stable.json','blue-m-stable.json.sig']:
            blob=api(path+'/git/blobs',{'content':base64.b64encode((READY/name).read_bytes()).decode(),'encoding':'base64'})
            entries.append({'path':'channels/'+name,'mode':'100644','type':'blob','sha':blob['sha']})
        tree=api(path+'/git/trees',{'base_tree':parent['tree']['sha'],'tree':entries})
        commit=api(path+'/git/commits',{'message':'Publish verified Blue M '+str(plan['code'])+' [skip ci]',
            'tree':tree['sha'],'parents':[current]})
        try:
            api(path+'/git/refs/heads/main',{'sha':commit['sha'],'force':False},method='PATCH')
            break
        except RuntimeError:
            if attempt == 3: raise
    for name in ['blue-m-stable.json','blue-m-stable.json.sig']:
        remote=api(path+'/contents/channels/'+name+'?ref='+commit['sha'])
        if base64.b64decode(remote['content']) != (READY/name).read_bytes(): raise ValueError('Channel readback mismatch')
    write(READY/'receipt.json',{'tag':tag,'commit':commit['sha'],'sequence':plan['code'],
        'public_apk_sha256_verified':True,'release_url':f'https://github.com/{REPO}/releases/tag/{tag}'})
    print('Published and verified https://github.com/'+REPO+'/releases/tag/'+tag)


if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser()
    parser.add_argument('action',choices=['prepare','publish'])
    parser.add_argument('--local',action='store_true')
    args=parser.parse_args()
    if args.action=='prepare':prepare(args.local)
    else:publish()
