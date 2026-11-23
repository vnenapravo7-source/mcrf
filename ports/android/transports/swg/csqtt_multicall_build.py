"""Real multi-call proof producer. Read-only pinned inputs; fresh external output.

The five protocol crates remain distinct. main.rs stays each library's crate
root, preserving crate:: paths, macros, runtime, IPC and shutdown ownership.
No APK/Gradle or package promotion is performed.
"""
import argparse
import concurrent.futures
import hashlib
import importlib.util
import json
import os
import pathlib
import re
import shutil
import subprocess
import tarfile
import tomllib
import zlib
from bs_engine_inputs import load, sha, require, elf

HERE = pathlib.Path(__file__).resolve().parent
SOURCE = HERE / "csqtt_multicall"
ROOT = HERE.parents[1]
BUILD = pathlib.Path("D:/MCRF/Builds/Android")
KEYS = ("g20", "g215", "g219", "g251", "g253")
TARGETS = {"arm64": ("arm64-v8a", "aarch64-linux-android"),
           "armeabi-v7a": ("armeabi-v7a", "armv7-linux-androideabi"),
           "x86_64": ("x86_64", "x86_64-linux-android")}
PINS = {
    "g20": ("bs-retained-source-recovery-20261101-03h/native-stage-csqtt20", "7092d7d541b2946aba90bacdc5fe7627005823c4a324a6cf46a76ef0ad9284c8", "61982fa11dadac4b17ee63b5bf9a99583d78ed5300b5f46a515ab9dcacad0bd9", "source", "2ff8a1a4fbe031fb76b799e172dce25cfe1ca942"),
    "g215": ("bs-retained-source-recovery-20261101-03h/native-stage-csqtt215", "0f71ce37a72cadf2f7542a03bf30625fb4c268a3a5bd3939ab0fa22c93f3a251", "e605241cbbaaf6a19955411a570f9172a9cba249f1c3ef53887da0b6d9e2a857", "source", "de7afc23cec7481f98845bf24fc8943af19b4cf8"),
    "g251": ("csqtt25-negotiated-478136905c174d7ebf61856bcd8ecd9b", "deae63b9cfcc7293a60e642ef045ec2aab814dd822323ae920eeaaf17577788c", "678a22b96822344860ecb5ee2adcb84dd159fe8f6ec544232445900fa2879ad2", "source", "d02da9bd9d8926969b766e197c434d830a83dbd8"),
    "g253": ("csqtt-swg-versioned-inputs-6b6e5851d7ec46f8885dc6da4233663a", "2d7fdcf3e6eece44d56f19d862ba6547a708c86c08f9816b487414721120df30", None, "source/2.5.3", "0188606a"),
}
LEGACY = pathlib.Path("D:/MCRF/Projects/MCRF/work/android-upstream/csqtt-legacy71712b")
LEGACY_REV = "71712b07dbabe16fa43f5a248dc69e1ab9f3a270"
OVERLAYS = {
    ROOT / "optional-cores/upstream-throne/script/prepare_csqtt.py": "27ca4d970f34cedd8f44e6cf0a8dbbcc51010ee24a33f238a2850688371dbd00",
    HERE / "csqtt-waiters.patch": "16f29e385972063c769c0e2a3c0bbe1014721c080c9170d839220176e1b6976e",
    HERE / "mcrf_captcha_waiters.rs": "7a075d8afb71142f846981c3ed7634104e5de00d21c64a0f1d88b2415900754d",
    HERE / "prepare-csqtt-streams.py": "ba3a0cd3fdc1faf678c19835332b7d2c519b13599836db884dfade64d6a9000f",
    HERE / "mcrf_streams.rs": "394fcd7b00623d71c23ae404f6f50917230a220dee1ce0cd2aa422e1155e047a",
}


FLOW_CASES = (
    "worker_auth_peer_flood_stops_next_operation_without_retry_or_fallback",
    "worker_bad_hash_isolated_and_cancelled_auth_does_not_continue",
    "bootstrap_typed_flood_prevents_second_attempt",
)


def admission_flow_cases_passed(log):
    # Current [[test]] crates define these at crate root, so libtest prints
    # unqualified names twice. Also admit only the explicit generation modules
    # if the same fixtures are wrapped later; never accept substring matches.
    for name in FLOW_CASES:
        scopes=re.findall(r"^test (?:(g251_flow|g253_flow)::(?:tests::)?)?"+
                          re.escape(name)+r" \.\.\. ok\r?$",log,re.MULTILINE)
        if len(scopes)!=2 or sorted(scopes) not in (["",""],["g251_flow","g253_flow"]):
            return False
    return True


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def inventory(root):
    rows = []
    for directory, dirs, files in os.walk(root):
        parent = pathlib.Path(directory)
        for name in dirs + files:
            p = parent / name
            require(not p.is_symlink() and not (hasattr(p, "is_junction") and p.is_junction()), "Redirected source")
        dirs[:] = [d for d in dirs if d not in (".git", "target")]
        for name in files:
            p = parent / name
            rows.append({"path": p.relative_to(root).as_posix(), "sha256": sha(p)})
    return sorted(rows, key=lambda r: r["path"])


def checked_file(root, rel):
    rel = pathlib.PurePosixPath(rel)
    require(not rel.is_absolute() and ".." not in rel.parts and not any(c in str(rel) for c in (":", "\\")), "Unsafe manifest path")
    p = root / rel
    require(p.is_file() and not any(q.is_symlink() or (hasattr(q, "is_junction") and q.is_junction()) for q in (p, *p.parents)), "Regular input required")
    return p


