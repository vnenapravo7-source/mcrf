// MCRF's private Windows host boundary. Transport/wire protocol stays upstream.
use std::{io::{self, BufRead, Read}, net::SocketAddr};
use clap::Parser;
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Startup {
    peer: String, listen: String, password: String, hashes: Vec<String>,
    device_id: String, generation: u64, salt: String,
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
        || value.hashes.is_empty() || value.hashes.len()>6 || value.hashes.iter().any(|h|h.len()!=43 || !h.bytes().all(|c|c.is_ascii_alphanumeric()||c==b'-'||c==b'_'))
        || value.peer.len()>512 || value.peer.contains(['\r','\n','\0']) {
        return Err("CSQTT_ERROR|Invalid private endpoint or credentials");
    }
    // argv below is an in-memory clap input, NOT the process command line.
    let mut arguments=super::Arguments::try_parse_from([
        "mcrf-csqtt25", "--peer", &value.peer, "--listen", &value.listen, "--vk", &value.hashes.join(","),
        "--workers", "18", "--runtime-threads", "4", "--captcha-mode", "manual", "--vk-auth-mode", "vkcalls",
    ]).map_err(|_| "CSQTT_ERROR|Invalid private transport settings")?;
    // 2.5 has no device-bound credential/generation. Never send legacy identity
    // fields as a new wire command or fall back to the 2.1 transport.
    let _=(value.device_id,value.generation,value.salt);
    arguments.private_password=value.password;
    Ok(arguments)
}
