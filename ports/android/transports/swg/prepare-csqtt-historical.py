"""Pinned historical clients: private ingress/CAPTCHA and local event bridge only; wire codecs unchanged."""
import pathlib
import re
import subprocess
import sys

REVISIONS = {
    "2.0": "2ff8a1a4fbe031fb76b799e172dce25cfe1ca942",
    "2.1.5": "de7afc23cec7481f98845bf24fc8943af19b4cf8",
}

def adapt(root, generation):
    if generation not in REVISIONS:
        raise ValueError("Unknown historical CSQTT generation")
    actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISIONS[generation]:
        raise ValueError("Historical source revision mismatch")
    main = root / "rust-client/main.rs"
    source = main.read_text(encoding="utf-8")
    old = "std::env::args().map(normalize_cli_argument).collect()"
    ingress = r'''let mut args: Vec<String> = std::env::args().map(normalize_cli_argument).collect();
    if let Ok(path) = std::env::var("CSQTT_CONFIG_FILE") {
        let bytes = std::fs::read(path).unwrap_or_default();
        let config: serde_json::Value = if bytes.len() <= 65536 {
            serde_json::from_slice(&bytes).unwrap_or(serde_json::Value::Null)
        } else { serde_json::Value::Null };
        if !config.is_object() || config["csqtt_generation"].as_str() != Some("MCRF_GENERATION")
            || config["vk_anon_path"].as_str() == Some("auto_js")
            || config["vk_runtime_token"].as_str().is_some() {
            eprintln!("Invalid private historical CSQTT configuration"); std::process::exit(2);
        }
        for (key, flag) in [("peer", "peer"), ("password", "password"), ("listen", "listen"),
            ("device_id", "device-id"), ("obfs", "obfs"), ("captcha_mode", "captcha-mode"),
            ("vk_anon_path", "vk-auth-mode"), ("vk_hash_mode", "vk-hash-mode"), ("fingerprint", "fingerprint")] {
            if let Some(value) = config[key].as_str() { args.push(format!("--{flag}={value}")); }
        }
        if let Some(hashes) = config["hashes"].as_array() {
            let values: Vec<&str> = hashes.iter().filter_map(|hash| hash.as_str()).collect();
            args.extend(["--vk".to_owned(), values.join(",")]);
        }
        if let Some(workers) = config["workers"].as_u64() {
            args.extend(["--workers".to_owned(), workers.to_string()]);
        }
        MCRF_TURN_TRANSPORT
    }
    args'''.replace("MCRF_GENERATION", generation).replace("MCRF_TURN_TRANSPORT", (
        'if config["turn_tcp"].as_bool().unwrap_or(false) { eprintln!("Historical CSQTT 2.0 supports UDP TURN only"); std::process::exit(2); }'
        if generation == "2.0" else
        'args.extend(["--turn-transport".to_owned(), if config["turn_tcp"].as_bool().unwrap_or(false) { "tcp" } else { "udp" }.to_owned()]);'
    ))
    if source.count(old) != 1:
        raise ValueError("Historical private ingress anchor mismatch")
    source = source.replace(old, ingress)
    if source.count("mod captcha;\n") != 1:
        raise ValueError("Historical CAPTCHA module anchor mismatch")
    source = source.replace("mod captcha;\n", "mod captcha;\nmod mcrf_captcha_waiters;\nmod mcrf_historical_tunnel;\n")
    event = "config_events.config(&config);"
    if source.count(event) != 1:
        raise ValueError("Historical local tunnel event anchor mismatch")
    source = source.replace(event, "config_events.config(&mcrf_historical_tunnel::for_ip_bridge(&config));")
    # Every admitted private profile is <=64KiB. 4096 hashes of minimum16 bytes
    # cannot fit; this bound can never truncate an admitted pool. Stream count
    # remains independently bounded, rather than multiplying it by the new pool.
    if source.count("const MAX_VK_HASHES: usize = 6;") != 1:
        raise ValueError("Historical call pool anchor mismatch")
    source = source.replace("const MAX_VK_HASHES: usize = 6;", "const MAX_VK_HASHES: usize = 4096;")
    source = source.replace("const MAX_WORKERS: usize = MAX_VK_HASHES * GROUPS_PER_VK_HASH * WORKERS_PER_GROUP;", "const MAX_WORKERS: usize = 126;")
    source = source.replace("assert_eq!(normalize_worker_count(MAX_WORKERS), 162);", "assert_eq!(normalize_worker_count(MAX_WORKERS), 126);")
    # Old stdin diagnostics included solved proofs. Suppress those before the
    # existing handler; do not change the old client's pause/stop semantics.
    pattern = r'if !line\.contains\("error:tunnel stopped"\)([^\n]*)\{'
    if len(re.findall(pattern, source)) != 1:
        raise ValueError("Historical stdin diagnostic anchor mismatch")
    source = re.sub(pattern, r'if !line.starts_with("CAPTCHA_RESULT|") && !line.contains("error:tunnel stopped")\1{', source)
    main.write_text(source, encoding="utf-8", newline="\n")
    scripts = pathlib.Path(__file__).resolve().parent
    patch = (scripts / "csqtt-waiters.patch").read_text(encoding="utf-8")
    patch = patch[patch.index("diff --git a/rust-client/captcha.rs"):]
    subprocess.run(["git", "-C", str(root), "apply", "--recount", "-"], input=patch, text=True, check=True)
    helper = scripts / "mcrf_captcha_waiters.rs"
    (root / "rust-client/mcrf_captcha_waiters.rs").write_bytes(helper.read_bytes())
    (root / "rust-client/mcrf_historical_tunnel.rs").write_bytes((scripts / "mcrf_historical_tunnel.rs").read_bytes())
    print("HISTORICAL_CS QTT_SOURCE_ADAPTED=".replace(" ", "") + generation)

if __name__ == "__main__":
    adapt(pathlib.Path(sys.argv[1]).resolve(), sys.argv[2])
