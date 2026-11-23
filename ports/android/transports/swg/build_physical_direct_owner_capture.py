"""Compile the separate Direct owner-only fixture against a frozen native stage."""
import argparse
import pathlib
import shutil

import csqtt_multicall_build as producer
from bs_engine_inputs import bound, load, require, sha
from native_routing_evidence import BUILD, GENERATIONS
from native_routing_physical_build import MANIFEST_SUFFIX, compile_one, configure_abi, verify_stage
from prepare_csqtt_direct_tun import checked_row, file_row

CAPTURE = pathlib.Path(__file__).with_name("physical_direct_owner_capture.rs")
ENTRY = """
fn main() {
    if std::env::var("MCRF_BS_RUST_MODE").as_deref() != Ok("OWNED_GENERATION") ||
       std::env::var("MCRF_BS_OWNER_ISOLATION").as_deref() != Ok("1") {
        eprintln!("OWNED_DIRECT_SCOPE_REQUIRED");
        std::process::exit(78);
    }
    let arguments = Arguments::parse_from(normalized_arguments());
    if arguments.peer != "192.0.2.1:9" ||
        std::env::var("CSQTT_PASSWORD").as_deref() != Ok("owned-fixture-only") {
        eprintln!("OWNED_DIRECT_PROFILE_REQUIRED");
        std::process::exit(78);
    }
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(4).enable_all().build().expect("owned Direct fixture runtime");
    let result = runtime.block_on(async {
        mcrf_direct_tun::configure(&arguments.tun_uds)?;
        mcrf_bs_network::configure(mcrf_direct_tun::active())?;
        mcrf_bs_routing::configure()?;
        dispatcher::mcrf_owned_direct_owner_fixture(&arguments.tun_uds).await
    });
    runtime.shutdown_timeout(std::time::Duration::from_secs(1));
    if let Err(error) = result {
        eprintln!("OWNED_DIRECT_FAILED {error:#}");
        std::process::exit(1);
    }
}
"""


def replace_once(text, old, new):
    require(text.count(old) == 1, "Frozen Direct fixture seam changed")
    return text.replace(old, new, 1)


def overlay_dispatcher(original):
    text = original.read_text(encoding="utf-8")
    without_carrier = "if self.workers.load().is_empty() && self.mcrf_native_runtime.get().is_none()"
    text = replace_once(text, without_carrier, "owned_direct_observe_ingress(&packet).await;\n"
                        "                        " + without_carrier + " && OWNED_DIRECT_CAPTURE.get().is_none()")
    leaf = "pub async fn mcrf_native_tunnel(&self, packet: PacketBuf) {"
    text = replace_once(text, leaf, leaf + """
        if OWNED_DIRECT_CAPTURE.get().is_some() {
            self.owned_direct_capture_egress(&packet);
            return;
        }""")
    return text + "\n" + CAPTURE.read_text(encoding="utf-8")


def overlay_entry(original, generation):
    text = original.read_text(encoding="utf-8")
    return replace_once(text, "fn main() {", "fn mcrf_unused_carrier_main() {") + ENTRY.replace("OWNED_GENERATION", generation)


