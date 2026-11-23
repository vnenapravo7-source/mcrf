"""Freeze the current November debug owner fixture inputs without certifying legacy P1."""
import hashlib
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent
RECEIPT = pathlib.Path("D:/MCRF/Work/ui-batch-20261120/strict-owner-fixture-build-inputs.json")


def main():
    gate = (ROOT / "strict-owner-fixture-gate.gradle.kts").read_text(encoding="utf-8")
    block = re.search(r"(?ms)^val paths = setOf\(\n(.*?)^\)", gate)
    if block is None:
        raise ValueError("Missing strict-owner fixture input allowlist")
    names = re.findall(r'"([A-Za-z0-9_./-]+)"', block[1])
    if len(names) != len(set(names)) or not names:
        raise ValueError("Invalid strict-owner fixture input allowlist")
    sources = []
    for name in sorted(names):
        file = ROOT / name
        if file.resolve(strict=True) != file or file.is_symlink() or not file.is_file():
            raise ValueError("Redirected or invalid fixture input: " + name)
        content = file.read_bytes()
        sources.append({"path": name, "bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()})
    scope = "nov20-strict-owner-debug-fixture"
    payload = {"schema": 1, "scope": scope, "physicalDevice": "R5CX73QYN3D", "androidUser": 0,
               "providerTraffic": False, "productionActivated": False, "sources": sources}
    content = (json.dumps(payload, indent=2) + "\n").encode("utf-8")
    if RECEIPT.exists():
        if RECEIPT.is_symlink() or not RECEIPT.is_file() or json.loads(RECEIPT.read_bytes()).get("scope") != scope:
            raise ValueError("Refusing to overwrite an unrelated fixture receipt")
    staging = RECEIPT.with_suffix(".pending")
    with staging.open("xb") as stream:
        stream.write(content)
    staging.replace(RECEIPT)
    print(json.dumps({"path": str(RECEIPT), "sha256": hashlib.sha256(content).hexdigest()}))


if __name__ == "__main__":
    main()
