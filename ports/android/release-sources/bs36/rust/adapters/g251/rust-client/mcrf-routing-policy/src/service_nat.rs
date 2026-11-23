//! Source/port translation for authenticated, scoped service sockets entering
//! the SAME BS carrier. This grants no route and cannot authorize an app tuple.
//! No payload copies or per-packet allocations; mappings follow exact leases.
use std::{collections::{HashMap,HashSet,BinaryHeap},cmp::Reverse,net::{IpAddr,SocketAddr}};
use crate::{packet::PacketInfo,packet_nat,service_flows::{Authorization,Key}};
pub(crate) mod sealed{pub trait Sealed{}}
/// A scoped service lease and an authenticated original app epoch are distinct
/// types. Neither can be manufactured from an arbitrary packet tuple.
pub trait Permit:Copy+Eq+std::hash::Hash+sealed::Sealed{
 fn generation(&self)->u64;fn key(&self)->Key;fn original_peer(&self)->SocketAddr;
 fn original_source(&self)->Option<IpAddr>{None}
}
impl sealed::Sealed for Authorization{}
impl Permit for Authorization{fn generation(&self)->u64{self.generation()}fn key(&self)->Key{self.key()}fn original_peer(&self)->SocketAddr{self.key().peer}}

#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid,Generation,Clock,Closed,Packet,Denied,Identity,Family,Capacity,Ports,Rewrite}
/// Keep parsed metadata tied to the mutable bytes it describes.
pub struct ReturnPacket<'a>{bytes:&'a mut [u8],info:PacketInfo}
impl<'a> ReturnPacket<'a>{
 pub fn parse(bytes:&'a mut [u8])->Result<Self,Error>{
  let info=PacketInfo::read(bytes).map_err(|_|Error::Packet)?;
  if info.fragment.is_some()||!matches!(info.protocol,6|17)||info.ports.is_none(){return Err(Error::Packet)}
  Ok(Self{bytes,info})
 }
 pub fn destination_port(&self)->u16{self.info.ports.expect("validated return ports").1}
}
#[derive(Clone,Copy)]struct Mapping<A>{proof:A,source:SocketAddr,wire:SocketAddr}
type ReverseKey=(u8,u16,SocketAddr);
pub struct Nat<A:Permit=Authorization>{
 generation:u64,last:u64,closed:bool,capacity:usize,locals:HashSet<IpAddr>,wire:Vec<IpAddr>,
 first:u16,last_port:u16,cursor:u16,mappings:HashMap<A,Mapping<A>>,reverse:HashMap<ReverseKey,A>,
 used:HashSet<(u8,u16)>,quarantine:BinaryHeap<Reverse<(u64,u8,u16)>>,
}
impl<A:Permit> Nat<A>{
 pub fn new(generation:u64,locals:&[IpAddr],wire:&[IpAddr],capacity:usize)->Result<Self,Error>{Self::with_ports(generation,locals,wire,capacity,1024,65535)}
 pub fn with_ports(generation:u64,locals:&[IpAddr],wire:&[IpAddr],capacity:usize,first:u16,last_port:u16)->Result<Self,Error>{
  if generation==0||generation>i64::MAX as u64||!(1..=8192).contains(&capacity)||locals.is_empty()||locals.len()>16||wire.is_empty()||wire.len()>2||first<1024||first>last_port||locals.iter().chain(wire).any(|v|v.is_unspecified()||v.is_loopback()||v.is_multicast()){return Err(Error::Invalid)}
  if wire.iter().filter(|v|v.is_ipv4()).count()>1||wire.iter().filter(|v|v.is_ipv6()).count()>1{return Err(Error::Invalid)}
  Ok(Self{generation,last:0,closed:false,capacity,locals:locals.iter().copied().collect(),wire:wire.to_vec(),first,last_port,cursor:first,mappings:HashMap::with_capacity(capacity),reverse:HashMap::with_capacity(capacity),used:HashSet::with_capacity(capacity),quarantine:BinaryHeap::new()})
 }
 fn check(&mut self,generation:u64,now:u64)->Result<(),Error>{
  if self.closed{return Err(Error::Closed)}if generation!=self.generation{return Err(Error::Generation)}if now<self.last{return Err(Error::Clock)}self.last=now;
  while let Some(Reverse((expires,protocol,port)))=self.quarantine.peek().copied(){if expires>now{break}self.quarantine.pop();self.used.remove(&(protocol,port));}Ok(())
 }
 fn port(&mut self,protocol:u8)->Result<u16,Error>{
  for _ in 0..=u32::from(self.last_port)-u32::from(self.first){let port=self.cursor;self.cursor=if port==self.last_port{self.first}else{port+1};if self.used.insert((protocol,port)){return Ok(port)}}Err(Error::Ports)
 }
 fn remove(&mut self,proof:&A,now:u64){
  if let Some(mapping)=self.mappings.remove(proof){let key=proof.key();self.reverse.remove(&(key.protocol,mapping.wire.port(),key.peer));
   // A delayed reply to a closed UDP socket must not inherit its old wire port.
   // Bounded quarantine also covers late TCP packets after reset/close.
   self.quarantine.push(Reverse((now.saturating_add(if key.protocol==6{120_000}else{30_000}),key.protocol,mapping.wire.port())));
  }
 }
 pub fn retire(&mut self,generation:u64,now:u64,proof:&A)->Result<(),Error>{self.check(generation,now)?;self.remove(proof,now);Ok(())}
 /// The caller obtained proof from a FRESH kernel owner check (or a pinned TCP
 /// epoch). `current` must query the owning service registry, never just a tuple.
 pub fn outbound(&mut self,generation:u64,now:u64,proof:A,bytes:&mut[u8],mut current:impl FnMut(&A)->bool)->Result<(),Error>{
  self.check(generation,now)?;if proof.generation()!=generation{return Err(Error::Generation)}
  if !current(&proof){self.remove(&proof,now);return Err(Error::Denied)}
  let info=PacketInfo::read(bytes).map_err(|_|Error::Packet)?;if info.fragment.is_some()||!matches!(info.protocol,6|17){return Err(Error::Packet)}
  let(a,b)=info.ports.ok_or(Error::Packet)?;let source=SocketAddr::new(info.source,a);let peer=SocketAddr::new(info.destination,b);let key=proof.key();
  if key.protocol!=info.protocol||key.local_port!=a||proof.original_peer()!=peer||proof.original_source().is_some_and(|ip|ip!=info.source)||!self.locals.contains(&info.source){return Err(Error::Identity)}
  let created=!self.mappings.contains_key(&proof);
  let mapping=if let Some(mapping)=self.mappings.get(&proof).copied(){if mapping.source!=source{return Err(Error::Identity)}mapping}else{
   // Retire only invalid leases, only on admission. Never scan all live sockets
   // for every packet or evict an active flow to make a new route succeed.
   let retired:Vec<_>=self.mappings.keys().copied().filter(|proof|!current(proof)).collect();for proof in retired{self.remove(&proof,now)}
   if self.mappings.len()>=self.capacity{return Err(Error::Capacity)}
   let address=*self.wire.iter().find(|ip|ip.is_ipv4()==source.is_ipv4()).ok_or(Error::Family)?;
   let port=self.port(key.protocol)?;let mapping=Mapping{proof,source,wire:SocketAddr::new(address,port)};
   self.reverse.insert((key.protocol,port,key.peer),proof);self.mappings.insert(proof,mapping);mapping
  };
  let result=packet_nat::rewrite_in_place(bytes,mapping.wire.ip(),key.peer.ip(),Some((mapping.wire.port(),key.peer.port()))).map_err(|_|Error::Rewrite);
  if result.is_err()&&created{self.mappings.remove(&proof);self.reverse.remove(&(key.protocol,mapping.wire.port(),key.peer));self.used.remove(&(key.protocol,mapping.wire.port()));}
  result
 }
 /// Only an exact still-live lease can translate a return packet. Unknown,
 /// retired and unsolicited peers are consumed by the caller, never leaked.
 pub fn incoming(&mut self,generation:u64,now:u64,bytes:&mut[u8],current:impl FnMut(&A)->bool)->Result<Option<A>,Error>{
  self.incoming_packet(generation,now,ReturnPacket::parse(bytes)?,current)
 }
 pub fn incoming_packet(&mut self,generation:u64,now:u64,packet:ReturnPacket<'_>,mut current:impl FnMut(&A)->bool)->Result<Option<A>,Error>{
  self.check(generation,now)?;
  let ReturnPacket{bytes,info}=packet;
  let(a,b)=info.ports.ok_or(Error::Packet)?;let peer=SocketAddr::new(info.source,a);
  let Some(proof)=self.reverse.get(&(info.protocol,b,peer)).copied()else{return Ok(None)};
  let mapping=*self.mappings.get(&proof).ok_or(Error::Identity)?;
  if mapping.wire.ip()!=info.destination{return Ok(None)}
  if !current(&mapping.proof){self.remove(&proof,now);return Ok(None)}
  packet_nat::rewrite_in_place(bytes,proof.original_peer().ip(),mapping.source.ip(),Some((proof.original_peer().port(),mapping.source.port()))).map_err(|_|Error::Rewrite)?;Ok(Some(proof))
 }
 pub fn close(&mut self){self.closed=true;self.mappings.clear();self.reverse.clear();self.used.clear();self.quarantine.clear()}
}

