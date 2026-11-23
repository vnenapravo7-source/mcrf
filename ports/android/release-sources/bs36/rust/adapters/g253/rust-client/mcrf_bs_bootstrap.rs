//! One owned original-client startup. The authenticated carrier configuration
//! and Android's VPN network must both arrive before the first TUN packet.
use std::{collections::HashMap,net::IpAddr,sync::{Arc,Mutex,OnceLock}};
use anyhow::{Result,bail};
use base64::Engine as _;
use crate::{dispatcher::Dispatcher,mcrf_bs_dns_handler::Underlay};
const SHA:&str="MCRF_BS_ROUTING_SESSION_SHA256";
struct Network{vpn:u64,v4:bool,v6:bool,underlay:Underlay}
struct State{assigned:Option<IpAddr>,network:Option<Network>,started:bool,session:Option<std::sync::Weak<crate::mcrf_bs_session::Session>>}
struct Health{last:u64,closed:bool,tasks:tokio::task::JoinSet<()>,slots:Arc<tokio::sync::Semaphore>}
struct Bootstrap{health:Mutex<Health>,health_cancel:tokio_util::sync::CancellationToken,handle:tokio::runtime::Handle,generation:u64,document:Vec<u8>,physical:u64,owner:Arc<crate::mcrf_bs_owner::Broker>,state:Mutex<State>,router_only:bool}
static ACTIVE:OnceLock<Bootstrap>=OnceLock::new();
// Pure admission uses the SAME full session constructor before Main retires a
// live VPN. No configure(), TUN, listener, owner query or upstream is permitted.
struct AdmissionIo(std::sync::atomic::AtomicUsize);
impl crate::mcrf_bs_dns_transport::SocketPolicy for AdmissionIo{fn prepare(&self,_:u8,_:i32,_:u16,_:std::net::SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->std::result::Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,std::sync::atomic::Ordering::SeqCst);Err(crate::mcrf_bs_dns_transport::Failure::Invalid)}}
impl crate::mcrf_bs_udp_dispatch::Reply for AdmissionIo{fn write<'a>(&'a self,_:std::net::SocketAddr,_:std::net::SocketAddr,_:&'a [u8],_:&'a tokio_util::sync::CancellationToken)->crate::mcrf_bs_udp_dispatch::Task<'a,()>{self.0.fetch_add(1,std::sync::atomic::Ordering::SeqCst);Box::pin(async{Err(crate::mcrf_bs_udp_dispatch::Failure::Reply)})}}
fn admission(document:&[u8],system_hosts:HashMap<String,Vec<IpAddr>>,proxy_only:bool)->Result<()>{
 let io=Arc::new(AdmissionIo(std::sync::atomic::AtomicUsize::new(0)));let calls=io.clone();
 let owner=Arc::new(crate::mcrf_bs_owner::Broker::new(1,Arc::new(move|_|{calls.0.fetch_add(1,std::sync::atomic::Ordering::SeqCst);Err(crate::mcrf_bs_owner::Failure::Closed)})).map_err(|_|anyhow::anyhow!("BS_NATIVE_ADMISSION_OWNER"))?);
 let dependencies=crate::mcrf_bs_session::Dependencies{generation:1,capacity:8192,owner,physical:1,hosts:system_hosts,excluded:Vec::new(),sockets:io.clone(),underlay:Underlay{kind:"other".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:io.clone()}),reply:io.clone()};
 let session=crate::mcrf_bs_session::Session::prepare(document,dependencies,&tokio_util::sync::CancellationToken::new()).map_err(|_|anyhow::anyhow!("BS_NATIVE_ADMISSION_DOCUMENT"))?;
 if proxy_only{
  validate_proxy_only(document)?;
  if session.tun.is_some()||session.needs_carrier(){bail!("BS_NATIVE_PROXY_POLICY")}
 }else if session.tun.is_none(){bail!("BS_NATIVE_ADMISSION_TUN_REQUIRED")}
 drop(session);
 if io.0.load(std::sync::atomic::Ordering::SeqCst)!=0{bail!("BS_NATIVE_ADMISSION_SIDE_EFFECT")}Ok(())
}
fn admission_parent(text:&str,actual:u32)->Result<i32>{let value=crate::mcrf_direct_tun::parse_token(text)?;if value>i32::MAX as u64||value!=u64::from(actual){bail!("BS_NATIVE_ADMISSION_PARENT")}Ok(value as i32)}
fn guard_parent(variable:&str)->Result<()>{
 #[cfg(any(target_os="android",target_os="linux"))]{
  let expected=admission_parent(&std::env::var(variable)?,unsafe{libc::getppid()}as u32)?;
  if unsafe{libc::prctl(libc::PR_SET_PDEATHSIG,libc::SIGKILL,0,0,0)}!=0||unsafe{libc::getppid()}!=expected{bail!("BS_NATIVE_ADMISSION_PARENT_GUARD")}Ok(())
 }
 #[cfg(not(any(target_os="android",target_os="linux")))]{let _=variable;bail!("BS_NATIVE_ADMISSION_PLATFORM")}
}
pub fn admit_only()->Result<bool>{
 admit(false)
}
fn admit(proxy_only:bool)->Result<bool>{
 let Some(flag)=std::env::var_os("MCRF_BS_ROUTING_ADMIT_ONLY")else{return Ok(false)};
 if flag!="1"||std::env::var_os("MCRF_DIRECT_TUN_GENERATION").is_some()||std::env::var_os("MCRF_BS_OWNER_CHANNEL").is_some()||std::env::var_os("MCRF_BS_ROUTER_ONLY").is_some(){bail!("BS_NATIVE_ADMISSION_SCOPE")}
 guard_parent("MCRF_BS_ADMISSION_PARENT_PID")?;
 let digest=std::env::var(SHA)?;let document=crate::mcrf_bs_routing::read_bundle(&digest)?;
 use std::io::Read;let mut text=String::new();std::fs::File::open("/system/etc/hosts")?.take(1024*1024+1).read_to_string(&mut text)?;
 admission(&document,hosts(&text).map_err(|_|anyhow::anyhow!("BS_NATIVE_SYSTEM_HOSTS"))?,proxy_only)?;
 println!("MCRF_BS_ROUTING_ADMITTED {digest}");Ok(true)
}
pub fn required()->bool{ACTIVE.get().is_some()}
pub fn configure()->Result<()>{
 let router_only=match std::env::var_os("MCRF_BS_ROUTER_ONLY"){
  None=>false,Some(flag) if flag=="1"=>true,_=>bail!("BS_NATIVE_ROUTER_MODE"),
 };
 let Some(digest)=std::env::var_os(SHA) else{
  if router_only{bail!("BS_NATIVE_ROUTER_DOCUMENT_REQUIRED")}
  return Ok(())
 };
 if std::env::var_os("MCRF_BS_ROUTING_POLICY_SHA256").is_some(){bail!("BS_NATIVE_POLICY_MODE_CONFLICT")}
 if !crate::mcrf_direct_tun::active(){bail!("BS_NATIVE_OWNED_TUN_REQUIRED")}
 let physical=crate::mcrf_bs_network::physical_network().ok_or_else(||anyhow::anyhow!("BS_NATIVE_PHYSICAL_REQUIRED"))?;
 let generation=crate::mcrf_direct_tun::parse_token(&std::env::var("MCRF_DIRECT_TUN_GENERATION")?)?;
 if std::env::var("MCRF_BS_OWNER_CHANNEL").as_deref()!=Ok("1"){bail!("BS_NATIVE_OWNER_REQUIRED")}
 let document=crate::mcrf_bs_routing::read_bundle(&digest.into_string().map_err(|_|anyhow::anyhow!("BS_NATIVE_DIGEST_INVALID"))?)?;
 let bundle=mcrf_bs_routing_policy::bundle::Bundle::parse(&document).map_err(|_|anyhow::anyhow!("BS_NATIVE_DOCUMENT_INVALID"))?;
 if router_only{
  if bundle.policy.uses_tunnel()||bundle.policy.egress_tags().is_empty(){bail!("BS_NATIVE_ROUTER_REQUIRES_NAMED_EGRESS")}
  guard_parent("MCRF_DIRECT_TUN_PARENT_PID")?;
 }
 let inbounds=bundle.document()["tunInbounds"].as_array().ok_or_else(||anyhow::anyhow!("BS_NATIVE_TUN_REQUIRED"))?;
 if inbounds.len()!=1{bail!("BS_NATIVE_ONE_TUN_REQUIRED")}
 let plan=mcrf_bs_routing_policy::tun::Plan::parse(&inbounds[0]).map_err(|_|anyhow::anyhow!("BS_NATIVE_TUN_INVALID"))?;
 if usize::from(plan.mtu)>crate::packet::PACKET_CAPACITY-crate::packet::PACKET_HEADROOM{bail!("BS_NATIVE_MTU_UNSUPPORTED")}
 let projection=serde_json::to_vec(&plan.projection())?;
 use std::{io::Write,os::unix::fs::OpenOptionsExt};use sha2::Digest;
 let mut file=std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).custom_flags(libc::O_NOFOLLOW|libc::O_CLOEXEC).open("routing-tun-plan.json")?;
 file.write_all(&projection)?;file.sync_all()?;drop(file);
 let plan_digest=hex::encode(sha2::Sha256::digest(&projection));
 let owner=crate::mcrf_bs_owner::configure(generation).map_err(|_|anyhow::anyhow!("BS_NATIVE_OWNER_FAILED"))?;
 crate::mcrf_bs_service::configure(generation,unsafe{libc::getuid()}).map_err(|_|anyhow::anyhow!("BS_NATIVE_SERVICE_FAILED"))?;
 ACTIVE.set(Bootstrap{health:Mutex::new(Health{last:0,closed:false,tasks:tokio::task::JoinSet::new(),slots:Arc::new(tokio::sync::Semaphore::new(4))}),health_cancel:tokio_util::sync::CancellationToken::new(),handle:tokio::runtime::Handle::current(),generation,document,physical,owner,state:Mutex::new(State{assigned:None,network:None,started:false,session:None}),router_only}).map_err(|_|anyhow::anyhow!("BS_NATIVE_ALREADY_CONFIGURED"))?;
 crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_NATIVE_PLAN {generation} {plan_digest}")).map_err(|_|anyhow::anyhow!("BS_NATIVE_PLAN_CHANNEL"))?;Ok(())
}
pub fn carrier_config(config:&str)->Result<()>{
 let Some(active)=ACTIVE.get() else{return Ok(())};
 if active.router_only{bail!("BS_NATIVE_ROUTER_CARRIER_CONFLICT")}
 let value=config.strip_prefix("TUNCONF:").and_then(|v|v.split(':').next()).ok_or_else(||anyhow::anyhow!("BS_NATIVE_CARRIER_CONFIG"))?;
 let ip=value.parse::<IpAddr>().map_err(|_|anyhow::anyhow!("BS_NATIVE_CARRIER_ADDRESS"))?;
 if !ip.is_ipv4()||ip.is_unspecified()||ip.is_loopback()||ip.is_multicast(){bail!("BS_NATIVE_CARRIER_ADDRESS")}
 let mut state=active.state.lock().map_err(|_|anyhow::anyhow!("BS_NATIVE_STATE"))?;
 if state.assigned.is_some_and(|old|old!=ip){bail!("BS_NATIVE_CARRIER_CHANGED")}
 state.assigned=Some(ip);Ok(())
}
pub async fn run_router_only(tun_uds:&str,has_carrier_arguments:bool)->Result<Option<i32>>{
 let Some(active)=ACTIVE.get().filter(|active|active.router_only) else{return Ok(None)};
 if has_carrier_arguments||tun_uds.is_empty()||std::env::var_os("CSQTT_PASSWORD").is_some(){bail!("BS_NATIVE_ROUTER_CARRIER_CONFLICT")}
 let cancel=tokio_util::sync::CancellationToken::new();
 let stats=Arc::new(crate::stats::Stats::default());
 let (dispatcher,_)=Dispatcher::start_with_domain_routing("127.0.0.1:0",Some(tun_uds.to_owned()),
  crate::packet::PacketPool::new(4096),stats.clone(),cancel.clone(),
  crate::dispatcher::DomainRoutingConfig::default(),1).await?;
 let stats_task=tokio::spawn(stats.run(crate::events::Events::from_env(),cancel.clone()));
 let mut stdin=crate::spawn_stdin_reader();
 let result=async{
  crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_ROUTER_LISTENING {}",active.generation)).map_err(|_|anyhow::anyhow!("BS_NATIVE_ROUTER_OUTPUT"))?;
  loop{
   let line=tokio::select!{biased;_=cancel.cancelled()=>break,value=stdin.recv()=>match value{
    Some(Ok(line))=>line,None=>break,Some(Err(error))=>return Err(error.into()),
   }};
   if line=="STOP"{break}
   let handled=control(&line).or(Err(anyhow::anyhow!("BS_NATIVE_ROUTER_CONTROL")))?;
   if handled{continue}
   if crate::mcrf_bs_owner::control_response(&line).or(Err(anyhow::anyhow!("BS_NATIVE_ROUTER_OWNER")))?{continue}
   bail!("BS_NATIVE_ROUTER_CONTROL")
  }
  Ok(())
 }.await;
 cancel.cancel();
 dispatcher.shutdown().await;
 let _=stats_task.await;
 result?;
 if dispatcher.termination().is_some(){bail!("BS_NATIVE_ROUTER_TERMINATED")}
 Ok(Some(0))
}

