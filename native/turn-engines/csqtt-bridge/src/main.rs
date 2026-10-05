// Host adapter only. The transport is the unmodified PolyForm-Noncommercial
// CSQTT core by amurcanov, with Windows portability by luminescq.
use std::io::{self, BufRead, Write};
use serde::Deserialize;
use csqtt_core::{ClientConfig, run_client, set_events_enabled, set_log_callback};
use tokio_util::sync::CancellationToken;

#[derive(Deserialize)]
struct Startup { peer: String, listen: String, password: String, hashes: Vec<String>, device_id: String, generation: u64, salt: String }

fn main() {
    let mut first = String::new();
    if io::stdin().read_line(&mut first).is_err() || first.len() > 32768 { std::process::exit(2); }
    let startup: Startup = match serde_json::from_str(&first) { Ok(value) => value, Err(_) => { eprintln!("CSQTT_ERROR|Invalid private startup configuration"); std::process::exit(2); } };
    first.clear();
    if !startup.listen.starts_with("127.0.0.1:") || startup.hashes.is_empty() || startup.hashes.len() > 6 || startup.password.is_empty() {
        eprintln!("CSQTT_ERROR|Invalid loopback listener or credentials"); std::process::exit(2);
    }
    // Never request wintun: local raw IP datagrams feed a userspace netstack.
    let config = ClientConfig { peer: startup.peer, listen: startup.listen, password: startup.password,
        vk: startup.hashes.join(","), device_id: startup.device_id, workers: 18,
        generation: startup.generation, salt: startup.salt,
        tun_uds: String::new(), ..Default::default() };
    let cancel = CancellationToken::new();
    let input_cancel = cancel.clone();
    std::thread::spawn(move || {
        for line in io::stdin().lock().lines() {
            match line { Ok(command) if command.trim() == "STOP" => { input_cancel.cancel(); return; }, Ok(command) => { csqtt_core::submit_control_line(command); }, Err(_) => break }
        }
        input_cancel.cancel();
    });
    set_events_enabled(true);
    set_log_callback(Box::new(|line| {
        // Structured events are parsed/redacted by the host; diagnostic lines
        // are not forwarded, because upstream can contain VK response secrets.
        if line.starts_with("__CSQTT_EVENT__|") { println!("{line}"); let _ = io::stdout().flush(); }
    }));
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build().expect("runtime");
    if runtime.block_on(run_client(config, Some(cancel))).is_err() { eprintln!("CSQTT_ERROR|Transport stopped before readiness; inspect structured events"); std::process::exit(1); }
}
