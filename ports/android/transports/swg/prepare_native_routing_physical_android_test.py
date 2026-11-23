"""Stage one frozen synthetic ARM64 ELF and SRS for the Android test APK."""
import argparse
import json
import pathlib
import shutil

from bs_engine_inputs import require, sha
from native_routing_evidence import BUILD, GENERATIONS
from native_routing_physical_evidence import SRS_SHA256, compiled


def prepare(args):
    output = pathlib.Path(args.output).resolve()
    require(BUILD in output.parents, "Keep synthetic Android test fixtures in controlled build storage")
    if args.direct_owner:
        from build_physical_direct_owner_capture import verify
        native = verify(args.compilation, args.stage)[args.generation]
    else:
        native = compiled(args.compilation, args.stage)[args.generation, "arm64-v8a", "bootstrap"]
    rules = pathlib.Path(args.rules).resolve(strict=True)
    require(sha(rules) == SRS_SHA256, "Exact synthetic SRS fixture required")
    output.mkdir()
    jni = output / "fixtures/arm64-v8a"
    jni.mkdir(parents=True)
    assets = output / "test-assets"
    assets.mkdir()
    shutil.copyfile(native, jni / "libmcrf_owned_native_udp_fixture.so")
    shutil.copyfile(rules, assets / "bs-owned-bootstrap-routes.srs")
    metadata = assets / "bs-owned-native-udp-fixture.json"
    data = {"abi": "arm64-v8a", "generation": args.generation, "bytes": native.stat().st_size,
            "sha256": sha(native), "bootstrap": True, "srsSha256": SRS_SHA256}
    if args.direct_owner:
        data["directOwnerCapture"] = True
    metadata.write_text(json.dumps(data) + "\n", encoding="utf-8")
    receipt = {**data, "stage": "debug-only-bs-physical-native-bootstrap", "metadataSha256": sha(metadata),
               "compilationReceiptSha256": sha(args.compilation), "providerTraffic": False, "productionActivated": False}
    (output / "receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for key in ("stage", "compilation", "rules", "output"):
        parser.add_argument("--" + key, required=True)
    parser.add_argument("--generation", choices=GENERATIONS, required=True)
    parser.add_argument("--direct-owner", action="store_true")
    print(prepare(parser.parse_args()))


if __name__ == "__main__":
    main()
