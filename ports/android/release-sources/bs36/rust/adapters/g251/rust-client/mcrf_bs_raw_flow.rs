//! Pre-handshake raw TCP admission. A lease owns the original app epoch and
//! FakeIP name; it opens no TCP connection and never accepts a smoltcp stream.
use std::{collections::{HashSet,HashMap},net::SocketAddr,sync::{Arc,Weak,Mutex,atomic::{AtomicBool,Ordering}}};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{associations::Tracker,packet::PacketInfo,raw_tcp::Authorization,Target};
use crate::{mcrf_bs_owner::Broker,mcrf_bs_stream_slots::Clock,mcrf_bs_dns_engine::Engine,mcrf_bs_tcp_policy::{PolicyRouter,RawDecision},mcrf_bs_tcp_dispatch::owned::{Original,Retire}};
use mcrf_bs_routing_policy::tcp_lifetime::{Frame,Lifetime,Direction,Signal};
type Key=(SocketAddr,SocketAddr);
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Packet,Busy,Capacity,State,Owner,Policy,Identity,Cancelled}
pub enum Admission{Tunnel(Arc<Lease>),Advanced,Direct,Block}
struct Slots{capacity:usize,active:Mutex<HashSet<Key>>,published:Mutex<HashMap<u64,Weak<Lease>>>}
struct Reservation{slots:Arc<Slots>,key:Key}
impl Drop for Reservation{fn drop(&mut self){if let Ok(mut active)=self.slots.active.lock(){active.remove(&self.key);}}}
struct Cleanup{tracker:Arc<Mutex<Tracker>>,generation:u64,clock:Clock,epoch:u64,engine:Arc<Engine>,named:bool,retire:Retire,cancel:CancellationToken,slots:Arc<Slots>}
impl Drop for Cleanup{
 fn drop(&mut self){
  let retired=(||{let mut state=self.tracker.lock().map_err(|_|())?;state.retire(self.generation,(self.clock)()?,self.epoch).map_err(|_|())})();
  let released=if self.named{self.engine.release_flow_name(self.epoch).map_err(|_|())}else{Ok(())};
  let metadata=(self.retire)(&[self.epoch]);
  let removed=self.slots.published.lock().map(|mut entries|entries.remove(&self.epoch));
  if retired.is_err()||released.is_err()||metadata.is_err()||removed.is_err(){self.cancel.cancel()}
 }
}

