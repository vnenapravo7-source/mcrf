use mcrf_bs_routing_policy::{associations::Tracker,packet::PacketInfo,packet_nat,raw_tcp::{Authorization,Error},service_nat::Nat};
mod support;
fn syn(v6:bool)->Vec<u8>{let mut bytes=support::packet(v6,true);let at=if v6{40}else{20};bytes[at+13]=2;bytes[at+16..at+18].fill(0);let checksum=support::checksum(&support::pseudo(&bytes,at));bytes[at+16..at+18].copy_from_slice(&checksum.to_be_bytes());bytes}
#[test]fn raw_nat_preserves_tcp_sequence_ack_flags_options_window_and_payload_both_families(){
 for v6 in [false,true]{
  let bytes=syn(v6);let info=PacketInfo::read(&bytes).unwrap();let mut tracker=Tracker::new(42,4).unwrap();let id=tracker.observe(42,0,&info).unwrap().id;tracker.confirm_owner(42,0,&info,id,10001,vec![]).unwrap();
  let peer=if v6{"[2001:db8::99]:8443"}else{"192.0.2.99:8443"}.parse().unwrap();let proof=Authorization::admit(&tracker,42,0,&info,id,10001,peer).unwrap();
  let wire=if v6{"2001:db8::7"}else{"192.0.2.7"}.parse().unwrap();let mut nat:Nat<Authorization>=Nat::with_ports(42,&[info.source],&[wire],4,32768,65535).unwrap();let mut outgoing=bytes.clone();
  nat.outbound(42,0,proof,&mut outgoing,|p|p.current(&tracker,0).unwrap()).unwrap();let projected=PacketInfo::read(&outgoing).unwrap();assert_eq!(projected.source,wire);assert_eq!(projected.destination,peer.ip());assert_eq!(projected.ports,Some((32768,8443)));
  let at=if v6{40}else{20};assert_eq!(&bytes[at+4..at+16],&outgoing[at+4..at+16]);assert_eq!(&bytes[at+18..],&outgoing[at+18..]);assert_eq!(support::checksum(&support::pseudo(&outgoing,at)),0);
  packet_nat::rewrite_in_place(&mut outgoing,peer.ip(),wire,Some((peer.port(),32768))).unwrap();assert_eq!(nat.incoming(42,1,&mut outgoing,|p|p.current(&tracker,1).unwrap()).unwrap(),Some(proof));let reverse=PacketInfo::read(&outgoing).unwrap();assert_eq!((reverse.source,reverse.destination),(info.destination,info.source));assert_eq!(reverse.ports,Some((53,41234)));assert_eq!(&bytes[at+4..at+16],&outgoing[at+4..at+16]);assert_eq!(&bytes[at+18..],&outgoing[at+18..]);assert_eq!(support::checksum(&support::pseudo(&outgoing,at)),0);
 }
}
#[test]fn raw_permission_needs_current_authenticated_syn_and_revokes_on_tuple_or_owner_change(){
 let bytes=syn(false);let mut info=PacketInfo::read(&bytes).unwrap();let mut tracker=Tracker::new(42,4).unwrap();let id=tracker.observe(42,0,&info).unwrap().id;let peer="192.0.2.99:443".parse().unwrap();
 assert_eq!(Authorization::admit(&tracker,42,0,&info,id,10001,peer),Err(Error::Identity));tracker.confirm_owner(42,0,&info,id,10001,vec![]).unwrap();assert_eq!(Authorization::admit(&tracker,42,0,&info,id,10002,peer),Err(Error::Identity));
 let proof=Authorization::admit(&tracker,42,0,&info,id,10001,peer).unwrap();let changed=tracker.confirm_owner(42,1,&info,id,10002,vec![]).unwrap();assert_ne!(changed.id,id);assert!(!proof.current(&tracker,1).unwrap());
 info.tcp.as_mut().unwrap().flags=16;assert_eq!(Authorization::admit(&tracker,42,1,&info,changed.id,10002,peer),Err(Error::Packet));tracker.close();assert!(proof.current(&tracker,2).is_err());
}
#[test]fn retired_raw_packet_is_not_mutated_or_delivered_to_a_new_epoch(){
 let original=syn(false);let info=PacketInfo::read(&original).unwrap();let mut tracker=Tracker::new(42,4).unwrap();let id=tracker.observe(42,0,&info).unwrap().id;tracker.confirm_owner(42,0,&info,id,10001,vec![]).unwrap();let peer="192.0.2.99:443".parse().unwrap();let proof=Authorization::admit(&tracker,42,0,&info,id,10001,peer).unwrap();
 let mut nat:Nat<Authorization>=Nat::with_ports(42,&[info.source],&["192.0.2.7".parse().unwrap()],4,32768,65535).unwrap();tracker.retire(42,1,id).unwrap();let mut packet=original.clone();assert_eq!(nat.outbound(42,1,proof,&mut packet,|p|p.current(&tracker,1).unwrap()),Err(mcrf_bs_routing_policy::service_nat::Error::Denied));assert_eq!(packet,original);
}
#[test]fn a_raw_proof_does_not_authorize_another_local_source_address(){
 let original=syn(false);let info=PacketInfo::read(&original).unwrap();let mut tracker=Tracker::new(42,4).unwrap();let id=tracker.observe(42,0,&info).unwrap().id;tracker.confirm_owner(42,0,&info,id,10001,vec![]).unwrap();let peer="192.0.2.99:443".parse().unwrap();let proof=Authorization::admit(&tracker,42,0,&info,id,10001,peer).unwrap();let other="10.89.0.3".parse().unwrap();
 let mut nat:Nat<Authorization>=Nat::with_ports(42,&[info.source,other],&["192.0.2.7".parse().unwrap()],4,32768,65535).unwrap();let mut bytes=original;packet_nat::rewrite_in_place(&mut bytes,other,info.destination,info.ports).unwrap();let before=bytes.clone();assert_eq!(nat.outbound(42,0,proof,&mut bytes,|p|p.current(&tracker,0).unwrap()),Err(mcrf_bs_routing_policy::service_nat::Error::Identity));assert_eq!(bytes,before);
}
