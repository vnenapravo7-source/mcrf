use std::{alloc::{GlobalAlloc,Layout,System},cell::Cell};
use mcrf_bs_routing_policy::{packet_nat,service_nat::Nat,service_flows::{Registry,Key}};
mod support;
thread_local!{static ALLOCATIONS:Cell<Option<usize>>=const{Cell::new(None)};}
struct Counted;
fn record(){ALLOCATIONS.with(|value|if let Some(count)=value.get(){value.set(Some(count+1))})}
unsafe impl GlobalAlloc for Counted{
 unsafe fn alloc(&self,layout:Layout)->*mut u8{record();unsafe{System.alloc(layout)}}
 unsafe fn alloc_zeroed(&self,layout:Layout)->*mut u8{record();unsafe{System.alloc_zeroed(layout)}}
 unsafe fn realloc(&self,ptr:*mut u8,layout:Layout,size:usize)->*mut u8{record();unsafe{System.realloc(ptr,layout,size)}}
 unsafe fn dealloc(&self,ptr:*mut u8,layout:Layout){unsafe{System.dealloc(ptr,layout)}}
}
#[global_allocator]static ALLOCATOR:Counted=Counted;
#[test]fn caller_owned_translation_and_warm_nat_have_no_per_packet_allocations(){
 for v6 in [false,true]{for tcp in [false,true]{let mut packet=support::packet(v6,tcp);let source=if v6{"2001:db8::7"}else{"192.0.2.7"}.parse().unwrap();let destination=if v6{"fd00::53"}else{"198.19.255.53"}.parse().unwrap();
  ALLOCATIONS.with(|v|v.set(Some(0)));let result=packet_nat::rewrite_in_place(&mut packet,source,destination,Some((50000,53)));let count=ALLOCATIONS.with(|v|v.replace(None).unwrap());assert!(result.is_ok());assert_eq!(count,0);
 }}
 let original=support::packet(false,false);let key=Key{protocol:17,local_port:41234,peer:"198.19.255.53:53".parse().unwrap()};let mut registry=Registry::scoped(42,10001).unwrap();registry.register_scoped(42,0,1,key).unwrap();let proof=registry.authorize(42,0,Some(10001),key).unwrap().unwrap();let mut nat=Nat::new(42,&["10.89.0.2".parse().unwrap()],&["192.0.2.7".parse().unwrap()],8).unwrap();let mut bytes=original.clone();nat.outbound(42,0,proof,&mut bytes,|p|registry.current(42,0,p).unwrap()).unwrap();bytes.copy_from_slice(&original);
 ALLOCATIONS.with(|v|v.set(Some(0)));let result=nat.outbound(42,1,proof,&mut bytes,|p|registry.current(42,1,p).unwrap());let count=ALLOCATIONS.with(|v|v.replace(None).unwrap());assert!(result.is_ok());assert_eq!(count,0);
 let info=mcrf_bs_routing_policy::packet::PacketInfo::read(&bytes).unwrap();let mut reply=packet_nat::rewrite(&bytes,key.peer.ip(),info.source,Some((key.peer.port(),info.ports.unwrap().0))).unwrap();
 ALLOCATIONS.with(|v|v.set(Some(0)));let result=nat.incoming(42,2,&mut reply,|p|registry.current(42,2,p).unwrap());let count=ALLOCATIONS.with(|v|v.replace(None).unwrap());assert_eq!(result.unwrap(),Some(proof));assert_eq!(count,0);
}
