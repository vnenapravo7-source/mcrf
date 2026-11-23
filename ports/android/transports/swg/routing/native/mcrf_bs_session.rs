//! Shared TCP/UDP session resources. This constructor admits both routers before
//! exposing either one; it does not activate a TUN, carrier or production feature.
use std::{sync::{Arc,Mutex,OnceLock},collections::HashMap,net::IpAddr};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{bundle::Bundle,associations::Tracker};
use crate::{mcrf_bs_owner::Broker,mcrf_bs_dns_engine::Engine,mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_dns_handler::Underlay,mcrf_bs_stream_slots::Clock,mcrf_bs_tcp_dispatch::owned::{Session as Tcp,Retire},mcrf_bs_udp_dispatch::{Session as Udp,Connector,Reply},mcrf_bs_datagram_slots::Slots,mcrf_bs_tcp_policy::PolicyRouter,mcrf_bs_udp_policy::Router};

#[derive(Debug,PartialEq,Eq)]pub enum Failure{Generation,Capacity,Document,Tun,Dns,TcpPolicy,UdpPolicy,Carrier,Cancelled}
pub struct Dependencies{
 pub generation:u64,pub capacity:usize,pub owner:Arc<Broker>,pub physical:u64,
 pub hosts:HashMap<String,Vec<IpAddr>>,pub excluded:Vec<IpAddr>,
 pub sockets:Arc<dyn SocketPolicy>,pub underlay:Underlay,pub retire:Retire,
 pub connector:Arc<dyn Connector>,pub reply:Arc<dyn Reply>,
}
pub struct Session{pub listeners:Mutex<Option<Vec<mcrf_bs_routing_policy::listeners::Plan>>>,pub raw:Arc<PolicyRouter>,
 pub raw_flows:Arc<crate::mcrf_bs_raw_flow::Factory>,
 pub tcp:Arc<Tcp>,pub udp:Arc<Udp>,
 pub tun:Option<mcrf_bs_routing_policy::tun::Plan>,
 pub service:Arc<crate::mcrf_bs_service_ingress::Ingress>,
 carrier:OnceLock<Arc<crate::mcrf_bs_carrier_nat::CarrierNat>>,
 needs_carrier:bool,
 fallback:mcrf_bs_routing_policy::Target,
 capacity:usize,
 cancel:CancellationToken,
}
impl Session{
 pub fn capacity(&self)->usize{self.capacity}
 pub fn needs_carrier(&self)->bool{self.needs_carrier}
 pub fn probe_direction(&self)->Option<crate::mcrf_bs_dns_transport::Direction>{
  use crate::mcrf_bs_dns_transport::Direction;
  if self.needs_carrier||self.carrier.get().is_some(){return Some(Direction::Vpn)}
  if let mcrf_bs_routing_policy::Target::Egress(slot)=self.fallback{Some(Direction::Egress(slot))}else{None}
 }
 /// Read the Main-captured snapshot with the existing digest/ownership/race
 /// checks. Call before attaching packet ingress; this is not TUN admission.
 pub fn prepare_private(expected:&str,deps:Dependencies,parent:&CancellationToken)->Result<Self,Failure>{
  if parent.is_cancelled(){return Err(Failure::Cancelled)}
  let bytes=crate::mcrf_bs_routing::read_bundle(expected).map_err(|_|Failure::Document)?;
  Self::prepare(&bytes,deps,parent)
 }
 pub fn prepare(document:&[u8],mut deps:Dependencies,parent:&CancellationToken)->Result<Self,Failure>{
  if parent.is_cancelled(){return Err(Failure::Cancelled)}
  if deps.generation!=deps.owner.generation(){return Err(Failure::Generation)}
  // A single immutable document is parsed for each consuming router. No router
  // can observe an independently reloaded policy or another profile's DNS.
  let tcp_bundle=Bundle::parse(document).map_err(|_|Failure::Document)?;
  let udp_bundle=Bundle::parse(document).map_err(|_|Failure::Document)?;
  let empty=serde_json::json!({});let root=tcp_bundle.document();
  let listeners=mcrf_bs_routing_policy::listeners::parse(root.get("listeners")).map_err(|_|Failure::Document)?;
  let tun=root["tunInbounds"].as_array().ok_or(Failure::Document)?.first().map(mcrf_bs_routing_policy::tun::Plan::parse).transpose().map_err(|_|Failure::Tun)?;
  if let Some(plan)=&tun{
   if usize::from(plan.mtu)>crate::packet::PACKET_CAPACITY-crate::packet::PACKET_HEADROOM{return Err(Failure::Tun)}
   for address in &plan.addresses{if !deps.excluded.contains(&address.address){deps.excluded.push(address.address)}}
  }
  deps.sockets=crate::mcrf_bs_certificates::wrap(root.get("certificates"),deps.sockets.clone()).map_err(|_|Failure::Dns)?;
  deps.sockets=crate::mcrf_bs_dns_transport::with_egresses(root.get("egressBindings"),deps.sockets).map_err(|_|Failure::Carrier)?;
  if !tcp_bundle.policy.egress_tags().is_empty(){
   deps.connector=Arc::new(crate::mcrf_bs_udp_transport::Named::new(deps.connector,deps.sockets.clone()));
  }
  let dialers=tcp_bundle.runtime_dialers();
  let fallback=tcp_bundle.policy.default_target();
  let needs_carrier=crate::mcrf_bs_server_routing::shared().enabled()||tcp_bundle.policy.uses_tunnel()||root["dns"]["servers"].as_array().is_some_and(|servers|
   servers.iter().filter_map(|server|server["detour"].as_str()).any(|tag|dialers[tag].as_str()==Some("proxy")));
  let engine=Arc::new(Engine::new(deps.generation,deps.physical,&root["dns"],tcp_bundle.sets().unwrap_or(&empty),&dialers,deps.hosts,&deps.excluded).map_err(|_|Failure::Dns)?);
  let tcp_router=Arc::new(PolicyRouter::new(tcp_bundle,engine.clone(),deps.sockets.clone(),deps.underlay.clone()).map_err(|_|Failure::TcpPolicy)?);
  let udp_router=Arc::new(Router::new(udp_bundle,engine.clone(),deps.sockets.clone(),deps.underlay.clone()).map_err(|_|Failure::UdpPolicy)?);
  let tracker=Arc::new(Mutex::new(Tracker::new(deps.generation,deps.capacity).map_err(|_|Failure::Capacity)?));
  let slots=Arc::new(Slots::new(deps.capacity).map_err(|_|Failure::Capacity)?);
  let started=std::time::Instant::now();
  let clock:Clock=Arc::new(move||u64::try_from(started.elapsed().as_millis()).map_err(|_|()));
  let tcp=Arc::new(Tcp{generation:deps.generation,tracker:tracker.clone(),clock:clock.clone(),owner:deps.owner.clone(),engine:engine.clone(),sockets:deps.sockets.clone(),underlay:deps.underlay.clone(),router:tcp_router.clone(),retire:deps.retire.clone()});
  let udp=Arc::new(Udp{generation:deps.generation,slots,tracker,clock,owner:deps.owner,engine,sockets:deps.sockets,underlay:deps.underlay,router:udp_router,retire:deps.retire,connector:deps.connector,reply:deps.reply});
  if parent.is_cancelled(){return Err(Failure::Cancelled)}
  let cancel=parent.child_token();
  let service=Arc::new(crate::mcrf_bs_service_ingress::Ingress::new(deps.generation,tcp.owner.clone(),tcp.tracker.clone(),tcp.clock.clone(),deps.capacity,Arc::new(crate::mcrf_bs_service_ingress::NativePermissions),tcp.retire.clone(),cancel.clone()).map_err(|_|Failure::Carrier)?);
  let raw_flows=Arc::new(crate::mcrf_bs_raw_flow::Factory::new(deps.generation,tcp.tracker.clone(),tcp.clock.clone(),tcp.owner.clone(),tcp.engine.clone(),tcp_router.clone(),deps.capacity,tcp.retire.clone(),cancel.clone()).map_err(|_|Failure::TcpPolicy)?);
  Ok(Self{listeners:Mutex::new(Some(listeners)),raw:tcp_router,raw_flows,tcp,udp,tun,service,carrier:OnceLock::new(),needs_carrier,fallback,capacity:deps.capacity,cancel})
 }
 /// Called once after the authenticated carrier configuration is received and
 /// before packet ingress starts. A reconnect creates a fresh session/generation.
 pub fn attach_carrier(&self,assigned:&[IpAddr])->Result<Arc<crate::mcrf_bs_carrier_nat::CarrierNat>,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}let plan=self.tun.as_ref().ok_or(Failure::Tun)?;
  let locals=plan.addresses.iter().map(|n|n.address).collect::<Vec<_>>();
  let carrier=Arc::new(crate::mcrf_bs_carrier_nat::CarrierNat::new(self.tcp.generation,self.tcp.clock.clone(),self.tcp.tracker.clone(),&locals,assigned,self.capacity,self.cancel.clone()).map_err(|_|Failure::Carrier)?);
  self.carrier.set(carrier.clone()).map_err(|_|Failure::Carrier)?;Ok(carrier)
 }
 /// Every task spawned for this session must inherit this token, including
 /// packet ingress and pending owner requests. Stopping cannot cancel a parent.
 pub fn cancellation(&self)->CancellationToken{self.cancel.child_token()}
 pub fn stop(&self){
  self.cancel.cancel();
  if self.service.stop().is_err(){self.cancel.cancel()}
  if let Some(carrier)=self.carrier.get(){carrier.stop()}
  // Close admission before an old retained Arc can manufacture another epoch.
  if let Ok(mut tracker)=self.tcp.tracker.lock(){tracker.close()}
 }
}
impl Drop for Session{fn drop(&mut self){self.stop()}}

