//! UDP ownership cannot be cached for the lifetime of a socket tuple. Every
//! outgoing datagram / reply must refresh against the Android owner broker.
use std::{sync::{Arc,Mutex},net::SocketAddr,collections::HashSet};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{associations::Tracker,packet::PacketInfo};
use crate::{mcrf_bs_owner::{Broker,Owner},mcrf_bs_stream_slots::Clock,mcrf_bs_dns_handler::{StreamFlow,StreamLookup},mcrf_bs_tcp_dispatch::owned::Retire};
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Invalid,Stale,Owner,Cancelled,State,Busy,Capacity,Retired(Vec<u64>)}
type Key=(SocketAddr,SocketAddr);
/// One slot per original tuple, including pending owner queries. A live lease
/// and its DNS callbacks keep the reservation until the last reference drops.
pub struct Slots{capacity:usize,active:Mutex<HashSet<Key>>}
struct Reservation{slots:Arc<Slots>,key:Key}
impl Drop for Reservation{fn drop(&mut self){if let Ok(mut active)=self.slots.active.lock(){active.remove(&self.key);}}}
impl Slots{
 pub fn new(capacity:usize)->Result<Self,Failure>{if !(1..=8192).contains(&capacity){return Err(Failure::Invalid)}Ok(Self{capacity,active:Mutex::new(HashSet::new())})}
 fn reserve(self:&Arc<Self>,key:Key)->Result<Reservation,Failure>{let mut active=self.active.lock().map_err(|_|Failure::State)?;if active.contains(&key){return Err(Failure::Busy)}if active.len()>=self.capacity{return Err(Failure::Capacity)}active.insert(key);Ok(Reservation{slots:self.clone(),key})}
}
// Cleanup must run BEFORE releasing the tuple reservation, on success and on
// every failed/pending admission. No new flow can inherit the previous epoch.
struct Cleanup{tracker:Arc<Mutex<Tracker>>,generation:u64,clock:Clock,flow:StreamFlow,retire:Retire,cancel:CancellationToken}
impl Drop for Cleanup{
 fn drop(&mut self){let result=(||{{let mut state=self.tracker.lock().map_err(|_|())?;let now=(self.clock)()?;state.retire(self.generation,now,self.flow.epoch).map_err(|_|())?;}(self.retire)(&[self.flow.epoch])})();if result.is_err(){self.cancel.cancel()}}
}
pub struct Lease{cleanup:Cleanup,_reservation:Reservation,pub owner:Owner}
fn same_packages(a:&[String],b:&[String])->bool{a.len()==b.len()&&a.iter().all(|p|b.iter().any(|q|p==q))}
fn packet(source:SocketAddr,destination:SocketAddr)->PacketInfo{PacketInfo{source:source.ip(),destination:destination.ip(),ports:Some((source.port(),destination.port())),protocol:17,fragment:None,tcp:None}}
impl Lease{
 pub async fn admit(slots:Arc<Slots>,tracker:Arc<Mutex<Tracker>>,generation:u64,clock:Clock,broker:&Broker,source:SocketAddr,destination:SocketAddr,retire:Retire,cancel:&CancellationToken)->Result<Arc<Self>,Failure>{
  if generation!=broker.generation()||source.port()==0||destination.port()==0||source.is_ipv4()!=destination.is_ipv4(){return Err(Failure::Invalid)}
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let reservation=slots.reserve((source,destination))?;
  let p=packet(source,destination);
  let association={let mut state=tracker.lock().map_err(|_|Failure::State)?;state.observe(generation,clock().map_err(|_|Failure::State)?,&p).map_err(|_|Failure::State)?};
  let epoch=association.id;let mut cleanup=Cleanup{tracker:tracker.clone(),generation,clock:clock.clone(),flow:StreamFlow{source,destination,epoch},retire:retire.clone(),cancel:cancel.clone()};
  if !association.retired.is_empty(){retire(&association.retired).map_err(|_|Failure::State)?}
  let owner=broker.query(17,source,destination,cancel).await.map_err(|_|Failure::Owner)?;
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let confirmed={let mut state=tracker.lock().map_err(|_|Failure::State)?;state.confirm_owner(generation,clock().map_err(|_|Failure::State)?,&p,epoch,owner.uid,owner.packages.clone()).map_err(|_|Failure::Stale)?};
  if !confirmed.retired.is_empty(){cleanup.flow.epoch=confirmed.id;retire(&confirmed.retired).map_err(|_|Failure::State)?;return Err(Failure::Retired(confirmed.retired))}if confirmed.id!=epoch{return Err(Failure::Stale)}
  {let mut state=tracker.lock().map_err(|_|Failure::State)?;state.own_udp(generation,clock().map_err(|_|Failure::State)?,source,destination,epoch).map_err(|_|Failure::Stale)?;}
  Ok(Arc::new(Self{cleanup,_reservation:reservation,owner}))
 }
 pub fn lookup(self:&Arc<Self>)->StreamLookup{let lease=self.clone();Arc::new(move||{let state=lease.cleanup.tracker.lock().map_err(|_|())?;let current=state.udp_identity(lease.cleanup.generation,(lease.cleanup.clock)()?,lease.cleanup.flow.source,lease.cleanup.flow.destination).map_err(|_|())?;let expected=state.owner(&packet(lease.cleanup.flow.source,lease.cleanup.flow.destination),lease.cleanup.flow.epoch).is_some_and(|(uid,packages)|uid==lease.owner.uid&&same_packages(packages,&lease.owner.packages));Ok(current.filter(|id|*id==lease.cleanup.flow.epoch&&expected).map(|_|lease.cleanup.flow))})}
 pub async fn refresh(&self,broker:&Broker,cancel:&CancellationToken)->Result<(),Failure>{
  if broker.generation()!=self.cleanup.generation{return Err(Failure::Invalid)}if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  {let state=self.cleanup.tracker.lock().map_err(|_|Failure::State)?;if state.udp_identity(self.cleanup.generation,(self.cleanup.clock)().map_err(|_|Failure::State)?,self.cleanup.flow.source,self.cleanup.flow.destination).map_err(|_|Failure::State)?!=Some(self.cleanup.flow.epoch){return Err(Failure::Stale)}}
  let fresh=broker.query(17,self.cleanup.flow.source,self.cleanup.flow.destination,cancel).await.map_err(|_|Failure::Owner)?;
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  self.confirm(&fresh)
 }
 /// Reuse an already fresh authenticated broker response (e.g. native DNS),
 /// so activity extends the epoch without adding another owner round trip.
 pub fn confirm(&self,fresh:&Owner)->Result<(),Failure>{
  let changed=fresh.uid!=self.owner.uid||!same_packages(&fresh.packages,&self.owner.packages);
  let mut state=self.cleanup.tracker.lock().map_err(|_|Failure::State)?;let now=(self.cleanup.clock)().map_err(|_|Failure::State)?;
  if state.udp_identity(self.cleanup.generation,now,self.cleanup.flow.source,self.cleanup.flow.destination).map_err(|_|Failure::State)?!=Some(self.cleanup.flow.epoch){return Err(Failure::Stale)}
  if changed{state.retire(self.cleanup.generation,now,self.cleanup.flow.epoch).map_err(|_|Failure::State)?;drop(state);(self.cleanup.retire)(&[self.cleanup.flow.epoch]).map_err(|_|Failure::State)?;return Err(Failure::Retired(vec![self.cleanup.flow.epoch]))}
  // Touch the live original association, without manufacturing a new one.
  let association=state.observe(self.cleanup.generation,now,&packet(self.cleanup.flow.source,self.cleanup.flow.destination)).map_err(|_|Failure::State)?;
  if association.id!=self.cleanup.flow.epoch{return Err(Failure::Stale)}Ok(())
 }
}

