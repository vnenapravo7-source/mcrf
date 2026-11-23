//! SOCKS UDP associations share the session UDP policy and selected connector.
//! The TCP control socket and actual client UDP tuple supply separate proofs.
use std::{sync::Arc,net::SocketAddr,collections::{HashMap,HashSet},time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt,BufReader},net::{TcpStream,UdpSocket},sync::{mpsc,Semaphore},task::JoinSet,time::Instant};
use tokio_util::sync::CancellationToken;
use futures::FutureExt;
use mcrf_bs_routing_policy::{Flow,Observed};
use crate::{mcrf_bs_proxy_listener::{Identity,Failure},mcrf_bs_proxy_protocol::{self as protocol,Destination},mcrf_bs_owner::Owner,mcrf_bs_udp_dispatch::Session,mcrf_bs_udp_policy::Decision,mcrf_bs_udp_inspect::Captured,mcrf_bs_datagram::Datagram,mcrf_bs_tcp_dispatch::owned::Original};
pub struct Limits{pub bytes:Arc<Semaphore>,pub flows:Arc<Semaphore>}
impl Limits{pub fn new()->Self{Self{bytes:Arc::new(Semaphore::new(2*1024*1024)),flows:Arc::new(Semaphore::new(64))}}}
fn same(a:&Owner,b:&Owner)->bool{a.uid==b.uid&&a.packages.iter().collect::<HashSet<_>>()==b.packages.iter().collect::<HashSet<_>>()}
struct Proof{session:Arc<Session>,control:Identity,expected:Arc<Owner>,source:SocketAddr,local:SocketAddr}
impl Proof{async fn fresh(&self,cancel:&CancellationToken)->Result<(),Failure>{
 let tcp=self.control.owner(&self.session.owner,cancel).await.map_err(|_|Failure::Backend)?;if !same(&tcp,&self.expected){return Err(Failure::Backend)}
 let udp=self.session.owner.query(17,self.source,self.local,cancel).await.map_err(|_|Failure::Backend)?;if cancel.is_cancelled()||!same(&udp,&self.expected){return Err(Failure::Backend)}Ok(())
}}
fn dns(proof:Arc<Proof>,destination:SocketAddr,cancel:CancellationToken)->crate::mcrf_bs_dns_rpc::Resolver{Arc::new(move|request|{let(proof,cancel)=(proof.clone(),cancel.clone());Box::pin(async move{
 if request.protocol!=17{return Err(3)}let deadline=Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE;
 tokio::time::timeout_at(deadline,proof.fresh(&cancel)).await.map_err(|_|7u8)?.map_err(|_|8u8)?;
 let flow=Flow{source:proof.source.ip(),destination:destination.ip(),source_port:proof.source.port(),port:destination.port(),network:"udp",domain:Observed::Pending,protocol:Observed::Known(Some("dns")),packages:Some(&proof.expected.packages),underlay:Observed::Known(Some(proof.session.underlay.borrowed()))};
 let answer=proof.session.engine.resolve(proof.session.sockets.as_ref(),&request.query,&flow,deadline,&cancel).await.map_err(|_|5u8)?;
 tokio::time::timeout_at(deadline,proof.fresh(&cancel)).await.map_err(|_|7u8)?.map_err(|_|8u8)?;proof.session.engine.admit(&request.query,&answer,proof.expected.uid).map_err(|_|9u8)?;Ok(answer)
})})}
async fn reply(socket:&UdpSocket,proof:&Proof,destination:&Destination,payload:&[u8],cancel:&CancellationToken)->Result<(),Failure>{
 proof.fresh(cancel).await?;let bytes=protocol::datagram_reply(destination,payload).map_err(|_|Failure::Backend)?;
 let size=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),value=tokio::time::timeout(Duration::from_secs(2),socket.send_to(&bytes,proof.source))=>value.map_err(|_|Failure::Backend)?.map_err(|_|Failure::Backend)?};if size!=bytes.len(){return Err(Failure::Backend)}Ok(())
}
async fn actor(proof:Arc<Proof>,socket:Arc<UdpSocket>,requested:Destination,mut input:mpsc::Receiver<Datagram>,cancel:CancellationToken)->Result<(),Failure>{
 proof.fresh(&cancel).await?;
 let(destination,name)=match &requested{Destination::Address(v)=>(*v,proof.session.engine.proxy_destination_name(v.ip()).map_err(|_|Failure::Backend)?),Destination::Domain(name,port)=>(SocketAddr::from(([0,0,0,0],*port)),Some(name.clone()))};
 let observed=if name.is_some(){None}else{proof.session.engine.domain(proof.expected.uid,destination.ip()).map_err(|_|Failure::Backend)?};let mut captured=Captured::default();
 let decision=proof.session.router.decide(Original{source:proof.source,destination,destination_domain:name.as_deref(),observed_domain:observed.as_deref(),uid:proof.expected.uid,packages:&proof.expected.packages},&mut input,&mut captured,&cancel).await.map_err(|_|Failure::Backend)?;
 proof.fresh(&cancel).await?;
 match decision{
 Decision::Block=>Ok(()),
 Decision::Dns{idle}=>{let resolve=dns(proof.clone(),destination,cancel.clone());let mut queued=captured.packets.into_iter();loop{
  let packet=if let Some(packet)=queued.next(){packet}else{tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),v=tokio::time::timeout(idle,input.recv())=>match v{Ok(Some(v))=>v,_=>return Ok(())}}};
  let answer=resolve(crate::mcrf_bs_dns_rpc::Request{protocol:17,translated_port:proof.source.port(),query:packet.to_vec()}).await.map_err(|_|Failure::Backend)?;reply(&socket,&proof,&requested,&answer,&cancel).await?;
 }},
 Decision::Forward{peers,target,options}=>{
  let peer=*peers.first().ok_or(Failure::Backend)?;if proof.session.engine.virtual_address(peer.ip()).map_err(|_|Failure::Backend)?{return Err(Failure::Backend)}
  let connection=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),v=tokio::time::timeout(Duration::from_secs(5),proof.session.connector.open(proof.source,destination,peer,target,&options,&cancel))=>v.map_err(|_|Failure::Backend)?.map_err(|_|Failure::Backend)?};
  for packet in captured.packets{proof.fresh(&cancel).await?;tokio::time::timeout(Duration::from_secs(5),connection.send(&packet,&cancel)).await.map_err(|_|Failure::Backend)?.map_err(|_|Failure::Backend)?}
  let reply_source=if options.domain_destination&&options.disable_domain_unmapping==Some(true){Destination::Address(peer)}else{requested};
  let idle=options.idle(captured.protocol,destination.port());let mut deadline=Instant::now()+idle;let mut response=vec![0;65535];let mut burst=0u8;
  loop{burst=burst.wrapping_add(1);if burst%32==0{tokio::task::yield_now().await}tokio::select!{
   _=cancel.cancelled()=>return Err(Failure::Cancelled),_=tokio::time::sleep_until(deadline)=>return Ok(()),
   packet=input.recv()=>{let Some(packet)=packet else{return Ok(())};proof.fresh(&cancel).await?;tokio::time::timeout(Duration::from_secs(5),connection.send(&packet,&cancel)).await.map_err(|_|Failure::Backend)?.map_err(|_|Failure::Backend)?;deadline=Instant::now()+idle},
   value=connection.receive(&mut response,&cancel)=>{let size=value.map_err(|_|Failure::Backend)?;if size>response.len(){return Err(Failure::Backend)}reply(&socket,&proof,&reply_source,&response[..size],&cancel).await?;deadline=Instant::now()+idle},
  }}
 }
 }
}
pub async fn associate(session:Arc<Session>,control:Identity,expected:Arc<Owner>,mut stream:BufReader<TcpStream>,requested:Destination,limits:Arc<Limits>,parent:&CancellationToken)->Result<(),Failure>{
 let wanted=match requested{Destination::Address(v)=>v,Destination::Domain(_,_)=>return Err(Failure::Backend)};
 if wanted.is_ipv4()!=control.peer().is_ipv4()||!wanted.ip().is_unspecified()&&wanted.ip()!=control.peer().ip(){return Err(Failure::Backend)}
 let socket=Arc::new(UdpSocket::bind(SocketAddr::new(control.local().ip(),0)).await.map_err(|_|Failure::Bind)?);let local=socket.local_addr().map_err(|_|Failure::Bind)?;
 let owner=control.owner(&session.owner,parent).await.map_err(|_|Failure::Backend)?;if !same(&owner,&expected){return Err(Failure::Backend)}
 tokio::select!{biased;_=parent.cancelled()=>return Err(Failure::Cancelled),value=tokio::time::timeout(Duration::from_secs(5),async{stream.write_all(&protocol::success(protocol::Protocol::Socks5,Some(local))).await?;stream.flush().await})=>value.map_err(|_|Failure::Backend)?.map_err(|_|Failure::Backend)?};
 let cancel=parent.child_token();let mut tasks=JoinSet::new();let mut flows=HashMap::<Destination,(u64,mpsc::Sender<Datagram>)>::new();let mut next=0u64;let mut client=None;let mut bytes=vec![0;65535];let mut control_byte=[0;1];
 let result=std::panic::AssertUnwindSafe(async{loop{tokio::select!{biased;
  _=cancel.cancelled()=>return Err(Failure::Cancelled),
  value=stream.read(&mut control_byte)=>{match value{Ok(0)=>return Ok(()),_=>return Err(Failure::Backend)}},
  value=tasks.join_next(),if !tasks.is_empty()=>{match value{Some(Ok((key,id)))=>{if flows.get(&key).is_some_and(|v|v.0==id){flows.remove(&key);}},Some(Err(_))=>return Err(Failure::Backend),None=>{}}},
  value=socket.recv_from(&mut bytes)=>{
   let(size,source)=value.map_err(|_|Failure::Backend)?;if source.ip()!=control.peer().ip()||wanted.port()!=0&&wanted.port()!=source.port()||client.is_some_and(|v|v!=source){continue}
   let Ok((destination,payload))=protocol::datagram(&bytes[..size]).await else{continue};
   let proof=Arc::new(Proof{session:session.clone(),control:control.clone(),expected:expected.clone(),source,local});if proof.fresh(&cancel).await.is_err(){continue}client=Some(source);
   let Ok(budget)=limits.bytes.clone().try_acquire_many_owned(payload.len().max(1)as u32)else{continue};let packet=Datagram::budgeted(payload.to_vec(),budget).map_err(|_|Failure::Backend)?;
   if let Some((_,tx))=flows.get(&destination){if !tx.is_closed(){let _=tx.try_send(packet);continue}flows.remove(&destination);}
   if flows.len()>=32{continue}let Ok(permit)=limits.flows.clone().try_acquire_owned()else{continue};let(tx,rx)=mpsc::channel(8);tx.try_send(packet).map_err(|_|Failure::Backend)?;next=next.checked_add(1).ok_or(Failure::State)?;let id=next;flows.insert(destination.clone(),(id,tx));let socket=socket.clone();let token=cancel.clone();tasks.spawn(async move{let _permit=permit;if let Err(error)=actor(proof,socket,destination.clone(),rx,token.clone()).await{if !token.is_cancelled(){crate::log_error!("BS_NATIVE_PROXY_UDP_FLOW {error:?}")}}(destination,id)});
  }
 }}}).catch_unwind().await.unwrap_or(Err(Failure::State));
 cancel.cancel();flows.clear();while tasks.join_next().await.is_some(){}result
}

#[cfg(test)]#[path="mcrf_bs_proxy_udp_tests.rs"]mod tests;
