"""Build the pinned, test-only owner QUIC peer and Android client."""

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
from pathlib import Path


HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[2]
TOOLS = PROJECT / "work/toolchains"
BUILD_ROOT = Path("D:/MCRF/Builds/Android").resolve()
FILES = ("bind_android.go", "bind_other.go", "go.mod", "go.sum", "main.go", "main_test.go", "build.py")
ABIS = {
    "arm64-v8a": ("arm64", "", "aarch64-linux-android29", 2, 183),
    "armeabi-v7a": ("arm", "7", "armv7a-linux-androideabi29", 1, 40),
    "x86_64": ("amd64", "", "x86_64-linux-android29", 2, 62),
}
LIBRARY = "libmcrf_owned_quic_fixture.so"
PIN = "github.com/sagernet/quic-go v0.61.0-sing-box-mod.9"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def inventory(root):
    require(all((root / name).is_file() and not (root / name).is_symlink() for name in FILES),
            "Exact regular QUIC inputs required")
    return [{"file": name, "sha256": sha(root / name)} for name in FILES]


def elf(path, abi):
    _, _, _, elf_class, machine = ABIS[abi]
    data = path.read_bytes()
    require(data[:7] == bytes((127, 69, 76, 70, elf_class, 1, 1)) and
            struct.unpack_from("<HH", data, 16) == (3, machine) and b"mcrf-owner-quic-v1" in data and
            b"\x00android_setsocknetwork\x00" in data,
            "Wrong Android QUIC PIE or ABI")


def run(args):
    stage = args.output.resolve()
    require(stage.parent == BUILD_ROOT and re.fullmatch(r"owner-quic-[A-Za-z0-9.-]+", stage.name)
            and not stage.exists(), "Fresh owned QUIC stage required")
    require(PIN in (HERE / "go.mod").read_text(encoding="utf-8"), "Pinned QUIC module required")
    go = TOOLS / "go1.26.8/go/bin/go.exe"
    clang = args.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
    require(go.is_file() and clang.is_file(), "Existing pinned Go and Android NDK required")
    sources = inventory(HERE)
    stage.mkdir()
    source = stage / "source"
    source.mkdir()
    for name in FILES:
        shutil.copyfile(HERE / name, source / name)
    require(inventory(source) == sources, "Source copy differs")

    temporary = stage / "tmp"
    temporary.mkdir()
    home = TOOLS / "go-home"
    environment = os.environ | {
        "GOOS": "windows", "GOARCH": "amd64", "CGO_ENABLED": "0",
        "GOTOOLCHAIN": "local", "GOENV": "off", "GOWORK": "off", "GOFLAGS": "-p=2",
        "GOPROXY": "off", "GOPATH": str(home), "GOMODCACHE": str(home / "pkg/mod"),
        "GOCACHE": str(TOOLS / "go-build-cache"), "GOTMPDIR": str(temporary),
        "TMP": str(temporary), "TEMP": str(temporary),
    }
    for inherited in ("GOROOT", "GOARM", "CC", "CXX"):
        environment.pop(inherited, None)

    def invoke(log, *command, env=environment):
        with (stage / log).open("xb") as stdout:
            subprocess.run([str(go), *command], cwd=source, env=env, stdout=stdout, stderr=subprocess.STDOUT,
                           check=True, timeout=600)

    try:
        invoke("go-version.log", "version")
        require((stage / "go-version.log").read_text().strip() == "go version go1.26.8 windows/amd64",
                "Pinned Go compiler required")
        invoke("dependencies.log", "mod", "verify")
        invoke("host.jsonl", "test", "-mod=readonly", "-count=1", "-json", "-timeout=30s", ".")
        events = [json.loads(line) for line in (stage / "host.jsonl").read_text(encoding="utf-8").splitlines()]
        cases = ("TestOwnedQUICPinnedEcho", "TestOwnedQUICFixedLanesAndBindingFailure",
                 "TestOwnedQUICConnectedUDPRejectsWrongPeer")
        require(not any(e.get("Action") in ("fail", "skip") for e in events) and
                all(sum(e.get("Action") == "pass" and e.get("Test") == case for e in events) == 1 for case in cases),
                "Actual local pinned QUIC handshake and echo required")
        host = stage / "WireQuicPeer.exe"
        invoke("windows.log", "build", "-mod=readonly", "-trimpath", "-ldflags=-s -w", "-o", str(host), ".")
        artifacts = []
        for abi, (goarch, goarm, target, _, _) in ABIS.items():
            folder = stage / "jniLibs" / abi
            folder.mkdir(parents=True)
            binary = folder / LIBRARY
            android = environment | {"GOOS": "android", "GOARCH": goarch, "CGO_ENABLED": "1",
                                     "CC": clang.as_posix() + " --target=" + target}
            if goarm:
                android["GOARM"] = goarm
            invoke(f"{abi}.log", "build", "-mod=readonly", "-trimpath", "-buildmode=pie",
                   "-ldflags=-s -w -linkmode=external -extldflags=-Wl,-z,max-page-size=16384",
                   "-o", str(binary), ".", env=android)
            elf(binary, abi)
            artifacts.append({"abi": abi, "file": binary.relative_to(stage).as_posix(),
                              "bytes": binary.stat().st_size, "sha256": sha(binary)})
        require(inventory(source) == inventory(HERE) == sources, "Source changed during fixture build")
        receipt = {"schema": 1, "state": "OWNER_QUIC_HOST_PASS_ANDROID_PIE_READY", "testOnly": True,
                   "dependency": PIN, "source": sources, "artifacts": artifacts,
                   "compilerSha256": sha(go), "clangSha256": sha(clang),
                   "hostTestSha256": sha(stage / "host.jsonl"),
                   "host": {"file": host.name, "bytes": host.stat().st_size, "sha256": sha(host)}}
        result = stage / "receipt.json"
        result.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"stage": str(stage), "receiptSha256": sha(result)}))
    except Exception:
        (stage / "failed.txt").write_text("Fixture producer failed. Inspect bounded source and build logs.\n",
                                          encoding="utf-8")
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--ndk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/ndk/27.2.12479018")
    run(parser.parse_args())
