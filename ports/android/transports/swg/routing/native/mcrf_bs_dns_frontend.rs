//! Kernel DNS listeners owned by the SAME native BS process. Source-only until
//! the owned-TUN lifecycle and all profile capabilities are attached/admitted.
use std::{sync::Arc,net::{IpAddr,SocketAddr,UdpSocket},os::fd::AsRawFd,time::Duration};
use tokio::{net::TcpListener,io::{AsyncReadExt,AsyncWriteExt,unix::AsyncFd},task::{JoinSet,JoinHandle},sync::Semaphore};
use tokio_util::sync::CancellationToken;
use crate::{mcrf_bs_dns_ingress as ingress,mcrf_bs_dns_rpc::{Request,Resolver}};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Invalid,Bind,Collision,Listen,Io,Worker}
pub struct Server{pub port:u16,cancel:CancellationToken,task:Option<JoinHandle<Result<(),Failure>>>}
#[cfg(test)]static RECEIVED:std::sync::atomic::AtomicUsize=std::sync::atomic::AtomicUsize::new(0);
impl Drop for Server{fn drop(&mut self){self.cancel.cancel();if let Some(task)=&self.task{task.abort()}}}
impl Server{
 pub async fn close(mut self)->Result<(),Failure>{
  self.cancel.cancel();let mut task=self.task.take().ok_or(Failure::Worker)?;
  match tokio::time::timeout(Duration::from_secs(2),&mut task).await{Ok(result)=>result.map_err(|_|Failure::Worker)?,Err(_)=>{task.abort();let _=task.await;Err(Failure::Worker)}}
 }
}
fn socket(local:IpAddr,tcp:bool,port:u16,network:u64)->Result<socket2::Socket,Failure>{
 let socket=socket2::Socket::new(if local.is_ipv4(){socket2::Domain::IPV4}else{socket2::Domain::IPV6},if tcp{socket2::Type::STREAM}else{socket2::Type::DGRAM},Some(if tcp{socket2::Protocol::TCP}else{socket2::Protocol::UDP})).map_err(|_|Failure::Bind)?;
 socket.set_nonblocking(true).map_err(|_|Failure::Bind)?;if local.is_ipv6(){socket.set_only_v6(true).map_err(|_|Failure::Bind)?;}
 crate::mcrf_bs_network::bind_socket(network,socket.as_raw_fd()).map_err(|_|Failure::Bind)?;
 socket.bind(&SocketAddr::new(local,port).into()).map_err(|error|if error.kind()==std::io::ErrorKind::AddrInUse{Failure::Collision}else{Failure::Bind})?;if tcp{socket.listen(16).map_err(|_|Failure::Listen)?;}Ok(socket)
}
pub fn start(network:u64,index:u32,locals:&[IpAddr],mtu:usize,resolver:Resolver,parent:&CancellationToken)->Result<Server,Failure>{
 if tokio::runtime::Handle::try_current().is_err()||network==0||index==0||locals.is_empty()||locals.len()>2||!(576..=9000).contains(&mtu)||parent.is_cancelled(){return Err(Failure::Invalid)}
 if locals.iter().any(|ip|ip.is_loopback()||ip.is_unspecified()||ip.is_multicast()||*ip==alias(*ip)||ip.is_ipv6()&&mtu<1280)||locals.len()==2&&locals[0].is_ipv4()==locals[1].is_ipv4(){return Err(Failure::Invalid)}
 let mut listeners=Vec::new();let mut selected=None;
 // TCP and UDP must reserve one common port on both address families before
 // the reflector advertises it. Bounded retry only for an ephemeral collision.
 for _ in 0..8{
  listeners.clear();let mut port=0;let attempt=(||{
   for local in locals{
    let tcp:std::net::TcpListener=socket(*local,true,port,network)?.into();let selected=tcp.local_addr().map_err(|_|Failure::Listen)?.port();if selected<1024{return Err(Failure::Listen)}port=selected;
    let udp:UdpSocket=socket(*local,false,port,network)?.into();ingress::enable(&udp,local.is_ipv6()).map_err(|_|Failure::Bind)?;
    listeners.push((*local,TcpListener::from_std(tcp).map_err(|_|Failure::Listen)?,Arc::new(AsyncFd::new(udp).map_err(|_|Failure::Listen)?)));
   }Ok(())
  })();match attempt{Ok(())=>{selected=Some(port);break},Err(Failure::Collision)=>{},Err(error)=>return Err(error),}
 }
 let port=selected.ok_or(Failure::Bind)?;let cancel=parent.child_token();let stopped=cancel.clone();let fatal=parent.clone();let budget=Arc::new(Semaphore::new(16));
 let task=tokio::spawn(async move{
  let mut loops=JoinSet::new();
  for(local,tcp,udp)in listeners{
   loops.spawn(tcp_loop(tcp,alias(local),resolver.clone(),budget.clone(),stopped.clone()));
   loops.spawn(udp_loop(udp,local,index,mtu,resolver.clone(),budget.clone(),stopped.clone()));
  }
  let result=loop{tokio::select!{biased;
   _=stopped.cancelled()=>break Ok(()),
   joined=loops.join_next()=>{match joined{Some(Ok(Ok(())))if stopped.is_cancelled()=>break Ok(()),Some(Ok(Err(error)))=>break Err(error),_=>break Err(Failure::Worker)}}
  }};
  if result.is_err(){fatal.cancel()}stopped.cancel();while loops.join_next().await.is_some(){}result
 });Ok(Server{port,cancel,task:Some(task)})
}
fn alias(local:IpAddr)->IpAddr{if local.is_ipv4(){"198.19.255.53"}else{"fdfe:dcba:9876:dd54::53"}.parse().expect("fixed DNS alias")}
async fn tcp_loop(listener:TcpListener,expected_peer:IpAddr,resolver:Resolver,budget:Arc<Semaphore>,cancel:CancellationToken)->Result<(),Failure>{
 let mut tasks=JoinSet::new();let result=loop{
  tokio::select!{biased;
   _=cancel.cancelled()=>break Ok(()),
   joined=tasks.join_next(),if !tasks.is_empty()=>{if matches!(joined,Some(Err(_))){break Err(Failure::Worker)}},
   accepted=listener.accept()=>{
    let(mut stream,peer)=match accepted{Ok(value)=>value,Err(_)=>break Err(Failure::Io)};
    if peer.ip()!=expected_peer{continue}let Ok(permit)=budget.clone().try_acquire_owned()else{continue};let resolve=resolver.clone();
    let stopped=cancel.clone();tasks.spawn(async move{let _permit=permit;let _=stream.set_nodelay(true);
     let _=serve_stream(&mut stream,Vec::new(),peer.port(),resolve,&stopped).await;
    });
   }
  }
 };tasks.abort_all();while tasks.join_next().await.is_some(){}result
}
/// Reuse the SAME DNS executor for an already accepted native TUN TCP
/// stream. Inspection may have consumed one or several frames; replay them
/// exactly once before reading the stream. The resolver must authenticate the
/// original flow before selection and again before admitting each response.
pub async fn serve_stream<T:AsyncReadExt+AsyncWriteExt+Unpin>(stream:&mut T,prefix:Vec<u8>,translated_port:u16,resolve:Resolver,cancel:&CancellationToken)->Result<(),Failure>{
 if prefix.len()>131072||translated_port==0{return Err(Failure::Invalid)}
 let mut captured=std::io::Cursor::new(prefix);
 let result=tokio::select!{biased;
  _=cancel.cancelled()=>Ok(()),
  result=async{
   loop{
    let query=tokio::time::timeout(Duration::from_secs(2),async{
     // A temporary read chain releases its stream borrow before writing.
     let mut input=(&mut captured).chain(&mut *stream);
     let size=input.read_u16().await?as usize;if !(12..=4096).contains(&size){return Err(std::io::ErrorKind::InvalidData.into())}
     let mut bytes=vec![0;size];input.read_exact(&mut bytes).await?;Ok::<_,std::io::Error>(bytes)
    }).await;
    let Ok(Ok(query))=query else{return Ok(())};if mcrf_bs_routing_policy::dns_wire::question(&query).is_err(){return Err(Failure::Invalid)}
    let answer=tokio::time::timeout(mcrf_bs_routing_policy::dns_limits::RESPONSE,resolve(Request{protocol:6,translated_port,query:query.clone()})).await;
    let Ok(Ok(answer))=answer else{return Err(Failure::Io)};
    if mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).is_err(){return Err(Failure::Invalid)}
    if !matches!(tokio::time::timeout(Duration::from_secs(2),async{stream.write_u16(answer.len()as u16).await?;stream.write_all(&answer).await?;stream.flush().await}).await,Ok(Ok(()))){return Err(Failure::Io)}
   }
  }=>result,
 };
 let _=tokio::time::timeout(Duration::from_millis(300),stream.shutdown()).await;result
}
async fn datagram(socket:&AsyncFd<UdpSocket>)->std::io::Result<Option<ingress::Datagram>>{
 loop{let mut ready=socket.readable().await?;match ready.try_io(|inner|ingress::receive(inner.get_ref())){Ok(result)=>return result,Err(_)=>{}}}
}
async fn send(socket:&AsyncFd<UdpSocket>,answer:&[u8],peer:SocketAddr)->std::io::Result<()>{
 loop{let mut ready=socket.writable().await?;match ready.try_io(|inner|inner.get_ref().send_to(answer,peer)){Ok(Ok(size))if size==answer.len()=>return Ok(()),Ok(Ok(_))=>return Err(std::io::ErrorKind::WriteZero.into()),Ok(Err(error))=>return Err(error),Err(_)=>{}}}
}
async fn udp_loop(socket:Arc<AsyncFd<UdpSocket>>,local:IpAddr,index:u32,mtu:usize,resolver:Resolver,budget:Arc<Semaphore>,cancel:CancellationToken)->Result<(),Failure>{
 let mut tasks=JoinSet::new();let mut count=0u8;let result=loop{
  tokio::select!{biased;
   _=cancel.cancelled()=>break Ok(()),
   joined=tasks.join_next(),if !tasks.is_empty()=>{if matches!(joined,Some(Err(_))){break Err(Failure::Worker)}},
   received=datagram(&socket)=>{
    count=count.wrapping_add(1);if count%32==0{tokio::task::yield_now().await}
    let packet=match received{Ok(Some(value))=>value,Ok(None)=>continue,Err(_)=>break Err(Failure::Io)};
    #[cfg(test)]RECEIVED.fetch_add(1,std::sync::atomic::Ordering::SeqCst);
    if !packet.from_owned_tun(index,local)||mcrf_bs_routing_policy::dns_wire::question(&packet.bytes).is_err(){continue}
    let Ok(permit)=budget.clone().try_acquire_owned()else{continue};let resolve=resolver.clone();let socket=socket.clone();
    tasks.spawn(async move{let _permit=permit;let peer=packet.peer;let query=packet.bytes;
     if let Ok(Ok(answer))=tokio::time::timeout(mcrf_bs_routing_policy::dns_limits::RESPONSE,resolve(Request{protocol:17,translated_port:peer.port(),query:query.clone()})).await{
      if let Ok(answer)=mcrf_bs_routing_policy::dns_wire::udp_response(&query,&answer,mtu,local.is_ipv6()){let _=tokio::time::timeout(Duration::from_secs(2),send(&socket,&answer,peer)).await;}
     }
    });
   }
  }
 };tasks.abort_all();while tasks.join_next().await.is_some(){}result
}
#[cfg(test)]mod tests{
 use super::*;use std::sync::atomic::{AtomicUsize,Ordering};
 fn resolver(calls:Arc<AtomicUsize>)->Resolver{Arc::new(move|request|{let calls=calls.clone();Box::pin(async move{calls.fetch_add(1,Ordering::SeqCst);assert_eq!(request.protocol,6);mcrf_bs_routing_policy::dns_wire::answer(&request.query,0,&["192.0.2.1".parse().unwrap()],60).map_err(|_|1u8)})})}
 #[tokio::test]async fn configured_dns_work_is_not_cut_off_at_old_ten_second_watchdog(){
  tokio::time::timeout(Duration::from_secs(16),async{
   let(mut app,mut inside)=tokio::io::duplex(4096);let query=mcrf_bs_routing_policy::dns_wire::query(88,"owned.invalid",1).unwrap();
   let resolve:Resolver=Arc::new(move|request|Box::pin(async move{tokio::time::sleep(Duration::from_secs(11)).await;mcrf_bs_routing_policy::dns_wire::answer(&request.query,0,&["192.0.2.1".parse().unwrap()],60).map_err(|_|1u8)}));
   let server=tokio::spawn(async move{serve_stream(&mut inside,Vec::new(),45000,resolve,&CancellationToken::new()).await.unwrap()});
   app.write_u16(query.len()as u16).await.unwrap();app.write_all(&query).await.unwrap();app.shutdown().await.unwrap();
   let size=app.read_u16().await.unwrap();let mut answer=vec![0;size as usize];app.read_exact(&mut answer).await.unwrap();assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap().len(),1);server.await.unwrap();
  }).await.unwrap()
 }
 #[tokio::test]async fn inspected_prefix_replays_pipeline_once_and_fin_preserves_answers(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let calls=Arc::new(AtomicUsize::new(0));let(mut app,mut inside)=tokio::io::duplex(4096);let mut prefix=Vec::new();
   let queries:Vec<_>=[7,8,9].into_iter().map(|id|mcrf_bs_routing_policy::dns_wire::query(id,"owned.invalid",1).unwrap()).collect();
   for query in &queries{prefix.extend((query.len()as u16).to_be_bytes());prefix.extend(query)}
   let tail=prefix.split_off(prefix.len()-3);app.write_all(&tail).await.unwrap();app.shutdown().await.unwrap();
   let server=tokio::spawn(async move{serve_stream(&mut inside,prefix,45000,resolver(calls.clone()),&CancellationToken::new()).await.unwrap();assert_eq!(calls.load(Ordering::SeqCst),3)});
   for query in queries{let size=app.read_u16().await.unwrap();let mut bytes=vec![0;size as usize];app.read_exact(&mut bytes).await.unwrap();assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&bytes).unwrap().len(),1)}
   let mut byte=[0];assert_eq!(app.read(&mut byte).await.unwrap(),0);server.await.unwrap();
  }).await.unwrap()
 }
 #[tokio::test]async fn owned_loopback_tcp_framing_supports_multiple_queries_and_closes_on_stop(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let calls=Arc::new(AtomicUsize::new(0));let cancel=CancellationToken::new();
   // Private loopback unit seam; production ALWAYS passes reserved alias(peer).
   let server=tokio::spawn(tcp_loop(listener,peer.ip(),resolver(calls.clone()),Arc::new(Semaphore::new(16)),cancel.clone()));
   let mut client=tokio::net::TcpStream::connect(peer).await.unwrap();for id in [1,99]{let query=mcrf_bs_routing_policy::dns_wire::query(id,"owned.invalid",1).unwrap();client.write_u16(query.len()as u16).await.unwrap();client.write_all(&query).await.unwrap();let size=client.read_u16().await.unwrap();let mut bytes=vec![0;size as usize];client.read_exact(&mut bytes).await.unwrap();assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&bytes).unwrap().len(),1);}
   cancel.cancel();server.await.unwrap().unwrap();let mut bytes=[0];assert_eq!(client.read(&mut bytes).await.unwrap(),0);assert_eq!(calls.load(Ordering::SeqCst),2);
  }).await.unwrap()
 }
 #[tokio::test]async fn synthetic_peer_gate_and_cancelled_partial_frames_do_not_invoke_resolver(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let calls=Arc::new(AtomicUsize::new(0));
   for accept_loopback in [false,true]{let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let cancel=CancellationToken::new();let expected=if accept_loopback{peer.ip()}else{alias(peer.ip())};
    let server=tokio::spawn(tcp_loop(listener,expected,resolver(calls.clone()),Arc::new(Semaphore::new(1)),cancel.clone()));let mut client=tokio::net::TcpStream::connect(peer).await.unwrap();
    if accept_loopback{client.write_all(&[0]).await.unwrap();tokio::task::yield_now().await;cancel.cancel();server.await.unwrap().unwrap();let mut bytes=[0];match client.read(&mut bytes).await{Ok(0)=>{},Err(error)if error.kind()==std::io::ErrorKind::ConnectionReset=>{},other=>panic!("partial DNS socket remained open: {other:?}")}}else{let mut bytes=[0];assert_eq!(client.read(&mut bytes).await.unwrap(),0);cancel.cancel();server.await.unwrap().unwrap();}
   }assert_eq!(calls.load(Ordering::SeqCst),0);
  }).await.unwrap()
 }
 #[tokio::test]async fn udp_physical_loopback_peer_is_rejected_even_with_valid_dns_and_matching_ifindex(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let socket=UdpSocket::bind("127.0.0.1:0").unwrap();socket.set_nonblocking(true).unwrap();ingress::enable(&socket,false).unwrap();let peer=socket.local_addr().unwrap();let socket=Arc::new(AsyncFd::new(socket).unwrap());let weak=Arc::downgrade(&socket);
   let calls=Arc::new(AtomicUsize::new(0));let cancel=CancellationToken::new();let index=unsafe{libc::if_nametoindex(c"lo".as_ptr())};let server=tokio::spawn(udp_loop(socket,peer.ip(),index,1280,resolver(calls.clone()),Arc::new(Semaphore::new(16)),cancel.clone()));
   let before=RECEIVED.load(Ordering::SeqCst);let client=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let query=mcrf_bs_routing_policy::dns_wire::query(1,"owned.invalid",1).unwrap();client.send_to(&query,peer).await.unwrap();
   tokio::time::timeout(Duration::from_secs(1),async{while RECEIVED.load(Ordering::SeqCst)==before{tokio::task::yield_now().await}}).await.unwrap();let mut bytes=[0;512];assert!(tokio::time::timeout(Duration::from_millis(50),client.recv(&mut bytes)).await.is_err());
   cancel.cancel();server.await.unwrap().unwrap();assert!(weak.upgrade().is_none());assert_eq!(calls.load(Ordering::SeqCst),0);
  }).await.unwrap()
 }
}
