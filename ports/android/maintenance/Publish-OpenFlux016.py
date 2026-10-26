"""Publish final 0.7.1 / 10061 packaging and separate OpenFlux 15 modules.

No compilation or phone access. Immutable source/asset receipts, exact catalogue
backups, non-force source push, draft-first release and public-byte verification.
"""
import hashlib, json, os, shutil, subprocess, sys, urllib.request, zipfile
from pathlib import Path

ROOT = Path('D:/MCRF/Projects/MCRF')
STAGE = Path('D:/MCRF/Builds/Android/publish071-20261026')
PUBLIC = STAGE / 'repository'
BUILD = Path('D:/MCRF/Builds/Android/public071-8da7ba54413b4ef1b8dda5c90f443d39')
PREVIOUS = Path('D:/MCRF/Builds/Android/openflux016-routes-ad57825a9b0a4ed8b65b28830824a2ef')
NATIVE = Path('D:/MCRF/Builds/Android/openflux016-native-a876218007b84c43b6cef13b3c0ed59b')
BASE = Path('D:/MCRF/Builds/Android/release07-245e566d90dd490bb31202c5c6612fc0')
VALIDATION = Path('D:/MCRF/Builds/Android/openflux016-validation-e4ec438374e74ffbb0e44ac80e01fe88')
GH = 'C:/Program Files/GitHub CLI/gh.exe'
REPO = 'vnenapravo7-source/mcrf'
TAG = 'android-v0.7.1'
CAT = 'engines-2026-10-13-1'
PIN = '5a46f2f1f178637538542a36c7f086f26df86bcd'

def read(p):
    return json.loads(p.read_text(encoding='utf-8-sig'))

def save(p, value):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

def sha(p):
    with p.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()

def call(*args, data=None):
    return subprocess.check_output(list(args), input=data)

def gh(*args):
    return call(GH, *args)

def api(path, payload=None, method=None):
    args = ['api', 'repos/'+REPO+'/'+path]
    if payload is not None:
        args += ['--method', method or 'POST', '--input', '-']
    return json.loads(call(GH, *args, data=None if payload is None else json.dumps(payload).encode()))

def git(*args, data=None):
    return call('git', '-C', str(PUBLIC), *args, data=data).decode().strip()

def safe_name(name):
    name = name.replace('\\', '/')
    assert name and not name.startswith('/') and '..' not in name.split('/') and ':' not in name
    return name

def check_asset(asset, path):
    assert asset['digest'] == 'sha256:'+sha(path) and asset['size'] == path.stat().st_size, path.name

def download_verified(asset, path):
    if not path.exists():
        subprocess.run([GH, 'release', 'download', CAT, '--repo', REPO, '--pattern', asset['name'], '--dir', str(path.parent)], check=True, stdout=subprocess.DEVNULL)
    check_asset(asset, path)

def public_bytes(asset, expected):
    request = urllib.request.Request(asset['browser_download_url'], headers={'User-Agent':'MCRF-publication-verifier'})
    h = hashlib.sha256(); count = 0
    with urllib.request.urlopen(request, timeout=120) as response:
        while chunk := response.read(1024*1024):
            h.update(chunk); count += len(chunk)
    assert h.hexdigest() == expected and count == asset['size'], asset['name']
    return {'name':asset['name'], 'bytes':count, 'sha256':expected}

