//! Same-process UDP policy executor. An association owner must authenticate
//! before calling and again before opening an outbound or replying to DNS.
//! No connector, packet-copy relay or second engine belongs in this module.
use crate::mcrf_bs_datagram::Datagram;
use std::{net::SocketAddr,sync::Arc,time::Duration};
use serde_json::Value;use tokio::time::Instant;use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{bundle::Bundle,pipeline::{Kind,Instruction},Flow,Observed,DestinationView,Target,inspect};
use crate::{mcrf_bs_tcp_policy::{PolicyRouter,Error,Address,sniff,override_options,duration},mcrf_bs_tcp_dispatch::owned::{Original,Failure},mcrf_bs_udp_inspect::{self,Captured},mcrf_bs_dns_engine::Engine,mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_dns_handler::Underlay};
#[derive(Clone,Debug,Default,PartialEq,Eq)]pub struct Options{pub connect:Option<bool>,pub disable_domain_unmapping:Option<bool>,pub timeout:Option<Duration>,pub domain_destination:bool}
impl Options{
 pub fn idle(&self,protocol:Option<&str>,port:u16)->Duration{
  if let Some(timeout)=self.timeout.filter(|v|!v.is_zero()){return timeout}
  let protocol=protocol.unwrap_or(match port{53=>"dns",123=>"ntp",3478=>"stun",443=>"quic",_=>""});
  Duration::from_secs(match protocol{"dns"|"ntp"|"stun"=>10,"quic"|"dtls"=>30,_=>300})
 }
}
#[derive(Debug,PartialEq,Eq)]pub enum Decision{Forward{peers:Vec<SocketAddr>,target:Target,options:Options},Dns{idle:Duration},Block}
pub struct Router{base:PolicyRouter}
impl Router{
 pub fn new(bundle:Bundle,engine:Arc<Engine>,sockets:Arc<dyn SocketPolicy>,underlay:Underlay)->Result<Self,Error>{Ok(Self{base:PolicyRouter::new(bundle,engine,sockets,underlay)?})}
 pub async fn decide(&self,original:Original<'_>,input:&mut tokio::sync::mpsc::Receiver<Datagram>,captured:&mut Captured,cancel:&CancellationToken)->Result<Decision,Failure>{
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  if original.destination_domain.is_none()&&original.destination.port()==53&&crate::mcrf_bs_dns_resolver::dns_alias(original.destination.ip()){return Ok(Decision::Dns{idle:Options::default().idle(Some("dns"),53)})}
  let base=&self.base;
  let mut destination=original.destination.ip();let mut port=original.destination.port();let version=if original.destination_domain.is_some(){None}else{Some(if destination.is_ipv4(){4}else{6})};
  let mut name=original.destination_domain.map(str::to_owned);let mut addresses=if name.is_some(){None}else{Some(vec![destination])};let mut observed=original.observed_domain.map(str::to_owned);let mut protocol=None;let mut cursor=base.pipeline.start();let mut options=Options::default();
  loop{
   if cancel.is_cancelled(){return Err(Failure::Cancelled)}
   let flow=Flow{source:original.source.ip(),destination,source_port:original.source.port(),port,network:"udp",domain:Observed::Known(observed.as_deref().or(name.as_deref())),protocol:Observed::Known(protocol),packages:Some(original.packages),underlay:Observed::Known(Some(base.underlay.borrowed()))};
   let view=if let Some(ips)=addresses.as_deref(){DestinationView::Domain{addresses:ips,ip_version:version}}else{DestinationView::UnresolvedDomain{ip_version:version}};
   match base.pipeline.next(&flow,view,cursor).map_err(Failure::Policy)?{
    Instruction::Route(Target::Block)=>return Ok(Decision::Block),
    Instruction::Route(target)=>{
     let ips=if let Some(ips)=addresses{ips}else{base.engine.lookup(base.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&base.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?};
     if ips.is_empty(){return Err(Failure::Route)}
     options.domain_destination=name.is_some();options.timeout=Some(options.idle(protocol,port));
     crate::mcrf_bs_server_routing::observed(base.engine.generation(),17,original.source,original.destination,original.uid,observed.as_deref().or(name.as_deref()),&target);
     return Ok(Decision::Forward{peers:ips.into_iter().map(|ip|SocketAddr::new(ip,port)).collect(),target,options})
    },
    Instruction::ResolveNeeded{retry}=>{
     addresses=Some(base.engine.lookup(base.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&base.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?);cursor=retry;
    },
    Instruction::Effect{operation,next}=>{
     match operation.kind{
      Kind::Sniff=>{
       let config=sniff(&operation.options).map_err(|_|Failure::Route)?;
       if protocol.is_none()&&(config.names.is_empty()||config.names.iter().any(|v|inspect::datagram_sniffer(v))){
        let saved=std::mem::take(captured);
        *captured=mcrf_bs_udp_inspect::capture(input,saved,&config.names,config.timeout,cancel).await.map_err(|error|if error==mcrf_bs_udp_inspect::CaptureError::Cancelled{Failure::Cancelled}else{Failure::Route})?;protocol=captured.protocol;
        if let Some(found)=&captured.domain{observed=Some(found.clone());if config.override_destination{name=Some(found.clone());addresses=None}}
       }
      },
      Kind::HijackDns=>return Ok(Decision::Dns{idle:options.idle(Some("dns"),port)}),
      Kind::Resolve=>{if let Some(name)=name.as_deref(){addresses=Some(base.engine.lookup(base.sockets.as_ref(),name,&flow,&operation.options,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?)}},
      Kind::RouteOptions=>{
       let value=override_options(&operation.options).map_err(|_|Failure::Route)?;
       match value.address{Some(Address::Ip(ip))=>{destination=ip;name=None;addresses=Some(vec![ip])},Some(Address::Name(value))=>{name=Some(value);addresses=None},None=>{}}
       if let Some(value)=value.port{port=value}
       if let Some(value)=operation.options.get("udp_connect"){options.connect=Some(options.connect.unwrap_or(false)||value.as_bool().ok_or(Failure::Route)?)}
       if let Some(value)=operation.options.get("udp_disable_domain_unmapping"){options.disable_domain_unmapping=Some(options.disable_domain_unmapping.unwrap_or(false)||value.as_bool().ok_or(Failure::Route)?)}
       if let Some(value)=operation.options.get("udp_timeout"){let timeout=duration(value).map_err(|_|Failure::Route)?;if !timeout.is_zero(){options.timeout=Some(timeout)}}
      },
     }cursor=next;
    },
   }
  }
 }
}

#[cfg(test)]mod tests{
 use super::*;use serde_json::json;use std::sync::atomic::{AtomicUsize,Ordering};
 struct NoSockets(AtomicUsize);impl SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(crate::mcrf_bs_dns_transport::Failure::Bind)}}
 fn bundle(rules:Value,controls:Value)->Bundle{Bundle::parse(&serde_json::to_vec(&json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":rules},"controls":controls,"dns":null,"routeOptions":{"default_domain_resolver":"h"},"tunInbounds":[],"dnsDialers":{}})).unwrap()).unwrap()}
 fn setup(rules:Value,controls:Value)->(Router,Arc<NoSockets>){let engine=Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.9"}}],"final":"h"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Arc::new(NoSockets(AtomicUsize::new(0)));let router=Router::new(bundle(rules,controls),engine,sockets.clone(),Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()}).unwrap();(router,sockets)}
 fn original()->Original<'static>{Original{source:"10.89.0.2:45000".parse().unwrap(),destination:"192.0.2.1:5353".parse().unwrap(),destination_domain:None,observed_domain:None,uid:10001,packages:&[]}}
 #[test]fn udp_idle_matches_protocol_port_and_positive_explicit_override(){
  for(port,seconds)in [(53,10),(123,10),(3478,10),(443,30),(5353,300)]{assert_eq!(Options::default().idle(None,port),Duration::from_secs(seconds))}
  assert_eq!(Options::default().idle(Some("dtls"),5353),Duration::from_secs(30));
  assert_eq!(Options::default().idle(Some("wireguard"),443),Duration::from_secs(300));
  assert_eq!(Options{timeout:Some(Duration::ZERO),..Options::default()}.idle(None,443),Duration::from_secs(30));
  assert_eq!(Options{timeout:Some(Duration::from_secs(7)),..Options::default()}.idle(Some("dns"),53),Duration::from_secs(7));
 }
 #[tokio::test]async fn later_false_and_zero_do_not_clear_enabled_udp_options(){
  let(r,_)=setup(json!([{"match":{"network":"udp"},"target":"proxy"}]),json!([{"beforeRule":0,"operation":{"action":"route-options","udp_connect":true,"udp_disable_domain_unmapping":true,"udp_timeout":"7s"}},{"beforeRule":0,"operation":{"action":"route-options","udp_connect":false,"udp_disable_domain_unmapping":false,"udp_timeout":"0"}}]));
  let(_tx,mut rx)=tokio::sync::mpsc::channel(1);let mut saved=Captured::default();assert_eq!(r.decide(original(),&mut rx,&mut saved,&CancellationToken::new()).await.unwrap(),Decision::Forward{peers:vec!["192.0.2.1:5353".parse().unwrap()],target:Target::Tunnel,options:Options{connect:Some(true),disable_domain_unmapping:Some(true),timeout:Some(Duration::from_secs(7)),domain_destination:false}});
 }
 #[tokio::test]async fn dns_sniff_hijack_keeps_query_bytes_and_does_not_treat_question_as_destination(){
  let(r,s)=setup(json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}]));let(tx,mut rx)=tokio::sync::mpsc::channel(2);let query=mcrf_bs_routing_policy::dns_wire::query(7,"owned.invalid",1).unwrap();tx.send(query.clone().into()).await.unwrap();let mut saved=Captured::default();assert_eq!(r.decide(original(),&mut rx,&mut saved,&CancellationToken::new()).await.unwrap(),Decision::Dns{idle:Duration::from_secs(10)});assert_eq!(saved.packets,vec![query]);assert_eq!(saved.domain,None);assert_eq!(s.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn ordered_overrides_resolve_before_ip_rule_and_keep_udp_options(){
  let(r,s)=setup(json!([{"match":{"network":"udp","ip_cidr":"192.0.2.9/32","port":443},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"route-options","override_address":"owned.invalid","override_port":443,"udp_connect":true,"udp_timeout":"4s","udp_disable_domain_unmapping":true}},{"beforeRule":0,"operation":{"action":"resolve","server":"h","strategy":"ipv4_only"}}]));let(_tx,mut rx)=tokio::sync::mpsc::channel(1);let mut saved=Captured::default();assert_eq!(r.decide(original(),&mut rx,&mut saved,&CancellationToken::new()).await.unwrap(),Decision::Forward{peers:vec!["192.0.2.9:443".parse().unwrap()],target:Target::Direct,options:Options{connect:Some(true),disable_domain_unmapping:Some(true),timeout:Some(Duration::from_secs(4)),domain_destination:true}});assert!(saved.packets.is_empty());assert_eq!(s.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn packet_sniffers_classify_and_forward_preserved_datagram_once(){
  let(r,s)=setup(json!([{"match":{"protocol":"wireguard"},"target":"proxy"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":["wireguard"]}}]));let(tx,mut rx)=tokio::sync::mpsc::channel(2);let mut packet=vec![0;148];packet[0]=1;tx.send(packet.clone().into()).await.unwrap();let mut saved=Captured::default();assert_eq!(r.decide(original(),&mut rx,&mut saved,&CancellationToken::new()).await.unwrap(),Decision::Forward{peers:vec!["192.0.2.1:5353".parse().unwrap()],target:Target::Tunnel,options:Options{timeout:Some(Duration::from_secs(300)),..Options::default()}});assert_eq!(saved.packets,vec![packet]);assert_eq!(s.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn stream_only_sniff_does_not_wait_read_or_change_udp_rule(){
  let(r,_)=setup(json!([{"match":{"network":"udp"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":["tls","http"]}}]));let(_tx,mut rx)=tokio::sync::mpsc::channel(1);let mut saved=Captured::default();let result=tokio::time::timeout(Duration::from_millis(50),r.decide(original(),&mut rx,&mut saved,&CancellationToken::new())).await.unwrap().unwrap();assert!(matches!(result,Decision::Forward{target:Target::Direct,..}));assert!(saved.packets.is_empty());
 }
 #[tokio::test]async fn cancel_interrupts_pending_packet_capture_without_opening_socket(){
  let(r,s)=setup(json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff"}}]));let(_tx,mut rx)=tokio::sync::mpsc::channel(1);let cancel=CancellationToken::new();let stop=cancel.clone();let worker=tokio::spawn(async move{r.decide(original(),&mut rx,&mut Captured::default(),&stop).await});tokio::task::yield_now().await;cancel.cancel();assert!(matches!(worker.await.unwrap(),Err(Failure::Cancelled)));assert_eq!(s.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn terminal_block_does_not_consume_queued_packet_or_run_later_effect(){
  let(r,s)=setup(json!([{"match":{"network":"udp"},"target":"block"}]),json!([{"beforeRule":1,"operation":{"action":"sniff"}}]));let(tx,mut rx)=tokio::sync::mpsc::channel(1);tx.send(vec![7,8,9].into()).await.unwrap();assert_eq!(r.decide(original(),&mut rx,&mut Captured::default(),&CancellationToken::new()).await.unwrap(),Decision::Block);assert_eq!(rx.try_recv().unwrap(),vec![7,8,9]);assert_eq!(s.0.load(Ordering::SeqCst),0);
 }

 #[tokio::test]async fn internal_dns_service_needs_no_sniff_rule_and_public_dns_still_obeys_block(){
  let(router,sockets)=setup(json!([]),json!([]));
  for(endpoint,expected)in [("198.19.255.53:53",true),("[fdfe:dcba:9876:dd54::53]:53",true),("9.9.9.9:53",false),("198.19.255.53:5353",false)]{
   let mut value=original();value.destination=endpoint.parse().unwrap();let(_tx,mut rx)=tokio::sync::mpsc::channel(1);let mut captured=Captured::default();let decision=router.decide(value,&mut rx,&mut captured,&CancellationToken::new()).await.unwrap();assert_eq!(matches!(decision,Decision::Dns{..}),expected);
  }
  let stop=CancellationToken::new();stop.cancel();let mut value=original();value.destination="198.19.255.53:53".parse().unwrap();let(_tx,mut rx)=tokio::sync::mpsc::channel(1);assert!(matches!(router.decide(value,&mut rx,&mut Captured::default(),&stop).await,Err(Failure::Cancelled)));assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
}
