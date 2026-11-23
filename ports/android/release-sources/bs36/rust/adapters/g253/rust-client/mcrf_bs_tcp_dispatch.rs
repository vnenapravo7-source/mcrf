//! Per-relay native stream dispatch. Installed only after complete session
//! admission; no global handler, second engine, SOCKS hop or fallback on error.
use std::{sync::Arc,net::SocketAddr,future::Future,pin::Pin};
use tokio::io::{AsyncRead,AsyncWrite};use tokio_util::sync::CancellationToken;
pub trait Io:AsyncRead+AsyncWrite+Unpin+Send{}
impl<T:AsyncRead+AsyncWrite+Unpin+Send> Io for T{}
pub type Stream=Box<dyn Io>;
pub type Handler=Arc<dyn Fn(Stream,SocketAddr,SocketAddr,CancellationToken)->Pin<Box<dyn Future<Output=()>+Send>>+Send+Sync>;

/// Owns every admitted native handler scope, including its outbound FD and DNS
/// callbacks. Closing waits for scopes to DROP, not merely for cancel to fire.
pub struct HandlerGroup{cancel:CancellationToken,closed:std::sync::atomic::AtomicBool,active:std::sync::atomic::AtomicUsize,changed:tokio::sync::Notify,capacity:usize}
struct HandlerPermit{group:Arc<HandlerGroup>,scope:CancellationToken}
impl Drop for HandlerPermit{fn drop(&mut self){self.scope.cancel();self.group.active.fetch_sub(1,std::sync::atomic::Ordering::AcqRel);self.group.changed.notify_one();}}
impl HandlerGroup{
 pub fn new(capacity:usize,parent:&CancellationToken)->Result<Arc<Self>,()>{if !(1..=8192).contains(&capacity){return Err(())}Ok(Arc::new(Self{cancel:parent.child_token(),closed:std::sync::atomic::AtomicBool::new(false),active:std::sync::atomic::AtomicUsize::new(0),changed:tokio::sync::Notify::new(),capacity}))}
 fn enter(self:&Arc<Self>)->Option<HandlerPermit>{
  use std::sync::atomic::Ordering::*;
  if self.closed.load(Acquire)||self.cancel.is_cancelled(){return None}
  self.active.fetch_update(AcqRel,Acquire,|count|(count<self.capacity).then_some(count+1)).ok()?;
  let permit=HandlerPermit{group:self.clone(),scope:self.cancel.child_token()};if self.closed.load(Acquire)||self.cancel.is_cancelled(){drop(permit);return None}Some(permit)
 }
 pub fn wrap(self:&Arc<Self>,handler:Handler,rejected:Arc<dyn Fn()+Send+Sync>)->Handler{
  let group=self.clone();Arc::new(move|stream,source,destination,cancel|{let group=group.clone();let handler=handler.clone();let rejected=rejected.clone();Box::pin(async move{
   let Some(permit)=group.enter()else{if !group.closed.load(std::sync::atomic::Ordering::Acquire)&&!group.cancel.is_cancelled(){rejected()}return};
   tokio::select!{biased;_=permit.scope.cancelled()=>{},_=cancel.cancelled()=>{},_=handler(stream,source,destination,permit.scope.clone())=>{}}
   // select has dropped the forwarding future/FD before this active scope.
   drop(permit);
  })})
 }
 pub async fn close(&self){
  self.closed.store(true,std::sync::atomic::Ordering::Release);self.cancel.cancel();
  loop{let changed=self.changed.notified();tokio::pin!(changed);changed.as_mut().enable();if self.active.load(std::sync::atomic::Ordering::Acquire)==0{return}changed.await}
 }
 pub fn active(&self)->usize{self.active.load(std::sync::atomic::Ordering::Acquire)}
}

