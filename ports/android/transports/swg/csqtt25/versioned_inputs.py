"""Prepare/verify known signed upstream transports. Never builds or executes them.

The shared new library contains the exact official 2.5.2 AND 2.5.3 bytes.
The existing 2.5.1 stage remains a separate verified input to Gradle.
"""
import argparse
import hashlib
import io
import json
import pathlib
import struct
import subprocess
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
LOCK = HERE / "swg-versions.json"
LIBRARY = "libcsqtt252_transport.so"
ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def regular(path):
    path = pathlib.Path(path).absolute()
    require(not any(p.is_symlink() for p in (path, *path.parents)) and path.is_file(), "Regular owned input required")
    return path.read_bytes()


def prepared_bridge():
    """Return the exact deterministic bridge frozen by Prepare-P3AbiSource.ps1."""
    source = regular(HERE.parent / "mcrf_csqtt25.go").decode("utf-8").replace("\r\n", "\n")
    anchor = "NormalizeTunnel: csqtt25.NormalizeTunnel,"
    require("McrfSampleStreamStats" not in source and source.count(anchor) == 1,
            "CS25 sampled stream bridge anchor changed")
    return source.replace(anchor, anchor + "\n\t\tMcrfSampleStreamStats: true,").encode("utf-8")


def metadata():
    return json.loads(regular(LOCK))


def inspect_elf(data, abi):
    cls, machine = ABIS[abi]
    require(len(data) >= 64 and data[:4] == b"\x7fELF" and data[4:6] == bytes([cls, 1])
            and struct.unpack_from("<HH", data, 16) == (3, machine), "Wrong native ELF/ABI")


def inspect_apk(path, version, apksigner):
    lock = metadata()
    pin = lock["versions"][version]
    asset = next(a for a in pin["assets"] if a["name"] == f"CSQTT-{version}-universal.apk")
    data = regular(path)
    require(len(data) == asset["size"] and "sha256:" + digest(data) == asset["digest"], "Official APK digest mismatch")
    # APK signature verification is mandatory, separate from GitHub's byte digest.
    result = subprocess.run([str(apksigner), "verify", "--print-certs", str(path)],
                            capture_output=True, text=True, check=True)
    certificates = [line.split(": ", 1)[1].strip() for line in result.stdout.splitlines()
                    if "certificate SHA-256 digest:" in line]
    require(certificates == [lock["signerSha256"]], "Upstream signer mismatch")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate APK entries")
        clients = {n for n in names if n.startswith("lib/") and n.endswith("/libclient.so")}
        require(clients == {f"lib/{abi}/libclient.so" for abi in ABIS}, "Upstream client ABI composition changed")
        outputs = {}
        for abi, native in pin["native"].items():
            content = archive.read(native["entry"])
            require(len(content) == native["bytes"] and digest(content) == native["sha256"], "Native digest mismatch")
            inspect_elf(content, abi)
            outputs[abi] = content
    return outputs


def verify_source(source):
    lock = metadata()
    def git(*args):
        return subprocess.check_output(["git", "-C", str(source), *args])
    require(not git("status", "--porcelain", "--untracked-files=all").strip(), "Pristine source required")
    revisions = {v["revision"] for v in lock["versions"].values()}
    require(git("rev-parse", "HEAD").decode().strip() in revisions, "Pinned release checkout required")
    for pin in lock["versions"].values():
        revision = pin["revision"]
        require(git("rev-parse", f'{pin["tag"]}^{{commit}}').decode().strip() == revision, "Release tag moved")
        for name, key in (("rust-client", "clientTree"), ("shared", "sharedTree")):
            require(git("rev-parse", f"{revision}:{name}").decode().strip() == pin[key], "Source tree changed")
        require(digest(git("show", f"{revision}:LICENSE")) == lock["licenseSha256"], "PolyForm notice changed")
    return git


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def prepare(source, apks, apksigner, output):
    # Validate ALL inputs before creating an output directory.
    git = verify_source(source)
    outputs = [inspect_apk(apks[v], v, apksigner) for v in ("2.5.2", "2.5.3")]
    require(outputs[0] == outputs[1], "Versions no longer have identical transport bytes")
    require(not output.exists(), "Refusing to overwrite a stage")
    output.mkdir(parents=True)
    for abi, data in outputs[0].items():
        (output / abi).mkdir()
        (output / abi / LIBRARY).write_bytes(data)
    (output / "licenses").mkdir()
    (output / "licenses/swg-versions.json").write_bytes(regular(LOCK))
    license_data = git("show", metadata()["versions"]["2.5.3"]["revision"] + ":LICENSE")
    (output / "licenses/CSQTT-LICENSE").write_bytes(license_data)
    # A source-only rebuild path is frozen alongside official binaries. No patch
    # from the 2.5.1 worker transform is assumed applicable to this newer tree.
    for version, pin in metadata()["versions"].items():
        data = git("archive", "--format=zip", pin["revision"], "rust-client", "shared", "LICENSE")
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in archive.namelist():
                if name.endswith("/"):
                    continue
                rel = pathlib.PurePosixPath(name)
                require(not rel.is_absolute() and ".." not in rel.parts, "Unsafe source archive")
                target = output / "source" / version / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(name))
    files = {p.relative_to(output).as_posix(): digest(p.read_bytes())
             for p in output.rglob("*") if p.is_file()}
    write_json(output / "receipt.json", {"state": "UPSTREAM_INPUTS_VERIFIED_NOT_EXECUTED", "schema": 1,
               "lockSha256": digest(regular(LOCK)), "scriptSha256": digest(regular(__file__)),
               "versions": ["2.5.2", "2.5.3"], "files": files, "compiled": False, "executed": False})
    verify(output)


