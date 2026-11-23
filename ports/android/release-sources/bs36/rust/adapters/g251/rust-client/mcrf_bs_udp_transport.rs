//! Selected UDP legs of the same-process flow runtime. Tunnel sockets carry
//! scoped leases through the owned BS VPN Network; errors never fall back.
use std::{sync::{Arc,Mutex},net::{SocketAddr,IpAddr,Ipv4Addr,Ipv6Addr},os::fd::AsRawFd};
use tokio::net::UdpSocket;use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::Target;
use crate::{mcrf_bs_dns_transport::{self as sockets,SocketPolicy,Direction},mcrf_bs_udp_policy::Options,mcrf_bs_udp_dispatch::{Connection,Connector,Task,Failure}};
pub struct Direct{pub policy:Arc<dyn SocketPolicy>}
/// The VPN leg is explicitly bound to this SAME BS session's owned Android
/// VPN Network. NativePolicy registers its exact tuple before send; the raw
/// carrier admits only that live lease. No primary engine or SOCKS is started.
pub struct Selected{pub policy:Arc<dyn SocketPolicy>}
pub struct Named{base:Arc<dyn Connector>,policy:Arc<dyn SocketPolicy>,capacity:Arc<tokio::sync::Semaphore>}
impl Named{
 pub fn new(base:Arc<dyn Connector>,policy:Arc<dyn SocketPolicy>)->Self{
  Self{base,policy,capacity:Arc::new(tokio::sync::Semaphore::new(256))}
 }
}
// Revoke the tuple BEFORE closing the FD, so port reuse cannot inherit it.
struct Socket{_lease:Mutex<Box<dyn Send>>,io:UdpSocket,peer:SocketAddr,connected:bool}
impl Connection for Socket{
 fn send<'a>(&'a self,bytes:&'a [u8],cancel:&'a CancellationToken)->Task<'a,()>{Box::pin(async move{
  if bytes.len()>65535{return Err(Failure::Invalid)}
  let size=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=async{if self.connected{self.io.send(bytes).await}else{self.io.send_to(bytes,self.peer).await}}=>result.map_err(|_|Failure::Io)?};if size==bytes.len(){Ok(())}else{Err(Failure::Io)}
 })}
 fn receive<'a>(&'a self,bytes:&'a mut [u8],cancel:&'a CancellationToken)->Task<'a,usize>{Box::pin(async move{
  let mut count=0u8;loop{
   let(size,peer)=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=self.io.recv_from(bytes)=>result.map_err(|_|Failure::Io)?};
   if peer==self.peer{return Ok(size)}
   // An unconnected socket never grants reply access to arbitrary senders.
   count=count.wrapping_add(1);if count%32==0{tokio::task::yield_now().await}
  }
 })}
}
async fn open_leg(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction,options:&Options,cancel:&CancellationToken)->Result<Arc<dyn Connection>,Failure>{
 if cancel.is_cancelled(){return Err(Failure::Cancelled)}
 if peer.port()==0||peer.ip().is_unspecified()||peer.ip().is_multicast(){return Err(Failure::Invalid)}
 let loopback=direction==Direction::Direct&&peer.ip().is_loopback();let local=SocketAddr::new(if peer.is_ipv4(){IpAddr::V4(if loopback{Ipv4Addr::LOCALHOST}else{Ipv4Addr::UNSPECIFIED})}else{IpAddr::V6(if loopback{Ipv6Addr::LOCALHOST}else{Ipv6Addr::UNSPECIFIED})},0);
 let io=UdpSocket::bind(local).await.map_err(|_|Failure::Connect)?;
 let lease=policy.prepare(17,io.as_raw_fd(),io.local_addr().map_err(|_|Failure::Connect)?.port(),peer,direction).map_err(|_|Failure::Connect)?;
 let connected=options.connect.unwrap_or(false);if connected{tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=io.connect(peer)=>result.map_err(|_|Failure::Connect)?}}
 Ok(Arc::new(Socket{io,peer,connected,_lease:Mutex::new(lease)})as Arc<dyn Connection>)
}
impl Connector for Direct{
 fn open<'a>(&'a self,_source:SocketAddr,_destination:SocketAddr,peer:SocketAddr,target:Target,options:&'a Options,cancel:&'a CancellationToken)->Task<'a,Arc<dyn Connection>>{Box::pin(async move{
  if target!=Target::Direct{return Err(Failure::Connect)}open_leg(self.policy.as_ref(),peer,Direction::Direct,options,cancel).await
 })}
}
impl Connector for Selected{
 fn open<'a>(&'a self,_source:SocketAddr,_destination:SocketAddr,peer:SocketAddr,target:Target,options:&'a Options,cancel:&'a CancellationToken)->Task<'a,Arc<dyn Connection>>{Box::pin(async move{
  let direction=match target{Target::Direct=>Direction::Direct,Target::Tunnel=>Direction::Vpn,Target::Block|Target::Egress(_)=>return Err(Failure::Connect)};
  open_leg(self.policy.as_ref(),peer,direction,options,cancel).await
 })}
}
struct SocksDatagrams{_permit:tokio::sync::OwnedSemaphorePermit,socket:sockets::SocksUdp}
impl Connection for SocksDatagrams{
 fn send<'a>(&'a self,bytes:&'a [u8],cancel:&'a CancellationToken)->Task<'a,()>{Box::pin(async move{
  tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),result=self.socket.send(bytes)=>result.map_err(named_failure)}
 })}
 fn receive<'a>(&'a self,bytes:&'a mut [u8],cancel:&'a CancellationToken)->Task<'a,usize>{Box::pin(async move{
  tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),result=self.socket.receive(bytes)=>result.map_err(named_failure)}
 })}
}
fn named_failure(error:sockets::Failure)->Failure{
 if error==sockets::Failure::Invalid{Failure::Invalid}else{Failure::Io}
}
impl Connector for Named{
 fn open<'a>(&'a self,source:SocketAddr,destination:SocketAddr,peer:SocketAddr,target:Target,options:&'a Options,cancel:&'a CancellationToken)->Task<'a,Arc<dyn Connection>>{Box::pin(async move{
  let Target::Egress(slot)=target else{return self.base.open(source,destination,peer,target,options,cancel).await};
  let permit=self.capacity.clone().try_acquire_owned().map_err(|_|Failure::Admission)?;
  let socket=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),
   result=tokio::time::timeout(std::time::Duration::from_secs(5),sockets::SocksUdp::open(self.policy.as_ref(),slot,peer))=>result.map_err(|_|Failure::Connect)?.map_err(|_|Failure::Connect)?};
  Ok(Arc::new(SocksDatagrams{_permit:permit,socket}) as Arc<dyn Connection>)
 })}
}
#[cfg(test)]mod tests{
 use super::*;use std::{sync::atomic::{AtomicUsize,Ordering},time::Duration};
 struct Lease(Arc<AtomicUsize>);impl Drop for Lease{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
 struct Policy{port:AtomicUsize,dropped:Arc<AtomicUsize>,fail:bool}
 impl SocketPolicy for Policy{fn prepare(&self,protocol:u8,fd:i32,port:u16,_:SocketAddr,direction:Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{assert_eq!(protocol,17);assert!(fd>=0&&port>0);assert_eq!(direction,Direction::Direct);self.port.store(usize::from(port),Ordering::SeqCst);if self.fail{return Err(crate::mcrf_bs_dns_transport::Failure::Bind)}Ok(Box::new(Lease(self.dropped.clone())))}}
 fn policy(fail:bool)->Arc<Policy>{Arc::new(Policy{port:AtomicUsize::new(0),dropped:Arc::new(AtomicUsize::new(0)),fail})}
 #[tokio::test]
 async fn named_udp_uses_only_the_owned_relay_and_filters_bad_destination_headers(){
  use tokio::io::{AsyncReadExt,AsyncWriteExt};
  tokio::time::timeout(Duration::from_secs(5),async{
   struct LocalOnly;
   impl SocketPolicy for LocalOnly{
    fn prepare(&self,protocol:u8,_:i32,_:u16,peer:SocketAddr,direction:Direction)->Result<Box<dyn Send>,sockets::Failure>{
     assert_eq!(protocol,6);assert!(peer.ip().is_loopback());assert_eq!(direction,Direction::Direct);Ok(Box::new(()))
    }
   }
   let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
   let relay=UdpSocket::bind("127.0.0.1:0").await.unwrap();
   let port=listener.local_addr().unwrap().port();
   let bound=relay.local_addr().unwrap();
   let peer="[2001:db8::8]:5353".parse::<SocketAddr>().unwrap();
   let worker=tokio::spawn(async move{
    let(mut stream,_)=listener.accept().await.unwrap();
    let mut header=[0;3];stream.read_exact(&mut header).await.unwrap();assert_eq!(header,[5,1,2]);
    stream.write_all(&[5,2]).await.unwrap();assert_eq!(stream.read_u8().await.unwrap(),1);
    for expected in [b"fixture-socks-user".as_slice(),b"fixture-socks-pass".as_slice()]{
     let length=stream.read_u8().await.unwrap() as usize;
     let mut value=vec![0;length];stream.read_exact(&mut value).await.unwrap();assert_eq!(value,expected);
    }
    stream.write_all(&[1,0]).await.unwrap();
    stream.read_exact(&mut header).await.unwrap();assert_eq!(header,[5,3,0]);
    let client=sockets::read_socks_address(&mut stream).await.unwrap().unwrap();
    assert!(client.ip().is_loopback());
    let mut result=vec![5,0,0];sockets::encode_socks_address(bound,&mut result);
    stream.write_all(&result).await.unwrap();
    let mut buffer=[0;80];let (size,from)=relay.recv_from(&mut buffer).await.unwrap();
    assert_eq!(client,from);assert_eq!(&buffer[..3],&[0,0,0]);
    let mut packet=std::io::Cursor::new(&buffer[3..size]);
    assert_eq!(sockets::read_socks_address(&mut packet).await.unwrap(),Some(peer));
    assert_eq!(&buffer[3+packet.position() as usize..size],b"owned");
    let mut response=vec![0,0,0];sockets::encode_socks_address("192.0.2.8:5353".parse().unwrap(),&mut response);
    response.extend_from_slice(b"wrong peer");relay.send_to(&response,from).await.unwrap();
    let mut response=vec![0,0,0];sockets::encode_socks_address(peer,&mut response);
    response.extend_from_slice(b"correct peer");relay.send_to(&response,from).await.unwrap();
    assert!(stream.read_u8().await.is_err());
   });
   let endpoints=serde_json::json!([{"port":port,"username":"fixture-socks-user","password":"fixture-socks-pass"}]);
   let policy=sockets::with_egresses(Some(&endpoints),Arc::new(LocalOnly)).unwrap();
   let connector=Named::new(Arc::new(Direct{policy:policy.clone()}),policy);
   let stop=CancellationToken::new();
   let flow=connector.open(peer,peer,peer,Target::Egress(0),&Options::default(),&stop).await.unwrap();
   assert_eq!(connector.capacity.available_permits(),255);
   flow.send(b"owned",&stop).await.unwrap();
   let mut output=[0;80];let size=flow.receive(&mut output,&stop).await.unwrap();
   assert_eq!(&output[..size],b"correct peer");
   drop(flow);worker.await.unwrap();
   assert_eq!(connector.capacity.available_permits(),256);
  }).await.unwrap()
 }
 #[tokio::test]async fn connected_and_unconnected_owned_udp_preserve_payload_zero_datagrams_and_reject_other_peers(){
  tokio::time::timeout(Duration::from_secs(5),async{for connected in [false,true]{let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let stranger=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();let policy=policy(false);let connector=Direct{policy:policy.clone()};let options=Options{connect:Some(connected),..Options::default()};let stop=CancellationToken::new();let flow=connector.open(peer,peer,peer,Target::Direct,&options,&stop).await.unwrap();let mut buffer=[0;128];for data in [b"owned-udp".as_slice(),b"".as_slice()]{flow.send(data,&stop).await.unwrap();let(size,local)=server.recv_from(&mut buffer).await.unwrap();assert_eq!(&buffer[..size],data);assert_eq!(usize::from(local.port()),policy.port.load(Ordering::SeqCst));stranger.send_to(b"unsolicited",local).await.unwrap();server.send_to(data,local).await.unwrap();let size=flow.receive(&mut buffer,&stop).await.unwrap();assert_eq!(&buffer[..size],data);}drop(flow);assert_eq!(policy.dropped.load(Ordering::SeqCst),1);}}).await.unwrap()
 }
 #[tokio::test]async fn tunnel_request_and_failed_physical_binding_never_send_direct(){
  let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();let p=policy(false);let connector=Direct{policy:p.clone()};assert!(matches!(connector.open(peer,peer,peer,Target::Tunnel,&Options::default(),&CancellationToken::new()).await,Err(Failure::Connect)));assert_eq!(p.port.load(Ordering::SeqCst),0);let p=policy(true);let connector=Direct{policy:p.clone()};assert!(matches!(connector.open(peer,peer,peer,Target::Direct,&Options::default(),&CancellationToken::new()).await,Err(Failure::Connect)));assert!(p.port.load(Ordering::SeqCst)>0);assert!(server.try_recv_from(&mut [0;16]).is_err());
 }
 #[tokio::test]async fn cancellation_interrupts_waiting_receive_and_releases_descriptor_lease(){
  tokio::time::timeout(Duration::from_secs(5),async{let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();let policy=policy(false);let connector=Direct{policy:policy.clone()};let stop=CancellationToken::new();let flow=connector.open(peer,peer,peer,Target::Direct,&Options::default(),&stop).await.unwrap();let stopped=stop.clone();let worker=tokio::spawn(async move{let mut bytes=[0;128];flow.receive(&mut bytes,&stopped).await});tokio::task::yield_now().await;stop.cancel();assert_eq!(worker.await.unwrap(),Err(Failure::Cancelled));assert_eq!(policy.dropped.load(Ordering::SeqCst),1);}).await.unwrap()
 }
 #[tokio::test]async fn selected_route_registers_exact_direction_before_send_and_never_falls_back(){
  struct Directions{seen:Mutex<Vec<Direction>>,fail:bool}
  impl SocketPolicy for Directions{fn prepare(&self,protocol:u8,_:i32,port:u16,_:SocketAddr,direction:Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{assert_eq!(protocol,17);assert!(port>0);self.seen.lock().unwrap().push(direction);if self.fail{Err(crate::mcrf_bs_dns_transport::Failure::Bind)}else{Ok(Box::new(()))}}}
  tokio::time::timeout(Duration::from_secs(5),async{let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();let policy=Arc::new(Directions{seen:Mutex::new(Vec::new()),fail:false});let selected=Selected{policy:policy.clone()};let stop=CancellationToken::new();for target in [Target::Direct,Target::Tunnel]{let flow=selected.open(peer,peer,peer,target,&Options::default(),&stop).await.unwrap();flow.send(b"owned",&stop).await.unwrap();let mut bytes=[0;64];let(size,local)=server.recv_from(&mut bytes).await.unwrap();server.send_to(&bytes[..size],local).await.unwrap();assert_eq!(flow.receive(&mut bytes,&stop).await.unwrap(),5);}assert_eq!(*policy.seen.lock().unwrap(),vec![Direction::Direct,Direction::Vpn]);let policy=Arc::new(Directions{seen:Mutex::new(Vec::new()),fail:true});let selected=Selected{policy:policy.clone()};assert!(matches!(selected.open(peer,peer,peer,Target::Tunnel,&Options::default(),&stop).await,Err(Failure::Connect)));assert_eq!(*policy.seen.lock().unwrap(),vec![Direction::Vpn]);assert!(server.try_recv_from(&mut [0;64]).is_err());}).await.unwrap()
 }

 #[tokio::test]async fn scoped_tuple_is_revoked_before_descriptor_is_closed(){
  struct Scope{i:i32,checked:Arc<AtomicUsize>}
  impl Drop for Scope{fn drop(&mut self){assert!(unsafe{libc::fcntl(self.i,libc::F_GETFD)}>=0,"FD closed before tuple revocation");self.checked.fetch_add(1,Ordering::SeqCst);}}
  struct Check(Arc<AtomicUsize>);impl SocketPolicy for Check{fn prepare(&self,_:u8,fd:i32,_:u16,_:SocketAddr,_:Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{Ok(Box::new(Scope{i:fd,checked:self.0.clone()}))}}
  let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();let checked=Arc::new(AtomicUsize::new(0));let connector=Direct{policy:Arc::new(Check(checked.clone()))};let flow=connector.open(peer,peer,peer,Target::Direct,&Options::default(),&CancellationToken::new()).await.unwrap();drop(flow);assert_eq!(checked.load(Ordering::SeqCst),1);
 }

}