#[cfg(test)]mod tests{
 use super::*;use std::{net::SocketAddr,sync::atomic::{AtomicUsize,Ordering}};
 use mcrf_bs_routing_policy::packet::{PacketInfo,TcpControl};
 struct NoSockets(Arc<AtomicUsize>);
 impl SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(crate::mcrf_bs_dns_transport::Failure::Bind)}}
 struct NoReply;
 impl Reply for NoReply{fn write<'a>(&'a self,_:SocketAddr,_:SocketAddr,_:&'a [u8],_:&'a CancellationToken)->crate::mcrf_bs_udp_dispatch::Task<'a,()>{Box::pin(async{panic!("prepare must not send packets")})}}
 fn dependencies()->(Dependencies,Arc<AtomicUsize>){
  let opened=Arc::new(AtomicUsize::new(0));let sockets=Arc::new(NoSockets(opened.clone()));
  let deps=Dependencies{generation:42,capacity:8,owner:Arc::new(Broker::new(42,Arc::new(|_|panic!("prepare must not query owners"))).unwrap()),physical:1,hosts:HashMap::new(),excluded:Vec::new(),sockets:sockets.clone(),underlay:Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets}),reply:Arc::new(NoReply)};
  (deps,opened)
 }
 fn document()->serde_json::Value{serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":[]},"controls":[],"dns":{"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.9"}}],"final":"h"},"routeOptions":{},"tunInbounds":[],"dnsDialers":{}})}
 fn packet(protocol:u8)->PacketInfo{PacketInfo{source:"10.89.0.2".parse().unwrap(),destination:"192.0.2.9".parse().unwrap(),ports:Some((45000,5353)),protocol,fragment:None,tcp:if protocol==6{Some(TcpControl{sequence:1,flags:2})}else{None}}}
 #[test]fn tcp_udp_share_one_epoch_namespace_clock_owner_and_dns(){
  let(deps,opened)=dependencies();let session=Session::prepare(&serde_json::to_vec(&document()).unwrap(),deps,&CancellationToken::new()).unwrap();
  assert!(Arc::ptr_eq(&session.tcp.tracker,&session.udp.tracker));assert!(Arc::ptr_eq(&session.tcp.clock,&session.udp.clock));assert!(Arc::ptr_eq(&session.tcp.owner,&session.udp.owner));assert!(Arc::ptr_eq(&session.tcp.engine,&session.udp.engine));
  let now=(session.tcp.clock)().unwrap();let tcp=session.tcp.tracker.lock().unwrap().observe(42,now,&packet(6)).unwrap();let udp=session.udp.tracker.lock().unwrap().observe(42,now,&packet(17)).unwrap();assert_ne!(tcp.id,udp.id);assert_eq!(opened.load(Ordering::SeqCst),0);
 }
 #[test]fn stop_cancels_children_closes_retained_admission_and_preserves_parent(){
  let(deps,_)=dependencies();let parent=CancellationToken::new();let session=Session::prepare(&serde_json::to_vec(&document()).unwrap(),deps,&parent).unwrap();let token=session.cancellation();let retained=session.udp.clone();drop(session);
  assert!(token.is_cancelled());assert!(!parent.is_cancelled());assert!(retained.tracker.lock().unwrap().observe(42,(retained.clock)().unwrap(),&packet(17)).is_err());
 }
 #[test]fn invalid_dns_and_policy_fail_before_opening_sockets(){
  for key in ["dns","controls"]{let(deps,opened)=dependencies();let mut doc=document();if key=="dns"{doc["dns"]["optimistic"]=serde_json::json!(true)}else{doc["controls"]=serde_json::json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":["unsupported"]}}])};
   assert!(Session::prepare(&serde_json::to_vec(&doc).unwrap(),deps,&CancellationToken::new()).is_err());assert_eq!(opened.load(Ordering::SeqCst),0);
  }
 }
 #[test]fn generation_capacity_and_precancelled_session_are_rejected(){
  let bytes=serde_json::to_vec(&document()).unwrap();let(deps,_)=dependencies();let parent=CancellationToken::new();parent.cancel();assert!(matches!(Session::prepare(&bytes,deps,&parent),Err(Failure::Cancelled)));
  let(mut deps,_)=dependencies();deps.generation=43;assert!(matches!(Session::prepare(&bytes,deps,&CancellationToken::new()),Err(Failure::Generation)));
  let(mut deps,_)=dependencies();deps.capacity=0;assert!(matches!(Session::prepare(&bytes,deps,&CancellationToken::new()),Err(Failure::Capacity)));
 }
 #[test]fn tun_plan_is_admitted_before_dns_or_socket_side_effects(){
  let mut doc=document();doc["tunInbounds"]=serde_json::json!([{"type":"tun","address":["10.89.0.2/32","fd00::2/128"],"mtu":1500,"route_exclude_address":["10.0.0.0/8"],"include_package":["org.mcrf.client"]}]);
  let(deps,opened)=dependencies();let session=Session::prepare(&serde_json::to_vec(&doc).unwrap(),deps,&CancellationToken::new()).unwrap();let plan=session.tun.as_ref().unwrap();assert!(plan.captures_host("org.mcrf.client"));assert!(!plan.captures("10.1.0.1".parse().unwrap()));assert!(plan.captures("2001:db8::1".parse().unwrap()));assert_eq!(opened.load(Ordering::SeqCst),0);
  let carrier=session.attach_carrier(&["192.0.2.7".parse().unwrap()]).unwrap();assert!(matches!(session.attach_carrier(&["192.0.2.8".parse().unwrap()]),Err(Failure::Carrier)));session.stop();assert!(matches!(session.attach_carrier(&["192.0.2.7".parse().unwrap()]),Err(Failure::Cancelled)));drop(carrier);
  doc["tunInbounds"][0]["gso"]=serde_json::json!(true);let(deps,opened)=dependencies();assert!(matches!(Session::prepare(&serde_json::to_vec(&doc).unwrap(),deps,&CancellationToken::new()),Err(Failure::Tun)));assert_eq!(opened.load(Ordering::SeqCst),0);
 }
 #[test]fn tun_admission_reserves_the_real_packet_pool_headroom(){
  let maximum=crate::packet::PACKET_CAPACITY-crate::packet::PACKET_HEADROOM;
  for(mtu,accepted)in [(maximum,true),(maximum+1,false)]{let mut doc=document();doc["tunInbounds"]=serde_json::json!([{"type":"tun","address":["10.89.0.2/32"],"mtu":mtu}]);let(deps,opened)=dependencies();let result=Session::prepare(&serde_json::to_vec(&doc).unwrap(),deps,&CancellationToken::new());assert_eq!(result.is_ok(),accepted);if !accepted{assert!(matches!(result,Err(Failure::Tun)))}assert_eq!(opened.load(Ordering::SeqCst),0)}
 }
}
