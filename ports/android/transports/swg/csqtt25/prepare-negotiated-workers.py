"""Prepare worker-limit and server TUN queue fixes on a NEW owned copy; no build.

The supplied 2.5.1 APK binaries do not contain this fix. They must not be
relabelled as fixed; compile/package the prepared runtime in the common batch.
"""
import pathlib
import re
import sys
import hashlib
import json


# Git blob bytes (LF), pinned to d02da9b. The producer disables autocrlf.
# This is the Linux rust-server queue, not a client packet/socket budget.
SERVER_TUN_PATH = "rust-server/tokio_io.rs"
SERVER_TUN_BEFORE_SHA256 = "b18532ef518443c4abd186f0e9499c07b7b13906c6d6b9138e5fea65b7fe11d5"
SERVER_TUN_AFTER_SHA256 = "2a3d220c7f0cf386a4416ccc559920ec84474d011634d6968862bd1d29f6b341"
SERVER_TUN_OLD = b'.args(["link", "set", "dev", tun_iface, "txqueuelen", "48"])'
SERVER_TUN_NEW = b'.args(["link", "set", "dev", tun_iface, "txqueuelen", "500"])'
CHANGED_PATHS = ["rust-client/main.rs", "rust-client/worker.rs", SERVER_TUN_PATH,
                 "rust-client/session.rs"]
CLIENT_INPUT_PINS = (
    ("ffa97c86613f57505f88e9eae1c33bdfd9bf024d532d902f0d296ef87a2e5552",
     "975f6e7b92e7ba9e4ff17071954d879f0978d4228e026eacba9b2f9c49fb0aed"),
    ("3617fa0a67d022d6cd9c23a364701a93259d9667f082cc1134e2c67aa3d0db12",
     "dacf2f3a63b23878ad7f610e634fbda7609115f21748d28a54ef79219d661dc2"))
SESSION_INPUT_PIN = ("72a6ebccfac31102357deeae0df786210619d1f2eb4848354ae45952810e692f",
                     "e190b0a82ee07cf13776944a95d7debf3b94b25e4dcc072e08396a03ff155227")
DISPATCHER_SHA256 = "64f4f587bd8217f8ffa29d44a596d7c228881f7354fa19cadec585f961711899"

# The limit and its origin must share one lock. Checking only target == 1
# would mistake a real server cap of one for an unnegotiated bootstrap.
WORKER_TARGET = """pub struct McrfWorkerTarget {
    current: AtomicUsize,
    server_limit: Mutex<Option<usize>>,
}

impl McrfWorkerTarget {
    pub fn new(initial: usize) -> Self {
        Self { current: AtomicUsize::new(initial), server_limit: Mutex::new(None) }
    }

    pub fn load(&self, order: Ordering) -> usize { self.current.load(order) }

    fn apply(&self, agreed: Option<usize>, configured: usize) -> usize {
        let mut limit = self.server_limit.lock().expect("worker limit poisoned");
        if let Some(value) = agreed { *limit = Some(value.max(1)); }
        let effective = (*limit).unwrap_or(configured.max(1));
        self.current.swap(effective, Ordering::AcqRel)
    }
}

fn apply_tunconf_workers(
    text: &str,
    configured: usize,
    target: Option<&Arc<McrfWorkerTarget>>,
    notify: Option<&Arc<tokio::sync::Notify>>,
) {
    let agreed = parse_workers_from_tunconf(text);
    // Only a genuinely old response may use the configured fallback.
    if agreed.is_none() && text.split(':').any(|part| part.starts_with("workers=")) {
        return;
    }
    if let Some(target) = target {
        let previous = target.apply(agreed, configured);
        if previous != target.load(Ordering::Acquire) && let Some(notify) = notify {
            notify.notify_waiters();
        }
    }
}

"""


