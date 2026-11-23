import contextlib
import hashlib
import json
import pathlib
import tempfile
import unittest
from unittest import mock

import aligned_inputs as aligned
from csqtt25 import versioned_inputs as versioned


class AlignedInputStageTests(unittest.TestCase):
    @contextlib.contextmanager
    def stage(self):
        with tempfile.TemporaryDirectory(dir=pathlib.Path(__file__).parent) as temp:
            root = pathlib.Path(temp)
            upstream = root / "upstream"
            upstream.mkdir()
            source = root / "source"
            source.mkdir()
            auth = source / "auth.rs"
            auth.write_bytes(b"pinned Rust source")
            domains = b"d" * 5708
            (source / "domains.txt").write_bytes(domains)
            source_sha = aligned.bs.sha(auth)
            domains_sha = hashlib.sha256(domains).hexdigest()
            domains_blob = hashlib.sha1(b"blob 5708\0" + domains).hexdigest()

            def write(path, value):
                path.write_text(json.dumps(value), encoding="utf-8")
                return aligned.bs.sha(path)

            manifest = write(root / "source-manifest.json", [
                {"path": "auth.rs", "sha256": source_sha},
                {"path": "domains.txt", "sha256": domains_sha},
            ])
            upstream_sha = write(upstream / "receipt.json", {"files": {"source/2.5.3/auth.rs": source_sha}})
            (root / "upstream-receipt.json").write_bytes((upstream / "receipt.json").read_bytes())
            (root / "release-source.zip").write_bytes(b"archived source bytes")
            proof_sha = write(root / "source-proof.json", {
                "state": "PINNED_RELEASE_SOURCE_RECOVERED",
                "lockSha256": aligned.bs.sha(versioned.LOCK),
                "revision": versioned.metadata()["versions"]["2.5.3"]["revision"],
                "producerSha256": aligned.bs.sha(aligned.bs.ROOT / "maintenance/prepare_swg_armv7.py"),
                "sourceManifestSha256": manifest,
                "archiveSha256": aligned.bs.sha(root / "release-source.zip"),
                "upstreamStage": str(upstream),
                "upstreamReceiptSha256": upstream_sha,
                "domainsGitBlob": domains_blob,
                "domainsSha256": domains_sha,
                "domainsBytes": len(domains),
            })
            artifact = {
                "abi": "armeabi-v7a", "file": "armeabi-v7a/libcsqtt252_transport.so",
                "sha256": hashlib.sha256(b"expected artifact").hexdigest(), "bytes": 17,
                "nativeExecuted": False,
            }
            receipt_sha = write(root / "receipt.json", {
                "state": "COMPILED_NOT_EXECUTED", "kind": "csqtt252-armv7-aligned",
                "sourceStable": True, "nativeExecuted": False,
                "producerSha256": aligned.bs.sha(aligned.bs.ROOT / "maintenance/Build-SwgVersionArmv7.ps1"),
                "sourceProofSha256": proof_sha, "sourceManifestSha256": manifest,
                "upstreamReceiptSha256": upstream_sha, "artifacts": [artifact],
            })
            lock = root / "lock.json"
            write(lock, {"receiptSha256": receipt_sha, "sha256": artifact["sha256"], "bytes": artifact["bytes"]})
            with mock.patch.multiple(aligned, LOCK=lock, UPSTREAM_RECEIPT=upstream_sha,
                                     DOMAINS_SHA=domains_sha, DOMAINS_BLOB=domains_blob), \
                 mock.patch.object(versioned, "verify") as verify_upstream:
                yield root, auth, root / artifact["file"], verify_upstream, upstream

    def test_source_proof_works_without_historical_aligned_elf_but_rejects_changed_source(self):
        with self.stage() as (root, auth, artifact, verify_upstream, upstream):
            self.assertFalse(artifact.exists())
            aligned.verify_source(root)
            verify_upstream.assert_called_once_with(upstream)
            auth.write_bytes(b"changed Rust source")
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                aligned.verify_source(root)

    def test_native_proof_rejects_missing_or_changed_aligned_elf(self):
        with self.stage() as (root, _, artifact, _, _):
            with self.assertRaisesRegex(ValueError, "Missing input"):
                aligned.verify_native(root)
            artifact.parent.mkdir()
            artifact.write_bytes(b"changed ELF")
            with self.assertRaisesRegex(ValueError, "Frozen input changed"):
                aligned.verify_native(root)


if __name__ == "__main__":
    unittest.main()
