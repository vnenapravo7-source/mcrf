"""Read-only source-bound acceptance of owned native BS routing tests.
No compilation, downloads, device actions or performance acceptance.
"""
import pathlib,hashlib,zipfile,tomllib
from bs_engine_inputs import require,bound,load,sha,regular
HERE=pathlib.Path(__file__).resolve().parent
BUILD=pathlib.Path('D:/MCRF/Builds/Android').resolve()
GENERATIONS={'2.5.1':'g251','2.5.3':'g253'}

def source_equal(name,left,right):
    # Owned fixture assembly uses read_text/write_text on Windows. Permit its
    # LF/CRLF conversion for Rust and Cargo text only; hashes still bind each
    # original file, and every other byte and every binary asset must match.
    if name.endswith('.rs') or pathlib.PurePosixPath(name).name=='Cargo.toml':
        return left.replace(b'\r\n',b'\n')==right.replace(b'\r\n',b'\n')
    return left==right

def frozen(row):
    require(isinstance(row,dict) and set(row)=={'path','sha256'},'Native evidence file identity required')
    path=bound(row).resolve();require(BUILD in path.parents,'Native evidence must stay in Android build storage');return path

def accepted(row,generation,service=False):
    keys={'acceptance','log','testApk','mainApk','native','source'}|({'moduleReceipt'} if service else set())
    require(isinstance(row,dict) and set(row)==keys,'Incomplete native execution evidence')
    files={key:frozen(value) for key,value in row.items()};receipt=load(files['acceptance'])
    require(receipt.get('providerTraffic') is False and receipt.get('productionActivated') is False,'Native evidence scope changed')
    require(receipt.get('generation','2.5.3')==generation,'Native evidence generation mismatch')
    require(receipt.get('state') in ({'OWNED_ORDINARY_STANDALONE_BS_SERVICE_ACCEPTED'} if service else {'OWNED_G251_ORIGINAL_DISPATCHER_TUN_BOOTSTRAP_ACCEPTED','OWNED_DEFAULT_DNS_FIX_BOOTSTRAP_AND_WORKER_ACCEPTED'}),'Native execution was not accepted')
    for field,key in [('actualLogSha256','log'),('testApkSha256','testApk'),('mainSha256','mainApk'),('nativeSha256','native')]:
        require(receipt.get(field)==sha(files[key]),'Native acceptance identity changed')
    text=files['log'].read_text(encoding='utf-8');require('INSTRUMENTATION_CODE: -1' in text and 'failure=' not in text,'Native execution did not pass')
    source=load(files['source']);require(source.get('sourceStable') is True and source.get('productionActivated') is False,'Native source execution snapshot missing')
    require(any(item.get('sha256')==sha(files['native']) for item in source.get('binaries',[])),'Native source receipt references another ELF')
    if service:
        module=load(files['moduleReceipt']);require(receipt.get('testOnly') is True and module.get('testOnly') is True and module.get('providerTraffic') is False and module.get('nativeSha256')==sha(files['native']) and receipt.get('moduleReceiptSha256')==sha(files['moduleReceipt']),'Owned service module evidence changed')
    else:require(receipt.get('sourceReceiptSha256')==sha(files['source']),'Native source receipt identity changed')
    with zipfile.ZipFile(files['testApk']) as archive:
        data=archive.read('assets/bs-owned-native-udp-fixture.json');import json;metadata=json.loads(data)
        require(metadata.get('generation','2.5.3')==generation and metadata.get('sha256')==sha(files['native']),'Instrumentation contains another native generation')
        require(hashlib.sha256(archive.read('lib/x86_64/libmcrf_owned_native_udp_fixture.so')).hexdigest()==sha(files['native']),'Instrumentation native ELF changed')
    return files,source

