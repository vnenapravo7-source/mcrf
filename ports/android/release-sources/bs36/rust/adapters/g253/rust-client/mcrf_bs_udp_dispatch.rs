//! One UDP association in the SAME native BS process. Connectors must use
//! the original carrier/direct paths; a route failure never selects a fallback.
use crate::mcrf_bs_datagram::Datagram;
use std::{sync::{Arc,Mutex},net::SocketAddr,pin::Pin,future::Future,time::Duration};
use tokio::{sync::mpsc,time::Instant};use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{associations::Tracker,Target};
use crate::{mcrf_bs_owner::Broker,mcrf_bs_stream_slots::Clock,mcrf_bs_datagram_slots::{Lease,Slots},mcrf_bs_tcp_dispatch::owned::{Original,Retire},mcrf_bs_udp_policy::{Router,Decision,Options},mcrf_bs_udp_inspect::Captured,mcrf_bs_dns_engine::Engine,mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_dns_handler::{self,Underlay},mcrf_bs_dns_rpc::Request};
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Admission,Owner(crate::mcrf_bs_datagram_slots::Failure),Policy,Identity,Connect,Io,Dns,Reply,Cancelled,Invalid}
pub type Task<'a,T>=Pin<Box<dyn Future<Output=Result<T,Failure>>+Send+'a>>;
/// The selected endpoint is fixed. An unconnected UDP implementation must
/// filter unsolicited peers before receive returns; packets retain boundaries.
pub trait Connection:Send+Sync{
 fn send<'a>(&'a self,bytes:&'a [u8],cancel:&'a CancellationToken)->Task<'a,()>;
 fn receive<'a>(&'a self,bytes:&'a mut [u8],cancel:&'a CancellationToken)->Task<'a,usize>;
}
pub trait Connector:Send+Sync{
 fn open<'a>(&'a self,source:SocketAddr,destination:SocketAddr,peer:SocketAddr,target:Target,options:&'a Options,cancel:&'a CancellationToken)->Task<'a,Arc<dyn Connection>>;
}
/// Writes a UDP payload back with the ORIGINAL reverse tuple, including FakeIP
/// and route overrides. It must not deliver to another association/epoch.
pub trait Reply:Send+Sync{fn write<'a>(&'a self,source:SocketAddr,destination:SocketAddr,bytes:&'a [u8],cancel:&'a CancellationToken)->Task<'a,()>;}
struct DestinationPin{engine:Arc<Engine>,id:u64,domain:Option<String>}
impl Drop for DestinationPin{fn drop(&mut self){if self.domain.is_some(){let _=self.engine.release_flow_name(self.id);}}}
pub struct Session{pub generation:u64,pub slots:Arc<Slots>,pub tracker:Arc<Mutex<Tracker>>,pub clock:Clock,pub owner:Arc<Broker>,pub engine:Arc<Engine>,pub sockets:Arc<dyn SocketPolicy>,pub underlay:Underlay,pub router:Arc<Router>,pub retire:Retire,pub connector:Arc<dyn Connector>,pub reply:Arc<dyn Reply>}
impl Session{
 async fn fresh(&self,lease:&Arc<Lease>,cancel:&CancellationToken)->Result<(),Failure>{
  let deadline=Instant::now()+mcrf_bs_routing_policy::dns_limits::OWNER_QUERY;
  tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),value=tokio::time::timeout_at(deadline,lease.refresh(&self.owner,cancel))=>value.map_err(|_|Failure::Owner(crate::mcrf_bs_datagram_slots::Failure::Owner))?.map_err(Failure::Owner)}
 }
 async fn receive_input(input:&mut mpsc::Receiver<Datagram>,idle:Duration,cancel:&CancellationToken)->Result<Option<Datagram>,Failure>{
  tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),packet=tokio::time::timeout(idle,input.recv())=>match packet{Ok(Some(packet))if packet.len()<=65535=>Ok(Some(packet)),Ok(Some(_))=>Err(Failure::Invalid),_=>Ok(None)}}
 }
 pub async fn serve(&self,source:SocketAddr,destination:SocketAddr,mut input:mpsc::Receiver<Datagram>,cancel:&CancellationToken)->Result<(),Failure>{
  if self.generation!=self.engine.generation(){return Err(Failure::Admission)}
  let lease=Lease::admit(self.slots.clone(),self.tracker.clone(),self.generation,self.clock.clone(),&self.owner,source,destination,self.retire.clone(),cancel).await.map_err(Failure::Owner)?;
  let identity=lease.lookup()().map_err(|_|Failure::Identity)?.ok_or(Failure::Identity)?;
  let domain=self.engine.pin_flow_name(identity.epoch,destination.ip()).map_err(|_|Failure::Identity)?;
  let named=DestinationPin{engine:self.engine.clone(),id:identity.epoch,domain};
  let observed=if named.domain.is_some(){None}else{self.engine.domain(lease.owner.uid,destination.ip()).map_err(|_|Failure::Identity)?};
  let mut captured=Captured::default();
  let decision=tokio::select!{biased;
   _=cancel.cancelled()=>return Err(Failure::Cancelled),
   result=self.router.decide(Original{source,destination,destination_domain:named.domain.as_deref(),observed_domain:observed.as_deref(),uid:lease.owner.uid,packages:&lease.owner.packages},&mut input,&mut captured,cancel)=>result.map_err(|_|Failure::Policy)?,
  };
  match decision{
   Decision::Block=>Ok(()),
   Decision::Dns{idle}=>{
    let proof=lease.clone();let confirmed:mcrf_bs_dns_handler::OwnerProof=Arc::new(move|owner|proof.confirm(owner).map_err(|_|()));
    let resolve=mcrf_bs_dns_handler::datagram_handler(lease.owner.uid,lease.owner.packages.clone(),Some(confirmed),self.engine.clone(),self.owner.clone(),self.sockets.clone(),lease.lookup(),self.underlay.clone(),cancel).map_err(|_|Failure::Dns)?;
    let mut queued=captured.packets.into_iter();
    loop{
     let query=if let Some(packet)=queued.next(){packet}else{match Self::receive_input(&mut input,idle,cancel).await?{Some(packet)=>packet,None=>return Ok(())}};
     let answer=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),answer=resolve(Request{protocol:17,translated_port:source.port(),query:query.to_vec()})=>answer.map_err(|_|Failure::Dns)?};
     if lease.lookup()().map_err(|_|Failure::Identity)?!=Some(identity){return Err(Failure::Identity)}
     tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=tokio::time::timeout(Duration::from_secs(2),self.reply.write(destination,source,&answer,cancel))=>result.map_err(|_|Failure::Reply)??}
    }
   },
   Decision::Forward{peers,target,options}=>{
    let peer=*peers.first().ok_or(Failure::Policy)?;
    if peer.port()==0||peer.ip().is_unspecified()||peer.ip().is_multicast()||self.engine.virtual_address(peer.ip()).map_err(|_|Failure::Identity)?{return Err(Failure::Policy)}
    self.fresh(&lease,cancel).await?;
    let connection=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=tokio::time::timeout(Duration::from_secs(5),self.connector.open(source,destination,peer,target,&options,cancel))=>result.map_err(|_|Failure::Connect)??};
    for packet in captured.packets{self.fresh(&lease,cancel).await?;tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=tokio::time::timeout(Duration::from_secs(5),connection.send(&packet,cancel))=>result.map_err(|_|Failure::Io)??}}
    let idle=options.idle(None,peer.port());let mut deadline=Instant::now()+idle;let mut response=vec![0;65535];let mut burst=0u8;
    let reply_source=if options.domain_destination&&options.disable_domain_unmapping==Some(true){peer}else{destination};
    loop{
     if cancel.is_cancelled(){return Err(Failure::Cancelled)}
     burst=burst.wrapping_add(1);if burst%32==0{tokio::task::yield_now().await}
     // Fair polling is essential: continuously ready uploads must not starve
     // receive/ACK traffic on a bidirectional UDP association.
     tokio::select!{
      _=cancel.cancelled()=>return Err(Failure::Cancelled),
      _=tokio::time::sleep_until(deadline)=>return Ok(()),
      packet=input.recv()=>{
       let Some(packet)=packet else{return Ok(())};if packet.len()>65535{return Err(Failure::Invalid)}self.fresh(&lease,cancel).await?;
       tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=tokio::time::timeout(Duration::from_secs(5),connection.send(&packet,cancel))=>result.map_err(|_|Failure::Io)??}deadline=Instant::now()+idle;
      },
      received=connection.receive(&mut response,cancel)=>{
       let size=received?;if size>response.len(){return Err(Failure::Invalid)}self.fresh(&lease,cancel).await?;
       tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),result=tokio::time::timeout(Duration::from_secs(2),self.reply.write(reply_source,source,&response[..size],cancel))=>result.map_err(|_|Failure::Reply)??}deadline=Instant::now()+idle;
      },
     }
    }
   },
  }
 }
}

