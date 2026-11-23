//! One accepted native TCP stream owns one tracker epoch. DNS callbacks keep
//! its lease alive; the last callback/stream release unpins it. Never pin an
//! unauthenticated SYN, carry a lease across a new SYN, or evict a live stream.
use std::{net::SocketAddr,sync::{Arc,Mutex}};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{associations::Tracker,packet::{PacketInfo,TcpControl}};
use crate::{mcrf_bs_owner::{Broker,Owner},mcrf_bs_dns_handler::{StreamFlow,StreamLookup}};

pub type Clock=Arc<dyn Fn()->Result<u64,()>+Send+Sync>;
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Invalid,Stale,Owner,Cancelled,State,Retired(Vec<u64>)}
pub struct Lease{tracker:Arc<Mutex<Tracker>>,generation:u64,clock:Clock,flow:StreamFlow,pub owner:Owner}
impl Drop for Lease{
 fn drop(&mut self){if let Ok(mut tracker)=self.tracker.lock(){if let Ok(now)=(self.clock)(){let _=tracker.unpin_tcp(self.generation,now,self.flow.source,self.flow.destination,self.flow.epoch);}}}
}
impl Lease{
 pub async fn admit(tracker:Arc<Mutex<Tracker>>,generation:u64,clock:Clock,owner:&Broker,source:SocketAddr,destination:SocketAddr,cancel:&CancellationToken)->Result<Arc<Self>,Failure>{
  if generation!=owner.generation()||source.port()==0||destination.port()==0||source.is_ipv4()!=destination.is_ipv4(){return Err(Failure::Invalid)}
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let epoch={let state=tracker.lock().map_err(|_|Failure::State)?;state.tcp_candidate(generation,clock().map_err(|_|Failure::State)?,source,destination).map_err(|_|Failure::State)?.ok_or(Failure::Stale)?};
  // No tracker lock crosses the authenticated Android ownership round trip.
  let verified=owner.query(6,source,destination,cancel).await.map_err(|_|Failure::Owner)?;
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  {
   let mut state=tracker.lock().map_err(|_|Failure::State)?;let now=clock().map_err(|_|Failure::State)?;
   if state.tcp_candidate(generation,now,source,destination).map_err(|_|Failure::State)?!=Some(epoch){return Err(Failure::Stale)}
   let packet=PacketInfo{source:source.ip(),destination:destination.ip(),ports:Some((source.port(),destination.port())),protocol:6,fragment:None,tcp:Some(TcpControl{sequence:0,flags:16})};
   let confirmed=state.confirm_owner(generation,now,&packet,epoch,verified.uid,verified.packages.clone()).map_err(|_|Failure::State)?;
   // Caller must remove these retired IDs from its routing decision cache.
   if !confirmed.retired.is_empty(){return Err(Failure::Retired(confirmed.retired))}
   if confirmed.id!=epoch{return Err(Failure::Stale)}
   state.pin_tcp(generation,now,source,destination,epoch).map_err(|_|Failure::State)?;
  }
  Ok(Arc::new(Self{tracker,generation,clock,flow:StreamFlow{source,destination,epoch},owner:verified}))
 }
 pub fn lookup(self:&Arc<Self>)->StreamLookup{
  let lease=self.clone();Arc::new(move||{
   let state=lease.tracker.lock().map_err(|_|())?;let now=(lease.clock)()?;
   let current=state.tcp_stream_identity(lease.generation,now,lease.flow.source,lease.flow.destination).map_err(|_|())?;
   Ok(current.filter(|id|*id==lease.flow.epoch).map(|_|lease.flow))
  })
 }
}

