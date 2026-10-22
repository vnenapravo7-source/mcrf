// Host adapter only. The transport is the unmodified PolyForm-Noncommercial
// CSQTT core by amurcanov, with Windows portability by luminescq.
use std::io::{self, BufRead, Write};
use serde::Deserialize;
use csqtt_core::{ClientConfig, run_client, set_events_enabled, set_log_callback};
use tokio_util::sync::CancellationToken;

#[derive(Deserialize)]
struct Startup { peer: String, listen: String, password: String, hashes: Vec<String>, device_id: String, generation: u64, salt: String, #[serde(default)] options:std::collections::HashMap<String,String> }

fn main() {
    let mut first = String::new();
    if io::stdin().read_line(&mut first).is_err() || first.len() > 32768 { std::process::exit(2); }
    let startup: Startup = match serde_json::from_str(&first) { Ok(value) => value, Err(_) => { eprintln!("CSQTT_ERROR|Invalid private startup configuration"); std::process::exit(2); } };
    first.clear();
    if !startup.listen.starts_with("127.0.0.1:") || (startup.hashes.is_empty()&&startup.options.get("vk_hash_mode").map(String::as_str)!=Some("auto_js")) || startup.hashes.len() > 6 || startup.password.is_empty() {
        eprintln!("CSQTT_ERROR|Invalid loopback listener or credentials"); std::process::exit(2);
    }
    let workers: usize = std::env::var("MCRF_CSQTT_WORKERS").ok().and_then(|v|v.parse().ok()).unwrap_or(18);
    let raw_captcha=std::env::var("MCRF_CSQTT_CAPTCHA_MODE").unwrap_or_else(|_|"auto".into());let captcha_mode=if raw_captcha=="manual" {"wv".to_string()}else{raw_captcha};
    if captcha_mode!="wv" && captcha_mode!="auto" && captcha_mode!="rjs" {eprintln!("CSQTT_ERROR|Invalid captcha mode");std::process::exit(2);}
    if workers<9 || workers>126 || workers%9!=0 || (workers>startup.hashes.len()*27&&startup.options.get("vk_hash_mode").map(String::as_str)!=Some("auto_js")) {eprintln!("CSQTT_ERROR|Invalid private worker count");std::process::exit(2);}
    if startup.options.len()>40||startup.options.values().any(|v|v.len()>16384||v.contains('\0')){eprintln!("MCRF_TURN_OPTIONS_V1|Invalid options");std::process::exit(2);}
    let get=|key:&str,fallback:&str|startup.options.get(key).cloned().unwrap_or_else(||fallback.to_string());
    let obfs=get("obfs","audio");let fingerprint=get("fingerprint","chrome");let vk_auth_mode=get("vk_auth_mode","vkcalls");let vk_hash_mode=get("vk_hash_mode","manual");let vk_js_token=get("vk_token","");
    // Never request wintun: local raw IP datagrams feed a userspace netstack.
    let config = ClientConfig { peer: startup.peer, listen: startup.listen, password: startup.password,
        vk: startup.hashes.join(","), device_id: startup.device_id, workers,
        generation: startup.generation, salt: startup.salt,
        tun_uds: String::new(), captcha_mode, obfs, fingerprint,vk_auth_mode,vk_hash_mode,vk_js_token, turn_transport:get("turn_transport","udp"), ..Default::default() };
    if std::env::var("MCRF_VALIDATE_ONLY").as_deref()==Ok("1"){println!("MCRF_CONFIG_PARSED|{}",serde_json::json!({"obfs":config.obfs,"fingerprint":config.fingerprint,"captcha":config.captcha_mode,"auth":config.vk_auth_mode,"hash_mode":config.vk_hash_mode,"turn_transport":config.turn_transport,"workers":config.workers}));std::process::exit(0);}
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