def checked_sources(key):
    directory, receipt_sha, manifest_sha, subdir, revision = PINS[key]
    stage = BUILD / directory
    require(sha(stage / "receipt.json") == receipt_sha, "Pinned receipt changed: " + key)
    receipt = load(stage / "receipt.json")
    source = stage / subdir
    verified = {}
    if manifest_sha:
        require(receipt["revision"] == revision and receipt["sourceStable"] is True, "Source identity changed")
        require(sha(stage / "source-manifest.json") == manifest_sha == receipt["sourceManifestSha256"], "Manifest identity changed")
        declared = load(stage / "source-manifest.json")
        for row in declared:
            require(sha(checked_file(source, row["path"])) == row["sha256"], "Pinned source changed: " + row["path"])
        require(inventory(source) == sorted([{"path": r["path"], "sha256": r["sha256"]} for r in declared], key=lambda r:r["path"]), "Unpinned source files")
    else:
        lock = load(stage / "licenses/swg-versions.json")
        require(sha(stage / "licenses/swg-versions.json") == receipt["lockSha256"], "Version lock changed")
        require(lock["versions"]["2.5.3"]["revision"].startswith(revision), "2.5.3 identity changed")
        for rel, digest in receipt["files"].items():
            require(sha(checked_file(stage, rel)) == digest, "Versioned source changed: " + rel)
        a, b = lock["versions"]["2.5.2"], lock["versions"]["2.5.3"]
        require(a["native"] == b["native"], "2.5.2/3 no longer share native")
        require(a["clientTree"] == b["clientTree"] and a["sharedTree"] == b["sharedTree"], "2.5.2/3 source trees differ")
        revision = b["revision"]
        from csqtt25 import aligned_inputs
        canonical = BUILD / "csqtt252-aligned-armv7-20261101-02"
        _, aligned_receipt = aligned_inputs.verify_source(canonical)
        verified["alignedSource"] = {
            "historicalReceiptSha256": sha(canonical / "receipt.json"),
            "sourceManifestSha256": aligned_receipt["sourceManifestSha256"],
            "sourceProofSha256": aligned_receipt["sourceProofSha256"],
            "validatorSha256": sha(aligned_inputs.__file__),
            "historicalAlignedElfUsed": False,
            "historicalAlignedElfReverified": False,
        }
        source = canonical / "source"
    return source, {"receiptSha256": receipt_sha, "revision": revision, "inventory": inventory(source), **verified}


def copy_tree(source, dest, rows):
    for row in rows:
        target = dest / row["path"]
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(checked_file(source, row["path"]), target)
        require(sha(target) == row["sha256"], "Copy changed")