def source_matches(stage,files,receipt,generation):
    prefix='adapters/'+GENERATIONS[generation]+'/'
    shared='adapters/g253/rust-client/mcrf-routing-policy/'
    rows={item['path']:item for item in receipt['sources']};root=files['source'].parent
    require(len(rows)==len(receipt['sources']),'Duplicate native source identity')
    # Verify both the original generation and the actual shared policy dependency.
    from csqtt_multicall_build import inventory
    required=[item for item in inventory(stage) if item['path'].startswith((prefix,shared))]
    require(required,'Native production source inventory missing')
    for item in required:
        name=item['path'];proof=rows.get(name);require(proof is not None,'Executed native source omitted production input')
        path=bound(proof,root)
        if name==prefix+'rust-client/dispatcher.rs':
            suffix=b'\n'+(HERE/'routing/native/bootstrap_kernel_dispatcher.rs').read_bytes().replace(b'\r\n',b'\n')
            data=path.read_bytes().replace(b'\r\n',b'\n');require(data.endswith(suffix) and source_equal(name,data[:-len(suffix)],(stage/name).read_bytes()),'Owned dispatcher differs from production')
        elif name==prefix+'rust-client/Cargo.toml':
            suffix=b'\n[[bin]]\nname="mcrf-owned-native-udp-fixture"\npath="owned_native_udp_main.rs"\n'
            data=path.read_bytes().replace(b'\r\n',b'\n');require(data.endswith(suffix) and source_equal(name,data[:-len(suffix)],(stage/name).read_bytes()),'Owned native manifest differs from production')
        else:require(source_equal(name,path.read_bytes(),(stage/name).read_bytes()),'Executed native source differs from production')
    def registry(path):return {(p['name'],p['version'],p.get('source'),p.get('checksum')) for p in tomllib.loads(path.read_text(encoding='utf-8'))['package'] if 'source' in p}
    require(registry(root/'Cargo.lock')==registry(stage/'Cargo.lock'),'Executed native registry versions differ from production')

def unit_tests(row):
    path=frozen(row);data=load(path);require(data.get('sourceStable') is True and data.get('providerTraffic') is False and data.get('productionActivated') is False,'Native unit scope changed')
    rows=data.get('executions',[]);require({r.get('abi') for r in rows}=={'x86_64','arm64-v8a','armeabi-v7a'},'Native tests require all Android ABIs')
    total=0;groups=set()
    for item in rows:
        abi=item['abi'];require(sha(path.parent/(abi+'.test'))==item['binarySha256'],'Native unit binary changed')
        if abi!='x86_64':require(item.get('state')=='COMPILED_NOT_EXECUTED','Unexpected native ABI execution claim');continue
        name=item['group'];require(name not in groups and '/' not in name and '\\' not in name,'Duplicate or invalid native test group');groups.add(name)
        log=regular(path.parent/(abi+'-'+name+'.log'));require(sha(log)==item['logSha256'],'Native unit log changed')
        count=item['passed'];require(type(count) is int and count>0 and ('test result: ok. '+str(count)+' passed; 0 failed; 0 ignored;') in log.read_text(encoding='utf-8'),'Native unit tests failed');total+=count
    require(total>=81 and {'tcp-policy','udp-policy','startup-context','dispatcher-runtime','proxy-udp','forced-tunnel-health'}<=groups,'Incomplete native runtime regression')

def verify(path,stage):
    path=regular(path);data=load(path);require(set(data)=={'schema','kernels','services','unitTests'} and type(data['schema']) is int and data['schema']==1,'Invalid native acceptance document')
    stage=pathlib.Path(stage).absolute();require(all(set(data[key])==set(GENERATIONS) for key in ('kernels','services','unitTests')),'Both native generations must be accepted')
    for generation in GENERATIONS:
        files,source=accepted(data['kernels'][generation],generation);source_matches(stage,files,source,generation)
        files,source=accepted(data['services'][generation],generation,True);source_matches(stage,files,source,generation)
        unit_tests(data['unitTests'][generation])
    return {'path':str(path.absolute()),'sha256':sha(path),'state':'OWNED_NATIVE_ROUTING_ACCEPTED','generations':['2.5.1','2.5.2','2.5.3'],'providerTraffic':False,'performanceAccepted':False}
