//! Executes an already selected DNS server inside the native BS process.
//! Bootstrap resolution follows ONLY the captured resolver graph; the OS
//! resolver is reachable solely through an explicitly selected Local server.
use std::{collections::HashMap,net::{IpAddr,SocketAddr},future::Future,pin::Pin};
use tokio::time::Instant;use tokio_util::sync::CancellationToken;
use crate::{mcrf_bs_dns_plan::{Plan,Server},mcrf_bs_dns_transport::{self as transport,SocketPolicy}};
use mcrf_bs_routing_policy::dns_wire;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Invalid,Unsupported,Reference,Depth,NoAddress,Transport,Wire,Cancelled,Deadline}
pub struct Resolver{plan:Plan,physical:u64,generation:u64,hosts:HashMap<String,Vec<IpAddr>>,fake:HashMap<String,std::sync::Mutex<mcrf_bs_routing_policy::fake_ip::names::Table>>}
impl Resolver{
 pub fn new(plan:Plan,physical:u64,generation:u64,hosts:HashMap<String,Vec<IpAddr>>,excluded:&[IpAddr])->Result<Self,Failure>{
  if physical==0||hosts.len()>65536{return Err(Failure::Invalid)}
  for(name,values)in &hosts{let q=dns_wire::query(0,name,1).map_err(|_|Failure::Invalid)?;if dns_wire::question(&q).map_err(|_|Failure::Invalid)?.name!=*name||values.len()>64{return Err(Failure::Invalid)}}
  let mut fake=HashMap::<String,std::sync::Mutex<mcrf_bs_routing_policy::fake_ip::names::Table>>::new();
  for(tag,server)in plan.servers(){if let Server::Fake{inet4,inet6}=server{
   let table=mcrf_bs_routing_policy::fake_ip::names::Table::new(generation,8192,inet4.as_deref(),inet6.as_deref(),excluded).map_err(|_|Failure::Invalid)?;
   for other in fake.values(){let other=other.lock().map_err(|_|Failure::Invalid)?;if table.overlaps(&other){return Err(Failure::Invalid)}}
   fake.insert(tag.to_owned(),std::sync::Mutex::new(table));
  }}
  if generation==0||generation>i64::MAX as u64{return Err(Failure::Invalid)}
  Ok(Self{plan,physical,generation,hosts,fake})
 }
 /// None means an ordinary real destination. Unknown addresses INSIDE a
 /// reserved range are errors and must never be sent as real upstream IPs.
 pub fn pin_fake(&self,id:u64,ip:IpAddr)->Result<Option<String>,Failure>{
  if dns_alias(ip){return Ok(None)}
  for table in self.fake.values(){let mut table=table.lock().map_err(|_|Failure::Invalid)?;if table.contains(ip){return table.pin(self.generation,id,ip).map(|name|Some(name.to_owned())).map_err(|_|Failure::NoAddress)}}Ok(None)
 }
 pub fn release_fake(&self,id:u64)->Result<(),Failure>{
  for table in self.fake.values(){table.lock().map_err(|_|Failure::Invalid)?.release(self.generation,id).map_err(|_|Failure::Invalid)?;}Ok(())
 }
 pub fn is_fake(&self,ip:IpAddr)->Result<bool,Failure>{if dns_alias(ip){return Ok(true)}for table in self.fake.values(){if table.lock().map_err(|_|Failure::Invalid)?.contains(ip){return Ok(true)}}Ok(false)}
 /// Proxy associations have their own accepted-socket lifetime. Names in this
 /// session's FakeIP table are immutable until session close, so reading one
 /// needs no invented TUN epoch or guessed owner. Unknown virtual IPs fail.
 pub fn proxy_name(&self,ip:IpAddr)->Result<Option<String>,Failure>{
  if dns_alias(ip){return Ok(None)}
  for table in self.fake.values(){let table=table.lock().map_err(|_|Failure::Invalid)?;if table.contains(ip){return table.lookup(self.generation,ip).map_err(|_|Failure::Invalid)?.map(|name|Some(name.to_owned())).ok_or(Failure::NoAddress)}}Ok(None)
 }
 pub fn server(&self,tag:&str)->Option<&Server>{self.plan.server(tag)}
 pub fn preferred(&self,name:&str)->Vec<String>{self.plan.servers().filter_map(|(tag,server)|match server{Server::Hosts{predefined,system}if predefined.contains_key(name)||*system&&self.hosts.contains_key(name)=>Some(tag.to_owned()),_=>None}).collect()}
 pub async fn exchange(&self,policy:&dyn SocketPolicy,tag:&str,query:&[u8],deadline:Instant,cancel:&CancellationToken)->Result<Vec<u8>,Failure>{
  dns_wire::question(query).map_err(|_|Failure::Invalid)?;
  self.selected(policy,tag,query,deadline,cancel,0).await
 }
 fn selected<'a>(&'a self,policy:&'a dyn SocketPolicy,tag:&'a str,query:&'a [u8],deadline:Instant,cancel:&'a CancellationToken,depth:usize)->Pin<Box<dyn Future<Output=Result<Vec<u8>,Failure>>+Send+'a>>{
  Box::pin(async move{
   if cancel.is_cancelled(){return Err(Failure::Cancelled)}if Instant::now()>=deadline{return Err(Failure::Deadline)}if depth>16{return Err(Failure::Depth)}
   let server=self.plan.server(tag).ok_or(Failure::Reference)?;
   match server{
    Server::Local=>crate::mcrf_bs_dns_system::exchange(self.physical,query,false,deadline,cancel).await.map_err(|error|match error{crate::mcrf_bs_dns_system::Failure::Unsupported=>Failure::Unsupported,crate::mcrf_bs_dns_system::Failure::Cancelled=>Failure::Cancelled,crate::mcrf_bs_dns_system::Failure::Deadline=>Failure::Deadline,_=>Failure::Transport}),
    Server::Hosts{predefined,system}=>{
     let q=dns_wire::question(query).map_err(|_|Failure::Wire)?;
     let values=predefined.get(&q.name).or_else(||if *system{self.hosts.get(&q.name)}else{None});
     // Match the selected primary Hosts transport: missing names and types
     // other than A/AAAA return NXDOMAIN, not an unrelated upstream fallback.
     let empty=Vec::new();let known=values.is_some()&&matches!(q.kind,1|28);
     dns_wire::answer(query,if known{0}else{3},if known{values.unwrap_or(&empty)}else{&empty},600).map_err(|_|Failure::Wire)
    },
    Server::Fake{..}=>{
     let q=dns_wire::question(query).map_err(|_|Failure::Wire)?;if !matches!(q.kind,1|28){return Err(Failure::Unsupported)}
     let mut table=self.fake.get(tag).ok_or(Failure::Reference)?.lock().map_err(|_|Failure::Invalid)?;
     let ips=if table.enabled(q.kind==28){vec![table.allocate(self.generation,&q.name,q.kind==28).map_err(|_|Failure::Invalid)?]}else{Vec::new()};
     dns_wire::answer(query,0,&ips,600).map_err(|_|Failure::Wire)
    },
    Server::Remote(remote)=>{
     let peers=if let Ok(ip)=remote.host.parse::<IpAddr>(){vec![ip]}else{
      let resolver=remote.resolver.as_deref().ok_or(Failure::Reference)?;let q=dns_wire::question(query).map_err(|_|Failure::Wire)?;
      let kinds:&[u16]=match remote.strategy.as_str(){"ipv4_only"=>&[1],"ipv6_only"=>&[28],"prefer_ipv6"=>&[28,1],""|"prefer_ipv4"=>&[1,28],_=>return Err(Failure::Unsupported)};
      let mut peers=Vec::new();let mut failed=None;
      for kind in kinds{
       let request=dns_wire::query(q.id.wrapping_add(depth as u16).wrapping_add(*kind),&remote.host,*kind).map_err(|_|Failure::Invalid)?;
       match self.selected(policy,resolver,&request,deadline,cancel,depth+1).await{
        Ok(reply)=>{for row in dns_wire::addresses(&request,&reply).map_err(|_|Failure::Wire)?{if !peers.contains(&row.ip){peers.push(row.ip)}}},
        Err(error @ (Failure::Cancelled|Failure::Deadline))=>return Err(error),Err(error)=>failed=Some(error),
       }
      }if peers.is_empty(){return Err(failed.unwrap_or(Failure::NoAddress))}peers
     };
     let mut failure=Failure::NoAddress;
     for peer in peers{
      match transport::exchange(policy,SocketAddr::new(peer,remote.port),remote.direction,&remote.transport,query,deadline,cancel).await{
       Ok(reply)=>return Ok(reply),Err(transport::Failure::Cancelled)=>return Err(Failure::Cancelled),Err(transport::Failure::Deadline)=>return Err(Failure::Deadline),Err(_)=>failure=Failure::Transport,
      }
     }Err(failure)
    },
   }
  })
 }
}
pub(crate) fn dns_alias(ip:IpAddr)->bool{match ip{IpAddr::V4(ip)=>ip.octets()==[198,19,255,53],IpAddr::V6(ip)=>ip.octets()==[0xfd,0xfe,0xdc,0xba,0x98,0x76,0xdd,0x54,0,0,0,0,0,0,0,0x53]}}
#[cfg(all(test,unix))]mod tests{
 use super::*;use serde_json::json;use std::sync::atomic::{AtomicUsize,Ordering};
 struct OwnedPolicy(AtomicUsize);
 impl SocketPolicy for OwnedPolicy{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,direction:transport::Direction)->Result<Box<dyn Send>,transport::Failure>{assert_eq!(direction,transport::Direction::Vpn);self.0.fetch_add(1,Ordering::SeqCst);Ok(Box::new(()))}}
 fn resolver(servers:serde_json::Value,hosts:HashMap<String,Vec<IpAddr>>)->Resolver{
  Resolver::new(Plan::parse(&json!({"servers":servers}),&json!({"chosen":"proxy"})).unwrap(),1,42,hosts,&[]).unwrap()
 }
 #[tokio::test]async fn private_hosts_bootstrap_selects_same_native_transport_without_os_fallback(){
  let socket=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();let port=socket.local_addr().unwrap().port();
  let server=tokio::spawn(async move{let mut bytes=[0;4096];let(size,peer)=socket.recv_from(&mut bytes).await.unwrap();let query=&bytes[..size];assert_eq!(dns_wire::question(query).unwrap().name,"owned.invalid");let reply=dns_wire::answer(query,0,&["192.0.2.44".parse().unwrap()],600).unwrap();socket.send_to(&reply,peer).await.unwrap();});
  let r=resolver(json!([{"type":"udp","tag":"remote","server":"dns.owned.invalid","server_port":port,"detour":"chosen","domain_resolver":{"server":"bootstrap","strategy":"ipv4_only"}},{"type":"hosts","tag":"bootstrap","predefined":{"dns.owned.invalid":"127.0.0.1"}}]),HashMap::new());
  let policy=OwnedPolicy(AtomicUsize::new(0));let query=dns_wire::query(7,"owned.invalid",1).unwrap();
  let reply=r.exchange(&policy,"remote",&query,Instant::now()+std::time::Duration::from_secs(3),&CancellationToken::new()).await.unwrap();server.await.unwrap();
  assert_eq!(dns_wire::addresses(&query,&reply).unwrap()[0].ip,"192.0.2.44".parse::<IpAddr>().unwrap());assert_eq!(policy.0.load(Ordering::SeqCst),1);
 }
 #[tokio::test]async fn hosts_overrides_system_and_missing_or_foreign_types_do_not_query_network(){
  let r=resolver(json!([{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.1"}}]),HashMap::from([("owned.invalid".into(),vec!["192.0.2.2".parse().unwrap()]),("system.invalid".into(),vec!["192.0.2.3".parse().unwrap()])]));
  let policy=OwnedPolicy(AtomicUsize::new(0));
  for(name,kind,rcode,expected)in [("owned.invalid",1,0,Some("192.0.2.1")),("system.invalid",1,0,Some("192.0.2.3")),("missing.invalid",1,3,None),("owned.invalid",65,3,None),("owned.invalid",28,0,None)]{
   let query=dns_wire::query(1,name,kind).unwrap();let reply=r.exchange(&policy,"h",&query,Instant::now()+std::time::Duration::from_secs(1),&CancellationToken::new()).await.unwrap();assert_eq!(reply[3]&15,rcode);
   let rows=dns_wire::addresses(&query,&reply).unwrap();assert_eq!(rows.first().map(|r|r.ip),expected.map(|s|s.parse::<IpAddr>().unwrap()));
  }assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn cancellation_missing_bootstrap_and_fake_queries_never_open_sockets(){
  let r=resolver(json!([{"type":"udp","tag":"r","server":"missing.invalid","domain_resolver":"h"},{"type":"hosts","tag":"h"},{"type":"fakeip","tag":"f","inet4_range":"198.18.0.0/15"}]),HashMap::new());
  let policy=OwnedPolicy(AtomicUsize::new(0));let query=dns_wire::query(1,"owned.invalid",1).unwrap();let cancel=CancellationToken::new();let deadline=Instant::now()+std::time::Duration::from_secs(1);
  assert_eq!(r.exchange(&policy,"r",&query,deadline,&cancel).await,Err(Failure::NoAddress));
  let reply=r.exchange(&policy,"f",&query,deadline,&cancel).await.unwrap();let rows=dns_wire::addresses(&query,&reply).unwrap();assert_eq!(rows.len(),1);assert_eq!(rows[0].ip,"198.18.0.1".parse::<IpAddr>().unwrap());
  assert_eq!(r.pin_fake(1,rows[0].ip).unwrap(),Some("owned.invalid".into()));r.release_fake(1).unwrap();
  assert_eq!(r.pin_fake(2,"198.18.0.99".parse().unwrap()),Err(Failure::NoAddress));assert_eq!(r.pin_fake(3,"192.0.2.1".parse().unwrap()).unwrap(),None);
  let query6=dns_wire::query(1,"owned.invalid",28).unwrap();let reply6=r.exchange(&policy,"f",&query6,deadline,&cancel).await.unwrap();assert!(dns_wire::addresses(&query6,&reply6).unwrap().is_empty());
  cancel.cancel();assert_eq!(r.exchange(&policy,"h",&query,deadline,&cancel).await,Err(Failure::Cancelled));assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[tokio::test]async fn dns_alias_inside_fake_ranges_is_reserved_but_not_a_fake_destination(){
  let r=resolver(json!([{"type":"fakeip","tag":"f","inet4_range":"198.18.0.0/15","inet6_range":"fdfe:dcba:9876::/48"}]),HashMap::new());
  for address in ["198.19.255.53","fdfe:dcba:9876:dd54::53"]{let ip=address.parse().unwrap();assert!(r.is_fake(ip).unwrap());assert_eq!(r.pin_fake(11,ip).unwrap(),None);assert_eq!(r.proxy_name(ip).unwrap(),None);}
  let policy=OwnedPolicy(AtomicUsize::new(0));let query=dns_wire::query(1,"owned.invalid",1).unwrap();let reply=r.exchange(&policy,"f",&query,Instant::now()+std::time::Duration::from_secs(1),&CancellationToken::new()).await.unwrap();let ip=dns_wire::addresses(&query,&reply).unwrap()[0].ip;
  assert_eq!(r.proxy_name(ip).unwrap(),Some("owned.invalid".into()));assert_eq!(r.proxy_name("198.18.0.99".parse().unwrap()),Err(Failure::NoAddress));assert_eq!(r.proxy_name("192.0.2.1".parse().unwrap()).unwrap(),None);
  let plain=resolver(json!([{"type":"hosts","tag":"h"}]),HashMap::new());assert!(plain.is_fake("198.19.255.53".parse().unwrap()).unwrap());assert_eq!(policy.0.load(Ordering::SeqCst),0);
 }
 #[test]fn overlapping_fake_servers_are_refused_before_any_socket_or_answer(){
  let plan=Plan::parse(&json!({"servers":[{"type":"fakeip","tag":"f1","inet4_range":"198.18.0.0/15"},{"type":"fakeip","tag":"f2","inet4_range":"198.19.0.0/24"}]}),&json!({})).unwrap();
  assert!(matches!(Resolver::new(plan,1,42,HashMap::new(),&[]),Err(Failure::Invalid)));
 }
}