def verify(compilation, stage):
    stage = pathlib.Path(stage).resolve(strict=True)
    compilation = pathlib.Path(compilation).resolve(strict=True)
    production, source_rows = verify_stage(stage)
    proof = load(compilation)
    require(proof["state"] == "DIRECT_OWNER_CAPTURE_COMPILED_NOT_EXECUTED" and proof["sourceStable"] is True and
            proof["providerTraffic"] is False and proof["productionActivated"] is False and
            proof["producerSha256"] == sha(__file__) and proof["captureOverlaySha256"] == sha(CAPTURE) and
            checked_row(proof["seedReceipt"]) == stage / "receipt.json", "Direct fixture provenance changed")
    root = compilation.parent
    overlays = {}
    for gen, key in GENERATIONS.items():
        prefix = f"adapters/{key}/rust-client/"
        overlays.update({
            prefix + "dispatcher.rs": overlay_dispatcher(stage / (prefix + "dispatcher.rs")),
            prefix + "Cargo.toml": (stage / (prefix + "Cargo.toml")).read_text(encoding="utf-8") + MANIFEST_SUFFIX,
            prefix + "owned_native_udp_main.rs": overlay_entry(stage / (prefix + "main.rs"), gen),
        })
    sources = {row["path"]: row for row in proof["sources"]}
    pure = {row["path"]: row["sha256"] for row in source_rows if not row["path"].startswith("admission-tests/")}
    pure["Cargo.lock"] = production["workspaceLockSha256"]
    require(set(sources) == set(pure) | set(overlays) and len(sources) == len(proof["sources"]),
            "Direct fixture source inventory changed")
    for name, row in sources.items():
        path = bound(row, root)
        if name in overlays:
            require(path.read_text(encoding="utf-8") == overlays[name], "Direct fixture changed original owner ingress")
        else:
            require(row["sha256"] == pure[name], "Untouched production Direct input changed")
    result = {}
    for binary in proof["binaries"]:
        generation = binary["generation"]
        require(generation in GENERATIONS and generation not in result and binary["abi"] == "arm64-v8a" and
                binary["purpose"] == "bootstrap", "Unexpected Direct fixture ELF record")
        result[generation] = checked_row({key: binary[key] for key in ("path", "bytes", "sha256")})
        checked_row(binary["cargoLog"])
        checked_row(binary["cargoStderr"])
    require(set(result) == set(GENERATIONS), "Both Direct fixture generations required")
    return result


def build(args):
    stage = pathlib.Path(args.stage).resolve(strict=True)
    out = pathlib.Path(args.output).resolve()
    require(BUILD in out.parents and stage not in out.parents, "Fresh separate Direct fixture output required")
    production, rows = verify_stage(stage)
    seed = file_row(stage / "receipt.json")
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
    for gen, key in GENERATIONS.items():
        crate = source / "adapters" / key / "rust-client"
        dispatcher = crate / "dispatcher.rs"
        dispatcher.write_text(overlay_dispatcher(dispatcher), encoding="utf-8", newline="\n")
        (crate / "owned_native_udp_main.rs").write_text(overlay_entry(crate / "main.rs", gen),
                                                         encoding="utf-8", newline="\n")
        with (crate / "Cargo.toml").open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(MANIFEST_SUFFIX)
    sources = producer.inventory(source)
    base = producer.environment(args, out)
    cargo = pathlib.Path(args.toolchain).resolve() / "bin/cargo.exe"
    clang = pathlib.Path(base["LIBCLANG_PATH"]) / "clang.exe"
    require(sha(cargo) == production["toolchain"]["cargoSha256"] and sha(clang) == production["toolchain"]["ndkClangSha256"]
            and sha(base["RUSTC"]) == production["toolchain"]["rustcSha256"], "Production Direct tool identity changed")
    env = configure_abi(base, clang, "arm64-v8a")
    binaries = [compile_one(source, out, cargo, env, gen, "arm64-v8a", "bootstrap") for gen in GENERATIONS]
    require(producer.inventory(source) == sources and file_row(stage / "receipt.json") == seed,
            "Frozen Direct fixture changed during compilation")
    proof = source / "direct-owner-compile-receipt.json"
    producer.write_json(proof, {
        "state": "DIRECT_OWNER_CAPTURE_COMPILED_NOT_EXECUTED", "sourceStable": True, "providerTraffic": False,
        "productionActivated": False, "captureOverlaySha256": sha(CAPTURE), "seedReceipt": seed,
        "producerSha256": sha(__file__), "sources": sources, "binaries": binaries,
    })
    verify(proof, stage)
    return proof


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("stage", "output", "target-dir"):
        parser.add_argument("--" + key, required=True)
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
