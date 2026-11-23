"""Build source-bound Android native kernel fixtures and regression ELF files.

The kernel fixture runs original adapter code against a synthetic carrier.
This command never installs APKs, invokes adb, or grants packaging acceptance.
"""
import argparse
import json
import pathlib
import shutil
import subprocess

import csqtt_multicall_build as producer
from bs_engine_inputs import load, require, sha
from native_routing_evidence import BUILD, GENERATIONS, HERE, source_matches
from prepare_csqtt_direct_tun import file_row

ABIS = {
    "arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android", 2, 183),
    "armeabi-v7a": ("armv7-linux-androideabi", "armv7a-linux-androideabi", 1, 40),
    "x86_64": ("x86_64-linux-android", "x86_64-linux-android", 2, 62),
}
BIN = "mcrf-owned-native-udp-fixture"
MANIFEST_SUFFIX = '\n[[bin]]\nname="mcrf-owned-native-udp-fixture"\npath="owned_native_udp_main.rs"\n'
ENTRY = """
fn main() {
    if std::env::var("MCRF_BS_RUST_MODE").as_deref() != Ok("OWNED_GENERATION") {
        eprintln!("OWNED_BOOTSTRAP_GENERATION_MISMATCH");
        std::process::exit(78);
    }
    let arguments = Arguments::parse_from(normalized_arguments());
    let admission = std::env::var_os("MCRF_BS_ROUTING_ADMIT_ONLY").is_some();
    if !admission && (arguments.peer != "192.0.2.1:9" ||
        std::env::var("CSQTT_PASSWORD").as_deref() != Ok("owned-fixture-only") ||
        std::env::var_os("MCRF_BS_ROUTING_SESSION_SHA256").is_none()) {
        eprintln!("OWNED_BOOTSTRAP_SYNTHETIC_PROFILE_REQUIRED");
        std::process::exit(78);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4).enable_all().build().expect("owned bootstrap runtime");
    let result = runtime.block_on(async {
        if mcrf_bs_bootstrap::admit_only()? { return Ok(()); }
        mcrf_direct_tun::configure(&arguments.tun_uds)?;
        mcrf_bs_network::configure(mcrf_direct_tun::active())?;
        mcrf_bs_bootstrap::configure()?;
        // Exercise the carrier contract without changing the synthetic local policy.
        mcrf_bs_server_routing::announce("").map_err(|_| anyhow::anyhow!("OWNED_BOOTSTRAP_ROUTING_ANNOUNCE_FAILED"))?;
        dispatcher::mcrf_owned_bootstrap_fixture(&arguments.tun_uds).await
    });
    runtime.shutdown_timeout(std::time::Duration::from_secs(1));
    if let Err(error) = result {
        eprintln!("OWNED_BOOTSTRAP_FAILED {error:#}");
        std::process::exit(1);
    }
}
"""


def verify_stage(stage):
    require(BUILD in stage.parents, "Use the frozen Android native producer stage")
    receipt = load(stage / "receipt.json")
    require(receipt["state"] == "COMPILED_MULTICALL_PROOF_NOT_PACKAGED" and receipt["sourceStable"] is True
            and receipt["nativeRoutingPrepared"] is True and receipt["packageReady"] is False,
            "Complete native compile stage required")
    require(receipt["nativeRoutingOverlays"] == producer.native_routing_inventory(),
            "Current native route inputs differ from the compile stage")
    rows = []
    for row in receipt["generated"]:
        name = row["path"]
        if name in ("Cargo.lock", "admission-tests/Cargo.lock"):
            continue
        path = producer.checked_file(stage, name)
        require(row["sha256"] == sha(path), "Native source changed: " + name)
        rows.append(row)
    require(receipt["workspaceLockSha256"] == sha(stage / "Cargo.lock"), "Resolved lock changed")
    return receipt, rows


def bootstrap_entry(source, generation):
    text = source.read_text(encoding="utf-8")
    require(generation in GENERATIONS and text.count("fn main() {") == 1, "Original carrier binary entry changed")
    return text.replace("fn main() {", "fn mcrf_unused_carrier_main() {", 1) + ENTRY.replace("OWNED_GENERATION", generation)


def elf(path, abi):
    with path.open("rb") as stream:
        header = stream.read(64)
    _, _, elf_class, machine = ABIS[abi]
    require(len(header) == 64 and header[:4] == b"\x7fELF" and header[4:7] == bytes((elf_class, 1, 1))
            and int.from_bytes(header[16:18], "little") in (2, 3)
            and int.from_bytes(header[18:20], "little") == machine, "Android ELF ABI mismatch")


def configure_abi(base, clang, abi):
    rust_target, clang_target, _, _ = ABIS[abi]
    suffix = rust_target.replace("-", "_")
    target_flag = "--target=" + clang_target + "26"
    env = base.copy()
    env.update({
        "CC_" + suffix: str(clang), "CXX_" + suffix: str(clang.with_name("clang++.exe")),
        "CFLAGS_" + suffix: target_flag, "CXXFLAGS_" + suffix: target_flag,
        "AR_" + suffix: str(clang.with_name("llvm-ar.exe")),
        "CARGO_TARGET_" + suffix.upper() + "_LINKER": str(clang),
        "CARGO_NET_OFFLINE": "true", "CARGO_PROFILE_DEV_DEBUG": "0", "CARGO_PROFILE_TEST_DEBUG": "0",
        "RUSTFLAGS": "-C link-arg=" + target_flag + " -C link-arg=-Wl,-z,max-page-size=16384",
    })
    return env


