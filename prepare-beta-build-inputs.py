import argparse, hashlib, json, pathlib, zipfile

parser=argparse.ArgumentParser()
router_input=parser.add_mutually_exclusive_group(required=True)
router_input.add_argument('--router-directory',type=pathlib.Path)
router_input.add_argument('--reference-apk',type=pathlib.Path)
parser.add_argument('--libbox-aar',type=pathlib.Path,required=True)
parser.add_argument('--build-inputs',type=pathlib.Path,required=True)
args=parser.parse_args()
root=pathlib.Path(__file__).resolve().parent
lock=json.loads((root/'beta-build-inputs.json').read_text())
def verified(path,pin):
    content=path.read_bytes()
    if len(content)!=pin['bytes'] or hashlib.sha256(content).hexdigest()!=pin['sha256']:
        raise SystemExit(f'Input differs from verified beta payload: {path.name}')
    return content

aars=verified(args.libbox_aar,lock['libbox'])
if args.reference_apk:
    verified(args.reference_apk,lock['apk'])
    with zipfile.ZipFile(args.reference_apk) as apk:
        routers={abi:apk.read(f'lib/{abi}/libmcrf_router.so') for abi in lock['router']}
    for abi,data in routers.items():
        pin=lock['router'][abi]
        if len(data)!=pin['bytes'] or hashlib.sha256(data).hexdigest()!=pin['sha256']:
            raise SystemExit(f'Router in reference APK differs for {abi}')
else:
    routers={abi:verified(args.router_directory/abi/'libmcrf_router.so',pin) for abi,pin in lock['router'].items()}
stage=args.build_inputs.resolve()
stage.mkdir(parents=True,exist_ok=False)
rows=[]
for abi,data in routers.items():
    output=stage/'router-artifacts'/abi/'libmcrf_router.so'
    output.parent.mkdir(parents=True)
    output.write_bytes(data)
    rows.append({'abi':abi,'path':output.as_posix(),**lock['router'][abi]})
receipt=stage/'receipt.json'
serialized=(json.dumps({'state':'COMPILED_MULTICALL_PROOF_NOT_PACKAGED','sourceStable':True,'routerArtifacts':rows},indent=2)+'\n').encode()
receipt.write_bytes(serialized)
libbox=root/lock['libbox']['destination']
libbox.parent.mkdir(parents=True,exist_ok=True)
if libbox.exists(): verified(libbox,lock['libbox'])
else: libbox.write_bytes(aars)
print(f'-PnativeRouterInputs={receipt.as_posix()} -PnativeRouterInputsSha256={hashlib.sha256(serialized).hexdigest()}')