#[cfg(test)]mod tests{
 use super::*;use std::sync::atomic::{AtomicU64,AtomicUsize,Ordering};use base64::Engine as _;
 fn setup()->(Arc<Mutex<Tracker>>,Arc<Broker>,tokio::sync::mpsc::UnboundedReceiver<String>,Clock,Arc<AtomicU64>,Retire,Arc<AtomicUsize>){
  let(tx,rx)=tokio::sync::mpsc::unbounded_channel();let broker=Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let now=Arc::new(AtomicU64::new(1));let read=now.clone();let clock:Clock=Arc::new(move||Ok(read.load(Ordering::SeqCst)));let retired=Arc::new(AtomicUsize::new(0));let log=retired.clone();let retire:Retire=Arc::new(move|ids|{log.fetch_add(ids.len(),Ordering::SeqCst);Ok(())});(Arc::new(Mutex::new(Tracker::new(42,4).unwrap())),broker,rx,clock,now,retire,retired)
 }
 fn endpoints()->(SocketAddr,SocketAddr){("10.89.0.2:45000".parse().unwrap(),"192.0.2.1:5353".parse().unwrap())}
 fn reply(broker:&Broker,line:&str,uid:u32){let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(fields[3],"17");let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);broker.respond(&format!("BS_OWNER|42|{}|{uid}|{data}",fields[2])).unwrap();}
 #[tokio::test]async fn aborted_pending_admission_retires_before_freeing_capacity_one_slot(){
  let(state,broker,mut rx,clock,_,retire,count)=setup();*state.lock().unwrap()=Tracker::new(42,1).unwrap();let slots=Arc::new(Slots::new(1).unwrap());let(source,destination)=endpoints();
  let owner=broker.clone();let tracker=state.clone();let registry=slots.clone();let time=clock.clone();let cleanup=retire.clone();
  let pending=tokio::spawn(async move{Lease::admit(registry,tracker,42,time,&owner,source,destination,cleanup,&CancellationToken::new()).await});rx.recv().await.unwrap();assert!(state.lock().unwrap().is_full());pending.abort();assert!(matches!(pending.await,Err(error) if error.is_cancelled()));
  assert!(!state.lock().unwrap().is_full());assert!(slots.active.lock().unwrap().is_empty());assert_eq!(count.load(Ordering::SeqCst),1);
  let other="10.89.0.2:45001".parse().unwrap();let owner=broker.clone();let tracker=state.clone();let registry=slots.clone();let task=tokio::spawn(async move{Lease::admit(registry,tracker,42,clock,&owner,other,destination,retire,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();drop(lease);assert!(!state.lock().unwrap().is_full());
 }
 #[tokio::test]async fn each_refresh_queries_owner_and_last_callback_retires_epoch(){
  let(state,broker,mut rx,clock,_,retire,count)=setup();let(source,destination)=endpoints();let owner=broker.clone();let tracker=state.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),tracker,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});let line=rx.recv().await.unwrap();reply(&broker,&line,10001);let lease=task.await.unwrap().unwrap();let lookup=lease.lookup();let epoch=lookup().unwrap().unwrap().epoch;
  for _ in 0..2{let current=lease.clone();let owner=broker.clone();let task=tokio::spawn(async move{current.refresh(&owner,&CancellationToken::new()).await});let line=rx.recv().await.unwrap();reply(&broker,&line,10001);assert_eq!(task.await.unwrap(),Ok(()));assert_eq!(lookup().unwrap().unwrap().epoch,epoch)}
  drop(lease);assert!(lookup().unwrap().is_some());drop(lookup);assert_eq!(state.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));assert_eq!(count.load(Ordering::SeqCst),1);
 }
 #[tokio::test]async fn changed_owner_revokes_epoch_instead_of_reusing_old_route(){
  let(state,broker,mut rx,clock,_,retire,count)=setup();let(source,destination)=endpoints();let owner=broker.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),state,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();let lookup=lease.lookup();let epoch=lookup().unwrap().unwrap().epoch;let current=lease.clone();let owner=broker.clone();let task=tokio::spawn(async move{current.refresh(&owner,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10002);assert_eq!(task.await.unwrap(),Err(Failure::Retired(vec![epoch])));assert_eq!(lookup().unwrap(),None);assert_eq!(count.load(Ordering::SeqCst),1);
 }
 #[tokio::test]async fn pending_admission_cannot_confirm_replacement_tuple(){
  let(state,broker,mut rx,clock,_,retire,_)=setup();let(source,destination)=endpoints();let owner=broker.clone();let tracker=state.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),tracker,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});let line=rx.recv().await.unwrap();let replacement={let mut tracker=state.lock().unwrap();tracker.retire(42,1,1).unwrap();tracker.observe(42,1,&packet(source,destination)).unwrap().id};reply(&broker,&line,10001);assert!(matches!(task.await.unwrap(),Err(Failure::Stale)));assert_eq!(state.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));assert!(replacement>1);
 }
 #[tokio::test]async fn cancelled_or_retired_flow_never_queries_owner_again(){
  let(state,broker,mut rx,clock,now,retire,_)=setup();let(source,destination)=endpoints();let owner=broker.clone();let tracker=state.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),tracker,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();let stop=CancellationToken::new();stop.cancel();assert_eq!(lease.refresh(&broker,&stop).await,Err(Failure::Cancelled));now.store(30001,Ordering::SeqCst);state.lock().unwrap().retire(42,30001,lease.cleanup.flow.epoch).unwrap();assert_eq!(lease.lookup()().unwrap(),None);assert_eq!(lease.refresh(&broker,&CancellationToken::new()).await,Err(Failure::Stale));assert!(rx.try_recv().is_err());
 }
 #[tokio::test]async fn failed_retirement_cancels_owner_lifetime(){
  let(state,broker,mut rx,clock,_,_,_)=setup();let(source,destination)=endpoints();let stop=CancellationToken::new();let stopped=stop.clone();let owner=broker.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),state,42,clock,&owner,source,destination,Arc::new(|_|Err(())),&stopped).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();drop(lease);assert!(stop.is_cancelled());
 }
 #[tokio::test]async fn duplicate_and_capacity_are_rejected_before_owner_query_and_cancel_releases_slot(){
  let(state,broker,mut rx,clock,_,retire,_)=setup();let(source,destination)=endpoints();let slots=Arc::new(Slots::new(1).unwrap());let owner=broker.clone();let tracker=state.clone();let registry=slots.clone();let time=clock.clone();let retire_first=retire.clone();let stop=CancellationToken::new();let stopped=stop.clone();
  let task=tokio::spawn(async move{Lease::admit(registry,tracker,42,time,&owner,source,destination,retire_first,&stopped).await});let _line=rx.recv().await.unwrap();assert!(matches!(Lease::admit(slots.clone(),state.clone(),42,clock.clone(),&broker,source,destination,retire.clone(),&CancellationToken::new()).await,Err(Failure::Busy)));let other="10.89.0.2:45001".parse().unwrap();assert!(matches!(Lease::admit(slots.clone(),state.clone(),42,clock.clone(),&broker,other,destination,retire.clone(),&CancellationToken::new()).await,Err(Failure::Capacity)));assert!(rx.try_recv().is_err());stop.cancel();assert!(task.await.unwrap().is_err());assert!(slots.active.lock().unwrap().is_empty());
  let owner=broker.clone();let registry=slots.clone();let task=tokio::spawn(async move{Lease::admit(registry,state,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();drop(lease);assert!(slots.active.lock().unwrap().is_empty());
 }

 #[tokio::test]async fn owned_udp_lives_until_last_callback_but_never_inherits_changed_owner(){
  let(state,broker,mut rx,clock,now,retire,_)=setup();let(source,destination)=endpoints();let owner=broker.clone();let task=tokio::spawn(async move{Lease::admit(Arc::new(Slots::new(4).unwrap()),state,42,clock,&owner,source,destination,retire,&CancellationToken::new()).await});reply(&broker,&rx.recv().await.unwrap(),10001);let lease=task.await.unwrap().unwrap();let lookup=lease.lookup();now.store(30000,Ordering::SeqCst);assert!(lookup().unwrap().is_some());assert_eq!(lease.confirm(&Owner{uid:10001,packages:vec!["org.allowed".into()]}),Ok(()));now.store(59999,Ordering::SeqCst);assert!(lookup().unwrap().is_some());now.store(60000,Ordering::SeqCst);assert!(lookup().unwrap().is_some());assert!(matches!(lease.confirm(&Owner{uid:10002,packages:vec!["org.allowed".into()]}),Err(Failure::Retired(_))));assert_eq!(lookup().unwrap(),None);
 }

}