#[cfg(all(test,unix))]mod group_tests{
 use super::*;use std::sync::atomic::{AtomicUsize,Ordering};use tokio::io::{AsyncReadExt,AsyncWriteExt};
 #[tokio::test]async fn close_waits_for_the_real_outbound_socket_to_drop_and_preserves_parent(){
  tokio::time::timeout(std::time::Duration::from_secs(5),async{
   let parent=CancellationToken::new();let group=HandlerGroup::new(4,&parent).unwrap();let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let(tx,mut rx)=tokio::sync::mpsc::channel(1);
   let handler:Handler=Arc::new(move|_stream,_,_,_|{let tx=tx.clone();Box::pin(async move{use std::os::fd::AsRawFd;let mut socket=tokio::net::TcpStream::connect(peer).await.unwrap();socket.write_all(&[1]).await.unwrap();tx.send(socket.as_raw_fd()).await.unwrap();std::future::pending::<()>().await;drop(socket);})});
   let wrapped=group.wrap(handler,Arc::new(||panic!("not at capacity")));let(_app,stream)=tokio::io::duplex(16);let task=tokio::spawn(wrapped(Box::new(stream),"127.0.0.1:40000".parse().unwrap(),peer,CancellationToken::new()));let(mut accepted,_)=listener.accept().await.unwrap();let mut byte=[0];accepted.read_exact(&mut byte).await.unwrap();let fd=rx.recv().await.unwrap();assert_eq!(group.active(),1);
   assert!(unsafe{libc::fcntl(fd,libc::F_GETFD)}>=0);group.close().await;assert_eq!(group.active(),0);assert!(!parent.is_cancelled());assert_eq!(unsafe{libc::fcntl(fd,libc::F_GETFD)},-1);assert_eq!(accepted.read(&mut byte).await.unwrap(),0);task.await.unwrap();
  }).await.unwrap()
 }
 #[tokio::test]async fn a_closed_group_never_polls_a_late_handler_or_uses_a_fallback(){
  let parent=CancellationToken::new();let group=HandlerGroup::new(1,&parent).unwrap();let called=Arc::new(AtomicUsize::new(0));let count=called.clone();let handler:Handler=Arc::new(move|_,_,_,_|{count.fetch_add(1,Ordering::SeqCst);Box::pin(async{})});let wrapped=group.wrap(handler,Arc::new(||panic!("normal close is not capacity")));group.close().await;let(_app,stream)=tokio::io::duplex(16);wrapped(Box::new(stream),"127.0.0.1:1".parse().unwrap(),"127.0.0.1:2".parse().unwrap(),CancellationToken::new()).await;assert_eq!(called.load(Ordering::SeqCst),0);assert_eq!(group.active(),0);assert!(!parent.is_cancelled());
 }
}