def transform_session(session):
    # Exactly the three target declarations plus WorkerScale's borrowed target.
    if session.count("Arc<AtomicUsize>") != 4:
        raise ValueError("CSQTT25 session worker target declarations changed")
    session = session.replace("Arc<AtomicUsize>", "Arc<McrfWorkerTarget>")
    session = once(session, "fn apply_workers_limit(", WORKER_TARGET + "fn apply_workers_limit(")
    session = once(session, "let prev = target.swap(effective, Ordering::AcqRel);",
                   "let prev = target.apply(Some(effective), effective);")
    session = once(session, """                            if let Some(agreed) = parse_workers_from_tunconf(&value) {
                                apply_workers_limit(agreed, scale.target, scale.notify);
                            }""", """                            apply_tunconf_workers(&value, scale.configured, scale.target, scale.notify);""")
    session = once(session, """                if let Some(agreed) = parse_workers_from_tunconf(&config) {
                    apply_workers_limit(agreed, target_workers.as_ref(), scale_notify.as_ref());
                }""", """                apply_tunconf_workers(&config, configured_workers, target_workers.as_ref(), scale_notify.as_ref());""")
    session += """
#[cfg(test)]
mod mcrf_legacy_worker_tests {
    use super::*;

    #[test]
    fn legacy_config_scales_and_explicit_cap_survives_repeated_config() {
        let target = Arc::new(McrfWorkerTarget::new(1));
        let old = "TUNCONF:10.66.67.2:1.1.1.1:stream-v2";
        apply_tunconf_workers(old, 50, Some(&target), None);
        assert_eq!(target.load(Ordering::Acquire), 50);
        apply_workers_limit(20, Some(&target), None);
        apply_tunconf_workers(old, 50, Some(&target), None);
        assert_eq!(target.load(Ordering::Acquire), 20);
    }

    #[test]
    fn cap_before_config_including_one_and_inline_cap_are_preserved() {
        for cap in [1, 20] {
            let target = Arc::new(McrfWorkerTarget::new(1));
            apply_workers_limit(cap, Some(&target), None);
            apply_tunconf_workers("TUNCONF:10.66.67.2:1.1.1.1", 50, Some(&target), None);
            assert_eq!(target.load(Ordering::Acquire), cap);
        }
        let target = Arc::new(McrfWorkerTarget::new(1));
        apply_tunconf_workers("TUNCONF:10.66.67.2:1.1.1.1:workers=20", 50, Some(&target), None);
        apply_tunconf_workers("TUNCONF:10.66.67.2:1.1.1.1", 50, Some(&target), None);
        assert_eq!(target.load(Ordering::Acquire), 20);
    }

    #[test]
    fn malformed_cap_does_not_enable_legacy_fallback() {
        let target = Arc::new(McrfWorkerTarget::new(1));
        apply_tunconf_workers("TUNCONF:10.66.67.2:1.1.1.1:workers=bad", 50, Some(&target), None);
        assert_eq!(target.load(Ordering::Acquire), 1);
    }

    #[test]
    fn simultaneous_legacy_config_cannot_overwrite_server_cap() {
        for cap in [1, 20] {
            let target = Arc::new(McrfWorkerTarget::new(1));
            let other = target.clone();
            let limit = std::thread::spawn(move || apply_workers_limit(cap, Some(&other), None));
            apply_tunconf_workers("TUNCONF:10.66.67.2:1.1.1.1", 50, Some(&target), None);
            limit.join().unwrap();
            assert_eq!(target.load(Ordering::Acquire), cap);
        }
    }
}
"""
    return session


def server_tun_policy():
    return {"contract": 1, "path": SERVER_TUN_PATH, "txqueuelen": 500,
            "beforeSha256": SERVER_TUN_BEFORE_SHA256, "sha256": SERVER_TUN_AFTER_SHA256}


def transform_server_tun(source):
    # Hash the whole file, not only the replacement anchor: a second setter,
    # changed call site, or an already transformed input must fail before writes.
    if hashlib.sha256(source).hexdigest() != SERVER_TUN_BEFORE_SHA256:
        raise ValueError("CSQTT25 server TUN source drift")
    result = once(source, SERVER_TUN_OLD, SERVER_TUN_NEW)
    if hashlib.sha256(result).hexdigest() != SERVER_TUN_AFTER_SHA256:
        raise ValueError("CSQTT25 server TUN queue transform changed")
    return result


def once(text, old, new):
    if text.count(old) != 1:
        raise ValueError("CSQTT25 negotiation source anchor changed")
    return text.replace(old, new)


