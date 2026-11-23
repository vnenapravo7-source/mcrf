"""Build the dual-ABI Steer TUN owner module from the retained P3 closure."""

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[3]
STEER = REPOSITORY / "work/steer-android-port"
BUILD_ROOT = Path("D:/MCRF/Builds/Android")
BASE_SOURCE = BUILD_ROOT / "p3-steer-source-d470cf102b9a4d1eae0bc0cc4f35c467"
BASE_MANIFEST_SHA = "097d5dd3df3c25108cc955de83c654f574d0bdf69bf6a90ea63f545665fc091d"
BASE_NOTICES = BUILD_ROOT / "p3-steer-native-c04cdfc8e5574728a9b53cf133d0b1b4/primary-stage/steer/licenses"
CODECS = BUILD_ROOT / "p3-steer-codec-inputs-4fdfead0c2564b54b1ee1ba0493c920c"
ABIS = {"arm64-v8a": 183, "x86_64": 62}
NATIVE_SOURCES = (
    "src/tunnel/stack.c",
    "src/tunnel/stack.h",
    "src/tunnel/tun_owner.h",
    "android-vpn/core/src/main/cpp/bridge.c",
    "android-vpn/tests/host_tun_owner_test.c",
)
CORE_JAVA = "android-vpn/core/src/main/java/org/steer/core/SteerCore.java"
DNS_JAVA = "android-vpn/core/src/main/java/org/steer/core/TunnelDns.java"
ADAPTER_JAVA = "android-vpn/module-apk/src/main/java/org/mcrf/engines/steer/SteerEngine.java"
CLASS_PREFIXES = {
    "runtime": ("org/steer/core/SteerCore", "org/steer/core/TunnelDns"),
    "adapter": ("org/mcrf/engines/steer/SteerEngine",),
}
SPI_DIR = "android-vpn/module-contract/src/main/java/org/mcrf/engine/spi/v1"
SDK = Path("C:/Users/dieonfire/AppData/Local/Android/Sdk")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def checked_bytes(path, row):
    require(path.is_file() and not path.is_symlink(), f"Missing regular input: {path}")
    data = path.read_bytes()
    require(len(data) == row["bytes"] and digest(data) == row["sha256"], f"Changed input: {path}")
    return data


