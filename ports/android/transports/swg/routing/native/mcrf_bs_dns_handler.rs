//! Authenticated original-client DNS execution. A translated listener port is
//! only a lookup key: fresh kernel ownership is mandatory before rules AND
//! before attribution/reply, including cached/Hosts/predefined responses.
use std::{sync::Arc,net::SocketAddr,collections::HashSet};use tokio::time::Instant;use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{Flow,Observed,NetworkContext,dns_reflector::Client};
use crate::{mcrf_bs_owner::Broker,mcrf_bs_dns_engine::Engine,mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_dns_rpc::{Request,Resolver}};
type Lookup=Arc<dyn Fn(u8,u16)->Result<Option<Client>,()>+Send+Sync>;
#[derive(Clone)]
pub struct Underlay{pub kind:String,pub expensive:bool,pub constrained:bool,pub ssid:String,pub bssid:String}
impl Underlay{
 pub(crate) fn valid(&self)->bool{matches!(self.kind.as_str(),"wifi"|"cellular"|"ethernet"|"other")&&self.ssid.len()<=256&&self.bssid.len()<=64}
 pub(crate) fn borrowed(&self)->NetworkContext<'_>{NetworkContext{kind:&self.kind,expensive:self.expensive,constrained:self.constrained,wifi_ssid:&self.ssid,wifi_bssid:&self.bssid}}
}
pub fn handler(generation:u64,engine:Arc<Engine>,owner:Arc<Broker>,policy:Arc<dyn SocketPolicy>,underlay:Underlay,cancel:&CancellationToken)->Result<Resolver,()>{
 if !crate::mcrf_direct_tun::active()||generation!=engine.generation()||generation!=owner.generation(){return Err(())}
 let lookup:Lookup=Arc::new(move|protocol,port|crate::mcrf_bs_dns::client(generation,protocol,port));
 owned(engine,owner,policy,lookup,underlay,cancel)
}
fn owned(engine:Arc<Engine>,owner:Arc<Broker>,policy:Arc<dyn SocketPolicy>,lookup:Lookup,underlay:Underlay,cancel:&CancellationToken)->Result<Resolver,()>{
 if !underlay.valid()||engine.generation()!=owner.generation(){return Err(())}let underlay=Arc::new(underlay);let cancel=cancel.clone();
 Ok(Arc::new(move|request|{
  let engine=engine.clone();let owner=owner.clone();let policy=policy.clone();let lookup=lookup.clone();let underlay=underlay.clone();let cancel=cancel.clone();
  Box::pin(async move{execute(request,&engine,&owner,policy.as_ref(),lookup.as_ref(),&underlay,&cancel).await})
 }))
}
/// Identity of an accepted native TUN TCP association. The monotonically
/// unique epoch prevents a new connection reusing the same four-tuple from
/// receiving the previous connection's answer.
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub struct StreamFlow{pub source:SocketAddr,pub destination:SocketAddr,pub epoch:u64}
pub type StreamLookup=Arc<dyn Fn()->Result<Option<StreamFlow>,()>+Send+Sync>;
pub fn stream_handler(engine:Arc<Engine>,owner:Arc<Broker>,policy:Arc<dyn SocketPolicy>,lookup:StreamLookup,underlay:Underlay,cancel:&CancellationToken)->Result<Resolver,()>{
 association_handler(6,None,None,engine,owner,policy,lookup,underlay,cancel)
}
/// Native accepted TCP or UDP policy-hijacked DNS at its ORIGINAL port.
/// A UDP association owner must supply a current epoch lookup and retire it
/// immediately when its independently refreshed kernel owner changes.
pub type OwnerProof=Arc<dyn Fn(&crate::mcrf_bs_owner::Owner)->Result<(),()>+Send+Sync>;
pub fn datagram_handler(expected_uid:u32,expected_packages:Vec<String>,confirmed:Option<OwnerProof>,engine:Arc<Engine>,owner:Arc<Broker>,policy:Arc<dyn SocketPolicy>,lookup:StreamLookup,underlay:Underlay,cancel:&CancellationToken)->Result<Resolver,()>{
 association_handler(17,Some((expected_uid,expected_packages)),confirmed,engine,owner,policy,lookup,underlay,cancel)
}
fn association_handler(protocol:u8,expected:Option<(u32,Vec<String>)>,confirmed:Option<OwnerProof>,engine:Arc<Engine>,owner:Arc<Broker>,policy:Arc<dyn SocketPolicy>,lookup:StreamLookup,underlay:Underlay,cancel:&CancellationToken)->Result<Resolver,()>{
 if !matches!(protocol,6|17)||!underlay.valid()||engine.generation()!=owner.generation(){return Err(())}
 let underlay=Arc::new(underlay);let cancel=cancel.clone();
 Ok(Arc::new(move|request|{
  let(engine,owner,policy,lookup,underlay,cancel)=(engine.clone(),owner.clone(),policy.clone(),lookup.clone(),underlay.clone(),cancel.clone());
  let expected=expected.clone();let confirmed=confirmed.clone();
  Box::pin(async move{
   if request.protocol!=protocol||cancel.is_cancelled(){return Err(3)}
   let original=lookup().map_err(|_|3u8)?.ok_or(3u8)?;
   if original.epoch==0||original.source.port()==0||original.destination.port()==0||original.source.is_ipv4()!=original.destination.is_ipv4(){return Err(3)}
   let deadline=Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE;
   let first=tokio::time::timeout_at(deadline,owner.query(protocol,original.source,original.destination,&cancel)).await.map_err(|_|7u8)?.map_err(|_|4u8)?;
   if expected.as_ref().is_some_and(|(uid,packages)|first.uid!=*uid||first.packages.iter().collect::<HashSet<_>>()!=packages.iter().collect::<HashSet<_>>()){return Err(8)}
   if let Some(confirm)=&confirmed{confirm(&first).map_err(|_|8u8)?}
   let flow=Flow{source:original.source.ip(),destination:original.destination.ip(),source_port:original.source.port(),port:original.destination.port(),network:if protocol==6{"tcp"}else{"udp"},domain:Observed::Pending,protocol:Observed::Known(Some("dns")),packages:Some(&first.packages),underlay:Observed::Known(Some(underlay.borrowed()))};
   let answer=engine.resolve(policy.as_ref(),&request.query,&flow,deadline,&cancel).await.map_err(|_|5u8)?;
   if lookup().map_err(|_|8u8)?!=Some(original){return Err(8)}
   let fresh=tokio::time::timeout_at(deadline,owner.query(protocol,original.source,original.destination,&cancel)).await.map_err(|_|7u8)?.map_err(|_|8u8)?;
   if cancel.is_cancelled(){return Err(7)}
   if fresh.uid!=first.uid||fresh.packages.iter().collect::<HashSet<_>>()!=first.packages.iter().collect::<HashSet<_>>()||lookup().map_err(|_|8u8)?!=Some(original){return Err(8)}
   if let Some(confirm)=&confirmed{confirm(&fresh).map_err(|_|8u8)?}
   engine.admit(&request.query,&answer,fresh.uid).map_err(|_|9u8)?;Ok(answer)
  })
 }))
}
async fn execute(request:Request,engine:&Engine,owner:&Broker,policy:&dyn SocketPolicy,lookup:&(dyn Fn(u8,u16)->Result<Option<Client>,()>+Send+Sync),underlay:&Underlay,cancel:&CancellationToken)->Result<Vec<u8>,u8>{
 let deadline=Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE;
 let original=lookup(request.protocol,request.translated_port).map_err(|_|3u8)?.ok_or(3u8)?;
 if original.protocol!=request.protocol{return Err(3)}
 let source=SocketAddr::new(original.source,original.port);let destination=SocketAddr::new(original.destination,53);
 let first=tokio::time::timeout_at(deadline,owner.query(original.protocol,source,destination,cancel)).await.map_err(|_|7u8)?.map_err(|_|4u8)?;
 let flow=Flow{source:original.source,destination:original.destination,source_port:original.port,port:53,network:if original.protocol==6{"tcp"}else{"udp"},domain:Observed::Pending,protocol:Observed::Known(Some("dns")),packages:Some(&first.packages),underlay:Observed::Known(Some(underlay.borrowed()))};
 let answer=engine.resolve(policy,&request.query,&flow,deadline,cancel).await.map_err(|_|5u8)?;
 let current=lookup(request.protocol,request.translated_port).map_err(|_|8u8)?.ok_or(8u8)?;
 if current!=original{return Err(8)}
 let fresh=tokio::time::timeout_at(deadline,owner.query(original.protocol,source,destination,cancel)).await.map_err(|_|7u8)?.map_err(|_|8u8)?;
 if cancel.is_cancelled(){return Err(7)}
 if fresh.uid!=first.uid||fresh.packages.iter().collect::<HashSet<_>>()!=first.packages.iter().collect::<HashSet<_>>(){return Err(8)}
 if lookup(request.protocol,request.translated_port).map_err(|_|8u8)?!=Some(original){return Err(8)}
 engine.admit(&request.query,&answer,fresh.uid).map_err(|_|9u8)?;Ok(answer)
}
#[cfg(test)]mod tests{
 use super::*;use serde_json::json;use base64::Engine as _;use std::sync::atomic::{AtomicUsize,Ordering};use crate::mcrf_bs_dns_transport::{Direction,Failure};
 struct NoSockets(AtomicUsize);impl SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:Direction)->Result<Box<dyn Send>,Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(Failure::Bind)}}
 fn engine()->Arc<Engine>{Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.1"}}],"final":"h","reverse_mapping":true}),&json!({}),&json!({}),HashMap::new(),&[]).unwrap())}
 use std::collections::HashMap;
 fn underlay()->Underlay{Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()}}
 fn client()->Client{Client{source:"10.89.0.2".parse().unwrap(),destination:"8.8.8.8".parse().unwrap(),port:45000,protocol:17}}
 fn request()->Request{Request{protocol:17,translated_port:33000,query:mcrf_bs_routing_policy::dns_wire::query(7,"owned.invalid",1).unwrap()}}
 fn broker()->(Arc<Broker>,tokio::sync::mpsc::UnboundedReceiver<String>){let(tx,rx)=tokio::sync::mpsc::unbounded_channel();(Arc::new(Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap()),rx)}
 fn answer(b:&Broker,line:&str,uid:u32){let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(&fields[6..],&["8.8.8.8","53"]);let id=fields[2];let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);assert_eq!(b.respond(&format!("BS_OWNER|42|{id}|{uid}|{data}")),Ok(true));}
 #[tokio::test]async fn inspected_dns_uses_original_nonstandard_port_and_rejects_reused_stream_epoch(){
  let(engine,policy)=(engine(),Arc::new(NoSockets(AtomicUsize::new(0))));let(owner,mut requests)=broker();
  let epoch=Arc::new(AtomicUsize::new(1));let state=epoch.clone();
  let lookup:StreamLookup=Arc::new(move||Ok(Some(StreamFlow{source:"10.89.0.2:45000".parse().unwrap(),destination:"8.8.8.8:5353".parse().unwrap(),epoch:state.load(Ordering::SeqCst)as u64})));
  let handler=stream_handler(engine.clone(),owner.clone(),policy.clone(),lookup,underlay(),&CancellationToken::new()).unwrap();
  for changed in [true,false]{
   let mut query=request();query.protocol=6;let task=tokio::spawn(handler(query));
   for round in 0..2{let line=requests.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(&fields[6..],&["8.8.8.8","5353"]);
    if changed&&round==1{epoch.fetch_add(1,Ordering::SeqCst);}
    let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|10001|{data}",fields[2])).unwrap();
   }
   let answer=task.await.unwrap();if changed{assert_eq!(answer,Err(8));assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None)}else{assert!(answer.is_ok())}
  }assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn changed_uid_does_not_receive_or_admit_answer_but_stable_owner_does(){
  let engine=engine();let(owner,mut requests)=broker();let policy=Arc::new(NoSockets(AtomicUsize::new(0)));let lookup:Lookup=Arc::new(|_,_|Ok(Some(client())));
  let handler=owned(engine.clone(),owner.clone(),policy.clone(),lookup,underlay(),&CancellationToken::new()).unwrap();
  for(stable,second_uid)in [(false,10002),(true,10001)]{
   let task=tokio::spawn(handler(request()));let line=requests.recv().await.unwrap();answer(&owner,&line,10001);let line=requests.recv().await.unwrap();
   // Rules/resolution completed, but attribution must still be absent.
   assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None);answer(&owner,&line,second_uid);
   let result=task.await.unwrap();if stable{assert!(result.is_ok());assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),Some("owned.invalid".into()));}else{assert_eq!(result,Err(8));}
  }assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn changed_original_tuple_after_resolution_rejects_without_second_owner_or_attribution(){
  let engine=engine();let(owner,mut requests)=broker();let policy=Arc::new(NoSockets(AtomicUsize::new(0)));let calls=Arc::new(AtomicUsize::new(0));let count=calls.clone();
  let lookup:Lookup=Arc::new(move|_,_|{let mut client=client();if count.fetch_add(1,Ordering::SeqCst)>0{client.port+=1}Ok(Some(client))});
  let handler=owned(engine.clone(),owner.clone(),policy.clone(),lookup,underlay(),&CancellationToken::new()).unwrap();let task=tokio::spawn(handler(request()));let line=requests.recv().await.unwrap();answer(&owner,&line,10001);
  assert_eq!(task.await.unwrap(),Err(8));assert!(requests.try_recv().is_err());assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None);assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn missing_mapping_and_cancelled_owner_lookup_never_resolve_or_admit(){
  let engine=engine();let(owner,mut requests)=broker();let policy=Arc::new(NoSockets(AtomicUsize::new(0)));let cancel=CancellationToken::new();
  let foreign=Arc::new(Broker::new(43,Arc::new(|_|Ok(()))).unwrap());assert!(owned(engine.clone(),foreign,policy.clone(),Arc::new(|_,_|Ok(Some(client()))),underlay(),&cancel).is_err());
  let missing=owned(engine.clone(),owner.clone(),policy.clone(),Arc::new(|_,_|Ok(None)),underlay(),&cancel).unwrap();assert_eq!(missing(request()).await,Err(3));assert!(requests.try_recv().is_err());
  let handler=owned(engine.clone(),owner.clone(),policy.clone(),Arc::new(|_,_|Ok(Some(client()))),underlay(),&cancel).unwrap();let task=tokio::spawn(handler(request()));requests.recv().await.unwrap();cancel.cancel();assert!(task.await.unwrap().is_err());assert_eq!(policy.0.load(Ordering::SeqCst),0);assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None);
 }
 #[tokio::test]async fn udp_policy_hijack_keeps_original_port_and_reauthenticates_each_reply(){
  let engine=engine();let(owner,mut requests)=broker();let policy=Arc::new(NoSockets(AtomicUsize::new(0)));
  let epoch=Arc::new(AtomicUsize::new(9));let current=epoch.clone();let lookup:StreamLookup=Arc::new(move||Ok(Some(StreamFlow{source:"10.89.0.2:45000".parse().unwrap(),destination:"8.8.8.8:5353".parse().unwrap(),epoch:current.load(Ordering::SeqCst)as u64})));
  let handler=datagram_handler(10001,vec!["org.allowed".into()],None,engine.clone(),owner.clone(),policy.clone(),lookup,underlay(),&CancellationToken::new()).unwrap();
  // A protocol mismatch cannot start an owner request.
  let mut wrong=request();wrong.protocol=6;assert_eq!(handler(wrong).await,Err(3));assert!(requests.try_recv().is_err());
  for changed in [true,false]{let task=tokio::spawn(handler(request()));
   for round in 0..2{let line=requests.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(&fields[3..],&["17","10.89.0.2","45000","8.8.8.8","5353"]);if changed&&round==1{epoch.fetch_add(1,Ordering::SeqCst);}let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|10001|{data}",fields[2])).unwrap();}
   let result=task.await.unwrap();if changed{assert_eq!(result,Err(8));assert_eq!(engine.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None)}else{assert!(result.is_ok())}
  }assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }

 #[tokio::test]async fn udp_reused_socket_with_new_owner_cannot_resolve_under_old_admission(){
  let engine=engine();let(owner,mut requests)=broker();let policy=Arc::new(NoSockets(AtomicUsize::new(0)));let lookup:StreamLookup=Arc::new(||Ok(Some(StreamFlow{source:"10.89.0.2:45000".parse().unwrap(),destination:"8.8.8.8:5353".parse().unwrap(),epoch:9})));
  let handler=datagram_handler(10001,vec!["org.allowed".into()],None,engine.clone(),owner.clone(),policy.clone(),lookup,underlay(),&CancellationToken::new()).unwrap();let task=tokio::spawn(handler(request()));let line=requests.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();let data=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);owner.respond(&format!("BS_OWNER|42|{}|10002|{data}",fields[2])).unwrap();assert_eq!(task.await.unwrap(),Err(8));assert!(requests.try_recv().is_err());assert_eq!(policy.0.load(Ordering::SeqCst),0);assert_eq!(engine.domain(10002,"192.0.2.1".parse().unwrap()).unwrap(),None);
 }

}