/// Entrypoint for the app-owned router executable. No protocol adapter is
/// started in this process. Keep the ordinary CSQTT entry and raw path intact.
pub fn native_router_main() {
 let result=|| -> Result<()> {
  let args:Vec<_>=std::env::args().skip(1).collect();
  if args==["--mcrf-router-admit"] {
   if !admit_only()?{bail!("BS_NATIVE_ROUTER_ADMISSION_REQUIRED")}
   return Ok(())
  }
  if args==["--mcrf-router-proxy-admit"] {
   if !admit(true)?{bail!("BS_NATIVE_ROUTER_ADMISSION_REQUIRED")}
   return Ok(())
  }
  if args==["--mcrf-router-proxy"] {
   let runtime=tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build()?;
   let run=runtime.block_on(run_proxy_only());
   runtime.shutdown_timeout(std::time::Duration::from_secs(1));
   return run
  }
  let [flag,tun_uds]=args.as_slice() else{bail!("BS_NATIVE_ROUTER_ARGUMENTS")};
  if flag!="-tun-uds"{bail!("BS_NATIVE_ROUTER_ARGUMENTS")}
  let runtime=tokio::runtime::Builder::new_multi_thread().worker_threads(4).enable_all().build()?;
  let run=runtime.block_on(async {
   crate::mcrf_direct_tun::configure(tun_uds)?;
   crate::mcrf_bs_network::configure(crate::mcrf_direct_tun::active())?;
   configure()?;
   run_router_only(tun_uds,false).await
  });
  runtime.shutdown_timeout(std::time::Duration::from_secs(1));
  if run?!=Some(0){bail!("BS_NATIVE_ROUTER_MODE_REQUIRED")}
  Ok(())
 }();
 if let Err(error)=result{eprintln!("{error:#}");std::process::exit(78)}
}

