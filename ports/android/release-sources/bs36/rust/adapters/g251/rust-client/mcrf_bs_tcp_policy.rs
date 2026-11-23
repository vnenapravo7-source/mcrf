//! Ordered TCP policy executor for the same-process accepted-stream runtime.
//! Source-only until UDP, TUN admission and Main lifecycle are attached. Options
//! not implemented by this executor are rejected before a stream is admitted.
use std::{net::{IpAddr,SocketAddr},sync::Arc,time::Duration,pin::Pin,future::Future};
use serde_json::{Value,json};use tokio::time::Instant;use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{bundle::Bundle,pipeline::{Pipeline,Kind,Instruction},Flow,Observed,DestinationView,Target,dns_wire};
use crate::{mcrf_bs_tcp_dispatch::{Stream,owned::{Router,Original,Decision,Failure}},mcrf_bs_tcp_inspect::{self,Prefix},mcrf_bs_dns_engine::Engine,mcrf_bs_dns_transport::SocketPolicy,mcrf_bs_dns_handler::Underlay};
#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid,Unsupported,Policy,Dns}
pub(crate) struct Sniff{pub names:Vec<String>,pub timeout:Duration,pub override_destination:bool}
pub(crate) enum Address{Ip(IpAddr),Name(String)}
pub(crate) struct Override{pub address:Option<Address>,pub port:Option<u16>}
/// A pre-handshake result never consumes or reoriginates the application's TCP
/// stream. Advanced means the SAME BS accepted-stream executor is required.
#[derive(Debug,PartialEq,Eq)]
pub enum RawDecision{Forward{peer:SocketAddr,target:Target},Block,Advanced}
pub struct PolicyRouter{pub(crate) pipeline:Pipeline,pub(crate) engine:Arc<Engine>,pub(crate) sockets:Arc<dyn SocketPolicy>,pub(crate) underlay:Underlay,pub(crate) default_resolver:Value}
pub(crate) fn boolean(row:&serde_json::Map<String,Value>,key:&str)->Result<bool,Error>{row.get(key).map_or(Ok(false),|v|v.as_bool().ok_or(Error::Invalid))}
pub(crate) fn duration(value:&Value)->Result<Duration,Error>{
 let text=value.as_str().ok_or(Error::Invalid)?;if text=="0"{return Ok(Duration::ZERO)}let text=text.strip_prefix('+').unwrap_or(text);if text.len()>32{return Err(Error::Invalid)}
 let units=[("ms",1_000_000u128),("us",1_000),("µs",1_000),("μs",1_000),("ns",1),("s",1_000_000_000),("m",60_000_000_000),("h",3_600_000_000_000)];
 let (number,scale)=units.iter().find_map(|(suffix,scale)|text.strip_suffix(*suffix).map(|v|(v,*scale))).ok_or(Error::Invalid)?;
 let mut parts=number.split('.');let whole=parts.next().unwrap_or("");let fraction=parts.next().unwrap_or("");if parts.next().is_some()||whole.is_empty()&&fraction.is_empty()||!whole.bytes().chain(fraction.bytes()).all(|v|v.is_ascii_digit())||fraction.len()>9{return Err(Error::Invalid)}
 let whole=if whole.is_empty(){0}else{whole.parse::<u128>().map_err(|_|Error::Invalid)?};let fraction_value=if fraction.is_empty(){0}else{fraction.parse::<u128>().map_err(|_|Error::Invalid)?};
 let nanos=whole.checked_mul(scale).and_then(|v|fraction_value.checked_mul(scale).and_then(|f|v.checked_add(f/10u128.pow(fraction.len()as u32)))).filter(|v|*v<=86_400_000_000_000).ok_or(Error::Invalid)?;Ok(Duration::from_nanos(nanos as u64))
}
pub(crate) fn sniff(value:&Value)->Result<Sniff,Error>{
 let row=value.as_object().ok_or(Error::Invalid)?;let names=match row.get("sniffer"){None=>Vec::new(),Some(Value::String(v))=>vec![v.clone()],Some(Value::Array(v))=>v.iter().map(|v|v.as_str().map(str::to_owned).ok_or(Error::Invalid)).collect::<Result<Vec<_>,_>>()?,_=>return Err(Error::Invalid)};
 if names.len()>16||names.iter().any(|v|!mcrf_bs_routing_policy::inspect::supports(v)){return Err(Error::Unsupported)}
 let mut timeout=row.get("timeout").map_or(Ok(Duration::from_millis(300)),duration)?;if timeout.is_zero(){timeout=Duration::from_millis(300)}if timeout>Duration::from_secs(5){return Err(Error::Unsupported)}
 Ok(Sniff{names,timeout,override_destination:boolean(row,"override_destination")?})
}
pub(crate) fn override_options(value:&Value)->Result<Override,Error>{
 let row=value.as_object().ok_or(Error::Invalid)?;
 for key in ["udp_connect","udp_disable_domain_unmapping"]{boolean(row,key)?;}
 for key in ["tls_fragment","tls_record_fragment"]{if boolean(row,key)?{return Err(Error::Unsupported)}}
 for key in ["udp_timeout","tls_fragment_fallback_delay","tls_record_fragment_fallback_delay"]{if let Some(value)=row.get(key){duration(value)?;}}
 if let Some(value)=row.get("network_strategy"){if !matches!(value.as_str(),Some(""|"default")){return Err(Error::Unsupported)}}
 if row.get("fallback_delay").map(duration).transpose()?.is_some_and(|v|!v.is_zero()){return Err(Error::Unsupported)}
 let address=match row.get("override_address"){None=>None,Some(value)=>{let text=value.as_str().ok_or(Error::Invalid)?;if text.is_empty(){None}else{Some(if let Ok(ip)=text.parse::<IpAddr>(){Address::Ip(ip)}else{let query=dns_wire::query(0,text,1).map_err(|_|Error::Invalid)?;Address::Name(dns_wire::question(&query).map_err(|_|Error::Invalid)?.name)})}}};
 let port=row.get("override_port").map(|v|v.as_u64().and_then(|v|u16::try_from(v).ok()).ok_or(Error::Invalid)).transpose()?.filter(|v|*v!=0);Ok(Override{address,port})
}
impl PolicyRouter{
 /// Classify before handing a SYN to smoltcp. The caller retains the original
 /// packet and its authenticated owner/FakeIP epoch. This opens DNS sockets
 /// only if ordered resolution requires them; it never opens a TCP connector.
 pub async fn decide_raw(&self,original:Original<'_>,cancel:&CancellationToken)->Result<RawDecision,Failure>{
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  if original.destination_domain.is_none()&&original.destination.port()==53&&crate::mcrf_bs_dns_resolver::dns_alias(original.destination.ip()){return Ok(RawDecision::Advanced)}
  let mut destination=original.destination.ip();let mut port=original.destination.port();
  let version=original.destination_domain.is_none().then_some(if destination.is_ipv4(){4}else{6});
  let mut name=original.destination_domain.map(str::to_owned);
  let mut addresses=name.is_none().then_some(vec![destination]);let mut cursor=self.pipeline.start();
  loop{
   if cancel.is_cancelled(){return Err(Failure::Cancelled)}
   let flow=Flow{source:original.source.ip(),destination,source_port:original.source.port(),port,network:"tcp",domain:Observed::Known(original.observed_domain.or(name.as_deref())),protocol:Observed::Known(None),packages:Some(original.packages),underlay:Observed::Known(Some(self.underlay.borrowed()))};
   let view=match addresses.as_deref(){Some(ips)=>DestinationView::Domain{addresses:ips,ip_version:version},None=>DestinationView::UnresolvedDomain{ip_version:version}};
   match self.pipeline.next(&flow,view,cursor).map_err(Failure::Policy)?{
    Instruction::Route(Target::Block)=>return Ok(RawDecision::Block),
    Instruction::Route(target)=>{
     let ips=match addresses{Some(ips)=>ips,None=>self.engine.lookup(self.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&self.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?};
     if ips.is_empty(){return Err(Failure::Route)}
     // The advanced connector preserves ordered retries / family racing.
     // A raw SYN cannot switch remote IP after it has entered the carrier.
     if ips.len()!=1{return Ok(RawDecision::Advanced)}
     let peer=SocketAddr::new(ips[0],port);
     // Address-family translation needs the advanced executor. A header NAT
     // must never reinterpret an IPv4 SYN as an IPv6 packet (or vice versa).
     if peer.is_ipv4()!=original.destination.is_ipv4(){return Ok(RawDecision::Advanced)}
     crate::mcrf_bs_server_routing::observed(self.engine.generation(),6,original.source,original.destination,original.uid,original.observed_domain.or(name.as_deref()),&target);
     return Ok(RawDecision::Forward{peer,target})
    },
    Instruction::ResolveNeeded{retry}=>{addresses=Some(self.engine.lookup(self.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&self.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?);cursor=retry},
    Instruction::Effect{operation,next}=>{
     match operation.kind{
      Kind::Sniff=>{
       let config=sniff(&operation.options).map_err(|_|Failure::Route)?;
       let applicable=!matches!(port,25|465|587|143|993|110|995)&&(config.names.is_empty()||config.names.iter().any(|v|mcrf_bs_routing_policy::inspect::stream_sniffer(v)));
       if applicable&&(config.override_destination||self.pipeline.sniff_needed(&flow,view,next).map_err(Failure::Policy)?){return Ok(RawDecision::Advanced)}
      },
      Kind::HijackDns=>return Ok(RawDecision::Advanced),
      Kind::Resolve=>if let Some(name)=name.as_deref(){addresses=Some(self.engine.lookup(self.sockets.as_ref(),name,&flow,&operation.options,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?)},
      Kind::RouteOptions=>{let options=override_options(&operation.options).map_err(|_|Failure::Route)?;match options.address{Some(Address::Ip(ip))=>{destination=ip;name=None;addresses=Some(vec![ip])},Some(Address::Name(value))=>{name=Some(value);addresses=None},None=>{}}if let Some(value)=options.port{port=value}},
     }cursor=next;
    },
   }
  }
 }
 pub fn new(bundle:Bundle,engine:Arc<Engine>,sockets:Arc<dyn SocketPolicy>,underlay:Underlay)->Result<Self,Error>{
  if !underlay.valid(){return Err(Error::Invalid)}let options=&bundle.document()["routeOptions"];
  for key in ["auto_detect_interface","find_process"]{if options.get(key).is_some_and(|v|v.as_bool().is_none()){return Err(Error::Invalid)}}
  let default_resolver=match options.get("default_domain_resolver"){None|Some(Value::Null)=>json!({}),Some(Value::String(v))if v.is_empty()=>json!({}),Some(Value::String(v))=>json!({"server":v}),Some(v @ Value::Object(_))=>v.clone(),_=>return Err(Error::Invalid)};
  engine.validate_lookup(&default_resolver).map_err(|_|Error::Dns)?;let pipeline=Pipeline::new(bundle).map_err(|_|Error::Policy)?.with_server(crate::mcrf_bs_server_routing::shared());
  for operation in pipeline.operations(){match operation.kind{Kind::Sniff=>{sniff(&operation.options)?;},Kind::Resolve=>engine.validate_lookup(&operation.options).map_err(|_|Error::Dns)?,Kind::RouteOptions=>{override_options(&operation.options)?;},Kind::HijackDns=>{}}}
  Ok(Self{pipeline,engine,sockets,underlay,default_resolver})
 }
}
impl Router for PolicyRouter{
 fn decide<'a>(&'a self,original:Original<'a>,stream:&'a mut Stream,captured:&'a mut Prefix,cancel:&'a CancellationToken)->Pin<Box<dyn Future<Output=Result<Decision,Failure>>+Send+'a>>{Box::pin(async move{
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  // The VPN-advertised service address belongs to this resolver, regardless
  // of ordinary data routes. Never extend this exception to public DNS IPs.
  if original.destination_domain.is_none()&&original.destination.port()==53&&crate::mcrf_bs_dns_resolver::dns_alias(original.destination.ip()){return Ok(Decision::Dns)}
  let mut destination=original.destination.ip();let mut port=original.destination.port();let version=if original.destination_domain.is_some(){None}else{Some(if destination.is_ipv4(){4}else{6})};
  let mut name=original.destination_domain.map(str::to_owned);let mut addresses=if name.is_some(){None}else{Some(vec![destination])};let mut observed=original.observed_domain.map(str::to_owned);let mut protocol=None;let mut cursor=self.pipeline.start();
  loop{
   if cancel.is_cancelled(){return Err(Failure::Cancelled)}
   let flow=Flow{source:original.source.ip(),destination,source_port:original.source.port(),port,network:"tcp",domain:Observed::Known(observed.as_deref().or(name.as_deref())),protocol:Observed::Known(protocol),packages:Some(original.packages),underlay:Observed::Known(Some(self.underlay.borrowed()))};
   let view=if let Some(ips)=addresses.as_deref(){DestinationView::Domain{addresses:ips,ip_version:version}}else{DestinationView::UnresolvedDomain{ip_version:version}};
   match self.pipeline.next(&flow,view,cursor).map_err(Failure::Policy)?{
    Instruction::Route(Target::Block)=>return Ok(Decision::Block),
    Instruction::Route(target)=>{
     let ips=if let Some(ips)=addresses{ips}else{self.engine.lookup(self.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&self.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?};
     crate::mcrf_bs_server_routing::observed(self.engine.generation(),6,original.source,original.destination,original.uid,observed.as_deref().or(name.as_deref()),&target);
     return Ok(Decision::ForwardMany{peers:ips.into_iter().map(|ip|SocketAddr::new(ip,port)).collect(),target})
    },
    Instruction::ResolveNeeded{retry}=>{
     addresses=Some(self.engine.lookup(self.sockets.as_ref(),name.as_deref().ok_or(Failure::Route)?,&flow,&self.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?);cursor=retry;
    },
    Instruction::Effect{operation,next}=>{
     match operation.kind{
      Kind::Sniff=>{
       let config=sniff(&operation.options).map_err(|_|Failure::Route)?;let names:Vec<_>=config.names.iter().filter(|v|mcrf_bs_routing_policy::inspect::stream_sniffer(v)).cloned().collect();
       if protocol.is_none()&&!matches!(port,25|465|587|143|993|110|995)&&(config.names.is_empty()||!names.is_empty()){
        *captured=mcrf_bs_tcp_inspect::capture(stream,config.timeout,std::mem::take(&mut captured.bytes),&names,cancel).await.map_err(|_|Failure::Route)?;protocol=captured.protocol;
        if let Some(found)=&captured.domain{observed=Some(found.clone());if config.override_destination{name=Some(found.clone());addresses=None;}}
       }
      },
      Kind::HijackDns=>return Ok(Decision::Dns),
      Kind::Resolve=>{if let Some(name)=name.as_deref(){addresses=Some(self.engine.lookup(self.sockets.as_ref(),name,&flow,&operation.options,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(Failure::Resolve)?)}},
      Kind::RouteOptions=>{
       let options=override_options(&operation.options).map_err(|_|Failure::Route)?;match options.address{Some(Address::Ip(ip))=>{destination=ip;name=None;addresses=Some(vec![ip]);},Some(Address::Name(value))=>{name=Some(value);addresses=None;},None=>{}}if let Some(value)=options.port{port=value;}
      },
     }cursor=next;
    },
   }
  }
 })}
}

#[cfg(test)]mod tests{
 use super::*;use std::sync::atomic::{AtomicUsize,Ordering};use tokio::io::AsyncWriteExt;
 struct NoSockets(AtomicUsize);impl SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(crate::mcrf_bs_dns_transport::Failure::Bind)}}
 fn bundle(rules:Value,controls:Value,options:Value)->Bundle{Bundle::parse(&serde_json::to_vec(&json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":rules},"controls":controls,"dns":null,"routeOptions":options,"tunInbounds":[],"dnsDialers":{}})).unwrap()).unwrap()}
 fn setup()->(Arc<Engine>,Arc<NoSockets>,Underlay){(Arc::new(Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":["192.0.2.9","2001:db8::9"],"target.invalid":"192.0.2.42"}}],"final":"h"}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap()),Arc::new(NoSockets(AtomicUsize::new(0))),Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:"owned".into(),bssid:String::new()})}
 fn original<'a>(packages:&'a [String])->Original<'a>{Original{source:"10.89.0.2:45000".parse().unwrap(),destination:"192.0.2.1:443".parse().unwrap(),destination_domain:None,observed_domain:None,uid:10001,packages}}
 fn captured()->Prefix{Prefix{bytes:Vec::new(),protocol:None,domain:None}}
 fn http()->&'static [u8]{b"GET / HTTP/1.1\r\nHost: owned.invalid\r\n\r\nbody"}
 #[tokio::test]async fn raw_preflight_keeps_numeric_tunnel_and_skips_only_unobservable_sniff(){
  for controls in [json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff"}}])]{
   let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp","port":443},"target":"proxy"}]),controls,json!({})),engine,sockets.clone(),underlay).unwrap();
   assert_eq!(router.decide_raw(original(&[]),&CancellationToken::new()).await.unwrap(),RawDecision::Forward{peer:"192.0.2.1:443".parse().unwrap(),target:Target::Tunnel});assert_eq!(sockets.0.load(Ordering::SeqCst),0);
  }
 }
 #[tokio::test]async fn raw_preflight_preserves_sniff_hijack_and_domain_semantics(){
  for (rules,controls) in [
   (json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}])),
   (json!([{"match":{"domain":"owned.invalid"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff"}}])),
   (json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff","override_destination":true}}]))
  ]{let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(rules,controls,json!({})),engine,sockets.clone(),underlay).unwrap();assert_eq!(router.decide_raw(original(&[]),&CancellationToken::new()).await.unwrap(),RawDecision::Advanced);assert_eq!(sockets.0.load(Ordering::SeqCst),0)}
 }
 #[tokio::test]async fn port_scoped_default_dns_keeps_https_raw_and_dns_local(){
  let(engine,sockets,underlay)=setup();
  let controls=json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","port":53}}]);
  let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp"},"target":"proxy"}]),controls,json!({})),engine,sockets.clone(),underlay).unwrap();
  let cancel=CancellationToken::new();
  assert_eq!(router.decide_raw(original(&[]),&cancel).await.unwrap(),RawDecision::Forward{peer:"192.0.2.1:443".parse().unwrap(),target:Target::Tunnel});
  let dns=||{let mut flow=original(&[]);flow.destination.set_port(53);flow};
  assert_eq!(router.decide_raw(dns(),&cancel).await.unwrap(),RawDecision::Advanced);
  let(_app,inside)=tokio::io::duplex(128);let mut stream:Stream=Box::new(inside);
  assert!(matches!(router.decide(dns(),&mut stream,&mut captured(),&cancel).await.unwrap(),Decision::Dns));
  assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn only_domain_rules_applicable_to_this_app_or_port_require_tcp_inspection(){
  let(engine,sockets,underlay)=setup();
  let controls=json!([{"beforeRule":0,"operation":{"action":"sniff"}}]);
  let rules=json!([
   {"match":{"domain":"owned.invalid","package_name":"org.match"},"target":"direct"},
   {"match":{"domain":"owned.invalid","port":8443},"target":"block"},
   {"match":{"network":"tcp"},"target":"proxy"}
  ]);
  let router=PolicyRouter::new(bundle(rules,controls,json!({})),engine,sockets,underlay).unwrap();
  let cancel=CancellationToken::new();
  assert_eq!(router.decide_raw(original(&[]),&cancel).await.unwrap(),RawDecision::Forward{peer:"192.0.2.1:443".parse().unwrap(),target:Target::Tunnel});
  assert_eq!(router.decide_raw(original(&["org.match".into()]),&cancel).await.unwrap(),RawDecision::Advanced);
  let mut other_port=original(&[]);other_port.destination.set_port(8443);
  assert_eq!(router.decide_raw(other_port,&cancel).await.unwrap(),RawDecision::Advanced);
 }
 #[tokio::test]async fn raw_preflight_does_not_discard_dns_address_fallbacks(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp"},"target":"proxy"}]),json!([{"beforeRule":0,"operation":{"action":"route-options","override_address":"owned.invalid"}},{"beforeRule":0,"operation":{"action":"resolve","server":"h"}}]),json!({})),engine,sockets.clone(),underlay).unwrap();assert_eq!(router.decide_raw(original(&[]),&CancellationToken::new()).await.unwrap(),RawDecision::Advanced);assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn raw_preflight_resolves_ordered_override_and_never_crosses_address_families(){
  for(strategy,expected)in [("ipv4_only",RawDecision::Forward{peer:"192.0.2.9:8443".parse().unwrap(),target:Target::Tunnel}),("ipv6_only",RawDecision::Advanced)]{
   let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp"},"target":"proxy"}]),json!([{"beforeRule":0,"operation":{"action":"route-options","override_address":"owned.invalid","override_port":8443}},{"beforeRule":0,"operation":{"action":"resolve","server":"h","strategy":strategy}}]),json!({})),engine,sockets.clone(),underlay).unwrap();assert_eq!(router.decide_raw(original(&[]),&CancellationToken::new()).await.unwrap(),expected);assert_eq!(sockets.0.load(Ordering::SeqCst),0);
   let stop=CancellationToken::new();stop.cancel();assert!(matches!(router.decide_raw(original(&[]),&stop).await,Err(Failure::Cancelled)));
  }
 }
 fn assert_forward(decision:Decision,expected:SocketAddr){match decision{Decision::ForwardMany{peers,target}=>{assert!(target==Target::Direct);assert_eq!(peers,vec![expected]);},_=>panic!("expected one owned direct destination")}}
 #[tokio::test]async fn sniffed_domain_selects_route_without_replacing_ordinary_numeric_destination(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"domain":"owned.invalid","protocol":"http"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","timeout":".3s"}}]),json!({})),engine,sockets.clone(),underlay).unwrap();
  let(mut app,inside)=tokio::io::duplex(4096);app.write_all(http()).await.unwrap();let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=vec!["org.allowed".into()];
  let result=router.decide(original(&packages),&mut stream,&mut prefix,&CancellationToken::new()).await.unwrap();assert_forward(result,"192.0.2.1:443".parse().unwrap());assert_eq!(prefix.bytes,http());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn override_sniff_lazily_resolves_at_ip_rule_without_reading_prefix_twice(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"ip_cidr":"192.0.2.9/32","ip_version":4},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","override_destination":true}}]),json!({"default_domain_resolver":{"server":"h","strategy":"ipv4_only"}})),engine,sockets.clone(),underlay).unwrap();
  let(mut app,inside)=tokio::io::duplex(4096);app.write_all(http()).await.unwrap();let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=Vec::new();
  assert_forward(router.decide(original(&packages),&mut stream,&mut prefix,&CancellationToken::new()).await.unwrap(),"192.0.2.9:443".parse().unwrap());assert_eq!(prefix.bytes,http());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn fake_domain_resolve_keeps_unknown_original_ip_version_and_selected_family(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"ip_version":6},"target":"block"},{"match":{"ip_cidr":"2001:db8::/32"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"resolve","server":"h","strategy":"ipv6_only"}}]),json!({})),engine,sockets.clone(),underlay).unwrap();
  let(_app,inside)=tokio::io::duplex(4096);let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=Vec::new();let mut input=original(&packages);input.destination="198.18.0.1:443".parse().unwrap();input.destination_domain=Some("owned.invalid");
  assert_forward(router.decide(input,&mut stream,&mut prefix,&CancellationToken::new()).await.unwrap(),"[2001:db8::9]:443".parse().unwrap());assert!(prefix.bytes.is_empty());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn dns_sniff_and_conditional_hijack_preserve_original_nonstandard_tcp_frame(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}]),json!({})),engine,sockets.clone(),underlay).unwrap();let query=dns_wire::query(19,"owned.invalid",1).unwrap();let mut frame=(query.len()as u16).to_be_bytes().to_vec();frame.extend_from_slice(&query);
  let(mut app,inside)=tokio::io::duplex(4096);app.write_all(&frame).await.unwrap();let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=Vec::new();let mut input=original(&packages);input.destination.set_port(5353);
  assert!(matches!(router.decide(input,&mut stream,&mut prefix,&CancellationToken::new()).await.unwrap(),Decision::Dns));assert_eq!(prefix.bytes,frame);assert_eq!(prefix.protocol,Some("dns"));assert!(prefix.domain.is_none());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn server_first_and_udp_only_sniffers_never_wait_for_tcp_payload(){
  for port in [25,465,587,143,993,110,995,443]{let(engine,sockets,underlay)=setup();let names=if port==443{json!(["quic","dtls","stun"])}else{json!([])};let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","sniffer":names}}]),json!({})),engine,sockets.clone(),underlay).unwrap();
   let(_app,inside)=tokio::io::duplex(4096);let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=Vec::new();let mut input=original(&packages);input.destination.set_port(port);
   assert_forward(tokio::time::timeout(Duration::from_millis(50),router.decide(input,&mut stream,&mut prefix,&CancellationToken::new())).await.unwrap().unwrap(),SocketAddr::new("192.0.2.1".parse().unwrap(),port));assert!(prefix.bytes.is_empty());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
  }
 }
 #[tokio::test]async fn route_override_keeps_observed_domain_separate_from_connector_name_and_port(){
  for observed in [None,Some("original.invalid")]{let(engine,sockets,underlay)=setup();let matched=observed.unwrap_or("target.invalid");let router=PolicyRouter::new(bundle(json!([{"match":{"domain":matched},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"route-options","override_address":"target.invalid","override_port":8443}},{"beforeRule":0,"operation":{"action":"resolve","server":"h","strategy":"ipv4_only"}}]),json!({})),engine,sockets.clone(),underlay).unwrap();
   let(_app,inside)=tokio::io::duplex(4096);let mut stream:Stream=Box::new(inside);let mut prefix=captured();let packages=Vec::new();let mut input=original(&packages);input.observed_domain=observed;
   assert_forward(router.decide(input,&mut stream,&mut prefix,&CancellationToken::new()).await.unwrap(),"192.0.2.42:8443".parse().unwrap());assert_eq!(sockets.0.load(Ordering::SeqCst),0);
  }
 }
 #[test]fn unsupported_effects_are_rejected_before_any_socket_not_silently_replaced(){
  for operation in [json!({"action":"route-options","tls_fragment":true}),json!({"action":"route-options","override_port":70000}),json!({"action":"route-options","network_strategy":"hybrid"}),json!({"action":"route-options","fallback_delay":"100ms"}),json!({"action":"resolve","server":"missing"}),json!({"action":"sniff","sniffer":"unknown"}),json!({"action":"sniff","timeout":"6s"}),json!({"action":"resolve","client_subnet":"192.0.2.0/24"})]{let(engine,sockets,underlay)=setup();assert!(PolicyRouter::new(bundle(json!([]),json!([{"beforeRule":0,"operation":operation}]),json!({})),engine,sockets.clone(),underlay).is_err());assert_eq!(sockets.0.load(Ordering::SeqCst),0);}
 }
 #[test]fn duration_options_use_integer_nanoseconds_and_validate_fraction_and_bounds(){
  for(text,expected)in [(".3s",300_000_000),("500.5ms",500_500_000),("0",0),("+1s",1_000_000_000),("250us",250_000),("1h",3_600_000_000_000)]{assert_eq!(duration(&json!(text)).unwrap().as_nanos(),expected)}
  for text in ["-1s","1e3s","1s500ms","24.1h",".","1.2.3s"]{assert!(duration(&json!(text)).is_err())}
 }

 #[tokio::test]async fn internal_dns_service_needs_no_sniff_rule_and_cannot_enter_raw_forwarding(){
  let(engine,sockets,underlay)=setup();let router=PolicyRouter::new(bundle(json!([{"match":{"network":"tcp"},"target":"block"}]),json!([]),json!({})),engine,sockets.clone(),underlay).unwrap();
  for endpoint in ["198.19.255.53:53","[fdfe:dcba:9876:dd54::53]:53"]{
   let mut value=original(&[]);value.destination=endpoint.parse().unwrap();assert_eq!(router.decide_raw(value,&CancellationToken::new()).await.unwrap(),RawDecision::Advanced);
   let mut value=original(&[]);value.destination=endpoint.parse().unwrap();let(_app,inside)=tokio::io::duplex(128);let mut stream:Stream=Box::new(inside);assert!(matches!(router.decide(value,&mut stream,&mut captured(),&CancellationToken::new()).await.unwrap(),Decision::Dns));
  }
  for endpoint in ["9.9.9.9:53","198.19.255.53:5353","[fdfe:dcba:9876:dd54::53]:443"]{
   let mut value=original(&[]);value.destination=endpoint.parse().unwrap();let(_app,inside)=tokio::io::duplex(128);let mut stream:Stream=Box::new(inside);assert!(matches!(router.decide(value,&mut stream,&mut captured(),&CancellationToken::new()).await.unwrap(),Decision::Block));
  }
  let stop=CancellationToken::new();stop.cancel();let mut value=original(&[]);value.destination="198.19.255.53:53".parse().unwrap();assert!(matches!(router.decide_raw(value,&stop).await,Err(Failure::Cancelled)));assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 }
}