def legacy_source(dest):
    def git(*args):
        return subprocess.check_output(["git", "-C", str(LEGACY), *args])
    require(git("rev-parse", "HEAD").decode().strip() == LEGACY_REV and not git("status", "--porcelain").strip(), "Pristine 2.1.9 required")
    for p, digest in OVERLAYS.items():
        require(sha(p) == digest, "Legacy adapter changed")
    subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", "--no-checkout", "-c", "core.autocrlf=false", str(LEGACY), str(dest)], check=True)
    subprocess.run(["git", "-C", str(dest), "sparse-checkout", "set", "rust-client", "shared"], check=True, capture_output=True)
    subprocess.run(["git", "-C", str(dest), "checkout", "--quiet", "--detach", LEGACY_REV], check=True)
    original = inventory(dest)
    for entry in git("ls-tree", "-rz", LEGACY_REV, "rust-client", "shared", "LICENSE").split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, kind, digest = metadata.split()
        require(kind == b"blob" and mode == b"100644", "Unexpected pinned tree entry")
        data = checked_file(dest, name.decode()).read_bytes()
        require(hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest() == digest.decode(), "2.1.9 source blob changed")
    subprocess.run([os.sys.executable, str(next(iter(OVERLAYS))), str(dest)], check=True)
    subprocess.run(["git", "-C", str(dest), "apply", "--recount", str(HERE / "csqtt-waiters.patch")], check=True)
    shutil.copyfile(HERE / "mcrf_captcha_waiters.rs", dest / "rust-client/mcrf_captcha_waiters.rs")
    spec = importlib.util.spec_from_file_location("mcrf_stream_overlay", HERE / "prepare-csqtt-streams.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    main, session = (dest / "rust-client" / p for p in ("main.rs", "session.rs"))
    a, b = module.transform(main.read_text(encoding="utf-8"), session.read_text(encoding="utf-8"))
    main.write_text(a, encoding="utf-8", newline="\n")
    session.write_text(b, encoding="utf-8", newline="\n")
    shutil.copyfile(HERE / "mcrf_streams.rs", dest / "rust-client/mcrf_streams.rs")
    return {"revision": LEGACY_REV, "original": original, "overlays": {str(p): digest for p, digest in OVERLAYS.items()}}


def adapt_crate(root, key):
    crate = root / "rust-client"
    if key in ("g251", "g253"):
        from prepare_csqtt_vk_backoff import apply as apply_vk_backoff
        apply_vk_backoff(crate)
    manifest = crate / "Cargo.toml"
    text = manifest.read_text(encoding="utf-8")
    text, count = re.subn(r'(?m)^name = "csqtt-client"$', 'name = "mcrf-adapter-' + key + '"', text)
    require(count == 1, "Package anchor changed")
    text, count = re.subn(r'\[\[bin\]\]\s*name = "client"\s*path = "main.rs"', '[lib]\npath = "main.rs"', text)
    require(count == 1, "Binary boundary changed")
    require('[patch.crates-io]' in text and '[profile.release]' in text, "Manifest boundary changed")
    text = text.partition("[profile.release]")[0]
    require('primp = "1.3.1"' in text, "primp dependency changed")
    text = text.replace('primp = "1.3.1"', 'primp = { path = "vendor/primp" }')
    if key != "g20":
        common = root.parent / "g20/rust-client/vendor/primp"
        local = crate / "vendor/primp"
        require({r["path"] for r in inventory(common)} == {r["path"] for r in inventory(local)}, "primp inventory differs")
        for row in inventory(local):
            rel = row["path"]
            require((local / rel).read_bytes().replace(b"\r\n", b"\n") == (common / rel).read_bytes().replace(b"\r\n", b"\n"), "primp source differs")
        text = text.replace('path = "vendor/primp"', 'path = "../../g20/rust-client/vendor/primp"')
    if 'netstack-smoltcp = "0.2.4"' in text:
        vendor = crate / "vendor/netstack-smoltcp/Cargo.toml"
        value = vendor.read_text(encoding="utf-8")
        require(value.count('name = "netstack-smoltcp"') == 1, "netstack identity changed")
        vendor.write_text(value.replace('name = "netstack-smoltcp"', 'name = "mcrf-netstack-' + key + '"'), encoding="utf-8", newline="\n")
        text = text.replace('netstack-smoltcp = "0.2.4"', 'netstack-smoltcp = { package = "mcrf-netstack-' + key + '", path = "vendor/netstack-smoltcp" }')
    text = text.replace('edition = "2024"', 'edition = "2024"\nautobins = false')
    manifest.write_text(text, encoding="utf-8", newline="\n")
    main = crate / "main.rs"
    text = main.read_text(encoding="utf-8")
    require(text.count("fn main()") == 1 and text.count("std::env::args()") == 1, "Runtime boundary changed")
    text = text.replace("std::env::args()", "mcrf_arguments()")
    text += '\n// Generated boundary: original main retains runtime, IPC and shutdown.\npub fn mcrf_run() { main(); }\nfn mcrf_arguments() -> impl Iterator<Item = String> {\n    std::env::args().filter(|a| !a.starts_with("--mcrf-generation="))\n}\n'
    main.write_text(text, encoding="utf-8", newline="\n")


def isolate_old_crypto(output, cargo_home):
    """Cargo cannot resolve two exact compatible aws-lc-rs releases together.
    Namespace the old package, preserving its source and native symbol prefix.
    """
    lock = tomllib.loads((output / "adapters/g20/rust-client/Cargo.lock").read_text())
    pin = next(p for p in lock["package"] if p["name"] == "aws-lc-rs" and p["version"] == "1.17.3")
    archives = list(pathlib.Path(cargo_home).glob("registry/cache/*/aws-lc-rs-1.17.3.crate"))
    require(len(archives) == 1 and sha(archives[0]) == pin["checksum"], "Pinned aws-lc-rs archive required")
    dest = output / "crypto"
    with tarfile.open(archives[0]) as archive:
        for member in archive:
            rel = pathlib.PurePosixPath(member.name)
            require(not rel.is_absolute() and ".." not in rel.parts and (member.isfile() or member.isdir()), "Unsafe registry archive")
            if member.isfile():
                target = dest / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.extractfile(member).read())
    manifest = dest / "aws-lc-rs-1.17.3/Cargo.toml"
    text = manifest.read_text(encoding="utf-8")
    require(text.count('name = "aws-lc-rs"') == 1, "Crypto package identity changed")
    manifest.write_text(text.replace('name = "aws-lc-rs"', 'name = "mcrf-aws-lc-rs-1173"'), encoding="utf-8", newline="\n")
    adapter = output / "adapters/g20/rust-client/Cargo.toml"
    text = adapter.read_text(encoding="utf-8")
    require(text.count('aws-lc-rs = "=1.17.3"') == 1, "Old crypto requirement changed")
    adapter.write_text(text.replace('aws-lc-rs = "=1.17.3"', 'aws-lc-rs = { package = "mcrf-aws-lc-rs-1173", path = "../../../crypto/aws-lc-rs-1.17.3" }'), encoding="utf-8", newline="\n")
    return {"registryChecksum": pin["checksum"], "packageRenameOnly": True, "nativePrefixUnchanged": True}


def native_routing_inventory():
    paths=[HERE/"prepare_csqtt_routing.py",HERE/"native_routing_cli_evidence.py",HERE/"native_routing_evidence.py",HERE/"routing/Cargo.toml"]
    paths += [p for directory in (HERE/"routing/src",HERE/"routing/native") for p in directory.rglob("*") if p.is_file()]
    return {p.relative_to(HERE).as_posix():sha(p) for p in sorted(paths)}