fn validate_proxy_only(document:&[u8])->Result<()>{
 let bundle=mcrf_bs_routing_policy::bundle::Bundle::parse(document).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_DOCUMENT"))?;
 let root=bundle.document();
 fn app_condition(value:&serde_json::Value)->bool{
  match value {
   serde_json::Value::Object(fields)=>fields.iter().any(|(key,value)|matches!(key.as_str(),
    "package_name"|"package_name_regex"|"process_name"|"process_path"|"process_path_regex"|"user"|"user_id"|"uid")||app_condition(value)),
   serde_json::Value::Array(values)=>values.iter().any(app_condition),
   _=>false,
  }
 }
 if [&root["policy"],&root["dns"]["rules"],&root["controls"]].into_iter().any(app_condition){bail!("BS_NATIVE_PROXY_APP_RULE")}
 if bundle.policy.egress_tags().is_empty()||bundle.policy.uses_tunnel()||
    root["tunInbounds"].as_array().is_none_or(|v|!v.is_empty())||
    root["listeners"].as_array().is_none_or(|v|v.is_empty()){bail!("BS_NATIVE_PROXY_POLICY")}
 Ok(())
}

async fn run_proxy_only()->Result<()>{
 use crate::{mcrf_bs_dns_transport::{NativePolicy,SocketPolicy},mcrf_bs_proxy_listener as proxy};
 struct NoPacketOutput;
 impl crate::mcrf_bs_udp_dispatch::Reply for NoPacketOutput{
  fn write<'a>(&'a self,_:std::net::SocketAddr,_:std::net::SocketAddr,_:&'a[u8],_:&'a tokio_util::sync::CancellationToken)
   ->crate::mcrf_bs_udp_dispatch::Task<'a,()>{
   Box::pin(async{Err(crate::mcrf_bs_udp_dispatch::Failure::Reply)})
  }
 }
 if std::env::var_os("CSQTT_PASSWORD").is_some()||std::env::var_os("MCRF_BS_OWNER_CHANNEL").is_some()||
    std::env::var_os("MCRF_BS_ROUTING_ADMIT_ONLY").is_some(){bail!("BS_NATIVE_PROXY_SCOPE")}
 guard_parent("MCRF_DIRECT_TUN_PARENT_PID")?;
 let generation=crate::mcrf_direct_tun::parse_token(&std::env::var("MCRF_DIRECT_TUN_GENERATION")?)?;
 let physical=number(&std::env::var("MCRF_BS_UNDERLAY_NETWORK")?).map_err(|_|anyhow::anyhow!("BS_NATIVE_PHYSICAL_REQUIRED"))?;
 let digest=std::env::var(SHA)?;let document=crate::mcrf_bs_routing::read_bundle(&digest)?;
 validate_proxy_only(&document)?;
 let context:serde_json::Value=serde_json::from_str(&std::env::var("MCRF_BS_PROXY_NETWORK")?)?;
 let underlay=underlay(&context).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_NETWORK"))?;
 crate::mcrf_bs_network::bind_network(physical).map_err(|_|anyhow::anyhow!("BS_NATIVE_PHYSICAL_REQUIRED"))?;
 let owner=crate::mcrf_bs_owner::configure_local_proxy(generation,unsafe{libc::getuid()}).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_OWNER"))?;
 let sockets:Arc<dyn SocketPolicy>=Arc::new(NativePolicy::proxy_only(physical).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_BIND"))?);
 use std::io::Read;let mut text=String::new();std::fs::File::open("/system/etc/hosts")?.take(1024*1024+1).read_to_string(&mut text)?;
 let cancel=tokio_util::sync::CancellationToken::new();
 let dependencies=crate::mcrf_bs_session::Dependencies{generation,capacity:512,owner:owner.clone(),physical,
  hosts:hosts(&text).map_err(|_|anyhow::anyhow!("BS_NATIVE_SYSTEM_HOSTS"))?,excluded:Vec::new(),sockets:sockets.clone(),underlay,
  retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets}),reply:Arc::new(NoPacketOutput)};
 let session=Arc::new(crate::mcrf_bs_session::Session::prepare(&document,dependencies,&cancel).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_ADMISSION"))?);
 if session.tun.is_some()||session.needs_carrier(){bail!("BS_NATIVE_PROXY_POLICY")}
 let plans=session.listeners.lock().map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_LISTENERS"))?.take().ok_or_else(||anyhow::anyhow!("BS_NATIVE_PROXY_LISTENERS"))?;
 let group=proxy::Group::start(generation,plans,64,crate::mcrf_bs_proxy_backend::mixed_handler(session.tcp.clone(),session.udp.clone()),
  Arc::new(|event|crate::log_error!("BS_NATIVE_PROXY_EVENT {event:?}")),&cancel).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_LISTENERS"))?;
 ACTIVE.set(Bootstrap{
  health:Mutex::new(Health{last:0,closed:false,tasks:tokio::task::JoinSet::new(),slots:Arc::new(tokio::sync::Semaphore::new(4))}),
  health_cancel:cancel.child_token(),handle:tokio::runtime::Handle::current(),generation,document,physical,owner:owner.clone(),
  state:Mutex::new(State{assigned:None,network:None,started:true,session:Some(Arc::downgrade(&session))}),router_only:true,
 }).map_err(|_|anyhow::anyhow!("BS_NATIVE_ALREADY_CONFIGURED"))?;
 let closed=group.cancellation();let mut stdin=crate::spawn_stdin_reader();
 crate::mcrf_bs_owner::control_output(&format!("MCRF_DIRECT_TUN_IDENTITY {generation} {}",unsafe{libc::getpid()})).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_OUTPUT"))?;
 crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_PROXY_READY {generation}")).map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_OUTPUT"))?;
 let orderly=loop{
  let command=tokio::select!{biased;_=closed.cancelled()=>break false,command=stdin.recv()=>command};
  match command {
   None=>break true,
   Some(Ok(line)) if line=="STOP"=>break true,
   Some(Ok(line)) if line.starts_with("BS_NATIVE_HEALTH|")&&health_control(&line).is_ok()=>{},
   _=>break false,
  }
 };
 cancel.cancel();session.stop();owner.close();
 close_health().await;
 group.close().await.map_err(|_|anyhow::anyhow!("BS_NATIVE_PROXY_STOP"))?;
 if !orderly{bail!("BS_NATIVE_PROXY_TERMINATED")}
 Ok(())
}
fn number(value:&str)->Result<u64,()>{if value.is_empty()||value.starts_with('0')||!value.bytes().all(|v|v.is_ascii_digit()){return Err(())}value.parse::<u64>().ok().filter(|v|*v<=i64::MAX as u64).ok_or(())}
fn network(line:&str,generation:u64,physical:u64)->Result<Network,()>{
 if line.len()>4096||!line.is_ascii(){return Err(())}
 let fields=line.strip_prefix("BS_NATIVE_NETWORK|").ok_or(())?.split('|').collect::<Vec<_>>();
 if fields.len()!=5||number(fields[0])?!=generation{return Err(())}
 let vpn=number(fields[1])?;if vpn==physical{return Err(())}
 let flag=|v:&str|match v{"0"=>Ok(false),"1"=>Ok(true),_=>Err(())};let v4=flag(fields[2])?;let v6=flag(fields[3])?;
 if !v4&&!v6{return Err(())}
 let bytes=base64::engine::general_purpose::STANDARD.decode(fields[4]).map_err(|_|())?;
 if base64::engine::general_purpose::STANDARD.encode(&bytes)!=fields[4]{return Err(())}
 let value:serde_json::Value=serde_json::from_slice(&bytes).map_err(|_|())?;
 Ok(Network{vpn,v4,v6,underlay:underlay(&value)?})
}
fn underlay(value:&serde_json::Value)->Result<Underlay,()>{
 let object=value.as_object().ok_or(())?;
 if object.len()!=5||!object.keys().all(|k|matches!(k.as_str(),"kind"|"expensive"|"constrained"|"ssid"|"bssid")){return Err(())}
 let underlay=Underlay{kind:value["kind"].as_str().ok_or(())?.to_owned(),expensive:value["expensive"].as_bool().ok_or(())?,constrained:value["constrained"].as_bool().ok_or(())?,ssid:value["ssid"].as_str().ok_or(())?.to_owned(),bssid:value["bssid"].as_str().ok_or(())?.to_owned()};
 if !underlay.valid(){return Err(())}Ok(underlay)
}
fn health_control(line:&str)->Result<(),()>{
 let active=ACTIVE.get().ok_or(())?;let command=crate::mcrf_bs_health::command(line,active.generation).map_err(|_|())?;
 let session=active.state.lock().map_err(|_|())?.session.as_ref().and_then(std::sync::Weak::upgrade);
 let mut health=active.health.lock().map_err(|_|())?;if health.closed||command.id<=health.last{return Err(())}health.last=command.id;
 while let Some(result)=health.tasks.try_join_next(){if result.is_err(){crate::log_error!("BS_NATIVE_HEALTH_TASK_FAILED")}}
 let response=move|latency:i64|crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_HEALTH_RESULT {} {} {latency}",active.generation,command.id)).map_err(|_|());
 let Some(session)=session else{return response(-1)};let Ok(permit)=health.slots.clone().try_acquire_owned()else{return response(-1)};
 let cancel=active.health_cancel.child_token();let session_cancel=session.cancellation();
 health.tasks.spawn_on(async move{let latency=tokio::select!{biased;_=cancel.cancelled()=>-1,_=session_cancel.cancelled()=>-1,value=crate::mcrf_bs_health::probe(session,&command.url,command.timeout,&cancel)=>match value{
  Ok(ms)=>ms as i64,
  Err(crate::mcrf_bs_health::Failure::Cancelled)=>-1,
  Err(stage)=>{crate::log_error!("BS_NATIVE_HEALTH_STAGE {stage:?}");-1}
 }};
  drop(permit);if response(latency).is_err()&&!cancel.is_cancelled(){crate::log_error!("BS_NATIVE_HEALTH_OUTPUT_FAILED")}
 },&active.handle);Ok(())
}
pub async fn close_health(){
 let Some(active)=ACTIVE.get()else{return};active.health_cancel.cancel();
 let mut tasks={let mut health=match active.health.lock(){Ok(value)=>value,Err(poisoned)=>{crate::log_error!("BS_NATIVE_HEALTH_STATE_POISONED");poisoned.into_inner()}};health.closed=true;std::mem::replace(&mut health.tasks,tokio::task::JoinSet::new())};
 let deadline=tokio::time::Instant::now()+std::time::Duration::from_secs(2);
 while !tasks.is_empty(){match tokio::time::timeout_at(deadline,tasks.join_next()).await{Ok(Some(Ok(())))=>{},Ok(Some(Err(_)))=>crate::log_error!("BS_NATIVE_HEALTH_TASK_FAILED"),Ok(None)=>break,Err(_)=>{crate::log_error!("BS_NATIVE_HEALTH_STOP_TIMEOUT");tasks.abort_all();while tasks.join_next().await.is_some(){}break}}}
}
pub fn control(line:&str)->Result<bool,()>{
 if line.starts_with("BS_NATIVE_HEALTH|"){health_control(line)?;return Ok(true)}
 if !line.starts_with("BS_NATIVE_NETWORK|"){return Ok(false)}
 let active=ACTIVE.get().ok_or(())?;let network=network(line,active.generation,active.physical)?;
 let mut state=active.state.lock().map_err(|_|())?;if state.started||state.network.is_some(){return Err(())}state.network=Some(network);drop(state);
 crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_NATIVE_NETWORK_READY {}",active.generation)).map_err(|_|())?;Ok(true)
}
fn hosts(text:&str)->Result<HashMap<String,Vec<IpAddr>>,()>{
 if text.len()>1024*1024{return Err(())}let mut result=HashMap::<String,Vec<IpAddr>>::new();
 for line in text.lines(){let mut fields=line.split('#').next().unwrap_or_default().split_whitespace();let Some(address)=fields.next()else{continue};let ip=address.parse::<IpAddr>().map_err(|_|())?;
  for name in fields{let name=name.trim_end_matches('.').to_ascii_lowercase();mcrf_bs_routing_policy::dns_wire::query(0,&name,1).map_err(|_|())?;let values=result.entry(name).or_default();if !values.contains(&ip){if values.len()>=64{return Err(())}values.push(ip)}if result.len()>65536{return Err(())}}
 }Ok(result)
}
fn carrier_families(assigned:IpAddr,v4:bool,v6:bool)->(bool,bool){(v4&&assigned.is_ipv4(),v6&&assigned.is_ipv6())}
pub async fn attach(dispatcher:&Arc<Dispatcher>,cancel:&tokio_util::sync::CancellationToken)->Result<()>{
 let Some(active)=ACTIVE.get() else{return Ok(())};
 let(assigned,network)={let mut state=active.state.lock().map_err(|_|anyhow::anyhow!("BS_NATIVE_STATE"))?;
  if state.started{bail!("BS_NATIVE_TUN_REPLACEMENT_REQUIRES_RESTART")}
  let assigned=if active.router_only{None}else{Some(state.assigned.ok_or_else(||anyhow::anyhow!("BS_NATIVE_CARRIER_NOT_READY"))?)};
  let network=state.network.take().ok_or_else(||anyhow::anyhow!("BS_NATIVE_ANDROID_NETWORK_NOT_READY"))?;state.started=true;(assigned,network)};
 let(v4,v6)=assigned.map_or((network.v4,network.v6),|ip|carrier_families(ip,network.v4,network.v6));
 let sockets:Arc<dyn crate::mcrf_bs_dns_transport::SocketPolicy>=Arc::new(crate::mcrf_bs_dns_transport::NativePolicy::new(active.physical,network.vpn,v4,v6).map_err(|_|anyhow::anyhow!("BS_NATIVE_SOCKET_POLICY"))?);
 // Read only the fixed Android OS hosts file; never silently drop its entries.
 use std::io::Read;let mut contents=String::new();std::fs::File::open("/system/etc/hosts")?.take(1024*1024+1).read_to_string(&mut contents)?;
 let hosts=hosts(&contents).map_err(|_|anyhow::anyhow!("BS_NATIVE_SYSTEM_HOSTS"))?;
 let assigned=assigned.into_iter().collect::<Vec<_>>();
 let dependencies=crate::mcrf_bs_session::Dependencies{generation:active.generation,capacity:8192,owner:active.owner.clone(),physical:active.physical,hosts,excluded:assigned.clone(),sockets:sockets.clone(),underlay:network.underlay,
  retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets}),reply:Arc::new(crate::mcrf_bs_dispatch_runtime::Reply::new(dispatcher))};
 let session=Arc::new(crate::mcrf_bs_session::Session::prepare(&active.document,dependencies,cancel).map_err(|_|anyhow::anyhow!("BS_NATIVE_SESSION_INVALID"))?);
 crate::mcrf_bs_dispatch_runtime::Runtime::start(session.clone(),&assigned,dispatcher).await.map_err(|_|anyhow::anyhow!("BS_NATIVE_ATTACH_FAILED"))?;
 active.state.lock().map_err(|_|anyhow::anyhow!("BS_NATIVE_STATE"))?.session=Some(Arc::downgrade(&session));
 crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_NATIVE_READY {}",active.generation)).map_err(|_|anyhow::anyhow!("BS_NATIVE_READY_CHANNEL"))?;Ok(())
}
pub fn stop(){if let Some(active)=ACTIVE.get(){active.health_cancel.cancel();active.owner.close()}}
#[cfg(test)]mod tests{
 use super::*;
 fn line()->String{format!("BS_NATIVE_NETWORK|42|200|1|0|{}",base64::engine::general_purpose::STANDARD.encode(br#"{"kind":"wifi","expensive":false,"constrained":false,"ssid":"owned","bssid":""}"#))}
 #[test]fn admission_parent_is_canonical_and_exact_before_arming_death_signal(){assert_eq!(admission_parent("42",42).unwrap(),42);for text in ["0","042","41","-42","2147483648"]{assert!(admission_parent(text,42).is_err());}}
 #[test]fn pure_admission_checks_full_dns_controls_listeners_and_tun_without_io(){
  let valid=serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":[]},"dns":{"servers":[{"type":"hosts","tag":"h"}],"final":"h"},"controls":[],"routeOptions":{},"tunInbounds":[{"type":"tun","address":["10.89.0.2/32"],"mtu":1500}],"dnsDialers":{},"listeners":[{"type":"mixed","tag":"p","listen":"127.0.0.1","listen_port":10808}]});
  assert!(admission(&serde_json::to_vec(&valid).unwrap(),HashMap::new(),false).is_ok());
  for index in 0..4{let mut bad=valid.clone();match index{0=>bad["controls"]=serde_json::json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":["unsupported"]}}]),1=>bad["dns"]["servers"][0]["type"]=serde_json::json!("unsupported"),2=>bad["listeners"][0]["listen"]=serde_json::json!("0.0.0.0"),_=>bad["tunInbounds"][0]["mtu"]=serde_json::json!(2048)};assert!(admission(&serde_json::to_vec(&bad).unwrap(),HashMap::new(),false).is_err());}
 }
 #[test]fn proxy_only_admits_named_loopback_and_denies_application_rules_and_raw_tun(){
  let tag=format!("mcrf-route-{}","a".repeat(64));
  let valid=serde_json::json!({
   "schema":1,"policy":{"schema":2,"egressTags":[tag],"defaultTarget":tag,"rules":[]},"dns":{"servers":[{"type":"hosts","tag":"h"}],"final":"h"},
   "dnsDialers":{},"controls":[],"routeOptions":{},"tunInbounds":[],
   "listeners":[{"type":"mixed","tag":"p","listen":"127.0.0.1","listen_port":10808}],
   "egressBindings":[{"tag":tag,"port":10809,"username":"owned-user-fixture","password":"owned-pass-fixture"}]
  });
  let verify=|value:&serde_json::Value|admission(&serde_json::to_vec(value).unwrap(),HashMap::new(),true);
  assert!(verify(&valid).is_ok());
  for test in 0..4{
   let mut invalid=valid.clone();
   match test {
    0=>invalid["policy"]["rules"]=serde_json::json!([{"match":{"package_name":["org.example.test"]},"target":"block"}]),
    1=>invalid["dns"]["rules"]=serde_json::json!([{"package_name":["org.example.test"],"action":"route","server":"h"}]),
    2=>invalid["policy"]["defaultTarget"]=serde_json::json!("proxy"),
    _=>invalid["tunInbounds"]=serde_json::json!([{"type":"tun","address":["10.89.0.2/32"],"mtu":1500}]),
   }
   assert!(verify(&invalid).is_err());
  }
 }
 #[test]fn android_network_is_exact_generation_and_strict_context(){let line=line();let value=network(&line,42,100).unwrap();assert_eq!(value.vpn,200);assert!(value.v4);assert!(!value.v6);assert_eq!(value.underlay.ssid,"owned");
  for bad in [line.replace("|42|","|41|"),line.replace("|200|","|100|"),line.replace("|200|","|0200|"),line.replace("|1|0|","|0|0|"),format!("{line}|extra"),format!("{line}\n")]{assert!(network(&bad,42,100).is_err())}
 }
 #[test]fn tunnel_families_require_both_android_routes_and_authenticated_carrier_address(){
  let v4="192.0.2.7".parse().unwrap();let v6="2001:db8::7".parse().unwrap();
  assert_eq!(carrier_families(v4,true,true),(true,false));assert_eq!(carrier_families(v6,true,true),(false,true));assert_eq!(carrier_families(v4,false,true),(false,false));assert_eq!(carrier_families(v6,true,false),(false,false));
 }
 #[test]fn os_hosts_preserve_aliases_families_comments_and_bounds(){let parsed=hosts("127.0.0.1 localhost LOCALHOST. # same\n::1 localhost ip6-localhost\n192.0.2.9 Owned.Invalid\n").unwrap();assert_eq!(parsed["localhost"].len(),2);assert_eq!(parsed["owned.invalid"],["192.0.2.9".parse::<IpAddr>().unwrap()]);assert!(hosts("bogus owned.invalid").is_err());assert!(hosts("127.0.0.1 bad/name").is_err());assert!(hosts(&" ".repeat(1024*1024+1)).is_err())}
}
