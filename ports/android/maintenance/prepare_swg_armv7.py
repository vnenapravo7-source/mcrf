"""Recover complete, exact release source (including domains.txt) from Git."""
import argparse
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "transports/swg"))
import bs_engine_inputs as bs
from csqtt25 import versioned_inputs as versions


def prepare(repo, upstream, expected, output):
    repo, upstream, output = map(lambda p: pathlib.Path(p).absolute(), (repo, upstream, output))
    bs.require(bs.sha(upstream / "receipt.json") == expected, "Pinned upstream receipt changed")
    versions.verify(upstream)
    lock = versions.metadata()
    def git(*args):
        return subprocess.check_output(["git", "-C", str(repo), *args])
    domain_blobs = set()
    for pin in lock["versions"].values():
        for path, key in (("rust-client", "clientTree"), ("shared", "sharedTree")):
            bs.require(git("rev-parse", pin["revision"] + ":" + path).decode().strip() == pin[key], "Pinned release source tree changed")
        domain_blobs.add(git("rev-parse", pin["revision"] + ":domains.txt").decode().strip())
    bs.require(len(domain_blobs) == 1, "Release domain sources differ")
    revision = lock["versions"]["2.5.3"]["revision"]
    archive_data = git("archive", "--format=zip", revision, "rust-client", "shared", "LICENSE", "domains.txt")
    files = {}
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        bs.require(len(archive.namelist()) == len(set(archive.namelist())), "Duplicate release source path")
        for item in archive.infolist():
            if item.is_dir():
                continue
            name = pathlib.PurePosixPath(item.filename)
            bs.require(not name.is_absolute() and all(p not in ("", ".", "..") for p in name.parts) and "\\" not in item.filename,
                       "Unsafe release source path")
            files[item.filename] = archive.read(item)
    old = json.loads(versions.regular(upstream / "receipt.json"))
    prefix = "source/2.5.3/"
    old_files = {name[len(prefix):]: sha for name, sha in old["files"].items() if name.startswith(prefix)}
    bs.require(set(files) == set(old_files) | {"domains.txt"}, "Unexpected source delta")
    for name, expected_sha in old_files.items():
        bs.require(hashlib.sha256(files[name]).hexdigest() == expected_sha, "Release archive differs from retained source")
    # Git archive on this Windows host applies configured EOL conversion.
    # Materialize the missing input from the raw Git blob, not archive text.
    domains = git("show", revision + ":domains.txt")
    files["domains.txt"] = domains
    blob = hashlib.sha1(b"blob " + str(len(domains)).encode() + b"\0" + domains).hexdigest()
    bs.require(blob in domain_blobs and blob == "d52d29657e34ffa13544323087ed7f20b344737a", "Wrong canonical domains blob")
    bs.require(not output.exists(), "Fresh source/native stage required")
    (output / "source").mkdir(parents=True)
    for name, data in files.items():
        path = output / "source" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    (output / "release-source.zip").write_bytes(archive_data)
    rows = [{"path": name, "sha256": hashlib.sha256(data).hexdigest()} for name, data in sorted(files.items())]
    versions.write_json(output / "source-manifest.json", rows)
    (output / "upstream-receipt.json").write_bytes(versions.regular(upstream / "receipt.json"))
    proof = {"state": "PINNED_RELEASE_SOURCE_RECOVERED", "revision": revision,
             "versions": ["2.5.2", "2.5.3"], "upstreamStage": str(upstream), "upstreamReceiptSha256": expected,
             "upstreamProducerSha256": old["scriptSha256"], "lockSha256": bs.sha(versions.LOCK),
             "domainsGitBlob": blob, "domainsSha256": hashlib.sha256(domains).hexdigest(), "domainsBytes": len(domains),
             "archiveSha256": bs.sha(output / "release-source.zip"), "sourceManifestSha256": bs.sha(output / "source-manifest.json"),
             "producerSha256": bs.sha(__file__)}
    versions.write_json(output / "source-proof.json", proof)
    print(json.dumps(proof))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("repo", "upstream", "sha256", "output"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    prepare(args.repo, args.upstream, args.sha256, args.output)