#[cfg(test)]mod tests{
 use super::*;use crate::service_flows::Registry;
 mod packets{include!("../tests/support/mod.rs");}
 #[test]fn ports_exhaust_without_reusing_quarantine_then_recover_at_deadline(){
  let original=packets::packet(false,false);let key=crate::service_flows::Key{protocol:17,local_port:41234,peer:"198.19.255.53:53".parse().unwrap()};
  let mut registry=Registry::scoped(42,10001).unwrap();let mut nat=Nat::with_ports(42,&["10.89.0.2".parse().unwrap()],&["192.0.2.7".parse().unwrap()],2,50000,50001).unwrap();
  for(id,now,expected)in [(1,0,50000),(2,1,50001)]{
   registry.register_scoped(42,now,id,key).unwrap();let proof=registry.authorize(42,now,Some(10001),key).unwrap().unwrap();let mut packet=original.clone();nat.outbound(42,now,proof,&mut packet,|p|registry.current(42,now,p).unwrap()).unwrap();assert_eq!(PacketInfo::read(&packet).unwrap().ports.unwrap().0,expected);nat.retire(42,now,&proof).unwrap();registry.unregister(42,now,id).unwrap();
  }
  registry.register_scoped(42,2,3,key).unwrap();let proof=registry.authorize(42,2,Some(10001),key).unwrap().unwrap();let mut packet=original.clone();assert_eq!(nat.outbound(42,2,proof,&mut packet,|p|registry.current(42,2,p).unwrap()),Err(Error::Ports));assert_eq!(packet,original);
  nat.outbound(42,30000,proof,&mut packet,|p|registry.current(42,30000,p).unwrap()).unwrap();assert_eq!(PacketInfo::read(&packet).unwrap().ports.unwrap().0,50000);
 }
}
