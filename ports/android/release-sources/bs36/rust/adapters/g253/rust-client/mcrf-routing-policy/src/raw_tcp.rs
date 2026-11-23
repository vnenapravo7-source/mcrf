//! Original packet permission, separate from service-socket leases. Header NAT
//! may change a resolved/FakeIP destination, never TCP sequence or payload.
use std::net::SocketAddr;
use crate::{associations::Tracker,packet::PacketInfo,service_flows::Key,service_nat::{Permit,sealed}};
#[derive(Clone,Copy,Debug,Hash,PartialEq,Eq)]
pub struct Authorization{generation:u64,epoch:u64,uid:u32,source:SocketAddr,original:SocketAddr,selected:SocketAddr}
#[derive(Debug,PartialEq,Eq)]pub enum Error{Packet,Identity,State,Peer}
impl sealed::Sealed for Authorization{}
impl Permit for Authorization{
 fn generation(&self)->u64{self.generation}
 fn key(&self)->Key{Key{protocol:6,local_port:self.source.port(),peer:self.selected}}
 fn original_peer(&self)->SocketAddr{self.original}
 fn original_source(&self)->Option<std::net::IpAddr>{Some(self.source.ip())}
}
impl Authorization{
 /// Call only after the policy preflight selected the raw tunnel AND the owner
 /// broker confirmed this initial SYN. A tuple or UID alone cannot mint proof.
 pub fn admit(tracker:&Tracker,generation:u64,now:u64,packet:&PacketInfo,epoch:u64,uid:u32,selected:SocketAddr)->Result<Self,Error>{
  if packet.protocol!=6||packet.fragment.is_some()||!packet.tcp.is_some_and(|tcp|tcp.flags&0x17==2){return Err(Error::Packet)}
  let(a,b)=packet.ports.ok_or(Error::Packet)?;let source=SocketAddr::new(packet.source,a);let original=SocketAddr::new(packet.destination,b);
  if selected.port()==0||selected.ip().is_unspecified()||selected.ip().is_loopback()||selected.ip().is_multicast()||selected.is_ipv4()!=source.is_ipv4(){return Err(Error::Peer)}
  let proof=Self{generation,epoch,uid,source,original,selected};if !proof.current(tracker,now)?{return Err(Error::Identity)}Ok(proof)
 }
 pub fn epoch(&self)->u64{self.epoch}
 pub fn tuple(&self)->(SocketAddr,SocketAddr){(self.source,self.original)}
 pub fn uid(&self)->u32{self.uid}
 pub fn current(&self,tracker:&Tracker,now:u64)->Result<bool,Error>{
  if tracker.tcp_epoch(self.generation,now,self.source,self.original).map_err(|_|Error::State)?!=Some(self.epoch){return Ok(false)}
  let packet=PacketInfo{source:self.source.ip(),destination:self.original.ip(),ports:Some((self.source.port(),self.original.port())),protocol:6,fragment:None,tcp:None};
  Ok(tracker.owner(&packet,self.epoch).is_some_and(|(uid,_)|uid==self.uid))
 }
}