// One accepted-stream runtime. The router executes ordered policy effects on
// this SAME stream/prefix; this owner then authenticates again before opening
// an outbound or handing the captured bytes to the native DNS executor.
#[cfg(unix)]pub mod owned {
 use super::*;
 use std::{collections::HashSet,sync::Mutex};
 use mcrf_bs_routing_policy::{Target,associations::Tracker};
 use crate::{mcrf_bs_stream_slots::{Lease,Clock},mcrf_bs_owner::Broker,mcrf_bs_dns_engine::Engine,mcrf_bs_dns_handler::{self,Underlay},mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_tcp_inspect::{self,Prefix}};
 pub enum Decision{Forward{peer:SocketAddr,target:Target},ForwardMany{peers:Vec<SocketAddr>,target:Target},Dns,Block}
 #[derive(Debug,PartialEq,Eq)]pub enum Failure{Admission,Retirement,Route,Identity,Dns,Forward,Cancelled,Resolve(crate::mcrf_bs_dns_engine::Failure),Policy(mcrf_bs_routing_policy::Error)}
 pub struct Original<'a>{pub source:SocketAddr,pub destination:SocketAddr,pub destination_domain:Option<&'a str>,pub observed_domain:Option<&'a str>,pub uid:u32,pub packages:&'a [String]}
 pub trait Router:Send+Sync{
  // Captured bytes are shared across every sniff/resolve/options effect and
  // consumed only after a terminal decision. No connector belongs in a router.
  fn decide<'a>(&'a self,original:Original<'a>,stream:&'a mut Stream,captured:&'a mut Prefix,cancel:&'a CancellationToken)->Pin<Box<dyn Future<Output=Result<Decision,Failure>>+Send+'a>>;
 }
 struct DestinationPin{engine:Arc<Engine>,id:u64,domain:Option<String>}
 impl DestinationPin{fn new(engine:Arc<Engine>,id:u64,ip:std::net::IpAddr)->Result<Self,Failure>{let domain=engine.pin_flow_name(id,ip).map_err(|_|Failure::Identity)?;Ok(Self{engine,id,domain})}}
 impl Drop for DestinationPin{fn drop(&mut self){if self.domain.is_some(){let _=self.engine.release_flow_name(self.id);}}}
 pub type Retire=Arc<dyn Fn(&[u64])->Result<(),()>+Send+Sync>;
 pub struct Session{pub generation:u64,pub tracker:Arc<Mutex<Tracker>>,pub clock:Clock,pub owner:Arc<Broker>,pub engine:Arc<Engine>,pub sockets:Arc<dyn SocketPolicy>,pub underlay:Underlay,pub router:Arc<dyn Router>,pub retire:Retire}
 impl Session{
  pub fn handler(self:Arc<Self>,report:Arc<dyn Fn(Failure)+Send+Sync>)->Handler{
   Arc::new(move|stream,source,destination,cancel|{let session=self.clone();let report=report.clone();Box::pin(async move{if let Err(error)=session.serve(stream,source,destination,&cancel).await{report(error)}})})
  }
  fn revoke_identity(&self,id:u64,cancel:&CancellationToken)->Failure{
   // A failed final owner check also revokes the old cached decision. Retire
   // this epoch only: a new SYN may already own the same original tuple.
   let result=(||{let now=(self.clock)()?;self.tracker.lock().map_err(|_|())?.retire(self.generation,now,id).map_err(|_|())?;(self.retire)(&[id])})();
   if result.is_err(){cancel.cancel();Failure::Retirement}else{Failure::Identity}
  }
  pub async fn serve(&self,mut stream:Stream,source:SocketAddr,destination:SocketAddr,cancel:&CancellationToken)->Result<(),Failure>{
   if self.generation!=self.engine.generation(){return Err(Failure::Admission)}
   let lease=match Lease::admit(self.tracker.clone(),self.generation,self.clock.clone(),&self.owner,source,destination,cancel).await{
    Ok(value)=>value,Err(crate::mcrf_bs_stream_slots::Failure::Retired(ids))=>{(self.retire)(&ids).map_err(|_|Failure::Retirement)?;return Err(Failure::Admission)},Err(_)=>return Err(Failure::Admission),
   };
   let lookup=lease.lookup();let identity=lookup().map_err(|_|Failure::Identity)?.ok_or(Failure::Identity)?;
   let named=DestinationPin::new(self.engine.clone(),identity.epoch,destination.ip())?;
   let observed=if named.domain.is_some(){None}else{self.engine.domain(lease.owner.uid,destination.ip()).map_err(|_|Failure::Identity)?};
   let mut captured=Prefix{bytes:Vec::new(),protocol:None,domain:None};
   let decision=tokio::select!{biased;
    _=cancel.cancelled()=>return Err(Failure::Cancelled),
    result=self.router.decide(Original{source,destination,destination_domain:named.domain.as_deref(),observed_domain:observed.as_deref(),uid:lease.owner.uid,packages:&lease.owner.packages},&mut stream,&mut captured,cancel)=>result?,
   };
   if cancel.is_cancelled()||lookup().ok().flatten()!=Some(identity){return Err(self.revoke_identity(identity.epoch,cancel))}
   let fresh=match self.owner.query(6,source,destination,cancel).await{Ok(value)=>value,Err(_)=>return Err(self.revoke_identity(identity.epoch,cancel))};
   if cancel.is_cancelled()||fresh.uid!=lease.owner.uid||fresh.packages.iter().collect::<HashSet<_>>()!=lease.owner.packages.iter().collect::<HashSet<_>>()||lookup().ok().flatten()!=Some(identity){return Err(self.revoke_identity(identity.epoch,cancel))}
   match decision{
    Decision::Block=>Ok(()),
    Decision::ForwardMany{peers,target}=>{
     for peer in &peers{if self.engine.virtual_address(peer.ip()).map_err(|_|Failure::Identity)?{return Err(Failure::Route)}}
     mcrf_bs_tcp_inspect::forward_candidates(stream,&peers,self.sockets.as_ref(),captured,target,cancel).await.map_err(|_|Failure::Forward)
    },
    Decision::Forward{peer,target}=>{
     if self.engine.virtual_address(peer.ip()).map_err(|_|Failure::Identity)?{return Err(Failure::Route)}
     mcrf_bs_tcp_inspect::forward_captured(stream,peer,self.sockets.as_ref(),captured,target,cancel).await.map_err(|_|Failure::Forward)
    },
    Decision::Dns=>{
     let resolve=mcrf_bs_dns_handler::stream_handler(self.engine.clone(),self.owner.clone(),self.sockets.clone(),lookup,self.underlay.clone(),cancel).map_err(|_|Failure::Dns)?;
     crate::mcrf_bs_dns_frontend::serve_stream(&mut stream,captured.bytes,source.port(),resolve,cancel).await.map_err(|_|Failure::Dns)
    },
   }
   // Both lookup and lease remain live until forwarding/DNS has fully ended.
  }
 }

 #[cfg(test)]mod tests{
  use super::*;use serde_json::json;use std::sync::atomic::{AtomicUsize,Ordering};use tokio::io::{AsyncReadExt,AsyncWriteExt};use mcrf_bs_routing_policy::packet::{PacketInfo,TcpControl};use base64::Engine as _;
  struct Sockets(AtomicUsize);impl SocketPolicy for Sockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Ok(Box::new(()))}}
  struct Route(SocketAddr);impl Router for Route{fn decide<'a>(&'a self,original:Original<'a>,stream:&'a mut Stream,captured:&'a mut Prefix,cancel:&'a CancellationToken)->Pin<Box<dyn Future<Output=Result<Decision,Failure>>+Send+'a>>{Box::pin(async move{
   assert_eq!(original.destination_domain,None);assert_eq!(original.observed_domain,None);assert_eq!(original.uid,10001);assert_eq!(original.source.port(),45000);assert_eq!(original.destination.port(),443);assert_eq!(original.packages,&["org.allowed".to_owned()]);
   *captured=mcrf_bs_tcp_inspect::capture(stream,std::time::Duration::from_secs(1),std::mem::take(&mut captured.bytes),&[],cancel).await.map_err(|_|Failure::Route)?;assert_eq!(captured.protocol,Some("ssh"));Ok(Decision::Forward{peer:self.0,target:Target::Tunnel})
  })}}
  fn session(peer:SocketAddr)->(Session,tokio::sync::mpsc::UnboundedReceiver<String>,Arc<Sockets>){
   let mut tracker=Tracker::new(42,8).unwrap();let mut packet=PacketInfo{source:"10.89.0.2".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),ports:Some((45000,443)),protocol:6,fragment:None,tcp:Some(TcpControl{sequence:7,flags:2})};tracker.observe(42,0,&packet).unwrap();packet.tcp.as_mut().unwrap().flags=16;tracker.observe(42,1,&packet).unwrap();
   let(tx,rx)=tokio::sync::mpsc::unbounded_channel();let owner=Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());
   let engine=Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h"}],"final":"h"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Arc::new(Sockets(AtomicUsize::new(0)));
   (Session{generation:42,tracker:Arc::new(Mutex::new(tracker)),clock:Arc::new(||Ok(1)),owner,engine,sockets:sockets.clone(),underlay:Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},router:Arc::new(Route(peer)),retire:Arc::new(|_|Ok(()))},rx,sockets)
  }
  fn owners(owner:Arc<Broker>,mut rx:tokio::sync::mpsc::UnboundedReceiver<String>,changed:bool)->tokio::task::JoinHandle<()>{tokio::spawn(async move{for round in 0..2{let line=rx.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(&fields[3..],&["6","10.89.0.2","45000","192.0.2.1","443"]);let uid=if changed&&round==1{10002}else{10001};let packages=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|{uid}|{packages}",fields[2])).unwrap();}})}
  #[tokio::test]async fn authenticated_inspected_stream_forwards_once_and_releases_tracker_after_half_close(){
   tokio::time::timeout(std::time::Duration::from_secs(5),async{
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let(session,rx,sockets)=session(peer);let broker=owners(session.owner.clone(),rx,false);let tracker=session.tracker.clone();
    let server=tokio::spawn(async move{let(mut stream,_)=listener.accept().await.unwrap();let mut body=Vec::new();stream.read_to_end(&mut body).await.unwrap();assert_eq!(body,b"SSH-2.0-owned\r\n");stream.write_all(b"owned-reply").await.unwrap();stream.shutdown().await.unwrap();});
    let(mut app,inside)=tokio::io::duplex(4096);app.write_all(b"SSH-2.0-owned\r\n").await.unwrap();app.shutdown().await.unwrap();
    let worker=tokio::spawn(async move{session.serve(Box::new(inside),"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap(),&CancellationToken::new()).await});
    let mut reply=Vec::new();app.read_to_end(&mut reply).await.unwrap();assert_eq!(reply,b"owned-reply");assert_eq!(worker.await.unwrap(),Ok(()));server.await.unwrap();broker.await.unwrap();assert_eq!(sockets.0.load(Ordering::SeqCst),1);
    assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap()),Ok(None));
   }).await.unwrap()
  }
  #[tokio::test]async fn fake_destination_is_domain_only_unknown_range_is_rejected_and_pin_is_released(){
   let engine=Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"fakeip","tag":"f","inet4_range":"198.18.0.0/15"}],"final":"f"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Sockets(AtomicUsize::new(0));let packages=vec!["org.allowed".into()];
   let flow=mcrf_bs_routing_policy::Flow{source:"10.89.0.2".parse().unwrap(),destination:"198.19.255.53".parse().unwrap(),source_port:45000,port:53,network:"udp",domain:mcrf_bs_routing_policy::Observed::Pending,protocol:mcrf_bs_routing_policy::Observed::Known(Some("dns")),packages:Some(&packages),underlay:mcrf_bs_routing_policy::Observed::Known(None)};
   let mut addresses=Vec::new();for name in ["first.invalid","second.invalid"]{let query=mcrf_bs_routing_policy::dns_wire::query(1,name,1).unwrap();let answer=engine.resolve(&sockets,&query,&flow,tokio::time::Instant::now()+std::time::Duration::from_secs(1),&CancellationToken::new()).await.unwrap();addresses.push(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap()[0].ip)}
   let first=DestinationPin::new(engine.clone(),7,addresses[0]).unwrap();assert_eq!(first.domain.as_deref(),Some("first.invalid"));assert!(DestinationPin::new(engine.clone(),7,addresses[1]).is_err());drop(first);
   let second=DestinationPin::new(engine.clone(),7,addresses[1]).unwrap();assert_eq!(second.domain.as_deref(),Some("second.invalid"));assert!(DestinationPin::new(engine.clone(),8,"198.18.255.254".parse().unwrap()).is_err());assert!(DestinationPin::new(engine,9,"192.0.2.1".parse().unwrap()).unwrap().domain.is_none());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
  }
  struct PendingRoute(Arc<tokio::sync::Notify>);impl Router for PendingRoute{fn decide<'a>(&'a self,_:Original<'a>,_:&'a mut Stream,_:&'a mut Prefix,_:&'a CancellationToken)->Pin<Box<dyn Future<Output=Result<Decision,Failure>>+Send+'a>>{Box::pin(async move{self.0.notify_one();std::future::pending().await})}}
  #[tokio::test]async fn stop_interrupts_pending_policy_and_releases_accepted_flow_without_outbound(){
   tokio::time::timeout(std::time::Duration::from_secs(5),async{
    let(mut session,mut rx,sockets)=session("127.0.0.1:9".parse().unwrap());let entered=Arc::new(tokio::sync::Notify::new());session.router=Arc::new(PendingRoute(entered.clone()));let owner=session.owner.clone();let tracker=session.tracker.clone();let cancel=CancellationToken::new();let stop=cancel.clone();
    let(_app,inside)=tokio::io::duplex(4096);let worker=tokio::spawn(async move{session.serve(Box::new(inside),"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap(),&stop).await});
    let line=rx.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();let packages=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|10001|{packages}",fields[2])).unwrap();entered.notified().await;cancel.cancel();assert_eq!(worker.await.unwrap(),Err(Failure::Cancelled));assert_eq!(sockets.0.load(Ordering::SeqCst),0);
    assert_eq!(tracker.lock().unwrap().tcp_stream_identity(42,1,"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap()),Ok(None));
   }).await.unwrap()
  }
  #[tokio::test]async fn changed_kernel_owner_after_policy_revokes_cache_and_opens_no_outbound(){
   for broken_cache in [false,true]{tokio::time::timeout(std::time::Duration::from_secs(5),async{
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let(mut session,rx,sockets)=session(listener.local_addr().unwrap());let retired=Arc::new(AtomicUsize::new(0));let record=retired.clone();session.retire=Arc::new(move|ids|{assert_eq!(ids.len(),1);record.fetch_add(1,Ordering::SeqCst);if broken_cache{Err(())}else{Ok(())}});let broker=owners(session.owner.clone(),rx,true);let(mut app,inside)=tokio::io::duplex(4096);app.write_all(b"SSH-2.0-owned\r\n").await.unwrap();let cancel=CancellationToken::new();
    assert_eq!(session.serve(Box::new(inside),"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap(),&cancel).await,Err(if broken_cache{Failure::Retirement}else{Failure::Identity}));broker.await.unwrap();assert_eq!(cancel.is_cancelled(),broken_cache);assert_eq!(retired.load(Ordering::SeqCst),1);assert_eq!(session.tracker.lock().unwrap().tcp_candidate(42,1,"10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap()),Ok(None));assert_eq!(sockets.0.load(Ordering::SeqCst),0);assert!(tokio::time::timeout(std::time::Duration::from_millis(20),listener.accept()).await.is_err());
   }).await.unwrap()}
  }
 }
}
