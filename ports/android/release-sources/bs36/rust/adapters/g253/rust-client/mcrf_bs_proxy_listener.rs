//! Owned local proxy acceptors. A backend receives the real kernel client
//! tuple separately from its requested destination; it must never ask Android
//! for ownership of the latter as though it were an accepted connection.
use std::{future::Future,pin::Pin,net::SocketAddr,sync::{Arc,atomic::{AtomicU64,AtomicBool,Ordering}},time::Duration};
use tokio::{io::BufReader,net::{TcpListener,TcpStream},sync::Semaphore,task::JoinSet};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::listeners::Plan;
use futures::FutureExt;
use std::os::fd::{AsRawFd,FromRawFd,OwnedFd};
use crate::mcrf_bs_proxy_protocol::{self as protocol,Negotiated};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Config,Bind,State,Cancelled,Backend}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Event{Capacity,Handshake(protocol::Failure),Backend,Accept,Task}
pub type Report=Arc<dyn Fn(Event)+Send+Sync>;
pub type Handler=Arc<dyn Fn(Accepted,CancellationToken)->Pin<Box<dyn Future<Output=Result<(),Failure>>+Send>>+Send+Sync>;
pub struct Accepted{pub stream:BufReader<TcpStream>,pub request:Negotiated,pub id:u64,generation:u64,peer:SocketAddr,local:SocketAddr}
#[derive(Clone)]pub struct Identity{pub id:u64,generation:u64,peer:SocketAddr,local:SocketAddr,socket:Arc<OwnedFd>}
impl Identity{
 pub fn peer(&self)->SocketAddr{self.peer}
 pub fn local(&self)->SocketAddr{self.local}
 pub async fn owner(&self,broker:&crate::mcrf_bs_owner::Broker,cancel:&CancellationToken)->Result<crate::mcrf_bs_owner::Owner,crate::mcrf_bs_owner::Failure>{
  let socket=socket2::SockRef::from(self.socket.as_ref());
  if broker.generation()!=self.generation||socket.peer_addr().ok().and_then(|v|v.as_socket())!=Some(self.peer)||socket.local_addr().ok().and_then(|v|v.as_socket())!=Some(self.local){return Err(crate::mcrf_bs_owner::Failure::Stale)}
  broker.query(6,self.peer,self.local,cancel).await
 }
}
impl Accepted{
 pub fn peer(&self)->SocketAddr{self.peer}
 pub fn local(&self)->SocketAddr{self.local}
 pub fn into_parts(self)->Result<(Identity,Negotiated,BufReader<TcpStream>),Failure>{
  if self.stream.get_ref().peer_addr().ok()!=Some(self.peer)||self.stream.get_ref().local_addr().ok()!=Some(self.local){return Err(Failure::State)}
  // A scoped duplicate retains proof of THIS kernel socket while the data
  // stream is wrapped for inspection. It is never a second TCP connection.
  let fd=unsafe{libc::fcntl(self.stream.get_ref().as_raw_fd(),libc::F_DUPFD_CLOEXEC,3)};
  if fd<0{return Err(Failure::State)}let socket=Arc::new(unsafe{OwnedFd::from_raw_fd(fd)});
  Ok((Identity{id:self.id,generation:self.generation,peer:self.peer,local:self.local,socket},self.request,self.stream))
 }
 /// Only the accepted kernel tuple supplies ownership. Requested proxy IP,
 /// hostname, username or protocol fields are never identity assertions.
 pub async fn owner(&self,broker:&crate::mcrf_bs_owner::Broker,cancel:&CancellationToken)->Result<crate::mcrf_bs_owner::Owner,crate::mcrf_bs_owner::Failure>{
  if broker.generation()!=self.generation||self.stream.get_ref().peer_addr().ok()!=Some(self.peer)||self.stream.get_ref().local_addr().ok()!=Some(self.local){return Err(crate::mcrf_bs_owner::Failure::Stale)}
  broker.query(6,self.peer,self.local,cancel).await
 }
}
struct Stop(CancellationToken);impl Drop for Stop{fn drop(&mut self){self.0.cancel()}}
pub struct Group{addresses:Vec<SocketAddr>,tasks:tokio::sync::Mutex<JoinSet<()>>,cancel:CancellationToken,failed:Arc<AtomicBool>}
fn bind(address:SocketAddr)->Result<TcpListener,Failure>{
 let socket=socket2::Socket::new(if address.is_ipv4(){socket2::Domain::IPV4}else{socket2::Domain::IPV6},socket2::Type::STREAM,Some(socket2::Protocol::TCP)).map_err(|_|Failure::Bind)?;
 socket.set_reuse_address(true).map_err(|_|Failure::Bind)?;socket.set_nonblocking(true).map_err(|_|Failure::Bind)?;if address.is_ipv6(){socket.set_only_v6(true).map_err(|_|Failure::Bind)?}
 socket.bind(&address.into()).map_err(|_|Failure::Bind)?;socket.listen(128).map_err(|_|Failure::Bind)?;
 TcpListener::from_std(socket.into()).map_err(|_|Failure::Bind)
}
impl Group{
 pub fn start(generation:u64,plans:Vec<Plan>,capacity:usize,handler:Handler,report:Report,parent:&CancellationToken)->Result<Self,Failure>{
  if generation==0||generation>i64::MAX as u64||plans.is_empty()||plans.len()>16||!(1..=8192).contains(&capacity){return Err(Failure::Config)}
  if parent.is_cancelled(){return Err(Failure::Cancelled)}
  // Bind every required endpoint before exposing any acceptor. A conflict
  // drops all preceding bindings and cannot leave a partially started group.
  let mut listeners=Vec::new();let mut addresses=Vec::new();
  for plan in plans{if !plan.address.ip().is_loopback()||(plan.address.port()<1024&&!(cfg!(test)&&plan.address.port()==0)){return Err(Failure::Config)}let listener=bind(plan.address)?;addresses.push(listener.local_addr().map_err(|_|Failure::Bind)?);listeners.push((listener,Arc::new(plan)))}
  if parent.is_cancelled(){return Err(Failure::Cancelled)}
  let cancel=parent.child_token();let permits=Arc::new(Semaphore::new(capacity));let next=Arc::new(AtomicU64::new(0));let failed=Arc::new(AtomicBool::new(false));let mut tasks=JoinSet::new();
  for(listener,plan)in listeners{
   let token=cancel.clone();let permits=permits.clone();let next=next.clone();let handler=handler.clone();let report=report.clone();let failed=failed.clone();
   tasks.spawn(async move{
    let mut flows=JoinSet::new();
    let outcome=std::panic::AssertUnwindSafe(async {loop{tokio::select!{biased;
     _=token.cancelled()=>break,
     result=flows.join_next(),if !flows.is_empty()=>{if matches!(result,Some(Err(_))){failed.store(true,Ordering::Release);report(Event::Task);token.cancel();break}},
     incoming=listener.accept()=>{
      let(stream,peer)=match incoming{Ok(value)=>value,Err(_)=>{failed.store(true,Ordering::Release);report(Event::Accept);token.cancel();break}};
      let permit=match permits.clone().try_acquire_owned(){Ok(value)=>value,Err(_)=>{drop(stream);report(Event::Capacity);continue}};
      let id=match next.fetch_update(Ordering::AcqRel,Ordering::Acquire,|n|n.checked_add(1).filter(|n|*n<=i64::MAX as u64)){Ok(old)=>old+1,Err(_)=>{failed.store(true,Ordering::Release);report(Event::Task);token.cancel();break}};
      let local=match stream.local_addr(){Ok(value)=>value,Err(_)=>{drop(stream);continue}};let stopped=token.child_token();let plan=plan.clone();let handler=handler.clone();let report=report.clone();
      flows.spawn(async move{let _permit=permit;let _stop=Stop(stopped.clone());let mut stream=BufReader::new(stream);
       let request=match protocol::negotiate(&mut stream,&plan,&stopped).await{Ok(value)=>value,Err(protocol::Failure::Cancelled)=>return,Err(error)=>{report(Event::Handshake(error));return}};
       let accepted=Accepted{stream,request,id,generation,peer,local};
       let work=handler(accepted,stopped.clone());tokio::pin!(work);
       let result=tokio::select!{biased;result=&mut work=>result,_=stopped.cancelled()=>{let _=tokio::time::timeout(Duration::from_secs(2),&mut work).await;return}};if result.is_err()&&!stopped.is_cancelled(){report(Event::Backend)}
      });
     },
    }}}).catch_unwind().await;
    if outcome.is_err(){failed.store(true,Ordering::Release);token.cancel()}
    // Drop listening FD before waiting on children so no replacement launch
    // can be accepted by this retired generation.
    drop(listener);let deadline=tokio::time::Instant::now()+Duration::from_millis(2500);
    while !flows.is_empty(){if tokio::time::timeout_at(deadline,flows.join_next()).await.is_err(){failed.store(true,Ordering::Release);flows.abort_all();while flows.join_next().await.is_some(){}break}}
   });
  }
  Ok(Self{addresses,tasks:tokio::sync::Mutex::new(tasks),cancel,failed})
 }
 pub fn addresses(&self)->&[SocketAddr]{&self.addresses}
 pub fn cancellation(&self)->CancellationToken{self.cancel.child_token()}
 pub async fn close(&self)->Result<(),Failure>{
  self.cancel.cancel();let mut tasks=self.tasks.lock().await;let deadline=tokio::time::Instant::now()+Duration::from_secs(3);
  while !tasks.is_empty(){match tokio::time::timeout_at(deadline,tasks.join_next()).await{Ok(Some(Ok(())))=>{},Ok(Some(Err(_)))=>self.failed.store(true,Ordering::Release),Ok(None)=>break,Err(_)=>return Err(Failure::State)}}
  if self.failed.load(Ordering::Acquire){Err(Failure::State)}else{Ok(())}
 }
}
impl Drop for Group{fn drop(&mut self){self.cancel.cancel()}}
#[cfg(test)]#[path="mcrf_bs_proxy_listener_tests.rs"]mod tests;
