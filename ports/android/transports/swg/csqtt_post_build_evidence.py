"""Attach separately executed native evidence without changing the compiled receipt."""
import argparse
import json
import pathlib

from bs_engine_inputs import bound, load, require, sha
from native_routing_evidence import BUILD, frozen, verify as verify_routing
from prepare_csqtt_direct_tun import verify_unix_proof

STATE = "CSQTT_POST_BUILD_EVIDENCE"


def row(path):
    path = pathlib.Path(path).absolute()
    return {"path": str(path), "sha256": sha(path)}


def verify(evidence_row, compiled_row):
    evidence_path = frozen(evidence_row)
    evidence = load(evidence_path)
    require(type(evidence["schema"]) is int and evidence["schema"] == 1 and evidence["state"] == STATE and
            set(evidence) == {"schema", "state", "compiledReceipt", "directTunUnix", "nativeRouting"},
            "Invalid post-build evidence document")
    compiled_path = frozen(evidence["compiledReceipt"])
    require(compiled_path == bound(compiled_row).resolve() and evidence["compiledReceipt"]["sha256"] == compiled_row["sha256"],
            "Post-build evidence belongs to a different compiled receipt")
    compiled = load(compiled_path)
    require(compiled.get("sourceStable") is True and compiled.get("packageReady") is False and
            compiled.get("state") == "COMPILED_MULTICALL_PROOF_NOT_PACKAGED" and
            compiled.get("nativeRoutingPrepared") is True and compiled.get("nativeRoutingContract") == 0,
            "Post-build evidence requires exact unpublished native routing candidate")
    require(len(compiled["artifacts"]) == 3 and
            {item["abi"] for item in compiled["artifacts"]} == {"arm64-v8a", "armeabi-v7a", "x86_64"},
            "All three compiled ABI artifacts are required")
    for artifact in compiled["artifacts"]:
        native = frozen({"path": artifact["path"], "sha256": artifact["sha256"]})
        require(native == compiled_path.parent / "artifacts" / artifact["abi"] / "libcsqtt_transport.so" and
                native.stat().st_size == artifact["bytes"], "Post-build scope references another native artifact")
    unix = verify_unix_proof(frozen(evidence["directTunUnix"]))
    routing = None
    if evidence["nativeRouting"] is not None:
        routing_path = frozen(evidence["nativeRouting"])
        routing_doc = load(routing_path)
        if routing_doc.get("scope") == "owned-physical-arm64":
            from native_routing_physical_evidence import verify as verify_physical
            routing = verify_physical(routing_path, compiled_path.parent)
        else:
            routing = verify_routing(routing_path, compiled_path.parent)
        require(routing.get("state") == "OWNED_NATIVE_ROUTING_ACCEPTED" and
                routing.get("providerTraffic") is False and routing.get("performanceAccepted") is False,
                "Full post-build native routing acceptance required")
    return {"directTunUnixTests": unix, "nativeRoutingUnixTests": routing,
            "path": str(evidence_path), "sha256": sha(evidence_path)}


def create(compiled, direct_unix, routing, output):
    compiled_row = row(compiled)
    output = pathlib.Path(output).absolute()
    require(BUILD in output.resolve().parents and output.parent.resolve() == output.parent,
            "Post-build evidence must stay in Android build storage")
    pending = output.with_name(output.name + ".pending")
    require(not output.exists() and not pending.exists(), "Fresh post-build evidence path required")
    data = {"schema": 1, "state": STATE, "compiledReceipt": compiled_row,
            "directTunUnix": row(direct_unix), "nativeRouting": row(routing) if routing else None}
    with pending.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(data, stream, indent=2)
        stream.write("\n")
    checked = verify(row(pending), compiled_row)
    pending.rename(output)
    checked["path"] = str(output)
    return checked


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiled-receipt", required=True)
    parser.add_argument("--direct-unix", required=True)
    parser.add_argument("--native-routing")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(json.dumps(create(args.compiled_receipt, args.direct_unix, args.native_routing, args.output)))