def prepare(output, cargo_home="D:/MCRF/Projects/MCRF/work/toolchains/cargo-home",native_routing=False):
    from prepare_csqtt_gooby import apply as apply_gooby, source_inventory as gooby_inventory
    output = pathlib.Path(output).absolute()
    require(not output.exists() and BUILD in output.parents, "Fresh external Android stage required")
    require(not any(p.is_symlink() or (hasattr(p, "is_junction") and p.is_junction()) for p in (output, *output.parents)), "Redirected stage")
    inputs = {key: checked_sources(key) for key in PINS}
    output.mkdir(parents=True)
    receipt = {"schema": 1, "state": "PREPARING", "packageReady": False, "producerSha256": sha(__file__),
               "directTunFdContract": 2 if native_routing else 1, "directTunGenerations": ["2.5.1","2.5.2","2.5.3"] if native_routing else ["2.5.2", "2.5.3"],
               "nativeRoutingPrepared":bool(native_routing),"nativeRoutingContract":0,
               "privatePacketBridgeContract":1,
               "privatePacketBridgeOverlays":{name:sha(HERE/name) for name in ("prepare_csqtt_private_udp.py","native-packet/mcrf_private_udp.rs","native-packet/mcrf_private_udp_dispatcher_test.rs.in")},
               "directTunOverlays": {name:sha(HERE/name) for name in ("prepare_csqtt_direct_tun.py","mcrf_direct_tun.rs","mcrf_direct_tun_receive.rs","direct_tun_harness.rs","test_prepare_csqtt_direct_tun.py")},
               "vkBackoffOverlays": {name: sha(HERE / name) for name in ("prepare_csqtt_vk_backoff.py", "mcrf_vk_backoff.rs", "prepare_csqtt_flow_harness.py", "vk_backoff_harness/Cargo.toml", "vk_backoff_harness/lib.rs", "vk_backoff_harness/auth.rs.in", "vk_backoff_harness/worker.rs.in", "vk_backoff_harness/bootstrap.rs.in", "vk_backoff_harness/flow.rs.in")},
               "goobyBuiltinOverlays": gooby_inventory(),
               "inputs": {}, "artifacts": []}
    if native_routing:
        receipt["nativeRoutingOverlays"]=native_routing_inventory()
    try:
        for key, (source, proof) in inputs.items():
            copy_tree(source, output / "adapters" / key, proof["inventory"])
            receipt["inputs"][key] = {**proof, "path": str(source)}
        receipt["inputs"]["g219"] = legacy_source(output / "adapters/g219")
        for key in KEYS:
            receipt["inputs"][key]["cargoLockSha256"] = sha(output / "adapters" / key / "rust-client/Cargo.lock")
            adapt_crate(output / "adapters" / key, key)
            if key in ("g251", "g253"):
                apply_gooby(output / "adapters" / key / "rust-client")
            if key == "g253" or native_routing and key == "g251":
                from prepare_csqtt_direct_tun import apply as apply_direct_tun
                apply_direct_tun(output / "adapters" / key / "rust-client")
            if native_routing and key in ("g251","g253"):
                from prepare_csqtt_routing import prepare as prepare_routing
                crate=output / "adapters" / key / "rust-client"
                prepare_routing(crate,generation="2.5.1" if key=="g251" else "2.5.3")
                if key=="g251":
                    manifest=crate/"Cargo.toml";text=manifest.read_text(encoding="utf-8")
                    require(text.count('path = "mcrf-routing-policy"')==1,"Shared native policy path changed")
                    manifest.write_text(text.replace('path = "mcrf-routing-policy"','path = "../../g253/rust-client/mcrf-routing-policy"'),encoding="utf-8",newline="\n")
            from prepare_csqtt_private_udp import apply as apply_private_udp
            apply_private_udp(output / "adapters" / key / "rust-client", key)
        receipt["oldCrypto"] = isolate_old_crypto(output, cargo_home)
        for name in ("lib.rs", "main.rs", "Cargo.toml"):
            shutil.copyfile(SOURCE / name, output / name)
        if native_routing:
            shutil.copyfile(SOURCE / "native_router_entry.rs", output / "native_router_entry.rs")
        cargo = output / "Cargo.toml"
        text = cargo.read_text(encoding="utf-8")
        if native_routing:
            text += '\n[[bin]]\nname = "mcrf-native-router"\npath = "native_router_entry.rs"\nrequired-features = ["ported", "native-routing"]\n'
        # Modern crates are explicit testable members. This keeps their tests on
        # exactly the production workspace lock instead of resolving a second
        # independent dependency graph in an adapter directory.
        modern_members=['adapters/' + k + '/rust-client' for k in ('g251','g253')]
        excluded = ['adapters/' + k + '/rust-client' for k in KEYS if k not in ('g251','g253')]
        excluded += ['adapters/' + k + '/rust-client/vendor/' + v for k in KEYS for v in ('primp', 'netstack-smoltcp')]
        excluded += ['crypto/aws-lc-rs-1.17.3', 'admission-tests', 'gooby_builtin']
        text += '\n[workspace]\nresolver = "3"\nmembers = '+json.dumps(modern_members)+'\nexclude = ' + json.dumps(excluded) + '\n\n[features]\nnative-routing = []\nported = [' + ', '.join('"dep:mcrf-adapter-' + k + '"' for k in KEYS) + ']\n\n[dependencies]\n'
        text += ''.join('mcrf-adapter-' + k + ' = { path = "adapters/' + k + '/rust-client", optional = true }\n' for k in KEYS)
        cargo.write_text(text, encoding="utf-8", newline="\n")
        # Seed registry resolution from the five pinned lockfiles, never from
        # today's registry head. The resolved lock is recorded as a new input.
        packages = {}
        for key in KEYS:
            lock = (output / "adapters" / key / "rust-client/Cargo.lock").read_text(encoding="utf-8")
            for block in lock.split("[[package]]")[1:]:
                item = tomllib.loads("[[package]]" + block)["package"][0]
                if "source" in item:
                    identity = (item["name"], item["version"], item["source"])
                    if identity in packages:
                        require(packages[identity][0].get("checksum") == item.get("checksum"), "Lockfile checksum conflict")
                    packages[identity] = (item, block)
        # Admit only exact additional dependencies already exercised by the
        # standalone Gooby component. Existing core pins remain the union above.
        lock = (HERE / "gooby_builtin/Cargo.lock").read_text(encoding="utf-8")
        for block in lock.split("[[package]]")[1:]:
            item = tomllib.loads("[[package]]" + block)["package"][0]
            if "source" in item:
                identity = (item["name"], item["version"], item["source"])
                if identity in packages:
                    require(packages[identity][0].get("checksum") == item.get("checksum"), "Gooby lockfile checksum conflict")
                else:
                    packages[identity] = (item, block)
        (output / "Cargo.lock").write_text('version = 4\n\n' + ''.join('[[package]]' + packages[k][1] for k in sorted(packages)), encoding="utf-8", newline="\n")
        receipt["allowedRegistry"] = [v[0] for v in packages.values()]
        harness=output / "admission-tests"
        harness.mkdir()
        for name in ("Cargo.toml", "lib.rs"):
            shutil.copyfile(HERE / "vk_backoff_harness" / name, harness / name)
        # Test harness resolves ONLY from the same admitted registry union.
        manifest=harness / "Cargo.toml"
        text=manifest.read_text()
        for dependency in ("anyhow","serde_json"):
            versions=[p["version"] for p in receipt["allowedRegistry"] if p["name"]==dependency]
            require(versions,"Pinned harness dependency version required")
            exact=max(versions,key=lambda v:tuple(map(int,v.split("."))))
            text=text.replace(dependency+' = "1"',dependency+' = "='+exact+'"')
        require(text.count('"test-util"]')==1,"Admission Tokio feature boundary changed")
        text=text.replace('"test-util"]','"test-util", "net"]')
        manifest.write_text(text,encoding="utf-8",newline="\n")
        libc_versions=[p["version"] for p in receipt["allowedRegistry"] if p["name"]=="libc"]
        require(libc_versions,"Pinned libc required for direct TUN admission")
        libc_version=max(libc_versions,key=lambda v:tuple(map(int,v.split("."))))
        with manifest.open("a",encoding="utf-8") as stream:stream.write('\n[dev-dependencies]\nlibc = "='+libc_version+'"\n')
        shutil.copyfile(HERE/"direct_tun_harness.rs",harness/"direct_tun_harness.rs")
        with (harness/"lib.rs").open("a",encoding="utf-8") as stream:stream.write('\n#[cfg(test)]\nmod direct_tun_harness;\n')
        from prepare_csqtt_flow_harness import prepare as prepare_flow_harness
        prepare_flow_harness(output)
        shutil.copyfile(output / "Cargo.lock",harness / "Cargo.lock")
        receipt["generated"] = inventory(output)
        receipt["state"] = "PREPARED_REAL_ADAPTERS"
    except Exception:
        receipt["state"] = "FAILED_PREPARATION"
        raise
    finally:
        write_json(output / "receipt.json", receipt)
    return receipt