def verify(stage, wrapper=None):
    proof = json.loads(regular(stage / "receipt.json"))
    if proof.get("state") == "SOURCE_ALIGNED_INPUTS_READY":
        import sys
        sys.path.insert(0, str(HERE.parent))
        from csqtt25 import aligned_inputs
        return aligned_inputs.verify_versions(stage, wrapper)
    # Preserve one already verified immutable source/release receipt across
    # verifier evolution. Its receipt AND producer identities are both pinned;
    # every original file/native/source/signature-derived pin is still checked.
    retained = (digest(regular(stage / "receipt.json")) == "2d7fdcf3e6eece44d56f19d862ba6547a708c86c08f9816b487414721120df30"
                and proof.get("scriptSha256") == "9950a1b551afc0710628868b36a10f945678323b640913c15e5a60dd4aa2f4a8")
    require(proof["schema"] == 1 and proof["state"] == "UPSTREAM_INPUTS_VERIFIED_NOT_EXECUTED"
            and proof["compiled"] is False and proof["executed"] is False
            and proof["versions"] == ["2.5.2", "2.5.3"]
            and proof["lockSha256"] == digest(regular(LOCK))
            and (proof["scriptSha256"] == digest(regular(__file__)) or retained), "Version input receipt mismatch")
    actual = {p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file()} - {"receipt.json"}
    require(actual == set(proof["files"]), "Version stage file set changed")
    for name, sha in proof["files"].items():
        rel = pathlib.PurePosixPath(name)
        require(not rel.is_absolute() and ".." not in rel.parts and "\\" not in name, "Unsafe manifest path")
        require(digest(regular(stage / name)) == sha, "Version stage input changed")
    for abi in ABIS:
        data = regular(stage / abi / LIBRARY)
        native = metadata()["versions"]["2.5.2"]["native"][abi]
        require(digest(data) == native["sha256"] and len(data) == native["bytes"], "Version stage native mismatch")
        inspect_elf(data, abi)
    require(regular(stage / "licenses/swg-versions.json") == regular(LOCK), "Version notice mismatch")
    require(digest(regular(stage / "licenses/CSQTT-LICENSE")) == metadata()["licenseSha256"], "License mismatch")
    if wrapper:
        # The normal native-stage verifier separately binds this manifest to the
        # compiled wrapper. An old wrapper cannot advertise the new capability.
        receipt = json.loads(regular(wrapper / "receipt.json"))
        manifest_data = regular(wrapper / "source-manifest.json")
        require(digest(manifest_data) == receipt["sourceManifestSha256"], "Wrapper manifest changed")
        rows = json.loads(manifest_data)
        manifest = {r["path"]: r["sha256"] for r in rows}
        require(len(rows) == len(manifest), "Duplicate wrapper sources")
        for name in ("versions.go", "profile.go"):
            require(manifest.get("internal/csqtt25/" + name) == digest(regular(HERE / name)), "Rebuild version-aware wrapper first")
        require(manifest.get("mcrf_csqtt25.go") == digest(prepared_bridge()), "Rebuild native selector first")
        require(receipt["state"] == "COMPILED_NOT_EXECUTED" and receipt["sourceStable"] is True, "Frozen compiled wrapper required")
    return {"versions": proof["versions"], "library": LIBRARY}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify", type=pathlib.Path)
    parser.add_argument("--wrapper-stage", type=pathlib.Path)
    parser.add_argument("--source", type=pathlib.Path)
    parser.add_argument("--apk252", type=pathlib.Path)
    parser.add_argument("--apk253", type=pathlib.Path)
    parser.add_argument("--apksigner", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.verify:
        print(json.dumps(verify(args.verify.absolute(), args.wrapper_stage)))
    else:
        require(all((args.source, args.apk252, args.apk253, args.apksigner, args.output)), "Preparation inputs required")
        prepare(args.source, {"2.5.2": args.apk252, "2.5.3": args.apk253}, args.apksigner, args.output)
        print(json.dumps(verify(args.output)))