#[cfg(test)]mod tests{
 use super::*;use serde_json::json;use std::sync::atomic::{AtomicU64,AtomicUsize,Ordering};use base64::Engine as _;
 mod packets{include!("mcrf-routing-policy/tests/support/mod.rs");}
 struct NoSockets;
 impl crate::mcrf_bs_dns_transport::SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{panic!("raw admission must not open a TCP connector or DNS socket for numeric destination")}}
 struct Fixture{factory:Arc<Factory>,requests:tokio::sync::mpsc::UnboundedReceiver<String>,tracker:Arc<Mutex<Tracker>>,clock:Arc<AtomicU64>,retired:Arc<AtomicUsize>}
 fn setup(controls:serde_json::Value)->Fixture{
  let document=json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[]},"controls":controls,"dns":null,"routeOptions":{},"tunInbounds":[],"dnsDialers":{}});
  let engine=Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h"}],"final":"h"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Arc::new(NoSockets);
  let router=Arc::new(PolicyRouter::new(mcrf_bs_routing_policy::bundle::Bundle::parse(&serde_json::to_vec(&document).unwrap()).unwrap(),engine.clone(),sockets,crate::mcrf_bs_dns_handler::Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()}).unwrap());
  let(tx,requests)=tokio::sync::mpsc::unbounded_channel();let owner=Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let tracker=Arc::new(Mutex::new(Tracker::new(42,1).unwrap()));let clock=Arc::new(AtomicU64::new(1));let time=clock.clone();let retired=Arc::new(AtomicUsize::new(0));let count=retired.clone();
  let factory=Arc::new(Factory::new(42,tracker.clone(),Arc::new(move||Ok(time.load(Ordering::SeqCst))),owner,engine,router,1,Arc::new(move|ids|{count.fetch_add(ids.len(),Ordering::SeqCst);Ok(())}),CancellationToken::new()).unwrap());Fixture{factory,requests,tracker,clock,retired}
 }
 fn packet()->Vec<u8>{let mut bytes=packets::packet(false,true);bytes[33]=2;bytes[36..38].fill(0);let checksum=packets::checksum(&packets::pseudo(&bytes,20));bytes[36..38].copy_from_slice(&checksum.to_be_bytes());bytes}
 fn reply(factory:&Factory,line:&str){let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(fields[3],"6");let packages=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);factory.owner.respond(&format!("BS_OWNER|42|{}|10001|{packages}",fields[2])).unwrap();}
 #[tokio::test]async fn authenticated_raw_lease_survives_idle_and_last_reference_retires_before_reuse(){
  let mut f=setup(json!([]));let bytes=packet();let factory=f.factory.clone();let first=bytes.clone();let task=tokio::spawn(async move{factory.admit(&first).await});let request=f.requests.recv().await.unwrap();assert!(matches!(f.factory.admit(&bytes).await,Err(Failure::Busy)));reply(&f.factory,&request);
  let lease=match task.await.unwrap().unwrap(){Admission::Tunnel(lease)=>lease,_=>panic!("numeric tunnel must remain raw")};assert!(lease.proof.current(&f.tracker.lock().unwrap(),1).unwrap());f.clock.store(300_001,Ordering::SeqCst);assert!(lease.proof.current(&f.tracker.lock().unwrap(),300_001).unwrap());
  let callback=lease.clone();drop(lease);assert!(f.tracker.lock().unwrap().is_full());drop(callback);assert!(!f.tracker.lock().unwrap().is_full());assert!(f.factory.slots.active.lock().unwrap().is_empty());assert_eq!(f.retired.load(Ordering::SeqCst),1);assert!(f.requests.try_recv().is_err());
 }
 #[tokio::test]async fn pending_abort_releases_metadata_and_tuple_slot_without_granting_a_route(){
  let mut f=setup(json!([]));let factory=f.factory.clone();let bytes=packet();let pending=tokio::spawn(async move{factory.admit(&bytes).await});f.requests.recv().await.unwrap();pending.abort();assert!(matches!(pending.await,Err(error)if error.is_cancelled()));assert!(!f.tracker.lock().unwrap().is_full());assert!(f.factory.slots.active.lock().unwrap().is_empty());assert_eq!(f.retired.load(Ordering::SeqCst),1);
 }
 #[tokio::test]async fn observable_sniff_uses_advanced_executor_without_leaving_a_raw_grant(){
  let mut f=setup(json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}]));let factory=f.factory.clone();let task=tokio::spawn(async move{factory.admit(&packet()).await});reply(&f.factory,&f.requests.recv().await.unwrap());assert!(matches!(task.await.unwrap(),Ok(Admission::Advanced)));assert!(!f.tracker.lock().unwrap().is_full());assert!(f.factory.slots.active.lock().unwrap().is_empty());
 }
}
/// Field order matters: revoke metadata before releasing the tuple reservation.
pub struct Lease{cleanup:Cleanup,_reservation:Reservation,pub proof:Authorization,owner:crate::mcrf_bs_owner::Owner,broker:Arc<Broker>,passive:Mutex<(Lifetime,Option<crate::mcrf_bs_perf::TcpCounters>)>,closed:AtomicBool,probe:AtomicBool,changed:tokio::sync::Notify}
#[derive(Debug,PartialEq,Eq)]pub enum Probe{Live,Retired,Unavailable}
#[derive(Debug,PartialEq,Eq)]pub enum Wake{Closed,Probe}
impl Lease{
 pub fn observe(&self,direction:Direction,frame:Frame)->Result<Signal,Failure>{
  let signal={
   let mut state=self.passive.lock().map_err(|_|Failure::State)?;
   let signal=state.0.observe(direction,frame);
   if signal==Signal::Continue{if let Some(traffic)=state.1.as_mut(){traffic.observe(direction,frame)}}
   signal
  };
  match signal{Signal::Closed|Signal::AppReset=>{self.closed.store(true,Ordering::Release);self.changed.notify_one();},Signal::PeerResetProbe=>{self.probe.store(true,Ordering::Release);self.changed.notify_one();},_=>{}}Ok(signal)
 }
 pub async fn wait_event(&self)->Wake{loop{let changed=self.changed.notified();if self.closed.load(Ordering::Acquire){return Wake::Closed}if self.probe.swap(false,Ordering::AcqRel){return Wake::Probe}changed.await}}
 /// Probe QUIET flows only. TCP packets on an existing epoch never incur a
 /// per-packet broker round trip. A transient query failure grants no new flow
 /// and must not terminate an otherwise live connection.
 pub async fn probe(&self)->Result<Probe,Failure>{
  if self.cleanup.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let(source,destination)=self.proof.tuple();
  let owner=match self.broker.query(6,source,destination,&self.cleanup.cancel).await{
   Ok(owner)=>owner,Err(crate::mcrf_bs_owner::Failure::Denied)=>return Ok(Probe::Retired),Err(crate::mcrf_bs_owner::Failure::Cancelled)=>return Err(Failure::Cancelled),
   Err(crate::mcrf_bs_owner::Failure::Timeout|crate::mcrf_bs_owner::Failure::Busy)=>return Ok(Probe::Unavailable),Err(_)=>return Err(Failure::State),
  };
  let tracker=self.cleanup.tracker.lock().map_err(|_|Failure::State)?;
  let now=(self.cleanup.clock)().map_err(|_|Failure::State)?;
  let current=self.proof.current(&tracker,now).map_err(|_|Failure::State)?;
  let packages=owner.packages.len()==self.owner.packages.len()&&owner.packages.iter().all(|p|self.owner.packages.contains(p));
  Ok(if current&&owner.uid==self.owner.uid&&packages{Probe::Live}else{Probe::Retired})
 }
}
pub struct Factory{generation:u64,tracker:Arc<Mutex<Tracker>>,clock:Clock,owner:Arc<Broker>,engine:Arc<Engine>,router:Arc<PolicyRouter>,slots:Arc<Slots>,retire:Retire,cancel:CancellationToken}
impl Factory{
 pub fn new(generation:u64,tracker:Arc<Mutex<Tracker>>,clock:Clock,owner:Arc<Broker>,engine:Arc<Engine>,router:Arc<PolicyRouter>,capacity:usize,retire:Retire,cancel:CancellationToken)->Result<Self,Failure>{
  if !(1..=8192).contains(&capacity)||generation!=owner.generation()||generation!=engine.generation(){return Err(Failure::State)}
  Ok(Self{generation,tracker,clock,owner,engine,router,slots:Arc::new(Slots{capacity,active:Mutex::new(HashSet::new()),published:Mutex::new(HashMap::new())}),retire,cancel})
 }
 /// Synchronous carrier reply admission: no per-packet oneshot allocation or
 /// round trip through the packet actor. Retain the guard through TUN write.
 pub fn reply(&self,proof:Authorization,frame:Frame)->Result<Option<Arc<Lease>>,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let weak=self.slots.published.lock().map_err(|_|Failure::State)?.get(&proof.epoch()).cloned();
  let Some(lease)=weak.and_then(|weak|weak.upgrade())else{return Ok(None)};
  if lease.proof!=proof||lease.closed.load(Ordering::Acquire){return Ok(None)}
  {let tracker=self.tracker.lock().map_err(|_|Failure::State)?;if !proof.current(&tracker,(self.clock)().map_err(|_|Failure::State)?).map_err(|_|Failure::State)?{return Ok(None)}}
  lease.observe(Direction::Peer,frame)?;Ok(Some(lease))
 }
 /// Only initial SYNs reach this function. The ingress owner keeps a successful
 /// lease for subsequent packets; a retransmit cannot start another admission.
 pub async fn admit(&self,bytes:&[u8])->Result<Admission,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let packet=PacketInfo::read(bytes).map_err(|_|Failure::Packet)?;
  if packet.protocol!=6||packet.fragment.is_some()||!packet.tcp.is_some_and(|tcp|tcp.flags&0x17==2){return Err(Failure::Packet)}
  let(a,b)=packet.ports.ok_or(Failure::Packet)?;let source=SocketAddr::new(packet.source,a);let destination=SocketAddr::new(packet.destination,b);let key=(source,destination);
  {let mut active=self.slots.active.lock().map_err(|_|Failure::State)?;if active.contains(&key){return Err(Failure::Busy)}if active.len()>=self.slots.capacity{return Err(Failure::Capacity)}active.insert(key);}
  let reservation=Reservation{slots:self.slots.clone(),key};
  let association={let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;tracker.observe(self.generation,(self.clock)().map_err(|_|Failure::State)?,&packet).map_err(|_|Failure::State)?};
  let mut cleanup=Cleanup{tracker:self.tracker.clone(),generation:self.generation,clock:self.clock.clone(),epoch:association.id,engine:self.engine.clone(),named:false,retire:self.retire.clone(),cancel:self.cancel.clone(),slots:self.slots.clone()};
  if !association.retired.is_empty(){(self.retire)(&association.retired).map_err(|_|Failure::State)?}
  let verified=self.owner.query(6,source,destination,&self.cancel).await.map_err(|_|Failure::Owner)?;
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  {let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;let confirmed=tracker.confirm_owner(self.generation,(self.clock)().map_err(|_|Failure::State)?,&packet,cleanup.epoch,verified.uid,verified.packages.clone()).map_err(|_|Failure::Identity)?;
   if confirmed.id!=cleanup.epoch{cleanup.epoch=confirmed.id;drop(tracker);(self.retire)(&confirmed.retired).map_err(|_|Failure::State)?;return Err(Failure::Identity)}
  }
  let domain=self.engine.pin_flow_name(cleanup.epoch,destination.ip()).map_err(|_|Failure::Identity)?;cleanup.named=domain.is_some();
  let observed=if domain.is_none(){self.engine.domain(verified.uid,destination.ip()).map_err(|_|Failure::Identity)?}else{None};
  let decision=self.router.decide_raw(Original{source,destination,destination_domain:domain.as_deref(),observed_domain:observed.as_deref(),uid:verified.uid,packages:&verified.packages},&self.cancel).await.map_err(|_|Failure::Policy)?;
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  match decision{
   RawDecision::Advanced=>Ok(Admission::Advanced),RawDecision::Block=>Ok(Admission::Block),RawDecision::Forward{target:Target::Direct,..}=>Ok(Admission::Direct),
   RawDecision::Forward{target:Target::Block,..}=>Ok(Admission::Block),
   RawDecision::Forward{target:Target::Egress(_),..}=>Ok(Admission::Advanced),
   RawDecision::Forward{peer,target:Target::Tunnel}=>{
    if self.engine.virtual_address(peer.ip()).map_err(|_|Failure::Identity)?{return Err(Failure::Identity)}
    let proof={let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;let now=(self.clock)().map_err(|_|Failure::State)?;let proof=Authorization::admit(&tracker,self.generation,now,&packet,cleanup.epoch,verified.uid,peer).map_err(|_|Failure::Identity)?;tracker.own_raw_tcp(self.generation,now,source,destination,cleanup.epoch).map_err(|_|Failure::Identity)?;proof};
    let syn=Frame::read(bytes).map_err(|_|Failure::Packet)?;
    let passive=(Lifetime::new(syn).map_err(|_|Failure::Packet)?,crate::mcrf_bs_perf::TcpCounters::new(syn));
    let lease=Arc::new(Lease{cleanup,_reservation:reservation,proof,owner:verified,broker:self.owner.clone(),passive:Mutex::new(passive),closed:AtomicBool::new(false),probe:AtomicBool::new(false),changed:tokio::sync::Notify::new()});
    self.slots.published.lock().map_err(|_|Failure::State)?.insert(proof.epoch(),Arc::downgrade(&lease));Ok(Admission::Tunnel(lease))
   }
  }
 }
}