def environment(args, output):
    tc = pathlib.Path(args.toolchain)
    compiler = pathlib.Path(args.ndk) / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    helper = output / "host-tools"
    helper.mkdir(exist_ok=True)
    shutil.copyfile(compiler / "llvm-dlltool.exe", helper / "dlltool.exe")
    env = {k:v for k,v in os.environ.items() if not k.startswith(("CARGO_PROFILE_", "CARGO_TARGET_")) and k not in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "RUSTC_WRAPPER", "RUSTC_WORKSPACE_WRAPPER")}
    mingw = pathlib.Path(args.mingw) / "bin"
    target = pathlib.Path(args.target_dir).absolute() if args.target_dir else output / "target"
    frozen = [BUILD / pin[0] for pin in PINS.values()] + [LEGACY, BUILD / "csqtt252-aligned-armv7-20261101-02"]
    require(BUILD in target.parents and target.name == "target" and not any(p == target or p in target.parents for p in frozen), "External mutable target cache required")
    require(not any(p.is_symlink() or (hasattr(p, "is_junction") and p.is_junction()) for p in (target, *target.parents)), "Redirected target cache")
    env.update({"RUSTC": str(tc / "bin/rustc.exe"), "CARGO_HOME": args.cargo_home, "CARGO_TARGET_DIR": str(target), "ANDROID_NDK_HOME": args.ndk, "ANDROID_NDK_ROOT": args.ndk, "LIBCLANG_PATH": str(compiler), "CMAKE_GENERATOR": "Ninja",
                "CC_x86_64_pc_windows_gnu": str(mingw / "x86_64-w64-mingw32-clang.exe"), "AR_x86_64_pc_windows_gnu": str(mingw / "llvm-ar.exe")})
    env["PATH"] = os.pathsep.join(map(str, [helper, tc / "bin", tc / "lib/rustlib/x86_64-pc-windows-gnu/bin", tc / "lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained", pathlib.Path(args.cargo_ndk), compiler, pathlib.Path(args.cmake)])) + os.pathsep + env.get("PATH", "")
    # Rust GNU's bundled linker supplies its libgcc/MSVCRT runtime. C compilation
    # uses explicit clang paths; do not shadow the Rust linker with clang's alias.
    env["CARGO_TARGET_X86_64_PC_WINDOWS_GNU_LINKER"] = str(tc / "lib/rustlib/x86_64-pc-windows-gnu/bin/self-contained/x86_64-w64-mingw32-gcc.exe")
    env["PATH"] += os.pathsep + str(mingw)
    env["PATH"] = args.nasm + os.pathsep + env["PATH"]
    return env