def transform(main, worker):
    # GETCONF still advertises configured_workers. Only slot 1 is allowed to
    # allocate until valid TUNCONF or TUNWORKERS supplies the effective target.
    main = once(main, "let initial_workers = workers;", "let initial_workers = 1;")
    main = once(main, "Arc::new(AtomicUsize::new(initial_workers))",
                "Arc::new(crate::session::McrfWorkerTarget::new(initial_workers))")
    worker = once(worker, "pub workers: Arc<AtomicUsize>,",
                  "pub workers: Arc<crate::session::McrfWorkerTarget>,")
    guards = r"\(id > context\.params\.workers\.load\(Ordering::Acquire\)\s*&& id > context\.params\.configured_workers\)"
    worker, count = re.subn(guards, "(id > context.params.workers.load(Ordering::Acquire))", worker)
    if count != 5:
        raise ValueError("CSQTT25 worker retirement guards changed")
    # Notify::notify_waiters does not retain permits for groups created after
    # the agreement. Periodically re-read the atomic target in both owners.
    group_poll = """            _ = tokio::time::sleep(Duration::from_millis(250)) => {
                let current_workers = context.params.workers.load(Ordering::Acquire);
                spawn_groups_up_to(current_workers, &mut tasks, &mut spawned_groups, &mut get_group_credentials);
            }
"""
    worker = once(worker, "            result = tasks.join_next(), if !tasks.is_empty() => {",
                  group_poll + "            result = tasks.join_next(), if !tasks.is_empty() => {")
    worker_poll = """            _ = tokio::time::sleep(Duration::from_millis(250)) => {
                spawn_workers(&mut workers, &mut spawned_worker_ids);
            }
"""
    worker = once(worker, "            result = workers.join_next(), if !workers.is_empty() => {",
                  worker_poll + "            result = workers.join_next(), if !workers.is_empty() => {")
    # Recheck after async credentials/pacing: a previously allowed queued slot
    # must not allocate after a later downward agreement.
    anchor = "        let config_guard = if id > 1 && !context.config_sent.load(Ordering::Acquire) {"
    worker = once(worker, anchor, """        if id > context.params.workers.load(Ordering::Acquire) {
            group_creds.release_allocation(&credential_lease).await;
            return;
        }
""" + anchor)
    # Exercise upstream's real slot partitioning for the reported 50 -> 20
    # case. Host execution of these Rust tests is reserved for the batch.
    worker += """
#[cfg(test)]
mod mcrf_negotiated_worker_tests {
    use super::*;
    #[test]
    fn bootstrap_and_fifty_requested_twenty_agreed() {
        assert_eq!(group_worker_ids(1, 1).collect::<Vec<_>>(), vec![1]);
        for group in 2..=5 { assert!(group_worker_ids(group, 1).is_empty()); }
        let slots: Vec<_> = (1..=5).flat_map(|g| group_worker_ids(g, 20)).collect();
        assert_eq!(slots, (1..=20).collect::<Vec<_>>());
    }
}
"""
    return main, worker


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def owned_root(root):
    root = pathlib.Path(root).absolute()
    if not re.fullmatch(r"D:/MCRF/Builds/Android/csqtt25-negotiated-[0-9a-f]{32}/source", root.as_posix()):
        raise ValueError("New owned CSQTT25 source copy required")
    paths = [root / name for name in CHANGED_PATHS]
    for path in (root, *root.parents, *(p.parent for p in paths), *paths):
        if path.is_symlink() or path.is_junction():
            raise ValueError("Redirected negotiation input refused")
    return root


def source_rows(root):
    rows = []
    for path in sorted(root.rglob("*")):
        if ".git" in path.relative_to(root).parts:
            continue
        if path.is_symlink() or path.is_junction():
            raise ValueError("Redirected source input refused")
        if path.is_file():
            rows.append({"path": path.relative_to(root).as_posix(),
                         "bytes": path.stat().st_size, "sha256": digest(path)})
    return rows


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def prepare(root):
    root = owned_root(root)
    stage = root.parent
    # The producer must create a new copy; an existing freeze is never updated.
    if (stage / "source-manifest.json").exists() or (stage / "negotiation.json").exists():
        raise ValueError("Negotiated source already frozen")
    source_rows(root)  # Refuse redirected inputs before changing any source.
    paths = [root / name for name in CHANGED_PATHS]
    before = [digest(p) for p in paths]
    for index, (expected, _) in zip((0, 1, 3), (*CLIENT_INPUT_PINS, SESSION_INPUT_PIN)):
        if before[index] != expected:
            raise ValueError("CSQTT25 client input source drift")
    main, worker = transform(*(p.read_text(encoding="utf-8") for p in paths[:2]))
    server = transform_server_tun(paths[2].read_bytes())
    session = transform_session(paths[3].read_text(encoding="utf-8"))
    # Validate every transformation before writing any source file.
    outputs = (main.encode("utf-8"), worker.encode("utf-8"), server, session.encode("utf-8"))
    for index, (_, expected) in zip((0, 1, 3), (*CLIENT_INPUT_PINS, SESSION_INPUT_PIN)):
        if hashlib.sha256(outputs[index]).hexdigest() != expected:
            raise ValueError("CSQTT25 client output source drift")
    for path, value in zip(paths, outputs):
        path.write_bytes(value)
    save_json(stage / "source-manifest.json", source_rows(root))
    proof = {"contract": 1, "applications": 1, "bootstrapWorkers": 1,
             "legacyTunconfFallback": True,
             "serverTunTxQueue": server_tun_policy(),
             "transformSha256": digest(pathlib.Path(__file__)),
             "sourceManifestSha256": digest(stage / "source-manifest.json"),
             "changedInputs": [{"path": p.relative_to(root).as_posix(),
                                "beforeSha256": old, "sha256": digest(p)}
                               for p, old in zip(paths, before)]}
    save_json(stage / "negotiation.json", proof)
    return proof


