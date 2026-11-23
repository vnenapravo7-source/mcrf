"""Build the exact retained WDTT source with a pinned private SOCKS UDP ingress."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess

from overlay import apply


BUILD_ROOT = Path("D:/MCRF/Builds/Android")
BASE_SOURCE = BUILD_ROOT / "p3-turn-source-c07ecc8c5cb249969456d62bb2f830d9"
BASE_PACKAGE = BUILD_ROOT / "p3-turn-native-36693de1b0fb487ab479cc4d102b533c"
BASE_PACKAGE_RECEIPT = "21389e5f55f769c88c98127462d5236f7d35abec80f80d4034e0b0453145d561"
BASE_MANIFEST = "2435c28078e3d423b0ddf4623cbc5c5cd292fd691a06eff17f007cc3998c26e1"
PATHPROBE_MANIFEST = "ebeeaaa8c25d28f8855fa147967e03ecedd9ed1d2a8a7a50da0e1cf5f092df94"
PROJECT = Path(__file__).resolve().parents[4]
TOOLS = PROJECT / "work/toolchains"
DEFAULT_NDK = Path("C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
ABIS = {
    "arm64-v8a": ("arm64", "", "aarch64-linux-android29", 183),
    "armeabi-v7a": ("arm", "7", "armv7a-linux-androideabi29", 40),
    "x86_64": ("amd64", "", "x86_64-linux-android29", 62),
}
TESTS = (
    "TestMcrfRawMTUCeilingPreservesSmallerServerValue",
    "TestMcrfIPRelayBuffersOnOwnedSocket",
    "TestMcrfSOCKSAdmissionQueuesAndResumes",
    "TestMcrfWdtt512ClientsAndOverflowBackpressure",
    "TestMcrfWdttRejectsPrivateConfigBeforeDNS",
    "TestMcrfSocksUdpSourcePinAndControlEOF",
)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(check, message):
    if not check:
        raise ValueError(message)


def load(path, expected=None):
    if expected:
        require(sha(path) == expected, f"Changed WDTT input: {path}")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def copy_files(source, destination, rows):
    for row in rows:
        name = PurePosixPath(row["path"].replace("\\", "/"))
        require(not name.is_absolute() and ".." not in name.parts, "Unsafe WDTT source path")
        origin, target = source / name, destination / name
        require(origin.is_file() and not origin.is_symlink() and sha(origin) == row["sha256"],
                f"Changed or missing WDTT source: {name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(origin, target)


def inventory(root):
    return [{"path": p.relative_to(root).as_posix(), "sha256": sha(p)}
            for p in sorted(root.rglob("*")) if p.is_file()]


def prepare(stage):
    require(stage.parent == BUILD_ROOT.resolve() and stage.name.startswith("wdtt-owner-"),
            "Use a new wdtt-owner-* directory under the Android build root")
    prior = load(BASE_PACKAGE / "receipt.json", BASE_PACKAGE_RECEIPT)
    require(prior["kind"] == "wdtt" and prior["packageVersionCode"] == 9, "Unexpected WDTT baseline")
    rows = load(BASE_SOURCE / "SOURCE_MANIFEST.json", BASE_MANIFEST)
    dependency_rows = load(BASE_SOURCE / "DEPENDENCY_MANIFEST.json", PATHPROBE_MANIFEST)
    stage.mkdir()
    module = stage / "module"
    copy_files(BASE_SOURCE / "module", module, rows)
    copy_files(BASE_SOURCE / "pathprobe", stage / "pathprobe", dependency_rows)
    proof = apply("wdtt", module)
    save(stage / "source-manifest.json", inventory(module))
    save(stage / "dependency-manifest.json", inventory(stage / "pathprobe"))
    save(stage / "prepared-wdtt-udp.json", {
        "sourceManifestSha256": sha(stage / "source-manifest.json"),
        "dependencyManifestSha256": sha(stage / "dependency-manifest.json"),
        "producerSha256": sha(Path(__file__)),
        "socksUdpSourceOverlay": proof,
    })
    print(f"WDTT_UDP_PREPARED={stage}")


def build(stage, ndk):
    proof = load(stage / "prepared-wdtt-udp.json")
    require(proof["producerSha256"] == sha(Path(__file__)) and
            proof["socksUdpSourceOverlay"]["overlaySha256"] == sha(Path(__file__).with_name("overlay.py")),
            "WDTT producer changed since preparation")
    module = stage / "module"
    manifest = load(stage / "source-manifest.json", proof["sourceManifestSha256"])
    dependencies = load(stage / "dependency-manifest.json", proof["dependencyManifestSha256"])
    require(inventory(module) == manifest and inventory(stage / "pathprobe") == dependencies,
            "WDTT prepared source changed")
    go = TOOLS / "go1.26.8/go/bin/go.exe"
    clang = ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    env = os.environ | {
        "GOOS": "windows", "GOARCH": "amd64", "GOARM": "", "CGO_ENABLED": "0",
        "GOROOT": "", "GOTOOLCHAIN": "local", "GOENV": "off", "GOWORK": "off",
        "GOFLAGS": "", "GOPROXY": "off", "GOPATH": str(TOOLS / "go-home"),
        "GOMODCACHE": str(TOOLS / "go-home/pkg/mod"), "GOCACHE": str(TOOLS / "go-build-cache"),
    }
    with (stage / "dependencies.log").open("w", encoding="utf-8") as log:
        subprocess.run([str(go), "mod", "verify"], cwd=module, env=env, stdout=log, stderr=subprocess.STDOUT, check=True)
    host_log = stage / "turn-performance-tests.jsonl"
    with host_log.open("w", encoding="utf-8") as log, (stage / "host-stderr.log").open("w", encoding="utf-8") as err:
        subprocess.run([str(go), "test", "-mod=readonly", "-ldflags=-checklinkname=0", "-count=1", "-json",
                        "-timeout", "180s", "-run", "^(" + "|".join(TESTS) + ")$", "."],
                       cwd=module, env=env, stdout=log, stderr=err, check=True)
    logs = [json.loads(line) for line in host_log.read_text(encoding="utf-8").splitlines() if line]
    passed = {row.get("Test") for row in logs if row.get("Action") == "pass"}
    require(set(TESTS) <= passed and all(row.get("Action") not in ("fail", "skip") for row in logs),
            "Fresh WDTT SOCKS/transport host tests missing")
    host_proof = stage / "turn-performance-host-evidence.json"
    save(host_proof, {"state": "PASS_P3_TURN_PERFORMANCE_HOST", "kind": "wdtt",
                      "sourceManifestSha256": proof["sourceManifestSha256"], "logSha256": sha(host_log),
                      "cases": sorted(case for case in passed if case), "failures": 0, "skipped": 0})
    artifacts = []
    for abi, (arch, arm, target, machine) in ABIS.items():
        android_env = env | {
            "GOOS": "android", "GOARCH": arch, "GOARM": arm, "CGO_ENABLED": "1",
            "PATH": str(clang) + os.pathsep + env["PATH"], "CC": "clang --target=" + target,
        }
        output = stage / abi / "libwdtt.so"
        output.parent.mkdir()
        with (stage / f"build-{abi}.log").open("w", encoding="utf-8") as log:
            subprocess.run([str(go), "build", "-mod=readonly", "-trimpath", "-buildmode=pie",
                            "-ldflags=-s -w -checklinkname=0 -linkmode=external -extldflags=-Wl,-z,max-page-size=16384",
                            "-o", str(output), "."], cwd=module, env=android_env,
                           stdout=log, stderr=subprocess.STDOUT, check=True)
        elf = output.read_bytes()
        require(elf.startswith(b"\x7fELF") and int.from_bytes(elf[18:20], "little") == machine
                and b"MCRF_SOCKS_UDP_SOURCE_V1_wdtt" in elf,
                f"WDTT native source contract missing for {abi}")
        artifacts.append({"abi": abi, "path": output.relative_to(stage).as_posix(),
                          "bytes": output.stat().st_size, "sha256": sha(output)})
    require(inventory(module) == manifest and inventory(stage / "pathprobe") == dependencies,
            "WDTT source changed during compilation")
    receipt = load(BASE_PACKAGE / "receipt.json", BASE_PACKAGE_RECEIPT)
    for row in receipt["turnFeatureInputs"]:
        name = row["file"]
        source = module / name
        output = stage / "turn-feature-inputs" / name
        output.parent.mkdir(exist_ok=True)
        shutil.copyfile(source, output)
        row["sha256"] = sha(output)
    for named, source, pin in (
        ("provider-options-input.go", "mcrf_wdtt.go", "providerOptionsInputSha256"),
        ("masque-main-input.go", "main.go", "masqueMainInputSha256"),
    ):
        require(sha(module / source) == receipt[pin], f"Unrelated WDTT feature source changed: {source}")
        shutil.copyfile(module / source, stage / named)
    shutil.copytree(BASE_PACKAGE / "licenses", stage / "licenses")
    receipt.update(state="COMPILED_NOT_EXECUTED", sourceStable=True, stage=stage.as_posix(), source=module.as_posix(),
                   sourceManifestSha256=proof["sourceManifestSha256"], hostEvidenceSha256=sha(host_proof),
                   nativeExecuted=False, apkBuilt=False, artifacts=artifacts,
                   socksUdpSource={"wdtt": 1}, socksUdpSourceOverlay=proof["socksUdpSourceOverlay"],
                   packageVersionCode=10, packageVersionName="0.1.0-performance.10",
                   hostTests=f"PASS {len(TESTS)} affected host tests")
    save(stage / "receipt.json", receipt)
    print(f"WDTT_UDP_NATIVE_STAGE={stage}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare", type=Path)
    mode.add_argument("--build", type=Path)
    parser.add_argument("--ndk", type=Path, default=DEFAULT_NDK)
    options = parser.parse_args()
    if options.prepare:
        prepare(options.prepare.resolve())
    else:
        build(options.build.resolve(), options.ndk.resolve())