def write_bytes(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return {"bytes": len(data), "sha256": digest(data)}


def zip_entries(data):
    with ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate archive entry")
        return {name: archive.read(name) for name in names}


def zip_bytes(entries):
    output = io.BytesIO()
    with ZipFile(output, "w", ZIP_DEFLATED) as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return output.getvalue()


def archives_for_stage(stage, descriptor):
    directory = stage / "steer"
    artifacts = {row["file"]: row for row in descriptor["artifacts"]}
    return {
        kind: zip_entries(checked_bytes(directory / f"{kind}.aar", artifacts[f"{kind}.aar"]))
        for kind in CLASS_PREFIXES
    }


def owned_class(name, prefixes):
    return any(name == prefix + ".class" or (name.startswith(prefix + "$") and name.endswith(".class"))
               for prefix in prefixes)


def verify(stage):
    descriptor = read_json(stage / "receipt.json")["modules"]["steer"]
    require(
        descriptor["versionCode"] == 18 and descriptor["vpnOwnerIsolation"] == 1
        and descriptor["privateHelperProfile"] == 1 and descriptor["socksUdpSourcePort"] == 1
        and descriptor["abis"] == list(ABIS),
        "Invalid Steer owner descriptor",
    )
    directory = stage / "steer"
    source = (directory / "source-manifest.json").read_bytes()
    require(digest(source) == descriptor["sourceSha256"], "Changed Steer owner source manifest")
    proof_bytes = (directory / "owner-proof.json").read_bytes()
    require(digest(proof_bytes) == descriptor["ownerProofSha256"], "Changed Steer owner package proof")
    proof = json.loads(proof_bytes)
    require(
        proof["sourceSha256"] == descriptor["sourceSha256"] and proof["baseManifestSha256"] == BASE_MANIFEST_SHA
        and proof["producerSha256"] == digest(Path(__file__).read_bytes()),
        "Invalid producer or source binding",
    )
    archives = archives_for_stage(stage, descriptor)
    baseline = read_json(BASE_SOURCE / "retained/descriptor.json")
    current_baseline = {row["file"]: row for row in baseline["artifacts"]}
    for kind, prefixes in CLASS_PREFIXES.items():
        prior = zip_entries(checked_bytes(BASE_SOURCE / f"retained/{kind}.aar", current_baseline[f"{kind}.aar"]))
        archive = archives[kind]
        native_names = {f"jni/{abi}/libsteer_vpn.so" for abi in ABIS} if kind == "runtime" else set()
        changed_entries = {"classes.jar", *native_names}
        if kind == "runtime":
            changed_entries.add("proguard.txt")
            require(
                archive["proguard.txt"] == prior["proguard.txt"] + OWNER_PROGUARD,
                "Changed Steer native callback keep rules",
            )
        require(prior.keys() == archive.keys(), f"Changed {kind} archive inventory")
        require(
            all(data == archive[name] for name, data in prior.items() if name not in changed_entries),
            f"Unrelated {kind} payload changed",
        )
        old_classes = zip_entries(prior["classes.jar"])
        classes = zip_entries(archive["classes.jar"])
        require(
            {k: v for k, v in old_classes.items() if not owned_class(k, prefixes)}
            == {k: v for k, v in classes.items() if not owned_class(k, prefixes)},
            f"Unrelated {kind} classes changed",
        )
        require(all(prefix + ".class" in classes for prefix in prefixes), f"Steer {kind} class missing")
        require(digest(archive["classes.jar"]) == proof["classes"][kind], f"Changed {kind} classes")
    runtime = archives["runtime"]
    require("org/steer/core/SteerCore$TunOwner.class" in zip_entries(runtime["classes.jar"]), "Owner callback type missing")
    for abi, machine in ABIS.items():
        native = runtime[f"jni/{abi}/libsteer_vpn.so"]
        require(
            native.startswith(b"\x7fELF\x02\x01") and int.from_bytes(native[18:20], "little") == machine
            and digest(native) == proof["native"][abi]
            and b"Java_org_steer_core_SteerCore_runOwnerIsolatedNative" in native
            and b"Java_org_steer_core_SteerCore_nativeTunOwnerSchema" in native,
            f"Missing owner-isolating Steer ELF for {abi}",
        )
    require(descriptor["bundledDependencies"] == baseline["bundledDependencies"], "Changed fat Steer dependency pins")
    for row in descriptor["bundledDependencies"]:
        checked_bytes(directory / row["file"], row)


OWNER_PROGUARD = (
    b"\n-keep interface org.steer.core.SteerCore$TunOwner { *; }\n"
    b"-keepclassmembers class * implements org.steer.core.SteerCore$TunOwner { public int connectionUid(byte[]); }\n"
)
OWNER_FIXTURE_CMAKE = """
add_executable(host_tun_owner_test EXCLUDE_FROM_ALL
    "${STEER}/android-vpn/tests/host_tun_owner_test.c" "${STEER}/src/tunnel/tun.c" ip6_packet.c)
target_include_directories(host_tun_owner_test PRIVATE ${INCLUDES} "${CMAKE_CURRENT_LIST_DIR}")
target_compile_definitions(host_tun_owner_test PRIVATE STEER_DUALSTACK)
target_compile_options(host_tun_owner_test PRIVATE -UNDEBUG -O2 -ffunction-sections -fdata-sections)
target_link_options(host_tun_owner_test PRIVATE -Wl,--gc-sections)
"""


def compile_java(javac, android, source, scratch, baseline_archives):
    jars = {}
    class_dir = scratch / "java"
    class_dir.mkdir()
    for kind in CLASS_PREFIXES:
        jars[kind] = scratch / f"{kind}.jar"
        jars[kind].write_bytes(baseline_archives[kind]["classes.jar"])
    spi_sources = sorted((source / SPI_DIR).glob("*.java"))
    require(spi_sources, "Steer module SPI sources missing")
    command = [str(javac), "--release", "17", "-encoding", "UTF-8", "-classpath",
               os.pathsep.join(map(str, [android, *jars.values()])), "-d", str(class_dir)]
    java_sources = [*spi_sources, *(source / name for name in (CORE_JAVA, DNS_JAVA, ADAPTER_JAVA))]
    subprocess.run([*command, *map(str, java_sources)], check=True)
    for kind, prefixes in CLASS_PREFIXES.items():
        classes = {k: v for k, v in zip_entries(jars[kind].read_bytes()).items() if not owned_class(k, prefixes)}
        for prefix in prefixes:
            for path in sorted((class_dir / prefix).parent.glob(Path(prefix).name + "*.class")):
                name = path.relative_to(class_dir).as_posix()
                if owned_class(name, (prefix,)):
                    classes[name] = path.read_bytes()
        require(all(prefix + ".class" in classes for prefix in prefixes), f"Compilation omitted {kind} entry")
        baseline_archives[kind]["classes.jar"] = zip_bytes(classes)


def build(args):
    stage = args.build.resolve()
    require(stage.parent == BUILD_ROOT.resolve() and stage.name.startswith("steer-owner-"),
            "Use a new steer-owner-* directory under the Android build root")
    manifest = BASE_SOURCE / "SOURCE_MANIFEST.json"
    require(digest(manifest.read_bytes()) == BASE_MANIFEST_SHA, "Unexpected retained source closure")
    descriptor = read_json(BASE_SOURCE / "retained/descriptor.json")
    require(descriptor["versionCode"] == 16, "Unexpected baseline Steer AAR")
    stage.mkdir()
    package = stage / "primary-stage"
    directory = package / "steer"
    directory.mkdir(parents=True)
    output = []
    with tempfile.TemporaryDirectory(prefix="so18-", dir=BUILD_ROOT) as scratch_name:
        scratch = Path(scratch_name)
        source = scratch / "source"
        changed = {f"source/{name}": (STEER / name).read_bytes() for name in NATIVE_SOURCES}
        for name in (CORE_JAVA, DNS_JAVA, ADAPTER_JAVA):
            changed[f"source/{name}"] = (STEER / name).read_bytes()
        for path in sorted((STEER / SPI_DIR).glob("*.java")):
            changed[f"source/{path.relative_to(STEER).as_posix()}"] = path.read_bytes()
        for row in read_json(manifest):
            name = row["path"]
            require(not PurePosixPath(name).is_absolute() and ".." not in PurePosixPath(name).parts, "Unsafe retained input path")
            original = BASE_SOURCE / name
            if name == "inputs/v5.9.4-stable.tar.gz" and not original.is_file():
                original = CODECS / original.name
            data = checked_bytes(original, row)
            if name == "tls.json":
                tls = json.loads(data)
                for abi in ABIS:
                    tls["abis"][abi]["path"] = (scratch / f"tls/{abi}/libmcrf_utls49.so").as_posix()
                data = json.dumps(tls).encode()
            if name == "source/android-vpn/core/src/main/cpp/CMakeLists.txt":
                data += OWNER_FIXTURE_CMAKE.encode()
            data = changed.pop(name, data)
            output.append({"path": name, **write_bytes(scratch / name, data)})
        for name, data in changed.items():
            output.append({"path": name, **write_bytes(scratch / name, data)})
        source_manifest = directory / "source-manifest.json"
        write_json(source_manifest, sorted(output, key=lambda row: row["path"]))
        archives = {
            kind: zip_entries((scratch / f"retained/{kind}.aar").read_bytes()) for kind in CLASS_PREFIXES
        }
        compile_java(args.javac, args.android_jar, source, scratch, archives)
        archives["runtime"]["proguard.txt"] += OWNER_PROGUARD
        native = {}
        for abi, machine in ABIS.items():
            binary = scratch / abi
            configure = [
                args.cmake, "-S", source / "android-vpn/core/src/main/cpp", "-B", binary, "-G", "Ninja",
                f"-DCMAKE_MAKE_PROGRAM={args.ninja.as_posix()}",
                f"-DCMAKE_TOOLCHAIN_FILE={(args.ndk / 'build/cmake/android.toolchain.cmake').as_posix()}",
                f"-DANDROID_NDK={args.ndk.as_posix()}", f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-29",
                "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_OBJECT_PATH_MAX=180",
                f"-DMCRF_TLS49_MANIFEST={(scratch / 'tls.json').as_posix()}",
                f"-DMCRF_CODEC49_ARCHIVES={(scratch / 'inputs').as_posix()}",
                "-DFETCHCONTENT_UPDATES_DISCONNECTED=ON",
            ]
            with (stage / f"configure-{abi}.log").open("w", encoding="utf-8") as log:
                subprocess.run(list(map(str, configure)), stdout=log, stderr=subprocess.STDOUT, check=True)
            with (stage / f"build-{abi}.log").open("w", encoding="utf-8") as log:
                subprocess.run([str(args.cmake), "--build", str(binary), "--target", "steer_vpn",
                                "host_tun_owner_test", "--parallel", "4"], stdout=log, stderr=subprocess.STDOUT, check=True)
            data = (binary / "libsteer_vpn.so").read_bytes()
            require(data.startswith(b"\x7fELF\x02\x01") and int.from_bytes(data[18:20], "little") == machine,
                    f"Incorrect Steer ELF for {abi}")
            archives["runtime"][f"jni/{abi}/libsteer_vpn.so"] = data
            native[abi] = digest(data)
            if abi == "x86_64":
                write_bytes(stage / "fixtures/host_tun_owner_test", (binary / "host_tun_owner_test").read_bytes())
        for row in output:
            checked_bytes(scratch / row["path"], row)
        descriptor["artifacts"] = [
            {"file": f"{kind}.aar", **write_bytes(directory / f"{kind}.aar", zip_bytes(archive))}
            for kind, archive in archives.items()
        ]
        for row in descriptor["bundledDependencies"]:
            write_bytes(directory / row["file"], checked_bytes(scratch / "retained" / row["file"], row))
        for row in descriptor["licenses"]:
            write_bytes(directory / "licenses" / row["file"], checked_bytes(BASE_NOTICES / row["file"], row))
        for key in ("p1Candidate", "p2Candidate", "p3SocksCandidate", "p3SocksProofSha256"):
            descriptor.pop(key, None)
        descriptor.update(
            versionCode=18, moduleVersion="0.3.3-mcrf.18", sourceVersion="0.3.3-mcrf-tun-owner.18",
            sourceSha256=digest(source_manifest.read_bytes()), vpnOwnerIsolation=1,
        )
        proof = directory / "owner-proof.json"
        write_json(proof, dict(
            sourceSha256=descriptor["sourceSha256"], baseManifestSha256=BASE_MANIFEST_SHA,
            producerSha256=digest(Path(__file__).read_bytes()), native=native,
            classes={kind: digest(archive["classes.jar"]) for kind, archive in archives.items()},
        ))
        descriptor["ownerProofSha256"] = digest(proof.read_bytes())
        write_json(package / "receipt.json", {"version": 1, "modules": {"steer": descriptor}})
        verify(package)
    print(f"STEER_OWNER_PRIMARY_STAGE={package}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--build", type=Path)
    mode.add_argument("--verify", type=Path)
    parser.add_argument("--ndk", type=Path, default=SDK / "ndk/27.2.12479018")
    parser.add_argument("--cmake", type=Path, default=SDK / "cmake/3.22.1/bin/cmake.exe")
    parser.add_argument("--ninja", type=Path, default=SDK / "cmake/3.22.1/bin/ninja.exe")
    parser.add_argument("--android-jar", type=Path, default=SDK / "platforms/android-34/android.jar")
    parser.add_argument("--javac", type=Path, default=Path("C:/Program Files/Java/jdk-17/bin/javac.exe"))
    args = parser.parse_args()
    if args.verify:
        verify(args.verify)
    else:
        build(args)


if __name__ == "__main__":
    main()