def compile_one(root, out, cargo, env, generation, abi, purpose):
    key = GENERATIONS[generation]
    package = "mcrf-adapter-" + key
    target, *_ = ABIS[abi]
    kind = ["build", "--bin", BIN] if purpose == "bootstrap" else ["test", "--lib", "--no-run"]
    command = [str(cargo), *kind, "--package", package, "--target", target, "--frozen", "--message-format=json"]
    provenance = out / (key + "-" + purpose + "-" + abi)
    provenance.mkdir()
    log = provenance / "cargo.jsonl"
    stderr = provenance / "cargo.stderr"
    with log.open("wb") as stdout, stderr.open("wb") as error:
        result = subprocess.run(command, cwd=root, env=env, stdout=stdout, stderr=error, timeout=900)
    require(result.returncode == 0, f"Native {generation} {abi} {purpose} compile failed: {stderr}")
    events = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines() if line]
    name = BIN if purpose == "bootstrap" else "mcrf_adapter_" + key
    matches = [event for event in events if event.get("reason") == "compiler-artifact" and
               event["target"]["name"] == name and event.get("executable") and
               event["profile"]["test"] == (purpose == "unit")]
    require(len(matches) == 1 and any(e.get("reason") == "build-finished" and e.get("success") is True for e in events),
            "Exact successful Cargo executable event required")
    binary = pathlib.Path(matches[0]["executable"]).resolve(strict=True)
    require(pathlib.Path(env["CARGO_TARGET_DIR"]) in binary.parents, "Cargo artifact outside controlled cache")
    output = provenance / ("libmcrf_owned_native_udp_fixture.so" if purpose == "bootstrap" else abi + ".test")
    shutil.copyfile(binary, output)
    elf(output, abi)
    return {"generation": generation, "purpose": purpose, "abi": abi,
            **file_row(output), "cargoLog": file_row(log), "cargoStderr": file_row(stderr), "command": command}


def build(args):
    stage = pathlib.Path(args.stage).resolve(strict=True)
    out = pathlib.Path(args.output).resolve()
    require(BUILD in out.parents and stage not in out.parents, "Fresh separate Android build output required")
    prepared, rows = verify_stage(stage)
    seed_sha = sha(stage / "receipt.json")
    out.mkdir()
    source = out / "source"
    source.mkdir()
    for row in rows:
        relative = row["path"]
        if relative.startswith("admission-tests/"):
            continue
        path = source / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(stage / relative, path)
    shutil.copyfile(stage / "Cargo.lock", source / "Cargo.lock")
    for generation, key in GENERATIONS.items():
        crate = source / "adapters" / key / "rust-client"
        dispatcher = crate / "dispatcher.rs"
        dispatcher.write_bytes(dispatcher.read_bytes().replace(b"\r\n", b"\n") + b"\n" +
            (HERE / "routing/native/bootstrap_kernel_dispatcher.rs").read_bytes().replace(b"\r\n", b"\n"))
        manifest = crate / "Cargo.toml"
        with manifest.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(MANIFEST_SUFFIX)
        (crate / "owned_native_udp_main.rs").write_text(bootstrap_entry(crate / "main.rs", generation),
                                                         encoding="utf-8", newline="\n")
    sources = producer.inventory(source)
    base = producer.environment(args, out)
    cargo = pathlib.Path(args.toolchain).resolve() / "bin/cargo.exe"
    clang = pathlib.Path(base["LIBCLANG_PATH"]) / "clang.exe"
    toolchain = {"cargo": file_row(cargo), "rustc": file_row(base["RUSTC"]), "ndkClang": file_row(clang)}
    require(all(toolchain[name]["sha256"] == prepared["toolchain"][digest]
                for name, digest in (("cargo", "cargoSha256"), ("rustc", "rustcSha256"), ("ndkClang", "ndkClangSha256"))),
            "Fixture compile must use the production stage toolchain")
    binaries = []
    for generation in GENERATIONS:
        for abi in ABIS:
            env = configure_abi(base, clang, abi)
            binaries.append(compile_one(source, out, cargo, env, generation, abi, "unit"))
            if abi == "arm64-v8a":
                binaries.append(compile_one(source, out, cargo, env, generation, abi, "bootstrap"))
    require(sources == producer.inventory(source) and
            all(file_row(row["path"]) == row for row in toolchain.values()) and
            verify_stage(stage) == (prepared, rows) and sha(stage / "receipt.json") == seed_sha, "Frozen build inputs drifted")
    receipt = {
        "schema": 1, "state": "NATIVE_PHYSICAL_FIXTURES_COMPILED_NOT_EXECUTED",
        "sourceStable": True, "providerTraffic": False, "productionActivated": False,
        "seedReceipt": file_row(stage / "receipt.json"), "producerSha256": sha(__file__),
        "toolchain": toolchain, "sources": sources, "binaries": binaries,
    }
    proof = source / "physical-compile-receipt.json"
    for generation in GENERATIONS:
        source_matches(stage, {"source": proof}, receipt, generation)
    producer.write_json(proof, receipt)
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--target-dir", required=True)
    parser.add_argument("--toolchain", default="D:/MCRF/Builds/Tools/rust-1.97.1-gnu/toolchain")
    parser.add_argument("--cargo-home", default="D:/MCRF/Projects/MCRF/work/toolchains/cargo-home")
    parser.add_argument("--cargo-ndk", default="D:/MCRF/Builds/Tools/cargo-ndk-4.1.2/bin")
    parser.add_argument("--mingw", default="D:/MCRF/Builds/Tools/llvm-mingw-20261020-ucrt-x86_64")
    parser.add_argument("--nasm", default="D:/MCRF/Builds/Tools/nasm-3.01")
    parser.add_argument("--ndk", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/ndk/27.2.12479018")
    parser.add_argument("--cmake", default="C:/Users/dieonfire/AppData/Local/Android/Sdk/cmake/3.22.1/bin")
    print(build(parser.parse_args()))


if __name__ == "__main__":
    main()