#[cfg(test)]mod tests{
 use super::*;use serde_json::{json,Value};use std::sync::atomic::{AtomicUsize,Ordering};use base64::Engine as _;
 struct NoSockets;impl SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{panic!("Mock session must not open a DNS socket")}}
 struct Pipe{sent:mpsc::UnboundedSender<Vec<u8>>,incoming:tokio::sync::Mutex<mpsc::UnboundedReceiver<Vec<u8>>>}
 impl Connection for Pipe{
  fn send<'a>(&'a self,bytes:&'a [u8],_:&'a CancellationToken)->Task<'a,()>{Box::pin(async move{self.sent.send(bytes.to_vec()).map_err(|_|Failure::Io)})}
  fn receive<'a>(&'a self,bytes:&'a mut [u8],_:&'a CancellationToken)->Task<'a,usize>{Box::pin(async move{let packet=self.incoming.lock().await.recv().await.ok_or(Failure::Io)?;if packet.len()>bytes.len(){return Err(Failure::Io)}bytes[..packet.len()].copy_from_slice(&packet);Ok(packet.len())})}
 }
 struct Connect{pipe:Arc<Pipe>,opened:Arc<AtomicUsize>}
 impl Connector for Connect{fn open<'a>(&'a self,source:SocketAddr,destination:SocketAddr,peer:SocketAddr,target:Target,_:&'a Options,_:&'a CancellationToken)->Task<'a,Arc<dyn Connection>>{Box::pin(async move{assert_eq!(source,"10.89.0.2:45000".parse().unwrap());assert_eq!(destination,"192.0.2.1:5353".parse().unwrap());assert_eq!(peer,destination);assert!(target==Target::Tunnel);self.opened.fetch_add(1,Ordering::SeqCst);Ok(self.pipe.clone()as Arc<dyn Connection>)})}}
 struct Replies(mpsc::UnboundedSender<(SocketAddr,SocketAddr,Vec<u8>)>);impl Reply for Replies{fn write<'a>(&'a self,source:SocketAddr,destination:SocketAddr,bytes:&'a [u8],_:&'a CancellationToken)->Task<'a,()>{Box::pin(async move{self.0.send((source,destination,bytes.to_vec())).map_err(|_|Failure::Reply)})}}
 struct Fixture{session:Arc<Session>,requests:mpsc::UnboundedReceiver<String>,sent:mpsc::UnboundedReceiver<Vec<u8>>,incoming:mpsc::UnboundedSender<Vec<u8>>,replies:mpsc::UnboundedReceiver<(SocketAddr,SocketAddr,Vec<u8>)>,opened:Arc<AtomicUsize>}
 fn fixture(controls:Value)->Fixture{
  let bundle=mcrf_bs_routing_policy::bundle::Bundle::parse(&serde_json::to_vec(&json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[]},"controls":controls,"dns":null,"routeOptions":{},"tunInbounds":[],"dnsDialers":{}})).unwrap()).unwrap();
  let engine=Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.9"}}],"final":"h"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Arc::new(NoSockets);let underlay=Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()};let router=Arc::new(Router::new(bundle,engine.clone(),sockets.clone(),underlay.clone()).unwrap());
  let(tx,requests)=mpsc::unbounded_channel();let owner=Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned().into()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let(sent_tx,sent)=mpsc::unbounded_channel();let(incoming,incoming_rx)=mpsc::unbounded_channel();let(replies_tx,replies)=mpsc::unbounded_channel();let opened=Arc::new(AtomicUsize::new(0));let connector=Arc::new(Connect{pipe:Arc::new(Pipe{sent:sent_tx,incoming:tokio::sync::Mutex::new(incoming_rx)}),opened:opened.clone()});
  Fixture{session:Arc::new(Session{generation:42,slots:Arc::new(Slots::new(4).unwrap()),tracker:Arc::new(Mutex::new(Tracker::new(42,4).unwrap())),clock:Arc::new(||Ok(1)),owner,engine,sockets,underlay,router,retire:Arc::new(|_|Ok(())),connector,reply:Arc::new(Replies(replies_tx))}),requests,sent,incoming,replies,opened}
 }
 fn endpoints()->(SocketAddr,SocketAddr){("10.89.0.2:45000".parse().unwrap(),"192.0.2.1:5353".parse().unwrap())}
 fn owners(owner:Arc<Broker>,mut rx:mpsc::UnboundedReceiver<String>,change_at:usize)->tokio::task::JoinHandle<()>{tokio::spawn(async move{let mut round=0;while let Some(line)=rx.recv().await{round+=1;let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(&fields[3..],&["17","10.89.0.2","45000","192.0.2.1","5353"]);let uid=if round>=change_at{10002}else{10001};let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|{uid}|{data}",fields[2])).unwrap();}})}
 #[tokio::test]async fn ingress_recreates_idle_flow_and_stop_returns_all_pooled_packets(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let mut f=fixture(json!([{"beforeRule":0,"operation":{"action":"route-options","udp_timeout":"10ms"}}]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);
   let pool=crate::packet::PacketPool::new(4);let(tx,rx)=mpsc::channel(2);let stop=CancellationToken::new();let stopped=stop.clone();let session=f.session.clone();
   let report:crate::mcrf_bs_udp_ingress::Report=Arc::new(|event|panic!("unexpected ingress event {event:?}"));let worker=tokio::spawn(async move{crate::mcrf_bs_udp_ingress::run(session,rx,1,2,report,&stopped).await});
   let(source,destination)=endpoints();
   for round in 0..2{
    let mut packet=pool.try_acquire().unwrap();let bytes=packet.extend_tail(29).unwrap();assert!(crate::direct_relay::craft_udp_packet_into(source,destination,&[7],bytes));tx.send(packet).await.unwrap();assert_eq!(f.sent.recv().await.unwrap(),vec![7]);
    // Wait for runtime idle to retire the actual socket and metadata lease.
    loop{if f.session.tracker.lock().unwrap().udp_identity(42,1,source,destination).unwrap().is_none(){break}tokio::time::sleep(Duration::from_millis(2)).await;}
    assert_eq!(f.opened.load(Ordering::SeqCst),round+1);
   }
   stop.cancel();assert_eq!(worker.await.unwrap(),Ok(()));assert_eq!(f.session.tracker.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));let mut returned=Vec::new();while let Some(packet)=pool.try_acquire(){returned.push(packet)}assert_eq!(returned.len(),4);broker.abort();
  }).await.unwrap()
 }
 #[tokio::test]async fn captured_udp_payload_and_empty_datagram_are_sent_once_reply_uses_original_tuple(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":"wireguard"}}]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);let(tx,rx)=mpsc::channel(4);let mut packet=vec![0;148];packet[0]=1;tx.send(packet.clone().into()).await.unwrap();let stop=CancellationToken::new();let stopped=stop.clone();let session=f.session.clone();let(source,destination)=endpoints();let task=tokio::spawn(async move{session.serve(source,destination,rx,&stopped).await});assert_eq!(f.sent.recv().await.unwrap(),packet);assert!(f.sent.try_recv().is_err());tx.send(Vec::new().into()).await.unwrap();assert_eq!(f.sent.recv().await.unwrap(),Vec::<u8>::new());f.incoming.send(b"owned-reply".to_vec()).unwrap();assert_eq!(f.replies.recv().await.unwrap(),(destination,source,b"owned-reply".to_vec()));stop.cancel();assert_eq!(task.await.unwrap(),Err(Failure::Cancelled));assert_eq!(f.opened.load(Ordering::SeqCst),1);assert_eq!(f.session.tracker.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));broker.abort();}).await.unwrap()
 }
 #[tokio::test]async fn owner_change_after_policy_prevents_connector_and_all_payload(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(json!([]));let broker=owners(f.session.owner.clone(),f.requests,2);let(_tx,rx)=mpsc::channel(1);let(source,destination)=endpoints();assert!(matches!(f.session.serve(source,destination,rx,&CancellationToken::new()).await,Err(Failure::Owner(_))));assert_eq!(f.opened.load(Ordering::SeqCst),0);assert!(f.sent.try_recv().is_err());assert!(f.replies.try_recv().is_err());broker.abort();}).await.unwrap()
 }
 #[tokio::test]async fn changed_owner_before_reply_prevents_delivery_to_reused_tuple(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(json!([]));let broker=owners(f.session.owner.clone(),f.requests,4);let(tx,rx)=mpsc::channel(1);tx.send(vec![7].into()).await.unwrap();let session=f.session.clone();let(source,destination)=endpoints();let task=tokio::spawn(async move{session.serve(source,destination,rx,&CancellationToken::new()).await});assert_eq!(f.sent.recv().await.unwrap(),vec![7]);f.incoming.send(vec![8]).unwrap();assert!(matches!(task.await.unwrap(),Err(Failure::Owner(_))));assert!(f.replies.try_recv().is_err());assert_eq!(f.session.tracker.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));broker.abort();}).await.unwrap()
 }
 #[tokio::test]async fn conditional_dns_hijack_answers_at_original_port_without_connector(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);let(tx,rx)=mpsc::channel(1);let query=mcrf_bs_routing_policy::dns_wire::query(17,"owned.invalid",1).unwrap();tx.send(query.clone().into()).await.unwrap();let stop=CancellationToken::new();let stopped=stop.clone();let session=f.session.clone();let(source,destination)=endpoints();let task=tokio::spawn(async move{session.serve(source,destination,rx,&stopped).await});let(a,b,answer)=f.replies.recv().await.unwrap();assert_eq!((a,b),(destination,source));assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap()[0].ip,"192.0.2.9".parse::<std::net::IpAddr>().unwrap());assert_eq!(f.opened.load(Ordering::SeqCst),0);stop.cancel();assert_eq!(task.await.unwrap(),Err(Failure::Cancelled));broker.abort();}).await.unwrap()
 }
 #[tokio::test]async fn idle_timeout_retires_connection_without_sending_and_stop_interrupts_sniff(){
  tokio::time::timeout(Duration::from_secs(5),async{let f=fixture(json!([{"beforeRule":0,"operation":{"action":"route-options","udp_timeout":"10ms"}}]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);let(_tx,rx)=mpsc::channel(1);let(source,destination)=endpoints();assert_eq!(f.session.serve(source,destination,rx,&CancellationToken::new()).await,Ok(()));assert_eq!(f.session.tracker.lock().unwrap().udp_identity(42,1,source,destination),Ok(None));broker.abort();let f=fixture(json!([{"beforeRule":0,"operation":{"action":"sniff","timeout":"5s"}}]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);let(_tx,rx)=mpsc::channel(1);let stop=CancellationToken::new();let stopped=stop.clone();let session=f.session.clone();let task=tokio::spawn(async move{session.serve(source,destination,rx,&stopped).await});tokio::time::sleep(Duration::from_millis(10)).await;stop.cancel();assert_eq!(task.await.unwrap(),Err(Failure::Cancelled));assert_eq!(f.opened.load(Ordering::SeqCst),0);broker.abort();}).await.unwrap()
 }
 #[tokio::test]async fn continuously_ready_upload_queue_does_not_starve_reply(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(json!([]));let broker=owners(f.session.owner.clone(),f.requests,usize::MAX);let(tx,rx)=mpsc::channel(64);for _ in 0..64{tx.send(vec![1].into()).await.unwrap()}let stop=CancellationToken::new();let producer_stop=stop.clone();let producer=tokio::spawn(async move{loop{tokio::select!{biased;_=producer_stop.cancelled()=>return,result=tx.send(vec![1].into())=>if result.is_err(){return}}}});let drain=tokio::spawn(async move{while f.sent.recv().await.is_some(){}});f.incoming.send(vec![9]).unwrap();let session=f.session.clone();let stopped=stop.clone();let(source,destination)=endpoints();let worker=tokio::spawn(async move{session.serve(source,destination,rx,&stopped).await});let reply=tokio::time::timeout(Duration::from_secs(1),f.replies.recv()).await.unwrap().unwrap();assert_eq!(reply,(destination,source,vec![9]));stop.cancel();assert_eq!(worker.await.unwrap(),Err(Failure::Cancelled));producer.await.unwrap();drain.abort();broker.abort();}).await.unwrap()
 }

}
