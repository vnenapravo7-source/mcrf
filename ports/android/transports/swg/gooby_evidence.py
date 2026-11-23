"""Admit owned Android Gooby tests bound to the exact prepared native sources."""
import pathlib
import re
from bs_engine_inputs import load, sha, require, bound, elf
from prepare_csqtt_gooby import source_inventory

CASES = {
    'mcrf_gooby::tests::enabled_presets_require_tcp_and_unknown_values_never_select_a_default',
    'mcrf_gooby::tests::off_preserves_ordinary_udp_and_tcp_without_a_preset',
    'turn_stream::mcrf_gooby_tests::cancelling_a_stalled_tls_handshake_closes_the_actual_remote_socket',
    'turn_stream::mcrf_gooby_tests::tcp_presets_preserve_two_allocate_challenges_and_original_data_queue',
    'turn_stream::mcrf_gooby_tests::tls_presets_keep_sni_and_certificate_validation',
}

def sources(receipt):
    # Generated adapter sources include every vendor module, not only the shim.
    prefixes=('adapters/g251/','adapters/g253/','gooby_builtin/','crypto/')
    return [row for row in receipt['generated'] if row['path'].startswith(prefixes)]

def verify(path, stage):
    path=pathlib.Path(path).absolute();stage=pathlib.Path(stage).absolute()
    proof=load(path);prepared=load(stage/'receipt.json')
    require(proof.get('state')=='GOOBY_ANDROID_TESTS_PASSED' and proof.get('providerTraffic') is False
            and proof.get('sourceStable') is True,'Gooby Android execution required')
    require(proof.get('canonical')==source_inventory()==prepared.get('goobyBuiltinOverlays'),
            'Gooby canonical sources changed')
    require(proof.get('sources')==sources(prepared),'Gooby tests belong to another native source')
    for row in proof['sources']:bound(row,stage)
    require(proof.get('workspaceLockSha256')==sha(stage/'Cargo.lock'),'Gooby dependency resolution changed')
    expected={(gen,abi,group) for gen in ('g251','g253') for abi in ('x86_64','arm64-v8a') for group in ('gooby','ordinary')}
    rows=proof.get('executions',[])
    require(len(rows)==len(expected) and {(r['generation'],r['abi'],r['group']) for r in rows}==expected,
            'Gooby Android generation/ABI checks incomplete')
    for row in rows:
        executable=bound(row['binary'],path.parent);elf(executable,row['abi'])
        log=bound(row['log'],path.parent).read_text(encoding='utf-8')
        require(row.get('exit')==0 and len(re.findall(r'^test result: ok\. 5 passed; 0 failed; 0 ignored;',log,re.M))==1,
                'Gooby Android tests failed/skipped')
        names=re.findall(r'^test ([\w:]+) \.\.\. ok\s*$',log,re.M)
        require(len(names)==5 and len(set(names))==5,'Duplicate/missing Gooby test cases')
        require(set(names)==CASES if row['group']=='gooby' else all(name.startswith('turn_stream::tests::') for name in names),
                'Wrong native Gooby test selection')
    return {'state':proof['state'],'path':str(path),'sha256':sha(path),'executions':len(rows)}