def host_proof(binary, output, runtime_env=None, native_routing=False, runner=None):
    def launch(mode, arguments=(), direct_token=None):
        env = {k:v for k,v in (runtime_env or os.environ).items() if k not in ("MCRF_BS_RUST_MODE", "CSQTT_CONFIG_FILE", "MCRF_DIRECT_TUN_GENERATION")}
        if direct_token is not None:env["MCRF_DIRECT_TUN_GENERATION"]=direct_token
        if mode is not None:
            env["MCRF_BS_RUST_MODE"] = mode
        p = subprocess.run([str(binary), *arguments], env=env, capture_output=True, timeout=20) if runner is None else runner(mode,arguments,direct_token)
        return {"mode": mode, "args": list(arguments), "exit": p.returncode, "stdout": p.stdout.decode(errors="replace"), "stderr": p.stderr.decode(errors="replace")}
    rows = [launch(None), launch("unknown"), launch("2.0", ("--mcrf-generation=2.5.1",))]
    require(all(r["exit"] == 78 for r in rows), "Selector rejection failed")
    modes = ("2.0", "2.1.5", "2.1.9", "2.5.1", "2.5.2", "2.5.3")
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        parallel = list(pool.map(lambda mode: launch(mode, ("--mcrf-unknown-cli",)), modes))
    require(all(r["exit"] == 2 and "unexpected argument" in r["stderr"] for r in parallel), "Real generation CLI dispatch failed")
    rows.extend(parallel)
    for mode in modes:
        row = launch(mode)
        require(row["exit"] == 1 and "[ФАТАЛ]" in row["stderr"] + row["stdout"], "Runtime/config validation failed")
        rows.append(row)
        matched = launch(mode, ("--mcrf-generation=" + mode,))
        require((matched["exit"], matched["stdout"], matched["stderr"]) == (row["exit"], row["stdout"], row["stderr"]), "Host CLI leaked into generation parser")
        rows.append(matched)
    # Preserve the historical CLI distinction rather than dispatching every mode
    # to one newer parser. These paths stop at config validation before networking.
    for mode in modes:
        row = launch(mode, ("--turn-transport=udp",))
        require(row["exit"] == (2 if mode == "2.0" else 1), "Historical TURN CLI distinction lost")
        rows.append(row)
    capability=launch(None,("--mcrf-direct-tun-capability",))
    expected={"marker":"MCRF_DIRECT_TUN_FD_V1","version":2 if native_routing else 1,"generations":["2.5.1","2.5.2","2.5.3"] if native_routing else ["2.5.2","2.5.3"],"fdAdoptionRequired":True}
    require(capability["exit"]==0 and json.loads(capability["stdout"])==expected,"Compiled direct TUN capability missing")
    negative=[launch("2.5.3",("--tun-uds","mcrf-direct-0123456789abcdef")),
              launch("2.5.3",("--tun-uds","mcrf-direct-0123456789abcdef"),"0"),
              launch("2.0",("--tun-uds","mcrf-direct-0123456789abcdef"),"1")]
    require(all(row["exit"]==78 for row in negative),"Unsafe direct TUN entry admitted")
    rows.extend([capability,*negative])
    write_json(output / "host-proof.json", {"state": "CLI_RUNTIME_PARALLEL_PASS", "platform":"android" if runner is not None else "windows", "binarySha256": sha(binary), "cases": rows, "providerTraffic": False,"directTunFdContract":2 if native_routing else 1,"directTunCapability":expected})
    return sha(output / "host-proof.json")


