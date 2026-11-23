//! Source-only composition of captured DNS rules, resolver and per-session
//! cache/attribution. Not a production capability: packet/control/flow runtime
//! must be attached and every unsupported option admitted before TUN startup.
use std::{net::IpAddr,collections::HashMap,sync::Mutex,time::Instant as Clock};
use serde_json::Value;use tokio::time::Instant;use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{Flow,Observed,dns_rules::Question,dns_selector::{Selector,Action},dns_options::{RootOptions,QueryOptions},dns_wire,dns_cache::Cache,observations::{DomainCache,DomainObservation}};
use crate::{mcrf_bs_dns_resolver::Resolver,mcrf_bs_dns_plan::{Plan,Server},mcrf_bs_dns_transport::SocketPolicy};
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Invalid,Unsupported,Rule,Resolve,Wire,Cache,Metadata,Drop,Cancelled}
pub struct Engine{generation:u64,started:Clock,selector:Selector,resolver:Resolver,root:RootOptions,cache:Mutex<Cache>,domains:Mutex<DomainCache>}
fn rcode(value:&Value)->Result<u8,Failure>{
 let row=value.as_object().ok_or(Failure::Invalid)?;
 for key in ["answer","ns","extra"]{if let Some(value)=row.get(key){if value.as_array().is_none_or(|a|!a.is_empty()){return Err(Failure::Unsupported)}}}
 let Some(value)=row.get("rcode")else{return Ok(0)};
 if let Some(number)=value.as_u64(){return u8::try_from(number).ok().filter(|v|*v<16).ok_or(Failure::Invalid)}
 match value.as_str(){Some("NOERROR")=>Ok(0),Some("FORMERR")=>Ok(1),Some("SERVFAIL")=>Ok(2),Some("NXDOMAIN")=>Ok(3),Some("NOTIMP")=>Ok(4),Some("REFUSED")=>Ok(5),_=>Err(Failure::Unsupported)}
}
fn admit(options:&QueryOptions)->Result<(),Failure>{
 if options.subnet.is_some()||options.remove_subnet||options.timeout>mcrf_bs_routing_policy::dns_limits::QUERY_MAX{return Err(Failure::Unsupported)}Ok(())
}
fn reject(value:&Value)->Result<bool,Failure>{
 let row=value.as_object().ok_or(Failure::Invalid)?;if row.get("no_drop").is_some_and(|v|v.as_bool().is_none()){return Err(Failure::Invalid)}
 match row.get("method").map(Value::as_str){None|Some(Some("default"))=>Ok(false),Some(Some("drop"))=>Ok(true),_=>Err(Failure::Unsupported)}
}
impl Engine{
 pub fn generation(&self)->u64{self.generation}
 // Called only after admitting a unique tracker epoch. A virtual destination
 // has a domain identity, not a real upstream address. Never resolve it eagerly
 // while answering the application's DNS query.
 pub(crate) fn pin_flow_name(&self,id:u64,ip:IpAddr)->Result<Option<String>,Failure>{self.resolver.pin_fake(id,ip).map_err(|_|Failure::Metadata)}
 pub(crate) fn release_flow_name(&self,id:u64)->Result<(),Failure>{self.resolver.release_fake(id).map_err(|_|Failure::Metadata)}
 pub(crate) fn virtual_address(&self,ip:IpAddr)->Result<bool,Failure>{self.resolver.is_fake(ip).map_err(|_|Failure::Metadata)}
 pub(crate) fn proxy_destination_name(&self,ip:IpAddr)->Result<Option<String>,Failure>{self.resolver.proxy_name(ip).map_err(|_|Failure::Metadata)}

