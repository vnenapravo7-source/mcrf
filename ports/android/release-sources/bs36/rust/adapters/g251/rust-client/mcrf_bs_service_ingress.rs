//! Authenticated same-process service ingress. TCP ownership is fixed to its
//! observed SYN epoch; UDP always asks Android again. A scoped ticket must stay
//! live, and a new SYN/lease never inherits the cached authorization.
use std::{sync::{Arc,Mutex},collections::HashMap,net::SocketAddr};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{associations::Tracker,packet::PacketInfo,service_flows::{Key,Authorization,Candidate}};
use crate::{mcrf_bs_owner::Broker,mcrf_bs_stream_slots::Clock,mcrf_bs_tcp_dispatch::owned::Retire};
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Admission,State,Service,Owner,Retirement,Cancelled,Capacity}
#[derive(Debug,PartialEq,Eq)]pub enum Decision{NotService,Drop,Forward(Authorization)}
pub trait Permissions:Send+Sync{
 fn ticket(&self,key:Key)->Result<Option<Candidate>,()>;
 fn authorize(&self,uid:u32,ticket:Candidate)->Result<Option<Authorization>,()>;
 fn current(&self,proof:&Authorization)->Result<bool,()>;
}

#[cfg(test)]mod tests{
 use super::*;use std::sync::atomic::{AtomicU64,Ordering};use mcrf_bs_routing_policy::service_flows::Registry;
 struct Memory{registry:Mutex<Registry>,now:Arc<AtomicU64>}
 impl Permissions for Memory{
  fn ticket(&self,key:Key)->Result<Option<Candidate>,()>{self.registry.lock().unwrap().ticket(42,self.now.load(Ordering::SeqCst),key).map_err(|_|())}
  fn authorize(&self,uid:u32,ticket:Candidate)->Result<Option<Authorization>,()>{let mut registry=self.registry.lock().unwrap();let now=self.now.load(Ordering::SeqCst);if registry.ticket(42,now,ticket.key()).map_err(|_|())?!=Some(ticket){return Ok(None)}Ok(registry.authorize(42,now,Some(uid),ticket.key()).map_err(|_|())?.filter(|p|p.candidate()==ticket))}
  fn current(&self,proof:&Authorization)->Result<bool,()>{self.registry.lock().unwrap().current(42,self.now.load(Ordering::SeqCst),proof).map_err(|_|())}
 }
 struct Fixture{ingress:Arc<Ingress>,permissions:Arc<Memory>,requests:tokio::sync::mpsc::UnboundedReceiver<String>,now:Arc<AtomicU64>,parent:CancellationToken}
 fn fixture(capacity:usize)->Fixture{
  let now=Arc::new(AtomicU64::new(1));let clock=now.clone();let(tx,rx)=tokio::sync::mpsc::unbounded_channel();let owner=Arc::new(Broker::new(42,Arc::new(move|s|tx.send(s.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let permissions=Arc::new(Memory{registry:Mutex::new(Registry::scoped(42,10001).unwrap()),now:now.clone()});let parent=CancellationToken::new();
  let ingress=Arc::new(Ingress::new(42,owner,Arc::new(Mutex::new(Tracker::new(42,capacity).unwrap())),Arc::new(move||Ok(clock.load(Ordering::SeqCst))),capacity,permissions.clone(),Arc::new(|_|Ok(())),parent.child_token()).unwrap());Fixture{ingress,permissions,requests:rx,now,parent}
 }
 fn packet(protocol:u8,sequence:u32,flags:u8)->Vec<u8>{let mut bytes=vec![0u8;if protocol==6{40}else{28}];let length=bytes.len()as u16;bytes[0]=0x45;bytes[2..4].copy_from_slice(&length.to_be_bytes());bytes[8]=64;bytes[9]=protocol;bytes[12..16].copy_from_slice(&[10,89,0,2]);bytes[16..20].copy_from_slice(&[192,0,2,42]);bytes[20..22].copy_from_slice(&41234u16.to_be_bytes());bytes[22..24].copy_from_slice(&443u16.to_be_bytes());if protocol==6{bytes[24..28].copy_from_slice(&sequence.to_be_bytes());bytes[32]=0x50;bytes[33]=flags}else{bytes[24..26].copy_from_slice(&8u16.to_be_bytes())}bytes}
 fn key(protocol:u8)->Key{Key{protocol,local_port:41234,peer:"192.0.2.42:443".parse().unwrap()}}
 fn register(f:&Fixture,protocol:u8,id:u64){f.permissions.registry.lock().unwrap().register_scoped(42,f.now.load(Ordering::SeqCst),id,key(protocol)).unwrap()}
 fn unregister(f:&Fixture,id:u64){f.permissions.registry.lock().unwrap().unregister(42,f.now.load(Ordering::SeqCst),id).unwrap()}
 fn start(f:&Fixture,bytes:Vec<u8>)->tokio::task::JoinHandle<Result<Decision,Failure>>{let ingress=f.ingress.clone();tokio::spawn(async move{ingress.admit(&bytes).await})}
 fn answer(f:&Fixture,line:&str,uid:i32){let id=line.split_whitespace().nth(2).unwrap();f.ingress.owner.respond(&format!("BS_OWNER|42|{id}|{uid}|{}",if uid<0{""}else{"W10="})).unwrap();}
 #[tokio::test]async fn tcp_owner_is_cached_through_idle_and_fin_but_new_syn_is_fresh(){
  let mut f=fixture(8);register(&f,6,1);let first=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();answer(&f,&line,10001);assert!(matches!(first.await.unwrap(),Ok(Decision::Forward(_))));
  assert!(matches!(f.ingress.admit(&packet(6,101,16)).await,Ok(Decision::Forward(_))));f.now.store(300001,Ordering::SeqCst);assert!(matches!(f.ingress.admit(&packet(6,102,17)).await,Ok(Decision::Forward(_))));assert!(f.requests.try_recv().is_err());
  let next=start(&f,packet(6,200,2));let line=f.requests.recv().await.unwrap();answer(&f,&line,-1);assert_eq!(next.await.unwrap(),Ok(Decision::Drop));assert!(f.ingress.cache.lock().unwrap().entries.is_empty());
 }
 #[tokio::test]async fn wrong_uid_is_not_cached_and_closed_scope_drops_without_owner_lookup(){
  let mut f=fixture(8);register(&f,6,1);let denied=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();answer(&f,&line,10002);assert_eq!(denied.await.unwrap(),Ok(Decision::Drop));
  let retry=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();answer(&f,&line,10001);assert!(matches!(retry.await.unwrap(),Ok(Decision::Forward(_))));assert!(matches!(f.ingress.admit(&packet(6,101,16)).await,Ok(Decision::Forward(_))));
  unregister(&f,1);assert_eq!(f.ingress.admit(&packet(6,102,17)).await,Ok(Decision::Drop));assert!(f.requests.try_recv().is_err());
 }
 #[tokio::test]async fn lease_replacement_during_owner_roundtrip_never_authorizes_old_packet(){
  let mut f=fixture(8);register(&f,6,1);let pending=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();unregister(&f,1);register(&f,6,2);answer(&f,&line,10001);assert_eq!(pending.await.unwrap(),Ok(Decision::Drop));assert!(f.ingress.cache.lock().unwrap().entries.is_empty());
 }
 #[tokio::test]async fn new_syn_during_pending_query_retires_old_epoch(){
  let mut f=fixture(8);register(&f,6,1);let old=start(&f,packet(6,100,2));let first=f.requests.recv().await.unwrap();let new=start(&f,packet(6,200,2));let second=f.requests.recv().await.unwrap();answer(&f,&first,10001);assert_eq!(old.await.unwrap(),Ok(Decision::Drop));answer(&f,&second,10001);assert!(matches!(new.await.unwrap(),Ok(Decision::Forward(_))));
 }
 #[tokio::test]async fn udp_always_refreshes_owner_and_unknown_ack_never_queries(){
  let mut f=fixture(8);register(&f,17,1);for uid in [10001,10002]{let task=start(&f,packet(17,0,0));let line=f.requests.recv().await.unwrap();answer(&f,&line,uid);let result=task.await.unwrap();if uid==10001{assert!(matches!(result,Ok(Decision::Forward(_))))}else{assert_eq!(result,Ok(Decision::Drop))}}
  register(&f,6,2);assert_eq!(f.ingress.admit(&packet(6,101,16)).await,Ok(Decision::Drop));assert!(f.requests.try_recv().is_err());
 }
 #[tokio::test]async fn closed_pins_are_swept_before_they_can_starve_normal_app_admission(){
  let mut f=fixture(1);register(&f,6,1);let first=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();answer(&f,&line,10001);assert!(matches!(first.await.unwrap(),Ok(Decision::Forward(_))));assert!(matches!(f.ingress.admit(&packet(6,101,16)).await,Ok(Decision::Forward(_))));assert!(f.ingress.tracker.lock().unwrap().is_full());unregister(&f,1);
  let mut normal=packet(6,200,2);normal[19]=43;assert_eq!(f.ingress.admit(&normal).await,Ok(Decision::NotService));assert!(!f.ingress.tracker.lock().unwrap().is_full());assert!(f.requests.try_recv().is_err());
 }
 #[tokio::test]async fn stop_cancels_pending_query_without_cancelling_parent(){
  let mut f=fixture(8);register(&f,6,1);let pending=start(&f,packet(6,100,2));let line=f.requests.recv().await.unwrap();f.ingress.stop().unwrap();assert_eq!(pending.await.unwrap(),Err(Failure::Cancelled));assert!(!f.parent.is_cancelled());answer(&f,&line,10001);assert_eq!(f.ingress.admit(&packet(6,101,16)).await,Err(Failure::Cancelled));
 }
}
pub struct NativePermissions;
impl Permissions for NativePermissions{
 fn ticket(&self,key:Key)->Result<Option<Candidate>,()>{crate::mcrf_bs_service::candidate_ticket(key).map_err(|_|())}
 fn authorize(&self,uid:u32,ticket:Candidate)->Result<Option<Authorization>,()>{crate::mcrf_bs_service::authorize_ticket(uid,ticket).map_err(|_|())}
 fn current(&self,proof:&Authorization)->Result<bool,()>{crate::mcrf_bs_service::current(proof).map_err(|_|())}
}
type Tuple=(SocketAddr,SocketAddr);
#[derive(Clone,Copy)]struct Cached{id:u64,proof:Authorization,pinned:bool}
struct Cache{entries:HashMap<Tuple,Cached>,last_sweep:u64}
pub struct Ingress{generation:u64,owner:Arc<Broker>,tracker:Arc<Mutex<Tracker>>,clock:Clock,capacity:usize,cache:Mutex<Cache>,permissions:Arc<dyn Permissions>,retire:Retire,cancel:CancellationToken}
impl Ingress{
 pub fn may_handle(&self,info:&PacketInfo)->Result<bool,Failure>{
  if !matches!(info.protocol,6|17){return Ok(false)}let(a,b)=info.ports.ok_or(Failure::Admission)?;let source=SocketAddr::new(info.source,a);let destination=SocketAddr::new(info.destination,b);let key=Key{protocol:info.protocol,local_port:a,peer:destination};
  if self.permissions.ticket(key).map_err(|_|Failure::Service)?.is_some(){return Ok(true)}
  Ok(info.protocol==6&&self.cache.lock().map_err(|_|Failure::State)?.entries.contains_key(&(source,destination)))
 }
 pub fn new(generation:u64,owner:Arc<Broker>,tracker:Arc<Mutex<Tracker>>,clock:Clock,capacity:usize,permissions:Arc<dyn Permissions>,retire:Retire,cancel:CancellationToken)->Result<Self,Failure>{
  if generation!=owner.generation()||!(1..=8192).contains(&capacity){return Err(Failure::Admission)}if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  Ok(Self{generation,owner,tracker,clock,capacity,cache:Mutex::new(Cache{entries:HashMap::with_capacity(capacity),last_sweep:0}),permissions,retire,cancel})
 }
 fn discard(&self,ids:&[u64])->Result<(),Failure>{
  if ids.is_empty(){return Ok(())}
  self.cache.lock().map_err(|_|Failure::State)?.entries.retain(|_,v|!ids.contains(&v.id));
  {let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;let now=(self.clock)().map_err(|_|Failure::State)?;for id in ids{tracker.retire(self.generation,now,*id).map_err(|_|Failure::State)?;}}
  (self.retire)(ids).map_err(|_|Failure::Retirement)
 }
 fn sweep(&self,force:bool)->Result<(),Failure>{
  let rows={let mut cache=self.cache.lock().map_err(|_|Failure::State)?;let now=(self.clock)().map_err(|_|Failure::State)?;
   if !force&&now.saturating_sub(cache.last_sweep)<1000{return Ok(())}cache.last_sweep=now;cache.entries.values().copied().collect::<Vec<_>>()};
  let mut retired=Vec::new();for row in rows{if !self.permissions.current(&row.proof).map_err(|_|Failure::Service)?{retired.push(row.id)}}self.discard(&retired)
 }
 pub async fn admit(&self,bytes:&[u8])->Result<Decision,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let info=PacketInfo::read(bytes).map_err(|_|Failure::Admission)?;if !matches!(info.protocol,6|17){return Ok(Decision::NotService)}if info.fragment.is_some(){return Ok(Decision::Drop)}
  let(a,b)=info.ports.ok_or(Failure::Admission)?;let source=SocketAddr::new(info.source,a);let destination=SocketAddr::new(info.destination,b);let tuple=(source,destination);let key=Key{protocol:info.protocol,local_port:a,peer:destination};
  if info.protocol==6&&info.tcp.is_some_and(|v|v.flags&2!=0){let pressure=self.tracker.lock().map_err(|_|Failure::State)?.is_full();self.sweep(pressure)?;}
  let ticket=self.permissions.ticket(key).map_err(|_|Failure::Service)?;
  let cached=if info.protocol==6{self.cache.lock().map_err(|_|Failure::State)?.entries.get(&tuple).copied()}else{None};
  if ticket.is_none()&&cached.is_none(){return Ok(Decision::NotService)}
  let epoch=if info.protocol==6{
   let association={let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;tracker.observe(self.generation,(self.clock)().map_err(|_|Failure::State)?,&info).map_err(|_|Failure::State)?};
   self.discard(&association.retired)?;
   if let Some(cached)=cached{
    if cached.id==association.id&&Some(cached.proof.candidate())==ticket{
     if !cached.pinned&&info.tcp.is_some_and(|v|v.flags&2==0){
      let mut cache=self.cache.lock().map_err(|_|Failure::State)?;
      let Some(row)=cache.entries.get_mut(&tuple).filter(|r|r.id==cached.id)else{return Ok(Decision::Drop)};
      if !row.pinned{let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;tracker.pin_tcp(self.generation,(self.clock)().map_err(|_|Failure::State)?,source,destination,cached.id).map_err(|_|Failure::State)?;row.pinned=true;}
     }
     {let tracker=self.tracker.lock().map_err(|_|Failure::State)?;if tracker.tcp_epoch(self.generation,(self.clock)().map_err(|_|Failure::State)?,source,destination).map_err(|_|Failure::State)?!=Some(cached.id){return Ok(Decision::Drop)}}
     if self.permissions.ticket(key).map_err(|_|Failure::Service)?!=ticket{return Ok(Decision::Drop)}
     if association.retire_after_packet{self.discard(&[cached.id])?;}if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}return Ok(Decision::Forward(cached.proof))
    }
    self.discard(&[cached.id])?;
   }
   // An unknown ACK/FIN is not a new connection authorization.
   if info.tcp.is_none_or(|v|v.flags&2==0){
    let live={let tracker=self.tracker.lock().map_err(|_|Failure::State)?;tracker.tcp_epoch(self.generation,(self.clock)().map_err(|_|Failure::State)?,source,destination).map_err(|_|Failure::State)?==Some(association.id)};
    if live{self.discard(&[association.id])?;}return Ok(Decision::Drop)
   }Some(association.id)
  }else{None};
  let Some(ticket)=ticket else{return Ok(Decision::NotService)};
  let owner=match self.owner.query(info.protocol,source,destination,&self.cancel).await{Ok(owner)=>owner,Err(error)=>{
   if let Some(id)=epoch{if self.discard(&[id]).is_err()&&!self.cancel.is_cancelled(){return Err(Failure::Retirement)}}
   return match error{crate::mcrf_bs_owner::Failure::Denied=>Ok(Decision::Drop),crate::mcrf_bs_owner::Failure::Cancelled=>Err(Failure::Cancelled),_=>Err(Failure::Owner)}
  }};
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let Some(proof)=self.permissions.authorize(owner.uid,ticket).map_err(|_|Failure::Service)?else{if let Some(id)=epoch{self.discard(&[id])?;}return Ok(Decision::Drop)};
  if let Some(id)=epoch{
   let mut cache=self.cache.lock().map_err(|_|Failure::State)?;let mut tracker=self.tracker.lock().map_err(|_|Failure::State)?;let now=(self.clock)().map_err(|_|Failure::State)?;
   if tracker.tcp_epoch(self.generation,now,source,destination).map_err(|_|Failure::State)?!=Some(id){return Ok(Decision::Drop)}
   let confirmed=tracker.confirm_owner(self.generation,now,&info,id,owner.uid,owner.packages).map_err(|_|Failure::State)?;
   if confirmed.id!=id||!confirmed.retired.is_empty(){return Err(Failure::State)}
   if let Some(row)=cache.entries.get(&tuple).filter(|r|r.id==id){if row.proof!=proof{return Ok(Decision::Drop)}}else{
    if cache.entries.len()>=self.capacity&&!cache.entries.contains_key(&tuple){return Err(Failure::Capacity)}
    cache.entries.insert(tuple,Cached{id,proof,pinned:false});
   }
  }
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}Ok(Decision::Forward(proof))
 }
 pub fn stop(&self)->Result<(),Failure>{
  self.cancel.cancel();let ids=self.cache.lock().map_err(|_|Failure::State)?.entries.values().map(|v|v.id).collect::<Vec<_>>();self.discard(&ids)
 }
}