def verify(root, built=False):
    root = owned_root(root)
    stage = root.parent
    proof = json.loads((stage / "negotiation.json").read_text(encoding="utf-8"))
    if (proof.get("contract"), proof.get("applications"), proof.get("bootstrapWorkers")) != (1, 1, 1):
        raise ValueError("Worker negotiation contract missing")
    if proof.get("legacyTunconfFallback") is not True:
        raise ValueError("Legacy TUNCONF fallback contract missing")
    if (proof.get("serverTunTxQueue") != server_tun_policy()
            or digest(root / SERVER_TUN_PATH) != SERVER_TUN_AFTER_SHA256):
        raise ValueError("Server TUN queue policy or source changed")
    if proof["transformSha256"] != digest(pathlib.Path(__file__)):
        raise ValueError("Worker transform changed")
    manifest = stage / "source-manifest.json"
    if proof["sourceManifestSha256"] != digest(manifest):
        raise ValueError("Negotiated source manifest changed")
    rows = json.loads(manifest.read_text(encoding="utf-8"))
    if rows != source_rows(root):
        raise ValueError("Frozen Rust source set changed")
    changed = proof["changedInputs"]
    if [row["path"] for row in changed] != CHANGED_PATHS:
        raise ValueError("Negotiated source delta missing")
    if changed[2] != {k: v for k, v in server_tun_policy().items()
                      if k in ("path", "beforeSha256", "sha256")}:
        raise ValueError("Server TUN queue provenance changed")
    for row in changed:
        if row["beforeSha256"] == row["sha256"] or row["sha256"] != digest(root / row["path"]):
            raise ValueError("Negotiated Rust input changed")
    for index, (before, after) in zip((0, 1, 3), (*CLIENT_INPUT_PINS, SESSION_INPUT_PIN)):
        row = changed[index]
        if (row["beforeSha256"], row["sha256"]) != (before, after):
            raise ValueError("Negotiated client generation changed")
    if built:
        receipt = json.loads((stage / "receipt.json").read_text(encoding="utf-8-sig"))
        if (receipt.get("state") != "COMPILED_NOT_EXECUTED" or receipt.get("sourceStable") is not True
                or receipt.get("nativeExecuted") is not False
                or receipt.get("revision") != "d02da9bd9d8926969b766e197c434d830a83dbd8"
                or receipt.get("swgPatchSha256") != "b61ad9dd4610b3ed200e9f2fb69567492987a4fb2d89c70255f0770148b2ff77"
                or digest(root / "rust-client/dispatcher.rs") != DISPATCHER_SHA256
                or receipt.get("workerNegotiationContract") != 1
                or receipt.get("serverTunTxQueueContract") != 1
                or receipt.get("source") != str(root)
                or receipt.get("sourceManifestSha256") != digest(manifest)
                or receipt.get("negotiationSha256") != digest(stage / "negotiation.json")
                or receipt.get("changedInputs") != changed
                or receipt.get("transformSha256") != proof["transformSha256"]
                or receipt.get("preparerSha256") != digest(pathlib.Path(__file__).with_name("Prepare-NegotiatedSource.ps1"))
                or receipt.get("producerSha256") != digest(pathlib.Path(__file__).with_name("Build-NegotiatedNative.ps1"))):
            raise ValueError("Compiled negotiated transport receipt missing")
        artifacts = receipt.get("artifacts", [])
        if [a["abi"] for a in artifacts] != ["arm64-v8a", "armeabi-v7a", "x86_64"]:
            raise ValueError("All negotiated transport ABIs required")
        for artifact, (elf_class, machine) in zip(artifacts, ((2, 183), (1, 40), (2, 62))):
            path = stage / artifact["abi"] / "libcsqtt25_transport.so"
            if (artifact["file"] != str(path) or path.is_symlink() or path.is_junction()
                    or path.parent.is_symlink() or path.parent.is_junction()):
                raise ValueError("Unexpected negotiated artifact path")
            if artifact["sha256"] != digest(path) or artifact["bytes"] != path.stat().st_size:
                raise ValueError("Negotiated native artifact changed")
            header = path.read_bytes()[:20]
            if (header[:6] != bytes((127, 69, 76, 70, elf_class, 1))
                    or int.from_bytes(header[16:18], "little") != 3
                    or int.from_bytes(header[18:20], "little") != machine):
                raise ValueError("Negotiated native ELF ABI mismatch")
    return proof


