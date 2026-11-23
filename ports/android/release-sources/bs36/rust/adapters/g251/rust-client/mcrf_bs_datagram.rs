//! A UDP payload may retain its original pooled IP buffer. Inspection and
//! forwarding borrow the payload; they never copy it into another Vec.
use std::{fmt,ops::Deref,net::SocketAddr};
enum Storage{Owned(Vec<u8>),Budgeted(Vec<u8>,tokio::sync::OwnedSemaphorePermit),Pooled(crate::packet::PacketBuf)}
pub struct Datagram{storage:Storage,offset:usize,length:usize}
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Packet,Bounds}
impl Datagram{
 pub fn from_ip(buffer:crate::packet::PacketBuf)->Result<(SocketAddr,SocketAddr,Self),Failure>{
  let bytes=buffer.as_slice();let info=mcrf_bs_routing_policy::packet::PacketInfo::read(bytes).map_err(|_|Failure::Packet)?;
  let payload=mcrf_bs_routing_policy::packet::PacketInfo::udp_payload(bytes).map_err(|_|Failure::Packet)?;
  let offset=(payload.as_ptr()as usize).checked_sub(bytes.as_ptr()as usize).ok_or(Failure::Bounds)?;let length=payload.len();
  if offset.checked_add(length).is_none_or(|end|end>bytes.len()){return Err(Failure::Bounds)}
  let(a,b)=info.ports.ok_or(Failure::Packet)?;Ok((SocketAddr::new(info.source,a),SocketAddr::new(info.destination,b),Self{storage:Storage::Pooled(buffer),offset,length}))
 }
 pub fn budgeted(bytes:Vec<u8>,permit:tokio::sync::OwnedSemaphorePermit)->Result<Self,Failure>{if bytes.len()>65535||permit.num_permits()<bytes.len().max(1){return Err(Failure::Bounds)}let length=bytes.len();Ok(Self{storage:Storage::Budgeted(bytes,permit),offset:0,length})}
 pub fn as_slice(&self)->&[u8]{let bytes=match &self.storage{Storage::Owned(bytes)|Storage::Budgeted(bytes,_)=>bytes.as_slice(),Storage::Pooled(buffer)=>buffer.as_slice()};&bytes[self.offset..self.offset+self.length]}
}
impl From<Vec<u8>> for Datagram{fn from(bytes:Vec<u8>)->Self{let length=bytes.len();Self{storage:Storage::Owned(bytes),offset:0,length}}}
impl Deref for Datagram{type Target=[u8];fn deref(&self)->&[u8]{self.as_slice()}}
impl AsRef<[u8]> for Datagram{fn as_ref(&self)->&[u8]{self.as_slice()}}
impl fmt::Debug for Datagram{fn fmt(&self,f:&mut fmt::Formatter<'_>)->fmt::Result{f.debug_struct("Datagram").field("bytes",&self.length).finish_non_exhaustive()}}
impl PartialEq for Datagram{fn eq(&self,other:&Self)->bool{self.as_slice()==other.as_slice()}}
impl Eq for Datagram{}
impl PartialEq<Vec<u8>> for Datagram{fn eq(&self,other:&Vec<u8>)->bool{self.as_slice()==other.as_slice()}}

#[cfg(test)]mod tests{
 use super::*;
 #[test]fn pooled_ipv4_ipv6_zero_and_payload_keep_storage_until_last_owner_drops(){
  for v6 in [false,true]{for payload in [&[][..],b"owned-payload".as_slice()]{
   let pool=crate::packet::PacketPool::new(1);let mut buffer=pool.try_acquire().unwrap();let size=if v6{48}else{28};let bytes=buffer.extend_tail(size+payload.len()).unwrap();let source:SocketAddr=if v6{"[fd00::2]:45000"}else{"10.89.0.2:45000"}.parse().unwrap();let destination:SocketAddr=if v6{"[2001:db8::9]:5353"}else{"192.0.2.9:5353"}.parse().unwrap();assert!(crate::direct_relay::craft_udp_packet_into(source,destination,payload,bytes));let pointer=buffer.as_slice().as_ptr()as usize+size;
   let(a,b,packet)=Datagram::from_ip(buffer).unwrap();assert_eq!(a,source);assert_eq!(b,destination);assert_eq!(packet.as_slice(),payload);assert_eq!(packet.as_slice().as_ptr()as usize,pointer);assert!(pool.try_acquire().is_none());drop(packet);assert!(pool.try_acquire().is_some());
  }}
 }
 #[test]fn invalid_frame_returns_the_original_pool_buffer(){let pool=crate::packet::PacketPool::new(1);let mut buffer=pool.try_acquire().unwrap();buffer.extend_tail(2).unwrap().fill(0);assert!(Datagram::from_ip(buffer).is_err());assert!(pool.try_acquire().is_some())}
}
