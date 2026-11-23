"""Evaluate explicitly pinned measured evidence; never publishes a native stage."""
import argparse
import json
import pathlib
import zlib
from bs_rust_size_experiment import evaluate, load, sha, require


def verify(receipt_path, receipt_sha, measurements_path, measurements_sha):
    require(sha(receipt_path) == receipt_sha and sha(measurements_path) == measurements_sha, "Pinned experiment and measurements required")
    receipt = load(receipt_path)
    for name in ("baseline", "size"):
        row = receipt["profiles"][name]
        require(sha(row["artifact"]) == row["artifactSha256"], "Experiment binary changed")
        binary = pathlib.Path(row["artifact"]).read_bytes()
        require(row["bytes"] == len(binary) and
                row["compressedBytes"] == len(zlib.compress(binary, 9)),
                "Experiment native size mismatch")
    result = evaluate(receipt, load(measurements_path))
    result.update(receiptSha256=receipt_sha, measurementsSha256=measurements_sha,
                  scope="One generation/ABI experiment only; not full multi-call acceptance")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipt", required=True)
    parser.add_argument("--receipt-sha256", required=True)
    parser.add_argument("--measurements", required=True)
    parser.add_argument("--measurements-sha256", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = pathlib.Path(args.output)
    require(not output.exists(), "Fresh evaluation output required")
    result = verify(args.receipt, args.receipt_sha256, args.measurements, args.measurements_sha256)
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result))
    raise SystemExit(0 if result["state"] == "ACCEPTED_EXPERIMENT" else 1)