 pub fn new(generation:u64,physical:u64,document:&Value,sets:&Value,dialers:&Value,hosts:HashMap<String,Vec<IpAddr>>,excluded:&[IpAddr])->Result<Self,Failure>{
  let selector=Selector::parse(document,sets).map_err(|_|Failure::Rule)?;let root=RootOptions::parse(document).map_err(|_|Failure::Invalid)?;admit(&root.query)?;
  if root.optimistic.is_some(){return Err(Failure::Unsupported)}
  for action in selector.actions(){match action{
   Action::Route{options,..}|Action::Options(options)=>{let mut options_full=root.query.clone();options_full.apply(options).map_err(|_|Failure::Invalid)?;admit(&options_full)?;},
   Action::Predefined(value)=>{rcode(value)?;},Action::Reject(value)=>{reject(value)?;},
  }}
  let plan=Plan::parse(document,dialers).map_err(|_|Failure::Invalid)?;
  let resolver=Resolver::new(plan,physical,generation,hosts,excluded).map_err(|_|Failure::Invalid)?;
  Ok(Self{generation,started:Clock::now(),selector,resolver,cache:Mutex::new(Cache::new(generation,root.capacity,root.disable_expire).map_err(|_|Failure::Invalid)?),domains:Mutex::new(DomainCache::new(generation,8192).map_err(|_|Failure::Invalid)?),root})
 }
 fn now(&self)->Result<u64,Failure>{u64::try_from(self.started.elapsed().as_millis()).map_err(|_|Failure::Invalid)}
 pub async fn resolve(&self,policy:&dyn SocketPolicy,query:&[u8],original:&Flow<'_>,deadline:Instant,cancel:&CancellationToken)->Result<Vec<u8>,Failure>{
  self.resolve_inner(policy,query,original,deadline,cancel,&serde_json::json!({}),None,false).await
 }
 async fn resolve_inner(&self,policy:&dyn SocketPolicy,query:&[u8],original:&Flow<'_>,deadline:Instant,cancel:&CancellationToken,overrides:&Value,server:Option<&str>,lookup:bool)->Result<Vec<u8>,Failure>{
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}let wire=dns_wire::question(query).map_err(|_|Failure::Wire)?;
  let preferred=self.resolver.preferred(&wire.name);
  let flow=Flow{domain:Observed::Known(Some(&wire.name)),protocol:if lookup{original.protocol}else{Observed::Known(Some("dns"))},..*original};
  let explicit=Action::Route{server:server.unwrap_or("").to_owned(),options:serde_json::json!({})};
  let selected=if server.is_some(){mcrf_bs_routing_policy::dns_selector::Selection{action:&explicit,preceding_options:Vec::new()}}else{self.selector.select(&Question{flow,query_type:wire.kind,preferred_by:Some(&preferred)}).map_err(|_|Failure::Rule)?};
  let mut options=self.root.query.clone();options.apply(overrides).map_err(|_|Failure::Invalid)?;admit(&options)?;for value in selected.preceding_options{options.apply(value).map_err(|_|Failure::Invalid)?;}
  let mut answer=match selected.action{
   Action::Predefined(value)=>dns_wire::answer(query,rcode(value)?,&[],0).map_err(|_|Failure::Wire)?,
   Action::Reject(value)=>{if reject(value)?{return Err(Failure::Drop)}dns_wire::answer(query,5,&[],0).map_err(|_|Failure::Wire)?},
   Action::Options(_)=>return Err(Failure::Invalid),
   Action::Route{server,options:action_options}=>{
    options.apply(action_options).map_err(|_|Failure::Invalid)?;admit(&options)?;
    if wire.kind==65&&matches!(options.strategy.as_str(),"ipv4_only"|"ipv6_only"){return Err(Failure::Unsupported)}
    if wire.kind==28&&options.strategy=="ipv4_only"||wire.kind==1&&options.strategy=="ipv6_only"{dns_wire::answer(query,0,&[],0).map_err(|_|Failure::Wire)?}else{
     let fake=matches!(self.resolver.server(server),Some(Server::Fake{..}));if lookup&&fake{return Err(Failure::Unsupported)}let disabled=options.disable_cache||fake;
     let cached=if disabled{None}else{let mut cache=self.cache.lock().map_err(|_|Failure::Cache)?;cache.get(self.generation,self.now()?,server,query).map_err(|_|Failure::Cache)?};
     if let Some(answer)=cached{answer}else{
      let mut answer=self.resolver.exchange(policy,server,query,deadline.min(Instant::now()+options.timeout),cancel).await.map_err(|error|match error{crate::mcrf_bs_dns_resolver::Failure::Cancelled=>Failure::Cancelled,crate::mcrf_bs_dns_resolver::Failure::Unsupported=>Failure::Unsupported,_=>Failure::Resolve})?;
      let ttl=options.rewrite_ttl.unwrap_or(dns_wire::cache_ttl(query,&answer).map_err(|_|Failure::Wire)?);answer=dns_wire::rewrite_ttl(query,&answer,ttl).map_err(|_|Failure::Wire)?;
      if !disabled{let mut cache=self.cache.lock().map_err(|_|Failure::Cache)?;cache.store(self.generation,self.now()?,server,query,&answer).map_err(|_|Failure::Cache)?;}answer
     }
    }
   },
  };
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  if let Some(ttl)=options.rewrite_ttl{answer=dns_wire::rewrite_ttl(query,&answer,ttl).map_err(|_|Failure::Wire)?;}
  Ok(answer)
 }
 fn lookup_options(&self,values:&Value)->Result<(Option<String>,Value,QueryOptions),Failure>{
  let mut values=values.as_object().ok_or(Failure::Invalid)?.clone();
  let server=match values.remove("server"){None=>None,Some(Value::String(value))if value.is_empty()=>None,Some(Value::String(value))=>Some(value),_=>return Err(Failure::Invalid)};
  if let Some(tag)=server.as_deref(){if self.resolver.server(tag).is_none(){return Err(Failure::Invalid)}if matches!(self.resolver.server(tag),Some(Server::Fake{..})){return Err(Failure::Unsupported)}}
  // Optimistic caching is not admitted by Engine::new. Disabling it is still
  // a valid lookup option; validate the flag rather than treating it as a field
  // for a different subsystem or enabling an unimplemented optimistic cache.
  if values.remove("disable_optimistic_cache").is_some_and(|v|v.as_bool().is_none()){return Err(Failure::Invalid)}
  let overrides=Value::Object(values);let mut options=self.root.query.clone();options.apply(&overrides).map_err(|_|Failure::Invalid)?;admit(&options)?;
  Ok((server,overrides,options))
 }
 pub(crate) fn validate_lookup(&self,values:&Value)->Result<(),Failure>{self.lookup_options(values).map(|_|())}
 /// Resolve a FLOW destination through this session's captured DNS rules or
 /// an explicitly selected transport. A/AAAA run concurrently; caller strategy
 /// orders the results. These internal queries never grant DNS attribution and
 /// can never return a virtual address as a real connector destination.
 pub async fn lookup(&self,policy:&dyn SocketPolicy,name:&str,original:&Flow<'_>,values:&Value,deadline:Instant,cancel:&CancellationToken)->Result<Vec<IpAddr>,Failure>{
  let(server,overrides,options)=self.lookup_options(values)?;
  let query=async |kind:u16|{
   let request=dns_wire::query(0,name,kind).map_err(|_|Failure::Invalid)?;
   let answer=self.resolve_inner(policy,&request,original,deadline,cancel,&overrides,server.as_deref(),true).await?;
   if answer[3]&15!=0{return Err(Failure::Resolve)}
   let rows=dns_wire::addresses(&request,&answer).map_err(|_|Failure::Wire)?;let mut ips=Vec::new();
   for row in rows{if row.ip.is_ipv4()!=(kind==1){continue}if self.resolver.is_fake(row.ip).map_err(|_|Failure::Metadata)?{return Err(Failure::Metadata)}if !ips.contains(&row.ip){ips.push(row.ip)}}Ok::<_,Failure>(ips)
  };
  let(mut v4,mut v6)=match options.strategy.as_str(){"ipv4_only"=>(query(1).await?,Vec::new()),"ipv6_only"=>(Vec::new(),query(28).await?),_=>{
   let(a,b)=tokio::join!(query(1),query(28));match(a,b){(Ok(a),Ok(b))=>(a,b),(Ok(a),Err(_))if !a.is_empty()=>(a,Vec::new()),(Err(_),Ok(b))if !b.is_empty()=>(Vec::new(),b),(Err(e),_)|(_,Err(e))=>return Err(e)}
  }};
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let ips=if options.strategy=="prefer_ipv6"{v6.append(&mut v4);v6}else{v4.append(&mut v6);v4};
  if ips.is_empty()||ips.len()>64{return Err(Failure::Resolve)}Ok(ips)
 }
 /// Call only AFTER rechecking original kernel owner and mapping. Resolution
 /// alone (including a successful cache hit) never grants caller attribution.
 pub fn admit(&self,query:&[u8],answer:&[u8],uid:u32)->Result<(),Failure>{
  if uid>i32::MAX as u32{return Err(Failure::Invalid)}
  // Attribute cache hits too, using the ORIGINAL freshly verified caller.
  // Zero TTL removes any older unique attribution instead of inheriting it.
  let rows=dns_wire::addresses(query,&answer).map_err(|_|Failure::Wire)?;let mut cache=self.domains.lock().map_err(|_|Failure::Metadata)?;let now=self.now()?;
  for row in rows{if self.resolver.is_fake(row.ip).map_err(|_|Failure::Metadata)?{continue} // Fake addresses have their own immutable domain table.
   if row.ttl_ms==0{cache.invalidate(self.generation,now,uid,&[row.ip]).map_err(|_|Failure::Metadata)?;}else{cache.admit(self.generation,now,uid,&row.domain,&[row.ip],row.ttl_ms.min(86400000)).map_err(|_|Failure::Metadata)?;}
  }Ok(())
 }
 #[cfg(test)]async fn exchange(&self,policy:&dyn SocketPolicy,query:&[u8],uid:u32,original:&Flow<'_>,deadline:Instant,cancel:&CancellationToken)->Result<Vec<u8>,Failure>{let answer=self.resolve(policy,query,original,deadline,cancel).await?;self.admit(query,&answer,uid)?;Ok(answer)}
 pub fn domain(&self,uid:u32,ip:IpAddr)->Result<Option<String>,Failure>{
  if !self.root.reverse_mapping{return Ok(None)}let mut cache=self.domains.lock().map_err(|_|Failure::Metadata)?;
  match cache.lookup(self.generation,self.now()?,uid,ip).map_err(|_|Failure::Metadata)?{DomainObservation::Unique(name)=>Ok(Some(name.to_owned())),DomainObservation::Missing|DomainObservation::Ambiguous=>Ok(None)}
 }
}
#[cfg(all(test,unix))]mod tests{
 use super::*;use serde_json::json;use std::sync::atomic::{AtomicUsize,Ordering};use crate::mcrf_bs_dns_transport::{Direction,Failure as SocketFailure};
 struct OwnedPolicy(AtomicUsize);impl SocketPolicy for OwnedPolicy{fn prepare(&self,_:u8,_:i32,_:u16,_:std::net::SocketAddr,_:Direction)->Result<Box<dyn Send>,SocketFailure>{self.0.fetch_add(1,Ordering::SeqCst);Ok(Box::new(()))}}
 fn engine(document:Value)->Engine{Engine::new(42,1,&document,&json!({}),&json!({}),HashMap::new(),&[]).unwrap()}
 fn flow<'a>(packages:&'a [String])->Flow<'a>{Flow{source:"10.89.0.2".parse().unwrap(),destination:"198.19.255.53".parse().unwrap(),source_port:40000,port:53,network:"udp",domain:Observed::Pending,protocol:Observed::Pending,packages:Some(packages),underlay:Observed::Known(None)}}
 #[tokio::test]async fn ordered_dns_rules_options_hosts_and_block_keep_original_caller(){
  let e=engine(json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.1"}}],"final":"h","reverse_mapping":true,"rules":[{"package_name":"org.blocked","action":"predefined","rcode":"NXDOMAIN"},{"domain":"owned.invalid","action":"route-options","rewrite_ttl":30},{"preferred_by":"h","action":"route","server":"h"}]}));
  let policy=OwnedPolicy(AtomicUsize::new(0));let q=dns_wire::query(8,"owned.invalid",1).unwrap();let cancel=CancellationToken::new();let deadline=Instant::now()+std::time::Duration::from_secs(2);
  let blocked=vec!["org.blocked".into()];let allowed=vec!["org.allowed".into()];
  let r=e.exchange(&policy,&q,10001,&flow(&blocked),deadline,&cancel).await.unwrap();assert_eq!(r[3]&15,3);assert_eq!(e.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None);
  for uid in [10002,10003]{let r=e.exchange(&policy,&q,uid,&flow(&allowed),deadline,&cancel).await.unwrap();assert_eq!(dns_wire::addresses(&q,&r).unwrap()[0].ttl_ms,30000);assert_eq!(e.domain(uid,"192.0.2.1".parse().unwrap()).unwrap(),Some("owned.invalid".into()));}
  assert_eq!(e.domain(10001,"192.0.2.1".parse().unwrap()).unwrap(),None);assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn cache_hit_does_not_open_another_socket_and_is_attributed_to_new_uid(){
  tokio::time::timeout(std::time::Duration::from_secs(5),async{
   let socket=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let port=socket.local_addr().unwrap().port();
   let server=tokio::spawn(async move{let mut bytes=[0;4096];let(size,peer)=socket.recv_from(&mut bytes).await.unwrap();let reply=dns_wire::answer(&bytes[..size],0,&["192.0.2.9".parse().unwrap()],60).unwrap();socket.send_to(&reply,peer).await.unwrap();});
   let e=engine(json!({"servers":[{"type":"udp","tag":"r","server":"127.0.0.1","server_port":port}],"final":"r","reverse_mapping":true}));let policy=OwnedPolicy(AtomicUsize::new(0));let packages=Vec::new();let cancel=CancellationToken::new();
   for(uid,id)in [(10001,1),(10002,99)]{let q=dns_wire::query(id,"owned.invalid",1).unwrap();let r=e.exchange(&policy,&q,uid,&flow(&packages),Instant::now()+std::time::Duration::from_secs(2),&cancel).await.unwrap();assert_eq!(&r[..2],&id.to_be_bytes());assert_eq!(e.domain(uid,"192.0.2.9".parse().unwrap()).unwrap(),Some("owned.invalid".into()));}
   server.await.unwrap();assert_eq!(policy.0.load(Ordering::SeqCst),1);
  }).await.unwrap()
 }
 #[tokio::test]async fn flow_lookup_preserves_protocol_explicit_transport_and_strategy_without_client_attribution(){
  let e=engine(json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":["192.0.2.1","2001:db8::1"]}}],"final":"h","reverse_mapping":true,"rules":[{"protocol":"tls","action":"predefined","rcode":"NXDOMAIN"}]}));let policy=OwnedPolicy(AtomicUsize::new(0));let packages=vec!["org.allowed".into()];let mut f=flow(&packages);f.protocol=Observed::Known(Some("tls"));let cancel=CancellationToken::new();let deadline=Instant::now()+std::time::Duration::from_secs(2);
  assert_eq!(e.lookup(&policy,"owned.invalid",&f,&json!({}),deadline,&cancel).await,Err(Failure::Resolve));
  let ips=e.lookup(&policy,"owned.invalid",&f,&json!({"server":"h","strategy":"prefer_ipv6","disable_optimistic_cache":true}),deadline,&cancel).await.unwrap();assert_eq!(ips,vec!["2001:db8::1".parse::<IpAddr>().unwrap(),"192.0.2.1".parse().unwrap()]);assert_eq!(e.domain(10001,ips[1]).unwrap(),None);
  let ips=e.lookup(&policy,"owned.invalid",&f,&json!({"server":"h","strategy":"ipv4_only"}),deadline,&cancel).await.unwrap();assert_eq!(ips,vec!["192.0.2.1".parse::<IpAddr>().unwrap()]);
  let query=dns_wire::query(1,"owned.invalid",1).unwrap();assert_eq!(e.resolve(&policy,&query,&f,deadline,&cancel).await.unwrap()[3]&15,0);assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn dual_family_lookup_runs_concurrently_and_keeps_success_when_other_family_fails(){
  tokio::time::timeout(std::time::Duration::from_secs(5),async{
   let socket=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let port=socket.local_addr().unwrap().port();let server=tokio::spawn(async move{
    let mut pending=Vec::new();let mut bytes=[0;4096];for _ in 0..2{let(size,peer)=socket.recv_from(&mut bytes).await.unwrap();pending.push((bytes[..size].to_vec(),peer));}
    // Neither exchange is answered until BOTH have arrived. A sequential
    // lookup would time out and cannot satisfy this owned-server test.
    for(query,peer)in pending{let kind=dns_wire::question(&query).unwrap().kind;let ips=if kind==1{vec!["192.0.2.9".parse().unwrap()]}else{Vec::new()};let answer=dns_wire::answer(&query,if kind==1{0}else{3},&ips,60).unwrap();socket.send_to(&answer,peer).await.unwrap();}
   });
   let e=engine(json!({"servers":[{"type":"udp","tag":"r","server":"127.0.0.1","server_port":port}],"final":"r"}));let policy=OwnedPolicy(AtomicUsize::new(0));let packages=vec!["org.allowed".into()];let ips=e.lookup(&policy,"owned.invalid",&flow(&packages),&json!({}),Instant::now()+std::time::Duration::from_secs(2),&CancellationToken::new()).await.unwrap();assert_eq!(ips,vec!["192.0.2.9".parse::<IpAddr>().unwrap()]);server.await.unwrap();assert_eq!(policy.0.load(Ordering::SeqCst),2);
  }).await.unwrap()
 }
 #[tokio::test]async fn flow_lookup_rejects_fake_unknown_and_unimplemented_options_without_network_fallback(){
  let e=engine(json!({"servers":[{"type":"fakeip","tag":"f","inet4_range":"198.18.0.0/15"},{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.1"}}],"final":"f"}));let policy=OwnedPolicy(AtomicUsize::new(0));let packages=Vec::new();let f=flow(&packages);let cancel=CancellationToken::new();let deadline=Instant::now()+std::time::Duration::from_secs(2);
  for options in [json!({}),json!({"server":"f"}),json!({"server":"missing"}),json!({"unused":true}),json!({"client_subnet":"192.0.2.0/24"})]{assert!(e.lookup(&policy,"owned.invalid",&f,&options,deadline,&cancel).await.is_err());}
  assert_eq!(e.lookup(&policy,"owned.invalid",&f,&json!({"server":"h","strategy":"ipv4_only"}),deadline,&cancel).await.unwrap(),vec!["192.0.2.1".parse::<IpAddr>().unwrap()]);cancel.cancel();assert_eq!(e.lookup(&policy,"owned.invalid",&f,&json!({"server":"h"}),deadline,&cancel).await,Err(Failure::Cancelled));assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[test]fn unsupported_options_are_rejected_before_runtime_not_silently_ignored(){
  for extra in [json!({"client_subnet":"192.0.2.0/24"}),json!({"optimistic":true}),json!({"timeout":"1m"}),json!({"rules":[{"action":"predefined","answer":["owned.invalid. 60 IN A 192.0.2.1"]}]})]{
   let mut doc=json!({"servers":[{"type":"hosts","tag":"h"}],"final":"h"});for(k,v)in extra.as_object().unwrap(){doc[k]=v.clone()};assert!(matches!(Engine::new(42,1,&doc,&json!({}),&json!({}),HashMap::new(),&[]),Err(Failure::Unsupported)));
  }
 }
}
