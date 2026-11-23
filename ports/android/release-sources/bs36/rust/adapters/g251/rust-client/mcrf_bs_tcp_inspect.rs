//! Optional handler for TCP flows that actually need stream inspection. Raw
//! classified proxy flows bypass this helper. It adds no new IP/TCP stack.
use std::{net::SocketAddr,time::Duration};
use tokio::{io::{AsyncRead,AsyncWrite,AsyncReadExt,AsyncWriteExt},time::Instant};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{Target,inspect::{self,Inspection}};
use crate::mcrf_bs_dns_transport::{self as sockets,SocketPolicy,Direction};
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Invalid,Cancelled,Read,Route,Blocked,Connect,Write,Copy}
pub struct Prefix{pub bytes:Vec<u8>,pub protocol:Option<&'static str>,pub domain:Option<String>}
pub async fn prefix<T:AsyncRead+Unpin>(stream:&mut T,timeout:Duration,cancel:&CancellationToken)->Result<Prefix,Failure>{
 if timeout.is_zero()||timeout>Duration::from_secs(5){return Err(Failure::Invalid)}
 capture(stream,timeout,Vec::new(),&[],cancel).await
}
/// Ordered sniff effects reuse the bytes already consumed by earlier effects.
/// A selected protocol never leaks metadata from an unselected sniffer. The
/// returned prefix must be forwarded once, or consumed by the native DNS server.
pub async fn capture<T:AsyncRead+Unpin>(stream:&mut T,timeout:Duration,mut bytes:Vec<u8>,sniffers:&[String],cancel:&CancellationToken)->Result<Prefix,Failure>{
 if timeout.is_zero()||timeout>Duration::from_secs(5)||bytes.len()>131072||sniffers.iter().any(|v|!mcrf_bs_routing_policy::inspect::stream_sniffer(v)){return Err(Failure::Invalid)}
 if cancel.is_cancelled(){return Err(Failure::Cancelled)}
 let selected=|protocol:&str|sniffers.is_empty()||sniffers.iter().any(|v|v==protocol);
 let deadline=Instant::now()+timeout;let mut chunk=[0;4096];
 loop{
  match inspect::tcp(&bytes,false){
   Inspection::Known{protocol,domain}=>return Ok(if selected(protocol){Prefix{bytes,protocol:Some(protocol),domain}}else{Prefix{bytes,protocol:None,domain:None}}),
   Inspection::Unknown=>return Ok(Prefix{bytes,protocol:None,domain:None}),Inspection::Pending=>{},
  }
  if bytes.len()>=131072{return Ok(Prefix{bytes,protocol:None,domain:None})}
  let size=chunk.len().min(131072-bytes.len());
  let read=tokio::select!{biased;
   _=cancel.cancelled()=>return Err(Failure::Cancelled),
   read=tokio::time::timeout_at(deadline,stream.read(&mut chunk[..size]))=>match read{
    Err(_)=>return Ok(Prefix{bytes,protocol:None,domain:None}),Ok(result)=>result.map_err(|_|Failure::Read)?,
   }
  };
  if read==0{return Ok(match inspect::tcp(&bytes,true){Inspection::Known{protocol,domain}if selected(protocol)=>Prefix{bytes,protocol:Some(protocol),domain},_=>Prefix{bytes,protocol:None,domain:None}})}
  bytes.extend_from_slice(&chunk[..read]);
 }
}
/// The caller supplies fresh original-flow ownership and captured underlay in
/// `decide`. No connection is opened before that policy returns an exact target.
pub async fn forward<T,F>(mut stream:T,peer:SocketAddr,policy:&dyn SocketPolicy,inspect_timeout:Duration,decide:F,cancel:&CancellationToken)->Result<(),Failure>
where T:AsyncRead+AsyncWrite+Unpin,F:FnOnce(&Prefix)->Result<Target,Failure>{
 let captured=prefix(&mut stream,inspect_timeout,cancel).await?;
 let target=decide(&captured)?;
 forward_captured(stream,peer,policy,captured,target,cancel).await
}
/// The ordered routing runtime may resolve a domain or revalidate ownership
/// asynchronously after inspection. It passes that exact captured prefix here;
/// never inspect/read it a second time or connect before the final decision.
pub async fn forward_captured<T:AsyncRead+AsyncWrite+Unpin>(stream:T,peer:SocketAddr,policy:&dyn SocketPolicy,captured:Prefix,target:Target,cancel:&CancellationToken)->Result<(),Failure>{
 forward_candidates(stream,&[peer],policy,captured,target,cancel).await
}
fn connect_budget(direction:Direction)->Duration{
 // A named SOCKS handshake includes transport negotiation to the remote
 // peer. Direct and raw VPN socket connection attempts still use 5 seconds.
 Duration::from_secs(if matches!(direction,Direction::Egress(_)){15}else{5})
}
async fn connect_serial(peers:&[SocketAddr],policy:&dyn SocketPolicy,direction:Direction,cancel:&CancellationToken)->Result<sockets::Leased<tokio::net::TcpStream>,Failure>{
 for peer in peers{
  let result=tokio::select!{biased;
   _=cancel.cancelled()=>return Err(Failure::Cancelled),
   result=tokio::time::timeout(connect_budget(direction),sockets::tcp(policy,*peer,direction))=>result,
  };
  if let Ok(Ok(stream))=result{return Ok(stream)}
 }Err(Failure::Connect)
}
pub(crate) async fn connect_candidates(peers:&[SocketAddr],policy:&dyn SocketPolicy,direction:Direction,cancel:&CancellationToken)->Result<sockets::Leased<tokio::net::TcpStream>,Failure>{
 if matches!(direction,Direction::Egress(_)){
  // A DNS carrier gets one negotiation budget across address fallback,
  // including any second-family attempt.
  return tokio::time::timeout(connect_budget(direction),connect_candidates_inner(peers,policy,direction,cancel)).await.map_err(|_|Failure::Connect)?
 }
 connect_candidates_inner(peers,policy,direction,cancel).await
}
async fn connect_candidates_inner(peers:&[SocketAddr],policy:&dyn SocketPolicy,direction:Direction,cancel:&CancellationToken)->Result<sockets::Leased<tokio::net::TcpStream>,Failure>{
 if peers.is_empty()||peers.len()>64||peers.iter().any(|v|v.port()==0){return Err(Failure::Invalid)}
 let (primary,secondary):(Vec<_>,Vec<_>)=peers.iter().copied().partition(|v|v.is_ipv4()==peers[0].is_ipv4());
 if secondary.is_empty(){return connect_serial(&primary,policy,direction,cancel).await}
 // At most two kernel connection attempts exist at once, one per family.
 // Failed addresses advance within their own ordered family. The winning
 // socket keeps its lease; dropping the other future closes every losing FD.
 let first=connect_serial(&primary,policy,direction,cancel);tokio::pin!(first);
 tokio::select!{biased;
  _=cancel.cancelled()=>return Err(Failure::Cancelled),
  result=&mut first=>return match result{Ok(stream)=>Ok(stream),Err(Failure::Cancelled)=>Err(Failure::Cancelled),Err(_)=>connect_serial(&secondary,policy,direction,cancel).await},
  _=tokio::time::sleep(Duration::from_millis(300))=>{},
 }
 let second=connect_serial(&secondary,policy,direction,cancel);tokio::pin!(second);
 tokio::select!{biased;
  _=cancel.cancelled()=>Err(Failure::Cancelled),
  result=&mut first=>match result{Ok(stream)=>Ok(stream),Err(Failure::Cancelled)=>Err(Failure::Cancelled),Err(_)=>second.await},
  result=&mut second=>match result{Ok(stream)=>Ok(stream),Err(Failure::Cancelled)=>Err(Failure::Cancelled),Err(_)=>first.await},
 }
}
/// Connect every resolved destination using bounded family fallback BEFORE
/// writing a captured byte. A failed address cannot consume/replay the prefix.
pub async fn forward_candidates<T:AsyncRead+AsyncWrite+Unpin>(mut stream:T,peers:&[SocketAddr],policy:&dyn SocketPolicy,captured:Prefix,target:Target,cancel:&CancellationToken)->Result<(),Failure>{
 if captured.bytes.len()>131072{return Err(Failure::Invalid)}
 let direction=match target{Target::Tunnel=>Direction::Vpn,Target::Direct=>Direction::Direct,Target::Block=>return Err(Failure::Blocked),Target::Egress(slot)=>Direction::Egress(slot)};
 let mut remote=connect_candidates(peers,policy,direction,cancel).await?;
 let result=tokio::select!{biased;
  _=cancel.cancelled()=>Err(Failure::Cancelled),
  result=async{
   remote.write_all(&captured.bytes).await.map_err(|_|Failure::Write)?;
   drop(captured);
   tokio::io::copy_bidirectional_with_sizes(&mut stream,&mut remote,65536,65536).await.map_err(|_|Failure::Copy)?;Ok(())
  }=>result,
 };
 let _=tokio::time::timeout(Duration::from_millis(300),async{let _=stream.shutdown().await;let _=remote.shutdown().await;}).await;
 result
}
#[cfg(all(test,unix))]mod tests{
 use super::*;use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};
 struct Ticket(Arc<AtomicUsize>);impl Drop for Ticket{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
 struct OwnedPolicy{opened:AtomicUsize,closed:Arc<AtomicUsize>}
 impl OwnedPolicy{fn new()->Self{Self{opened:AtomicUsize::new(0),closed:Arc::new(AtomicUsize::new(0))}}}
 impl SocketPolicy for OwnedPolicy{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,direction:Direction)->Result<Box<dyn Send>,sockets::Failure>{assert_eq!(direction,Direction::Vpn);self.opened.fetch_add(1,Ordering::SeqCst);Ok(Box::new(Ticket(self.closed.clone())))}}
 #[tokio::test(start_paused=true)]async fn slow_named_negotiation_keeps_a_bounded_budget(){
  let slow=Duration::from_secs(6);
  assert!(tokio::time::timeout(connect_budget(Direction::Egress(0)),tokio::time::sleep(slow)).await.is_ok());
  for direction in [Direction::Direct,Direction::Vpn]{
   assert!(tokio::time::timeout(connect_budget(direction),tokio::time::sleep(slow)).await.is_err());
  }
  assert!(tokio::time::timeout(connect_budget(Direction::Egress(0)),tokio::time::sleep(Duration::from_secs(16))).await.is_err());
 }
 #[tokio::test]async fn ordered_sniff_effects_reuse_bytes_and_do_not_attribute_unselected_protocol(){
  let(mut client,mut stream)=tokio::io::duplex(4096);let request=b"GET / HTTP/1.1\r\nHost: owned.invalid\r\n\r\n";client.write_all(request).await.unwrap();
  let first=capture(&mut stream,Duration::from_millis(100),Vec::new(),&["tls".into()],&CancellationToken::new()).await.unwrap();
  assert!(first.protocol.is_none()&&first.domain.is_none());assert_eq!(first.bytes,request);
  // No additional bytes or EOF are supplied: this must classify the retained
  // buffer immediately, rather than read the application stream a second time.
  let second=tokio::time::timeout(Duration::from_millis(50),capture(&mut stream,Duration::from_secs(1),first.bytes,&["http".into()],&CancellationToken::new())).await.unwrap().unwrap();
  assert_eq!(second.protocol,Some("http"));assert_eq!(second.domain.as_deref(),Some("owned.invalid"));assert_eq!(second.bytes,request);
 }
 #[tokio::test]async fn partial_prefix_continues_once_and_cancelled_buffer_never_yields_metadata(){
  let(mut client,mut stream)=tokio::io::duplex(4096);client.write_all(b"T / HTTP/1.1\r\nHost: owned.invalid\r\n\r\n").await.unwrap();
  let result=capture(&mut stream,Duration::from_millis(100),b"GE".to_vec(),&[],&CancellationToken::new()).await.unwrap();assert_eq!(result.protocol,Some("http"));assert!(result.bytes.starts_with(b"GET /"));
  let cancelled=CancellationToken::new();cancelled.cancel();
  assert!(matches!(capture(&mut stream,Duration::from_secs(1),result.bytes,&[],&cancelled).await,Err(Failure::Cancelled)));
  assert!(matches!(capture(&mut stream,Duration::from_secs(1),Vec::new(),&["unsupported".into()],&CancellationToken::new()).await,Err(Failure::Invalid)));
 }
 #[tokio::test]async fn failed_address_or_family_uses_next_without_replaying_captured_bytes(){
  for first_v6 in [false,true]{tokio::time::timeout(Duration::from_secs(5),async{
   let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();
   let unavailable=tokio::net::TcpListener::bind(if first_v6{"[::1]:0"}else{"127.0.0.1:0"}).await.unwrap();let failed=unavailable.local_addr().unwrap();drop(unavailable);
   let server=tokio::spawn(async move{let(mut io,_)=listener.accept().await.unwrap();let mut bytes=Vec::new();io.read_to_end(&mut bytes).await.unwrap();assert_eq!(bytes,b"once:body");io.write_all(b"reply").await.unwrap();io.shutdown().await.unwrap();});
   let(mut app,inside)=tokio::io::duplex(4096);app.write_all(b"body").await.unwrap();app.shutdown().await.unwrap();let policy=Arc::new(OwnedPolicy::new());let scoped=policy.clone();
   let worker=tokio::spawn(async move{forward_candidates(inside,&[failed,peer],scoped.as_ref(),Prefix{bytes:b"once:".to_vec(),protocol:None,domain:None},Target::Tunnel,&CancellationToken::new()).await});
   let mut response=Vec::new();app.read_to_end(&mut response).await.unwrap();assert_eq!(response,b"reply");assert_eq!(worker.await.unwrap(),Ok(()));server.await.unwrap();assert_eq!(policy.opened.load(Ordering::SeqCst),2);assert_eq!(policy.closed.load(Ordering::SeqCst),2);
  }).await.unwrap()}
 }
 #[tokio::test]async fn cancelled_or_invalid_candidate_list_never_opens_a_socket(){
  let policy=OwnedPolicy::new();let cancel=CancellationToken::new();cancel.cancel();let peers=["127.0.0.1:9".parse().unwrap(),"[::1]:9".parse().unwrap()];let(_app,inside)=tokio::io::duplex(1024);
  assert_eq!(forward_candidates(inside,&peers,&policy,Prefix{bytes:b"already-inspected".to_vec(),protocol:None,domain:None},Target::Tunnel,&cancel).await,Err(Failure::Cancelled));
  assert!(matches!(connect_candidates(&[],&policy,Direction::Vpn,&CancellationToken::new()).await,Err(Failure::Invalid)));assert!(matches!(connect_candidates(&["127.0.0.1:0".parse().unwrap()],&policy,Direction::Vpn,&CancellationToken::new()).await,Err(Failure::Invalid)));assert_eq!(policy.opened.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn buffered_prefix_is_written_once_and_half_close_preserves_response(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();
   let payload=b"GET / HTTP/1.1\r\nHost: owned.invalid\r\n\r\nbody";
   let server=tokio::spawn(async move{let(mut stream,_)=listener.accept().await.unwrap();let mut body=Vec::new();stream.read_to_end(&mut body).await.unwrap();assert_eq!(body,payload);stream.write_all(b"reply-after-fin").await.unwrap();stream.shutdown().await.unwrap();});
   let(mut client,inside)=tokio::io::duplex(4096);let app=tokio::spawn(async move{client.write_all(&payload[..9]).await.unwrap();tokio::task::yield_now().await;client.write_all(&payload[9..]).await.unwrap();client.shutdown().await.unwrap();let mut reply=Vec::new();client.read_to_end(&mut reply).await.unwrap();assert_eq!(reply,b"reply-after-fin");});
   let policy=OwnedPolicy::new();forward(inside,peer,&policy,Duration::from_secs(1),|meta|{assert_eq!(meta.protocol,Some("http"));assert_eq!(meta.domain.as_deref(),Some("owned.invalid"));Ok(Target::Tunnel)},&CancellationToken::new()).await.unwrap();
   app.await.unwrap();server.await.unwrap();assert_eq!(policy.opened.load(Ordering::SeqCst),1);assert_eq!(policy.closed.load(Ordering::SeqCst),1);
  }).await.unwrap()
 }
 #[tokio::test]async fn block_and_cancel_never_open_outbound_and_timeout_preserves_prefix(){
  let policy=OwnedPolicy::new();let(mut app,inside)=tokio::io::duplex(4096);app.write_all(b"SSH-2.0-owned").await.unwrap();
  assert_eq!(forward(inside,"127.0.0.1:9".parse().unwrap(),&policy,Duration::from_millis(20),|_|Ok(Target::Block),&CancellationToken::new()).await,Err(Failure::Blocked));
  let(_app,inside)=tokio::io::duplex(4096);let cancel=CancellationToken::new();cancel.cancel();assert_eq!(forward(inside,"127.0.0.1:9".parse().unwrap(),&policy,Duration::from_secs(1),|_|panic!("cancelled policy must not run"),&cancel).await,Err(Failure::Cancelled));
  let(mut app,mut inside)=tokio::io::duplex(4096);app.write_all(b"GET ").await.unwrap();let result=prefix(&mut inside,Duration::from_millis(20),&CancellationToken::new()).await.unwrap();assert_eq!(result.bytes,b"GET ");assert!(result.domain.is_none());
  assert_eq!(policy.opened.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn stop_during_established_copy_closes_outbound_and_lease(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let cancel=CancellationToken::new();let signal=cancel.clone();
   let server=tokio::spawn(async move{let(mut stream,_)=listener.accept().await.unwrap();let mut bytes=[0;3];stream.read_exact(&mut bytes).await.unwrap();assert_eq!(&bytes,b"SSH");signal.cancel();let mut rest=Vec::new();stream.read_to_end(&mut rest).await.unwrap();assert!(rest.is_empty());});
   let(mut app,inside)=tokio::io::duplex(4096);app.write_all(b"SSH").await.unwrap();let policy=OwnedPolicy::new();
   assert_eq!(forward(inside,peer,&policy,Duration::from_secs(1),|_|Ok(Target::Tunnel),&cancel).await,Err(Failure::Cancelled));server.await.unwrap();assert_eq!(policy.closed.load(Ordering::SeqCst),1);
  }).await.unwrap()
 }
}