def inputs():
    proof = read(BUILD/'verification-receipt.json')
    assert proof['sourceStable'] and proof['tests'] == 53 and proof['suites'] == 6 and proof['lintAndR8']
    assert proof['versionCode'] == 10061 and proof['versionName'] == '0.7.1'
    frozen = Path(proof['snapshot'])
    assert sha(frozen/'source-manifest.json') == proof['sourceManifestSha256']
    phone = read(PREVIOUS/'phone/receipt.json')
    assert phone['state'] == 'UPDATED_10060_OPENFLUX15_PRESERVED' and phone['protectedDataIdentical'] and phone['protectedEntries'] == 34
    runtime = read(VALIDATION/'production-phone/receipt.json')
    assert runtime['state'] == 'PASS_INSTALLED_016_DIRECT_FLUXFAST' and runtime['passed']
    np = read(NATIVE/'receipt.json')
    assert np['sourceStable'] and np['commit'] == PIN and len(np['tests']) == 6
    overlay = {}
    for row in read(frozen/'source-manifest.json'):
        name = safe_name(row['path']); path = frozen/name
        assert sha(path) == row['sha256'], name
        if name.startswith('ports/android/'):
            overlay[name] = path
    native_source = Path(np['source'])
    for row in read(NATIVE/'source-manifest.json'):
        name = safe_name(row['path']); path = native_source/name
        assert sha(path) == row['sha256'], name
        overlay['upstream/openflux-v0.16.0/'+name] = path
    for name in ('README.md', 'docs/ANDROID-GUIDE.md', 'docs/ANDROID-STATUS.md'):
        overlay[name] = ROOT/name
    overlay['ports/android/maintenance/Build-OpenFlux016Routes.ps1'] = ROOT/'ports/android/maintenance/Build-OpenFlux016Routes.ps1'
    overlay['ports/android/maintenance/Verify-OpenFlux016Routes.ps1'] = ROOT/'ports/android/maintenance/Verify-OpenFlux016Routes.ps1'
    overlay['ports/android/maintenance/Publish-OpenFlux016.py'] = Path(__file__)
    overlay['ports/android/maintenance/Build-OpenFlux016Public.ps1'] = ROOT/'ports/android/maintenance/Build-OpenFlux016Public.ps1'
    overlay['ports/android/release/0.7.1/public071.json'] = frozen/'public071.json'
    overlay['ports/android/release/0.7.1/openflux016.json'] = frozen/'openflux016.json'
    for row in proof['files']:
        assert sha(Path(row['path'])) == row['sha256']
    return proof, overlay

