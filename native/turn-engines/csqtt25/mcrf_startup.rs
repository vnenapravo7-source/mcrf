// MCRF's private Windows host boundary. Transport/wire protocol stays upstream.
use std::{io::{self, BufRead, Read}, net::SocketAddr};
use clap::Parser;
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Startup {
    peer: String, listen: String, password: String, hashes: Vec<String>,
    device_id: String, generation: u64, salt: String,
    #[serde(default)] options: std::collections::HashMap<String,String>,
}

pub(super) fn read() -> Result<super::Arguments, &'static str> {
    if std::env::args().count()!=2 || std::env::args().nth(1).as_deref()!=Some("--mcrf-config-stdin") {
        return Err("CSQTT_ERROR|Private startup input is required");
    }
    let mut first=String::new();
    let stdin=io::stdin();
    let count=stdin.lock().take(32769).read_line(&mut first).map_err(|_| "CSQTT_ERROR|Private startup input unavailable")?;
    if count==0 || count>32768 || !first.ends_with('\n') {return Err("CSQTT_ERROR|Invalid private startup size");}
    let value: Startup=serde_json::from_str(&first).map_err(|_| "CSQTT_ERROR|Invalid private startup configuration")?;
    first.clear();
    let listen: SocketAddr=value.listen.parse().map_err(|_| "CSQTT_ERROR|Invalid private loopback listener")?;
    if !listen.ip().is_loopback() || !listen.is_ipv4() || listen.port()==0 || value.password.is_empty() || value.password.len()>4096
        || (value.hashes.is_empty() && value.options.get("vk_hash_mode").map(String::as_str)!=Some("auto_js")) || value.hashes.len()>32 || value.hashes.iter().any(|h|h.len()<16 || h.len()>2048 || !h.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-'||c==b'_'||c==b'='||c==b'+'||c==b'/'))
        || value.peer.len()>512 || value.peer.contains(['\r','\n','\0']) {
        return Err("CSQTT_ERROR|Invalid private endpoint or credentials");
    }
    let workers=std::env::var("MCRF_CSQTT_WORKERS").unwrap_or_else(|_|"18".into());
    let raw_captcha=std::env::var("MCRF_CSQTT_CAPTCHA_MODE").unwrap_or_else(|_|"auto".into());let captcha=if raw_captcha=="manual" {"wv".to_string()}else{raw_captcha};
    if captcha!="wv" && captcha!="auto" && captcha!="rjs" {return Err("CSQTT_ERROR|Invalid captcha mode");}
    let number:usize=workers.parse().map_err(|_|"CSQTT_ERROR|Invalid private worker count")?;
    if number<3 || number>100 || (number!=18 && number>value.hashes.len()*10 && value.options.get("allow_hash_redistribution").map(String::as_str)!=Some("true") && value.options.get("vk_hash_mode").map(String::as_str)!=Some("auto_js")) {return Err("CSQTT_ERROR|Invalid private worker count");}
    if value.options.len()>40 || value.options.values().any(|v|v.len()>16384||v.contains('\0')) {return Err("MCRF_TURN_OPTIONS_V1|Invalid options");}
    // argv below is an in-memory clap input, NOT the process command line.
    let mut arguments=super::Arguments::try_parse_from([
        "mcrf-csqtt25", "--peer", &value.peer, "--listen", &value.listen, "--vk", &value.hashes.join(","),
        "--workers", &workers, "--runtime-threads", "4", "--captcha-mode", &captcha, "--vk-auth-mode", "vkcalls",
    ]).map_err(|_| "CSQTT_ERROR|Invalid private transport settings")?;
    if let Some(v)=value.options.get("obfs") {arguments.obfs=v.clone();}
    if let Some(v)=value.options.get("fingerprint") {arguments.fingerprint=v.clone();}
    if let Some(v)=value.options.get("turn_transport") {arguments.turn_transport=v.clone();}
    if let Some(v)=value.options.get("vk_auth_mode") {arguments.vk_auth_mode=v.clone();}
    if let Some(v)=value.options.get("vk_hash_mode") {arguments.vk_hash_mode=v.clone();}
    if let Some(v)=value.options.get("custom_turn_ips") {arguments.custom_turn_ips=v.split(|c:char|c.is_whitespace()||c==','||c==';').filter(|v|!v.is_empty()).collect::<Vec<_>>().join(",");}
    if let Some(v)=value.options.get("client_ids") {arguments.client_ids=v.clone();}
    if let Some(v)=value.options.get("generation") {arguments.generation=v.parse().map_err(|_|"MCRF_TURN_OPTIONS_V1|Invalid generation")?;}
    if let Some(v)=value.options.get("salt") {arguments.salt=v.clone();}
    arguments.exact_workers=true;
    if std::env::var("MCRF_VALIDATE_ONLY").as_deref()==Ok("1") {println!("MCRF_CONFIG_PARSED|{}",serde_json::json!({"obfs":arguments.obfs,"fingerprint":arguments.fingerprint,"turn_transport":arguments.turn_transport,"captcha":arguments.captcha_mode,"auth":arguments.vk_auth_mode,"hash_mode":arguments.vk_hash_mode,"workers":arguments.workers,"exact":arguments.exact_workers,"hash_count":value.hashes.len(),"MCRF_CSQTT_CALLS32":true,"turn_ips":arguments.custom_turn_ips,"client_ids":arguments.client_ids,"generation":arguments.generation}));std::process::exit(0);}
    // 2.5 has no device-bound credential/generation. Never send legacy identity
    // fields as a new wire command or fall back to the 2.1 transport.
    let _=(value.device_id,value.generation,value.salt);
    arguments.private_password=value.password;
    Ok(arguments)
}