def pin_wrapper(module, root):
    """Pin the NEW wrapper copy to rebuilt transports; never mutate a freeze."""
    module = pathlib.Path(module).absolute()
    if not re.fullmatch(r"D:/MCRF/Builds/Android/p3-csqtt25-source-[0-9a-f]{32}/module", module.as_posix()):
        raise ValueError("New owned wrapper source required")
    if (module.parent / "SOURCE_MANIFEST.json").exists():
        raise ValueError("Wrapper already frozen")
    root = owned_root(root)
    verify(root, built=True)
    artifacts = json.loads((root.parent / "receipt.json").read_text(encoding="utf-8-sig"))["artifacts"]
    runtime = module / "internal/csqtt25/runtime.go"
    abi = module / "internal/csqtt25/native_abi.go"
    for path in (module, *module.parents, runtime.parent, runtime.parent.parent, runtime, abi):
        if path.is_symlink() or path.is_junction():
            raise ValueError("Redirected wrapper input refused")
    text = runtime.read_text(encoding="utf-8")
    text, abi_text = wrapper_texts(text, abi.read_text(encoding="utf-8"), artifacts)
    # Both files validated before either write, preserving fail-closed anchors.
    runtime.write_text(text, encoding="utf-8", newline="\n")
    abi.write_text(abi_text, encoding="utf-8", newline="\n")


def wrapper_texts(text, abi_text, artifacts):
    """Exact reviewed wrapper delta, shared by pinning and read-only admission."""
    text = once(text, 'const NativeSHA256 = "9dad51554bafb2e6cf386a48cc278fb08c434a78ece40baff00fb51f203b237c"',
                f'const NativeSHA256 = "{artifacts[0]["sha256"]}"')
    text = once(text, "const NativeBytes int64 = 9306144", f'const NativeBytes int64 = {artifacts[0]["bytes"]}')
    text = once(text, "// SanityProtocol/swg-panel csqtt-app-2.5.1, upstream d02da9b +\n"
                "// csqtt-app-2.5.1.patch (UDP flow pin/bulk distribution fix).\n"
                "// APK SHA256: 733962c7a09f6d811797ca09c03ac5040ec21b0d6623ea21201a2c85762e7c12.\n"
                "// This generation must not silently accept the withdrawn prerelease artifact.",
                "// Owned CSQTT25 d02da9b + SWG 2.5.1 dispatcher + negotiated worker limit.\n"
                "// Exact rebuilt transport identities are bound by the native source receipt.")
    for artifact, old, size in zip(artifacts[1:],
            ("ef3c6ef1bc4937e416cb53c1ef4e29de388c3bc6dc9f06643f89ec68a2715751",
             "d3b1edf30235a879e745be8c60c3b155309f69fd3bf97e2426415c1de67a45ff"), (6823384, 11049008)):
        abi_text = once(abi_text, f'"{old}", {size}', f'"{artifact["sha256"]}", {artifact["bytes"]}')
    return text, abi_text


def verify_wrapper(module, root):
    module = pathlib.Path(module).absolute()
    if not re.fullmatch(r"D:/MCRF/Builds/Android/p3-csqtt25-source-[0-9a-f]{32}/module", module.as_posix()):
        raise ValueError("Owned wrapper source required")
    root = owned_root(root)
    verify(root, built=True)
    artifacts = json.loads((root.parent / "receipt.json").read_text(encoding="utf-8-sig"))["artifacts"]
    here = pathlib.Path(__file__).parent
    expected = wrapper_texts((here / "runtime.go").read_text(encoding="utf-8"),
                             (here / "native_abi.go").read_text(encoding="utf-8"), artifacts)
    for name, text in zip(("runtime.go", "native_abi.go"), expected):
        path = module / "internal/csqtt25" / name
        if any(p.is_symlink() or p.is_junction() for p in (path, *path.parents)):
            raise ValueError("Redirected wrapper input refused")
        if path.read_text(encoding="utf-8") != text:
            raise ValueError("Wrapper differs from exact negotiated pins/loader")


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "--pin-wrapper":
        pin_wrapper(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 3 and sys.argv[1] in ("--verify", "--verify-built"):
        verify(sys.argv[2], built=sys.argv[1] == "--verify-built")
    elif len(sys.argv) == 2:
        prepare(sys.argv[1])
    else:
        raise SystemExit("usage: prepare-negotiated-workers.py [--verify|--verify-built] OWNED_SOURCE")