def build(args, output, receipt):
    native_routing=receipt.get("nativeRoutingPrepared") is True
    require(not native_routing or "host" not in args.targets,"Native routing adapters are Unix-only; use Android targets and Android CLI evidence")
    physical_serial=getattr(args,"native_routing_device",None)
    require(not physical_serial or native_routing,"Physical CLI verification requires native routing")
    cli_serial=physical_serial or getattr(args,"native_routing_emulator","emulator-5556")
    cli_target="arm64" if physical_serial else "x86_64"
    if native_routing:
        require(cli_target in args.targets,"Native routing requires an Android artifact matching the CLI fixture ABI")
        from native_routing_cli_evidence import scope
        scope(args.native_routing_adb,cli_serial,physical=bool(physical_serial))
    features="ported,native-routing" if native_routing else "ported"
    env = environment(args, output)
    cargo = pathlib.Path(args.toolchain) / "bin/cargo.exe"
    receipt["toolchain"] = {"cargoSha256": sha(cargo), "rustcSha256": sha(env["RUSTC"])}
    receipt["toolchain"].update({"hostClangSha256": sha(pathlib.Path(args.mingw) / "bin/clang.exe"),
                                "nasmSha256": sha(pathlib.Path(args.nasm) / "nasm.exe"),
                                "ndkClangSha256": sha(pathlib.Path(args.ndk) / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe")})
    receipt["targetDirectory"] = env["CARGO_TARGET_DIR"]
    def command(argv, log):
        with (output / log).open("wb") as stream:
            p = subprocess.run([str(cargo), *argv], cwd=output, env=env, stdout=stream, stderr=subprocess.STDOUT)
        require(p.returncode == 0, "Cargo failed; see " + str(output / log))
    try:
        unix_proof=getattr(args,"direct_tun_unix_proof",None)
        if unix_proof:
            from prepare_csqtt_direct_tun import verify_unix_proof
            receipt["directTunUnixTests"]=verify_unix_proof(unix_proof)
        else:
            receipt["directTunUnixTests"]={"state":"NOT_EXECUTED_UNIX_RUNTIME_REQUIRED"}
        # rcgen's shared test-only dependency must use the same 1.18 registry
        # package as the modern core. The old core already owns its renamed
        # 1.17.3 source package; its crypto implementation stays unchanged.
        seeded=tomllib.loads((output / "Cargo.lock").read_text(encoding="utf-8"))
        if any(p["name"]=="aws-lc-rs" and p["version"]=="1.17.3" for p in seeded["package"]):
            command(["update","--offline","-p","aws-lc-rs@1.17.3","--precise","1.18.0"],"resolve-crypto.log")
        allowed = {(p["name"], p["version"], p.get("checksum")) for p in receipt["allowedRegistry"]}
        resolved = tomllib.loads((output / "Cargo.lock").read_text(encoding="utf-8"))
        require(all((p["name"], p["version"], p.get("checksum")) in allowed for p in resolved["package"] if "source" in p), "Resolved dependency outside pinned lockfiles")
        command(["fetch", "--locked", "--target", "x86_64-pc-windows-gnu"], "resolve.log")
        receipt["workspaceLockSha256"] = sha(output / "Cargo.lock")
        command(["test","--frozen","--lib",*(["--features","native-routing"] if native_routing else []),"--","--test-threads=1"],"direct-tun-boundary-tests.log")
        boundary_log=(output/"direct-tun-boundary-tests.log").read_text(encoding="utf-8")
        names=("direct_tun_requires_explicit_ingress_and_positive_token","direct_tun_cannot_enter_legacy_adapter_or_duplicate_socket","ordinary_raw_entry_is_unchanged_without_direct_request")
        require(all(re.search(r"^test direct_tun_tests::"+name+r" \.\.\. ok$",boundary_log,re.M) for name in names),"Direct TUN entry boundary tests missing")
        receipt["directTunBoundaryTests"]={"passed":3,"providerTraffic":False,"logSha256":sha(output/"direct-tun-boundary-tests.log")}
        command(["generate-lockfile", "--manifest-path", "admission-tests/Cargo.toml", "--offline"], "resolve-admission.log")
        admission_lock=output / "admission-tests/Cargo.lock"
        admission_packages=tomllib.loads(admission_lock.read_text(encoding="utf-8"))["package"]
        require(all((p["name"],p["version"],p.get("checksum")) in allowed for p in admission_packages if "source" in p),"Admission dependency outside pinned union")
        env.pop("RUSTFLAGS",None)
        command(["test", "--manifest-path", "admission-tests/Cargo.toml", "--frozen", "--", "--test-threads=1"], "admission-tests.log")
        log=(output / "admission-tests.log").read_text(encoding="utf-8")
        passed=sum(map(int,re.findall(r"test result: ok\.\s+(\d+) passed; 0 failed; 0 ignored;",log)))
        require(passed>=22 and admission_flow_cases_passed(log),"Missing actual transformed auth/worker/bootstrap admission tests")
        require(re.search(r"^test direct_tun_harness::direct_tun::tests::token_requires_canonical_positive_signed_63bit \.\.\. ok$",log,re.M),"Direct TUN native admission test missing")
        receipt["vkBackoffAdmission"]={"passed":passed,"flowCases":6,"providerTraffic":False,"logSha256":sha(output / "admission-tests.log"),"lockSha256":sha(admission_lock),"extractionSha256":sha(output / "admission-tests/extracted-flow-source.json")}
        for target in args.targets:
            if target == "host":
                env.pop("RUSTFLAGS", None)
                command(["build", "--release", "--frozen", "--features", features, "--bin", "mcrf-csqtt-transport"], "build-host.log")
                artifact = pathlib.Path(env["CARGO_TARGET_DIR"]) / "release/mcrf-csqtt-transport.exe"
                receipt["hostProofSha256"] = host_proof(artifact, output, env)
            else:
                env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                abi, triple = TARGETS[target]
                binaries=["--bin","mcrf-csqtt-transport"]
                if native_routing:binaries+=["--bin","mcrf-native-router"]
                command(["ndk", "-t", abi, "-P", "26", "build", "--release", "--frozen", "--features", features, *binaries], "build-" + abi + ".log")
                artifact = pathlib.Path(env["CARGO_TARGET_DIR"]) / triple / "release/mcrf-csqtt-transport"
                elf(artifact, abi)
                if native_routing:
                    router=output/"router-artifacts"/abi/"libmcrf_router.so"
                    router.parent.mkdir(parents=True,exist_ok=True)
                    shutil.copyfile(artifact.with_name("mcrf-native-router"),router)
                    elf(router,abi)
                    receipt.setdefault("routerArtifacts",[]).append({"abi":abi,"path":str(router),"sha256":sha(router),"bytes":router.stat().st_size})
            retained = output / "artifacts" / ("mcrf-csqtt-transport.exe" if target == "host" else TARGETS[target][0] + "/libcsqtt_transport.so")
            retained.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(artifact, retained)
            artifact = retained
            content = artifact.read_bytes()
            receipt["artifacts"].append({"target": target, "abi": "host" if target == "host" else TARGETS[target][0], "path": str(artifact), "sha256": sha(artifact), "bytes": len(content), "compressedBytes": len(zlib.compress(content, 9))})
            if native_routing and target==cli_target:
                from native_routing_cli_evidence import run as android_cli_proof
                receipt["nativeRoutingCliTests"]=android_cli_proof(artifact,output,args.native_routing_adb,cli_serial,host_proof,physical=bool(physical_serial))
                receipt["hostProofSha256"]=receipt["nativeRoutingCliTests"]["sha256"]
            write_json(output / "receipt.json", receipt)
        for key in PINS:
            _, proof = checked_sources(key)
            require(proof == {k:v for k,v in receipt["inputs"][key].items() if k not in ("path", "cargoLockSha256")}, "Frozen input changed")
        for row in receipt["generated"]:
            if row["path"] not in ("Cargo.lock","admission-tests/Cargo.lock"):
                require(sha(checked_file(output, row["path"])) == row["sha256"], "Generated source changed during build")
        require(receipt["vkBackoffOverlays"] == {name: sha(HERE / name) for name in receipt["vkBackoffOverlays"]}, "VK backoff overlay changed during build")
        require(receipt["directTunOverlays"] == {name:sha(HERE/name) for name in receipt["directTunOverlays"]},"Direct TUN overlay changed during build")
        require(receipt["privatePacketBridgeOverlays"] == {name:sha(HERE/name) for name in receipt["privatePacketBridgeOverlays"]},
                "Private UDP bridge overlay changed during build")
        if native_routing:
            require(receipt["nativeRoutingOverlays"]=={name:sha(HERE/name) for name in receipt["nativeRoutingOverlays"]},"Native routing overlay changed during build")
        from prepare_csqtt_gooby import source_inventory as gooby_inventory
        require(receipt["goobyBuiltinOverlays"] == gooby_inventory(), "Gooby overlay changed during build")
        receipt["csqttGoobyBuiltinContract"]=0
        if getattr(args,"gooby_unix_proof",None):
            from gooby_evidence import verify as verify_gooby
            receipt["goobyAndroidTests"]=verify_gooby(args.gooby_unix_proof,output)
            receipt["csqttGoobyBuiltinContract"]=1
        if native_routing and getattr(args,"native_routing_unix_proof",None):
            from native_routing_evidence import verify as verify_routing
            receipt["nativeRoutingUnixTests"]=verify_routing(args.native_routing_unix_proof,output)
            receipt["nativeRoutingContract"]=1
        receipt["sourceStable"] = True
        receipt["performanceStatus"] = "CANDIDATE_NOT_MEASURED"
        receipt["profile"] = {"lto":"fat", "opt-level":"z", "panic":"abort", "codegen-units":1}
        receipt["state"] = "COMPILED_MULTICALL_PROOF_NOT_PACKAGED"
    except Exception as error:
        receipt["state"] = "FAILED_BUILD_OR_PROOF"
        receipt["error"] = str(error)
        raise
    finally:
        write_json(output / "receipt.json", receipt)
    return receipt


def cli():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--native-routing",action="store_true",help="Prepare the independent Android routing adapters; does not grant packaging acceptance")
    parser.add_argument("--native-routing-adb",default="C:/Users/dieonfire/AppData/Local/Android/Sdk/platform-tools/adb.exe")
    parser.add_argument("--native-routing-emulator",default="emulator-5556")
    parser.add_argument("--native-routing-device",help="Explicit authorized physical ARM64 Android serial for configuration-only CLI verification")
    parser.add_argument("--native-routing-unix-proof",help="Exact owned native source/kernel and ordinary Main service evidence for both native generations")
    parser.add_argument("--direct-tun-unix-proof",help="Source-bound real Linux/Android FD tests; required before capability packaging, not native compile")
    parser.add_argument("--gooby-unix-proof",help="Exact prepared native sources and owned Android TCP/TLS test execution")
    parser.add_argument("--compile-direct-tun-tests",action="store_true",help="Only crosscompile ARM64 admission lib tests --no-run, offline; never access a device")
    parser.add_argument("--target-dir")
    parser.add_argument("--mingw", default="D:/MCRF/Builds/Tools/llvm-mingw-20261020-ucrt-x86_64")
    parser.add_argument("--nasm", default="D:/MCRF/Builds/Tools/nasm-3.01")
    parser.add_argument("--targets", nargs="+", choices=("host", *TARGETS), default=["host", *TARGETS])
    parser.add_argument("--toolchain", default="D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain")
    parser.add_argument("--cargo-home", default="D:/MCRF/Projects/MCRF/work/toolchains/cargo-home")
    parser.add_argument("--cargo-ndk", default="D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin")
    parser.add_argument("--ndk", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
    parser.add_argument("--cmake", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin")
    args = parser.parse_args()
    require(not (args.execute and args.compile_direct_tun_tests),"Choose native build or admission --no-run, not both")
    result = prepare(args.output, args.cargo_home,args.native_routing)
    if args.compile_direct_tun_tests:
        from prepare_csqtt_direct_tun import compile_android_tests
        result=compile_android_tests(args,pathlib.Path(args.output).absolute(),result)
    elif args.execute:
        result = build(args, pathlib.Path(args.output).absolute(), result)
    print(json.dumps({k:v for k,v in result.items() if k in ("state", "artifacts", "error", "sourceStable","testBinary")}))


if __name__ == "__main__":
    cli()