def prepare():
    assert not (STAGE/'plan.json').exists(), 'Plan already prepared; inspect before retry'
    proof, overlay = inputs()
    assets = STAGE/'assets'; assets.mkdir(exist_ok=True)
    app_files = []; modules = []
    for row in proof['files']:
        name = 'mcrf-android-universal-release.apk' if row['kind'] == 'app' else row['name'].replace('engines-openflux-', 'mcrf-engine-openflux-')
        target = assets/name; shutil.copyfile(row['path'], target)
        record = {'name':name, 'bytes':target.stat().st_size, 'sha256':sha(target)}
        (app_files if row['kind'] == 'app' else modules).append(record)
    alias = assets/'arm64-v8a-release.apk'; shutil.copyfile(assets/app_files[0]['name'], alias)
    app_files.append({'name':alias.name, 'bytes':alias.stat().st_size, 'sha256':sha(alias)})
    base_proof = read(BASE/'source-receipt.json'); base_zip = Path(base_proof['archive'])
    assert sha(base_zip) == base_proof['sha256'] == '79f94ba74f906f319a17efcd44adba3fe58fa81432d9da7c50853e483df7cd96'
    source = assets/'mcrf-android-0.7.1-source.zip'
    manifest = []
    print('Preparing corresponding source archive from verified 0.7 plus frozen 10060/0.16 inputs.', flush=True)
    with zipfile.ZipFile(base_zip) as old, zipfile.ZipFile(source,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as new:
        for info in old.infolist():
            name = safe_name(info.filename.rstrip('/'))
            if info.is_dir() or name in overlay or name in ('CORRESPONDING_SOURCE_MANIFEST.json','APP_BUILD_INPUT_MANIFEST.json'):
                continue
            with old.open(info) as inp, new.open(name,'w',force_zip64=True) as out:
                shutil.copyfileobj(inp,out,1024*1024)
            manifest.append({'path':name,'origin':'verified-0.7-corresponding-source'})
        for name,path in sorted(overlay.items()):
            new.write(path,name)
            manifest.append({'path':name,'sha256':sha(path),'bytes':path.stat().st_size})
        new.writestr('CORRESPONDING_SOURCE_MANIFEST.json',json.dumps(manifest,ensure_ascii=False,indent=2))
    engine_source = assets/'mcrf-engine-openflux-0.16.0-source.zip'
    with zipfile.ZipFile(engine_source,'w',zipfile.ZIP_DEFLATED,compresslevel=6,strict_timestamps=False) as out:
        for name,path in sorted(overlay.items()):
            if name.startswith(('upstream/openflux-v0.16.0/','ports/android/transports/openflux/')):
                out.write(path,name)
        out.writestr('SOURCE.json',json.dumps({'upstream':'https://github.com/OpenFluxTeam/OpenFlux','version':'v0.16.0','commit':PIN,'moduleVersionCode':15,'patchedSourceIncluded':True}))
    # Verify every new frozen input in the finished archives, not only the ZIP hash.
    with zipfile.ZipFile(source) as z:
        assert len(z.namelist()) == len(set(z.namelist()))
        for name,path in overlay.items():
            assert hashlib.sha256(z.read(name)).hexdigest() == sha(path), name
    parent = git('rev-parse','HEAD')
    assert api('git/ref/heads/main')['object']['sha'] == parent, 'Branch changed before source plan'
    git('read-tree','HEAD')
    current = {}
    for row in git('ls-tree','-r','HEAD').splitlines():
        meta,name = row.split('\t',1); current[name] = meta.split()[2]
    binary = {'.aar','.so','.apk','.a','.dll','.exe','.o','.class','.pyc','.zip','.tgz','.gz','.wasm'}
    changed = []; skipped = []
    for name,path in sorted(overlay.items()):
        if path.suffix.lower() in binary or path.stat().st_size > 5*1024*1024:
            skipped.append(name); continue
        data = path.read_bytes()
        blob = hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()
        if current.get(name) == blob: continue
        stored = git('hash-object','-w','--stdin',data=data); assert stored == blob
        git('update-index','--add','--cacheinfo','100644',blob,name)
        changed.append({'path':name,'sha256':sha(path),'blob':blob})
    # No unrelated deletion or Windows path can enter this index.
    actual = set(git('diff','--cached','--name-only').splitlines())
    assert actual == {r['path'] for r in changed} and all(n.startswith(('ports/android/','upstream/openflux-v0.16.0/','docs/ANDROID-')) or n=='README.md' for n in actual)
    catalogue = api('releases/tags/'+CAT); latest = api('releases/latest')
    assert not catalogue['draft'] and not catalogue['prerelease']
    assert not any(r['tag_name']==TAG for r in api('releases?per_page=100'))
    save(STAGE/'catalogue-before.json',catalogue); save(STAGE/'latest-before.json',latest)
    backup = STAGE/'catalogue-backup'; backup.mkdir(exist_ok=True)
    selected = {r['name'] for r in modules}|{'SHA256SUMS.txt'}
    for asset in catalogue['assets']:
        if asset['name'] in selected: download_verified(asset,backup/asset['name'])
    notes = ('## Движки и протоколы\n\n'
      '- OpenFlux обновлён до 0.16.0 и распространяется отдельным движком через каталог модулей.\n'
      '- Добавлен FluxFast; исправлено повторное подключение OpenFlux.\n\n'
      '## Маршруты\n\n'
      '- Добавлена привязка групп правил к типу подключения и общие списки серверов VPN и БС.\n'
      '- В режиме «Авто VPN + БС» тип выбирается по состоянию сети, если включены привязка маршрутов и автоопределение БС.\n'
      '- Сохранены прежние назначения серверов; улучшено применение настроек автовыбора.\n')
    (STAGE/'release-notes.txt').write_text(notes,encoding='utf-8')
    all_app = app_files + [{'name':source.name,'sha256':sha(source),'bytes':source.stat().st_size}]
    sums = assets/'SHA256SUMS.txt'
    sums.write_text(''.join(r['sha256']+'  '+r['name']+'\n' for r in all_app),encoding='utf-8')
    all_app.append({'name':sums.name,'sha256':sha(sums),'bytes':sums.stat().st_size})
    save(STAGE/'plan.json',{'state':'PREPARED_NOT_PUBLISHED','parent':parent,'tag':TAG,'changedSourceFiles':changed,'binaryInputsInArchiveNotGit':skipped,'appAssets':all_app,'modules':modules,'engineSource':{'name':engine_source.name,'sha256':sha(engine_source),'bytes':engine_source.stat().st_size},'sourceFiles':len(manifest),'sourceArchiveAllFrozenInputsVerified':True})
    print('PREPARED: source files '+str(len(changed))+'; separate module APKs '+str(len(modules))+'; source ZIP '+str(source.stat().st_size),flush=True)

def publish():
    plan = read(STAGE/'plan.json'); assert plan['state']=='PREPARED_NOT_PUBLISHED'
    assets = STAGE/'assets'
    for row in plan['appAssets']+plan['modules']+[plan['engineSource']]:
        assert sha(assets/row['name']) == row['sha256']
    receipt_path = STAGE/'publication-receipt.json'
    receipt = read(receipt_path) if receipt_path.exists() else {'state':'STARTED','tag':TAG,'published':False}
    try:
        if 'commit' not in receipt:
            remote_parent = api('git/ref/heads/main')['object']['sha']
            if remote_parent != plan['parent']:
                git('fetch','--depth','1','origin','main')
                assert git('rev-parse','FETCH_HEAD') == remote_parent
                concurrent=set(git('diff','--name-only',plan['parent'],remote_parent).splitlines())
                owned={r['path'] for r in plan['changedSourceFiles']}
                assert not concurrent.intersection(owned), 'Concurrent overlapping source/docs edits require explicit merge'
                git('read-tree',remote_parent)
                for row in plan['changedSourceFiles']:
                    git('update-index','--add','--cacheinfo','100644',row['blob'],row['path'])
                plan['previousParent']=plan['parent']; plan['parent']=remote_parent
                save(STAGE/'plan.json',plan)
            assert set(git('diff','--cached','--name-only').splitlines())=={r['path'] for r in plan['changedSourceFiles']}
            tree = git('write-tree')
            commit = git('-c','user.name=MCRF maintenance','-c','user.email=mcrf-maintenance@users.noreply.github.com','commit-tree',tree,'-p',plan['parent'],'-m','feat(android): publish frozen 10060 routing binding and OpenFlux 0.16 sources')
            receipt['commit']=commit; save(receipt_path,receipt)
        commit=receipt['commit']
        remote=api('git/ref/heads/main')['object']['sha']
        if remote!=commit:
            assert remote==plan['parent'], 'Concurrent branch change'
            git('-c','credential.helper=','-c','credential.helper=!"C:/Program Files/GitHub CLI/gh.exe" auth git-credential','push','origin',commit+':refs/heads/main')
        assert api('git/ref/heads/main')['object']['sha']==commit
        if 'releaseId' not in receipt:
            found=[r for r in api('releases?per_page=100') if r['tag_name']==TAG]
            if found:
                release=found[0]; assert release['draft'] and release['target_commitish']==commit
            else:
                release=api('releases',{'tag_name':TAG,'target_commitish':commit,'name':'MCRF Android 0.7.1','body':(STAGE/'release-notes.txt').read_text(encoding='utf-8'),'draft':True,'prerelease':True,'make_latest':'false'})
            receipt['releaseId']=release['id']; save(receipt_path,receipt)
        release=api('releases/'+str(receipt['releaseId']))
        assert release['target_commitish']==commit and release['prerelease']
        for row in plan['appAssets']:
            existing=[a for a in release['assets'] if a['name']==row['name']]
            if not existing:
                subprocess.run([GH,'release','upload',TAG,str(assets/row['name']),'--repo',REPO],check=True,stdout=subprocess.DEVNULL)
                release=api('releases/'+str(receipt['releaseId'])); existing=[a for a in release['assets'] if a['name']==row['name']]
            assert len(existing)==1; check_asset(existing[0],assets/row['name'])
        before=read(STAGE/'catalogue-before.json')
        for row in plan['modules']:
            current=api('releases/tags/'+CAT); existing=[a for a in current['assets'] if a['name']==row['name']]
            assert len(existing)==1
            if existing[0]['digest']!='sha256:'+row['sha256']:
                old=next(a for a in before['assets'] if a['name']==row['name'])
                assert existing[0]['id']==old['id'] and existing[0]['digest']==old['digest'], 'Concurrent engine mutation'
                assert 'mcrf-version-code=14' in old['label'], 'Unexpected old module version'
                subprocess.run([GH,'release','upload',CAT,str(assets/row['name']),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
            updated=next(a for a in api('releases/tags/'+CAT)['assets'] if a['name']==row['name'])
            check_asset(updated,assets/row['name'])
            api('releases/assets/'+str(updated['id']),{'label':'OpenFlux 0.16.0; mcrf-version-code=15'},method='PATCH')
            print('Separate OpenFlux module verified: '+row['name'],flush=True)
        current=api('releases/tags/'+CAT)
        es=plan['engineSource']; existing=[a for a in current['assets'] if a['name']==es['name']]
        if not existing:
            subprocess.run([GH,'release','upload',CAT,str(assets/es['name']),'--repo',REPO],check=True,stdout=subprocess.DEVNULL)
        else: check_asset(existing[0],assets/es['name'])
        current=api('releases/tags/'+CAT)
        sums=STAGE/'SHA256SUMS.txt'
        sums.write_text(''.join(a['digest'].removeprefix('sha256:')+'  '+a['name']+'\n' for a in sorted(current['assets'],key=lambda a:a['name']) if a['name']!='SHA256SUMS.txt'),encoding='utf-8')
        oldsum=next(a for a in before['assets'] if a['name']=='SHA256SUMS.txt')
        nowsum=next(a for a in current['assets'] if a['name']=='SHA256SUMS.txt')
        assert nowsum['digest'] in (oldsum['digest'],'sha256:'+sha(sums)), 'Concurrent checksum mutation'
        if nowsum['digest']!='sha256:'+sha(sums):
            subprocess.run([GH,'release','upload',CAT,str(sums),'--repo',REPO,'--clobber'],check=True,stdout=subprocess.DEVNULL)
        after=api('releases/tags/'+CAT)
        replaced={r['name'] for r in plan['modules']}|{'SHA256SUMS.txt'}
        for old in before['assets']:
            if old['name'] in replaced: continue
            new=next(a for a in after['assets'] if a['name']==old['name'])
            assert new['id']==old['id'] and new['digest']==old['digest'], 'Unrelated catalogue asset changed'
        assert current['body']==before['body'] and after['draft']==before['draft'] and after['prerelease']==before['prerelease']
        save(STAGE/'catalogue-after.json',after)
        receipt['state']='CATALOGUE_VERIFIED_PUBLISHING_APP'; save(receipt_path,receipt)
        final=api('releases/'+str(receipt['releaseId']),{'draft':False,'prerelease':True,'make_latest':'false'},method='PATCH')
        assert not final['draft'] and final['prerelease']
        assert api('releases/latest')['id']==read(STAGE/'latest-before.json')['id'], 'Windows latest pointer changed'
        save(STAGE/'release-after.json',final)
        receipt.update(state='PUBLISHED_METADATA_VERIFIED',published=True,url=final['html_url'],separateEngineModules=4,windowsLatestPreserved=True)
        save(receipt_path,receipt)
        print('PUBLISHED '+final['html_url'],flush=True)
    except Exception as error:
        receipt.update(state='FAILED_PRESERVED',error=str(error));save(receipt_path,receipt);raise

def verify():
    plan=read(STAGE/'plan.json'); release=api('releases/tags/'+TAG); cat=api('releases/tags/'+CAT)
    assert not release['draft'] and release['prerelease']
    results=[]
    # Hash actual anonymous public downloads, not only server metadata.
    for row in plan['appAssets']+plan['modules']+[plan['engineSource']]:
        owner=cat if row in plan['modules'] or row==plan['engineSource'] else release
        asset=next(a for a in owner['assets'] if a['name']==row['name'])
        check_asset(asset,STAGE/'assets'/row['name'])
        results.append(public_bytes(asset,row['sha256']))
        print('Public bytes verified: '+row['name'],flush=True)
    save(STAGE/'public-verification.json',{'state':'PUBLIC_DOWNLOADS_VERIFIED','files':results,'release':release['html_url'],'modulesSeparate':True,'windowsLatest':api('releases/latest')['tag_name']})

if __name__ == '__main__':
    {'prepare':prepare,'publish':publish,'verify':verify}[sys.argv[1]]()
