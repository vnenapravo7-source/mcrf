// Private Windows host boundary; the pinned upstream wire implementation is unchanged.
use std::io::{self, BufRead, Read};
use clap::Parser;
use serde::Deserialize;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Startup { peer:String, listen:String, password:String, hashes:Vec<String>, device_id:String, generation:u64, salt:String, #[serde(default)] options:std::collections::HashMap<String,String> }
pub(super) fn read()->Result<super::Arguments,&'static str>{
 if std::env::args().nth(1).as_deref()!=Some("--mcrf-config-stdin")||std::env::args().count()!=2{return Err("CSQTT_ERROR|Private startup input is required");}
 let mut first=String::new();let n=io::stdin().lock().take(32769).read_line(&mut first).map_err(|_|"CSQTT_ERROR|Private startup unavailable")?;
 if n==0||n>32768||!first.ends_with('\n'){return Err("CSQTT_ERROR|Invalid private startup size");}
 let v:Startup=serde_json::from_str(&first).map_err(|_|"CSQTT_ERROR|Invalid private startup configuration")?;
 let listen:std::net::SocketAddr=v.listen.parse().map_err(|_|"CSQTT_ERROR|Invalid private listener")?;
 if !listen.ip().is_loopback()||!listen.is_ipv4()||listen.port()==0||v.password.is_empty()||v.password.len()>4096||v.hashes.is_empty()||v.hashes.len()>6{return Err("CSQTT_ERROR|Invalid private endpoint or credentials");}
 let workers=std::env::var("MCRF_CSQTT_WORKERS").unwrap_or_else(|_|"18".into());let n:usize=workers.parse().map_err(|_|"CSQTT_ERROR|Invalid private worker count")?;
 if n<9||n>126||n%9!=0||n>v.hashes.len()*27{return Err("CSQTT_ERROR|Invalid private worker count");}
 let raw_captcha=std::env::var("MCRF_CSQTT_CAPTCHA_MODE").unwrap_or_else(|_|"auto".into());let captcha=if raw_captcha=="manual" {"wv".to_string()}else{raw_captcha};if captcha!="auto"&&captcha!="wv"&&captcha!="rjs"{return Err("CSQTT_ERROR|Invalid captcha mode");}
 let generation=v.generation.to_string();let hashes=v.hashes.join(",");
 if v.options.len()>40||v.options.values().any(|v|v.len()>16384||v.contains('\0')){return Err("MCRF_TURN_OPTIONS_V1|Invalid options");}
 let mut args:Vec<String>=["mcrf-csqtt-legacy","--peer",&v.peer,"--listen",&v.listen,"--password",&v.password,"--vk",&hashes,"--device-id",&v.device_id,"--gen",&generation,"--salt",&v.salt,"--workers",&workers,"--captcha-mode",&captcha].iter().map(|v|v.to_string()).collect();
 for (key,flag) in [("obfs","--obfs"),("fingerprint","--fingerprint"),("vk_auth_mode","--vk-auth-mode")] {if let Some(value)=v.options.get(key){args.push(flag.into());args.push(value.clone());}}
 if v.options.get("turn_transport").map(String::as_str)==Some("tcp"){args.push("--turn-transport".into());args.push("tcp".into());}
 let parsed=super::Arguments::try_parse_from(args).map_err(|_|"CSQTT_ERROR|Invalid private transport settings")?;
 if std::env::var("MCRF_VALIDATE_ONLY").as_deref()==Ok("1"){println!("MCRF_CONFIG_PARSED|{}",serde_json::json!({"obfs":parsed.obfs,"fingerprint":parsed.fingerprint,"captcha":parsed.captcha_mode,"auth":parsed.vk_auth_mode,"workers":parsed.workers,"turn_transport":v.options.get("turn_transport").map(String::as_str).unwrap_or("udp")}));std::process::exit(0);}
 Ok(parsed)
}