#[cfg(test)]mod tests{
 use super::*;use std::sync::atomic::{AtomicU64,Ordering};use base64::Engine as _;
 fn fixture()->(Arc<Mutex<Tracker>>,Arc<Broker>,tokio::sync::mpsc::UnboundedReceiver<String>,Clock,Arc<AtomicU64>,PacketInfo){
  let mut tracker=Tracker::new(42,4).unwrap();let mut p=PacketInfo{source:"10.89.0.2".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),ports:Some((45000,5353)),protocol:6,fragment:None,tcp:Some(TcpControl{sequence:100,flags:2})};
  tracker.observe(42,0,&p).unwrap();p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,1,&p).unwrap();
  let(tx,rx)=tokio::sync::mpsc::unbounded_channel();let broker=Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());
  let now=Arc::new(AtomicU64::new(1));let time=now.clone();let clock:Clock=Arc::new(move||Ok(time.load(Ordering::SeqCst)));
  (Arc::new(Mutex::new(tracker)),broker,rx,clock,now,p)
 }
 fn endpoints(p:&PacketInfo)->(SocketAddr,SocketAddr){let(a,b)=p.ports.unwrap();(SocketAddr::new(p.source,a),SocketAddr::new(p.destination,b))}
 fn reply(broker:&Broker,line:&str){let fields:Vec<_>=line.split_whitespace().collect();let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);broker.respond(&format!("BS_OWNER|42|{}|10001|{data}",fields[2])).unwrap();}
 #[tokio::test]async fn callbacks_keep_accepted_epoch_alive_until_last_release(){
  let(tracker,broker,mut requests,clock,now,p)=fixture();let(source,destination)=endpoints(&p);let state=tracker.clone();let owner=broker.clone();
  let task=tokio::spawn(async move{Lease::admit(state,42,clock,&owner,source,destination,&CancellationToken::new()).await});
  let line=requests.recv().await.unwrap();assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,source,destination),Ok(None));reply(&broker,&line);
  let lease=task.await.unwrap().unwrap();assert_eq!(lease.owner.uid,10001);let lookup=lease.lookup();let initial=lookup().unwrap().unwrap();
  now.store(270000,Ordering::SeqCst);drop(lease);assert_eq!(lookup().unwrap(),Some(initial));drop(lookup);
  assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,270000,source,destination),Ok(None));
 }
 #[tokio::test]async fn new_syn_during_owner_query_cannot_admit_or_pin_old_stream(){
  let(tracker,broker,mut requests,clock,_,mut p)=fixture();let(source,destination)=endpoints(&p);let state=tracker.clone();let owner=broker.clone();
  let task=tokio::spawn(async move{Lease::admit(state,42,clock,&owner,source,destination,&CancellationToken::new()).await});
  let line=requests.recv().await.unwrap();p.tcp.as_mut().unwrap().flags=2;tracker.lock().unwrap().observe(42,1,&p).unwrap();reply(&broker,&line);
  assert!(matches!(task.await.unwrap(),Err(Failure::Stale)));assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,source,destination),Ok(None));
 }
 #[tokio::test]async fn changed_owner_returns_retirement_for_routing_cache_invalidation(){
  let(tracker,broker,mut requests,clock,_,p)=fixture();let(source,destination)=endpoints(&p);
  let initial={let mut state=tracker.lock().unwrap();let id=state.tcp_candidate(42,1,source,destination).unwrap().unwrap();state.confirm_owner(42,1,&p,id,10002,vec![]).unwrap();id};
  let state=tracker.clone();let owner=broker.clone();let task=tokio::spawn(async move{Lease::admit(state,42,clock,&owner,source,destination,&CancellationToken::new()).await});
  let line=requests.recv().await.unwrap();reply(&broker,&line);
  assert!(matches!(task.await.unwrap(),Err(Failure::Retired(ids))if ids==vec![initial]));
  assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,source,destination),Ok(None));
 }
 #[tokio::test]async fn cancelled_admission_never_queries_owner_or_pins_tuple(){
  let(tracker,broker,mut requests,clock,_,p)=fixture();let(source,destination)=endpoints(&p);let cancel=CancellationToken::new();cancel.cancel();
  assert!(matches!(Lease::admit(tracker.clone(),42,clock,&broker,source,destination,&cancel).await,Err(Failure::Cancelled)));
  assert!(requests.try_recv().is_err());assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,source,destination),Ok(None));
 }
}
