//! One policy state per owned native process, shared by TCP and UDP.
use std::{io::Write,sync::{Arc,LazyLock,Mutex}};
use mcrf_bs_routing_policy::server_routing::{State,Mode};

static STATE:LazyLock<Arc<State>>=LazyLock::new(||Arc::new(State::with_mode(
 Mode::parse(&std::env::var("MCRF_BS_SERVER_ROUTING").unwrap_or_else(|_|"mcrf".to_owned())).expect("validated server routing mode"))));
static LAST:Mutex<Option<Vec<u8>>>=Mutex::new(None);
static OBSERVE:LazyLock<bool>=LazyLock::new(||std::env::var("MCRF_BS_OBSERVE_ROUTES").as_deref()==Ok("1"));
pub fn shared()->Arc<State>{STATE.clone()}
/// Actual decisions, not inferred routes. Main revalidates Android UID ownership
/// before using this optional metadata for a warning. No payload is inspected here.
pub fn observed(generation:u64,protocol:u8,source:std::net::SocketAddr,destination:std::net::SocketAddr,
                uid:u32,domain:Option<&str>,target:&mcrf_bs_routing_policy::Target){
 if !*OBSERVE{return}
 let target=match target{mcrf_bs_routing_policy::Target::Direct=>"direct",mcrf_bs_routing_policy::Target::Tunnel|mcrf_bs_routing_policy::Target::Egress(_)=>"proxy",_=>return};
 if domain.is_some_and(|v|v.len()>253){return}
 let data=serde_json::json!({"contract":"BS_NATIVE_FLOW_OBSERVATION_CONTRACT_1","generation":generation,"protocol":protocol,"source":source.ip().to_string(),"source_port":source.port(),"destination":destination.ip().to_string(),"destination_port":destination.port(),"uid":uid,"domain":domain,"target":target});
 let line=format!("__CSQTT_EVENT__|FLOW_ROUTED|{data}\n");
 // A closing observation pipe does not change a validated network decision.
 let _=std::io::stdout().lock().write_all(line.as_bytes());
}

pub fn announce(routing:&str)->Result<(),()>{
 let bytes=routing.as_bytes();
 // Order policy publication and its acknowledgement across transport workers.
 let mut last=LAST.lock().map_err(|_|())?;
 if last.as_deref()==Some(bytes){return Ok(())}
 let revision=STATE.update(bytes,crate::sni::TEMPLATE_DOMAINS_RAW).map_err(|_|())?;
 let payload=serde_json::json!({"routing":routing,"applied":STATE.enabled(),"revision":revision,"mode":STATE.mode().token(),"contract":"BS_SERVER_ROUTING_CONTRACT_1"});
 let mut line=format!("__CSQTT_EVENT__|ROUTING|{}",serde_json::to_string(&payload).map_err(|_|())?);
 line.push('\n');
 // Protocol data is not a diagnostic log line. The stdout lock prevents
 // interleaving; its complete bounded payload bypasses the 4096-byte logger.
 let mut output=std::io::stdout().lock();
 output.write_all(line.as_bytes()).and_then(|_|output.flush()).map_err(|_|())?;
 *last=Some(bytes.to_vec());Ok(())
}
