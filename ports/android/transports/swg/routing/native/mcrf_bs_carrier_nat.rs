//! Session-owned translation between captured local addresses and the addresses
//! assigned by this BS carrier. No socket, fallback or second engine is opened.
use std::{sync::{Arc,Mutex},net::IpAddr};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{service_nat::{Nat,ReturnPacket},service_flows::Authorization};
use mcrf_bs_routing_policy::{raw_tcp::Authorization as RawAuthorization,associations::Tracker};
use crate::mcrf_bs_stream_slots::Clock;
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Invalid,Cancelled,State,Lease,Translation(mcrf_bs_routing_policy::service_nat::Error)}
struct State{service:Nat,raw:Nat<RawAuthorization>}
#[derive(Debug,PartialEq,Eq)]pub enum Incoming{Service(Authorization),Raw(RawAuthorization)}
pub struct CarrierNat{generation:u64,clock:Clock,state:Mutex<State>,tracker:Arc<Mutex<Tracker>>,cancel:CancellationToken}
const FIRST_SERVICE_PORT:u16=1024;
const FIRST_RAW_PORT:u16=32768;
impl CarrierNat{
 pub fn new(generation:u64,clock:Clock,tracker:Arc<Mutex<Tracker>>,locals:&[IpAddr],assigned:&[IpAddr],capacity:usize,cancel:CancellationToken)->Result<Self,Failure>{
  if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  // Distinct port namespaces prevent a native socket and an original app flow
  // to the same peer from receiving each other's delayed reply.
  let service=Nat::with_ports(generation,locals,assigned,capacity,FIRST_SERVICE_PORT,FIRST_RAW_PORT-1).map_err(|_|Failure::Invalid)?;
  let raw=Nat::with_ports(generation,locals,assigned,capacity,FIRST_RAW_PORT,u16::MAX).map_err(|_|Failure::Invalid)?;
  Ok(Self{generation,clock,state:Mutex::new(State{service,raw}),tracker,cancel})
 }
 fn current(&self,proof:&Authorization,failed:&mut bool)->bool{match crate::mcrf_bs_service::current(proof){Ok(value)=>value,Err(_)=>{*failed=true;self.cancel.cancel();false}}}
 pub fn outbound(&self,proof:Authorization,packet:&mut[u8])->Result<(),Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let mut state=self.state.lock().map_err(|_|{self.cancel.cancel();Failure::State})?;let now=(self.clock)().map_err(|_|{self.cancel.cancel();Failure::State})?;let mut failed=false;
  let result=state.service.outbound(self.generation,now,proof,packet,|p|self.current(p,&mut failed));
  if failed{return Err(Failure::Lease)}if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}result.map_err(Failure::Translation)
 }
 pub fn outbound_raw(&self,proof:RawAuthorization,packet:&mut[u8])->Result<(),Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let info=mcrf_bs_routing_policy::packet::PacketInfo::read(packet)
   .map_err(|_|Failure::Translation(mcrf_bs_routing_policy::service_nat::Error::Denied))?;
  let mut state=self.state.lock().map_err(|_|{self.cancel.cancel();Failure::State})?;
  let mut tracker=self.tracker.lock().map_err(|_|{self.cancel.cancel();Failure::State})?;
  let now=(self.clock)().map_err(|_|{self.cancel.cancel();Failure::State})?;
  if !proof.current(&tracker,now).map_err(|_|Failure::Lease)?{
   return Err(Failure::Translation(mcrf_bs_routing_policy::service_nat::Error::Denied))
  }
  let observed=tracker.observe(self.generation,now,&info).map_err(|_|Failure::State)?;
  if observed.id!=proof.epoch(){return Err(Failure::Lease)}
  let mut failed=false;
  let result=state.raw.outbound(self.generation,now,proof,packet,|p|match p.current(&tracker,now){Ok(value)=>value,Err(_)=>{failed=true;false}});
  if failed{self.cancel.cancel();return Err(Failure::Lease)}result.map_err(Failure::Translation)
 }
 pub fn incoming(&self,packet:&mut[u8])->Result<Option<Incoming>,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let packet=ReturnPacket::parse(packet).map_err(Failure::Translation)?;
  let port=packet.destination_port();
  if port<FIRST_SERVICE_PORT{return Ok(None)}
  let mut state=self.state.lock().map_err(|_|{self.cancel.cancel();Failure::State})?;let now=(self.clock)().map_err(|_|{self.cancel.cancel();Failure::State})?;let mut failed=false;
  let result=if port<FIRST_RAW_PORT{
   state.service.incoming_packet(self.generation,now,packet,|p|self.current(p,&mut failed)).map(|p|p.map(Incoming::Service))
  }else{
   let tracker=self.tracker.lock().map_err(|_|{self.cancel.cancel();Failure::State})?;
   state.raw.incoming_packet(self.generation,now,packet,|p|match p.current(&tracker,now){Ok(value)=>value,Err(_)=>{failed=true;false}}).map(|p|p.map(Incoming::Raw))
  };
  if failed{self.cancel.cancel();return Err(Failure::Lease)}
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}result.map_err(Failure::Translation)
 }
 pub fn stop(&self){self.cancel.cancel();if let Ok(mut state)=self.state.lock(){state.service.close();state.raw.close()}}
}

#[cfg(test)]mod tests{
 use super::*;use mcrf_bs_routing_policy::{packet::PacketInfo,packet_nat};
 mod packets{include!("mcrf-routing-policy/tests/support/mod.rs");}
 fn setup()->(CarrierNat,Arc<Mutex<Tracker>>,RawAuthorization,Vec<u8>){
  let mut bytes=packets::packet(false,true);bytes[33]=2;bytes[36..38].fill(0);let checksum=packets::checksum(&packets::pseudo(&bytes,20));bytes[36..38].copy_from_slice(&checksum.to_be_bytes());
  let info=PacketInfo::read(&bytes).unwrap();let mut tracker=Tracker::new(42,4).unwrap();let id=tracker.observe(42,1,&info).unwrap().id;tracker.confirm_owner(42,1,&info,id,10001,vec![]).unwrap();let proof=RawAuthorization::admit(&tracker,42,1,&info,id,10001,"192.0.2.99:8443".parse().unwrap()).unwrap();let tracker=Arc::new(Mutex::new(tracker));
  let carrier=CarrierNat::new(42,Arc::new(||Ok(1)),tracker.clone(),&[info.source],&["192.0.2.7".parse().unwrap()],4,CancellationToken::new()).unwrap();(carrier,tracker,proof,bytes)
 }
 #[test]fn original_app_packets_use_separate_namespace_and_exact_reverse_nat_without_tcp_proxy(){
  let(carrier,_,proof,mut bytes)=setup();let sequence=bytes[24..36].to_vec();carrier.outbound_raw(proof,&mut bytes).unwrap();let info=PacketInfo::read(&bytes).unwrap();assert_eq!(info.ports,Some((32768,8443)));assert_eq!(&bytes[24..36],sequence);
  packet_nat::rewrite_in_place(&mut bytes,info.destination,info.source,Some((8443,32768))).unwrap();assert_eq!(carrier.incoming(&mut bytes),Ok(Some(Incoming::Raw(proof))));let reflected=PacketInfo::read(&bytes).unwrap();assert_eq!(reflected.source,"198.19.255.53".parse::<IpAddr>().unwrap());assert_eq!(reflected.destination,"10.89.0.2".parse::<IpAddr>().unwrap());assert_eq!(reflected.ports,Some((53,41234)));assert_eq!(&bytes[24..36],sequence);assert_eq!(packets::checksum(&packets::pseudo(&bytes,20)),0);
 }
 #[test]fn retired_app_epoch_and_stop_cannot_emit_original_packet(){
  let(carrier,tracker,proof,original)=setup();tracker.lock().unwrap().retire(42,1,proof.epoch()).unwrap();let mut bytes=original.clone();assert_eq!(carrier.outbound_raw(proof,&mut bytes),Err(Failure::Translation(mcrf_bs_routing_policy::service_nat::Error::Denied)));assert_eq!(bytes,original);carrier.stop();assert_eq!(carrier.outbound_raw(proof,&mut bytes),Err(Failure::Cancelled));
 }
 #[test]fn return_port_cannot_cross_nat_namespaces(){
  let(carrier,_,proof,mut bytes)=setup();carrier.outbound_raw(proof,&mut bytes).unwrap();
  let info=PacketInfo::read(&bytes).unwrap();
  for port in [1023,1024,FIRST_RAW_PORT-1]{
   let mut wrong=bytes.clone();
   packet_nat::rewrite_in_place(&mut wrong,info.destination,info.source,Some((8443,port))).unwrap();
   assert_eq!(carrier.incoming(&mut wrong),Ok(None));
  }
 }
}
