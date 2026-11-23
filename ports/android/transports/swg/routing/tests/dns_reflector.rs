use mcrf_bs_routing_policy::{dns_reflector::{Reflector,ALIAS4,ALIAS6,Error},packet::PacketInfo,packet_nat::rewrite};
mod support;use support::*;
fn reflector()->Reflector{Reflector::new(&["10.89.0.2".parse().unwrap(),"fd00::2".parse().unwrap()],Some("10.89.0.2".parse().unwrap()),Some("fd00::2".parse().unwrap()),48053).unwrap()}
fn request(v6:bool,tcp:bool,syn:u32)->Vec<u8>{
    let mut p=packet(v6,tcp);let at=if v6{40}else{20};
    if tcp{p[at+4..at+8].copy_from_slice(&syn.to_be_bytes());p[at+13]=2;p[at+16..at+18].fill(0);let c=checksum(&pseudo(&p,at));p[at+16..at+18].copy_from_slice(&c.to_be_bytes())}
    if v6{p=rewrite(&p,"fd00::2".parse().unwrap(),ALIAS6.parse().unwrap(),Some((41234,53))).unwrap()};p
}
#[test]fn policy_hijacked_public_resolvers_preserve_original_owner_tuple_and_reply_source(){
    for v6 in [false,true]{for tcp in [false,true]{let mut n=reflector();let p=request(v6,tcp,100);let original=PacketInfo::read(&p).unwrap();let resolver=if v6{"2001:db8::53"}else{"8.8.8.8"}.parse().unwrap();
        let p=rewrite(&p,original.source,resolver,Some((41234,53))).unwrap();assert!(n.reflect(0,&p).unwrap().is_none());
        let out=n.reflect_hijacked(1,&p).unwrap().unwrap().remove(0);let mapped=PacketInfo::read(&out).unwrap();let translated=mapped.ports.unwrap().0;assert_eq!(n.client(1,mapped.protocol,translated).unwrap().unwrap().destination,resolver);
        assert_eq!(mapped.source,if v6{ALIAS6}else{ALIAS4}.parse::<std::net::IpAddr>().unwrap());
        let reply=rewrite(&out,mapped.destination,mapped.source,Some((48053,translated))).unwrap();let delivered=n.reflect(2,&reply).unwrap().unwrap().remove(0);let expected=rewrite(&p,resolver,original.source,Some((53,41234))).unwrap();assert_eq!(delivered,expected);
        let spoof=rewrite(&reply,mapped.destination,if v6{"2001:db8::99"}else{"9.9.9.9"}.parse().unwrap(),Some((48053,translated))).unwrap();assert!(n.reflect(3,&spoof).unwrap().is_none());
        assert!(n.reflect_hijacked(4,&rewrite(&p,original.source,resolver,Some((41234,54))).unwrap()).unwrap().unwrap().is_empty());
    }}
}
#[test]fn dual_stack_dns_reflection_uses_local_listener_and_recovers_original_client(){
    for v6 in [false,true]{for tcp in [false,true]{let mut n=reflector();let p=request(v6,tcp,100);let original=PacketInfo::read(&p).unwrap();
        let outgoing=n.reflect(0,&p).unwrap().unwrap();assert_eq!(outgoing.len(),1);
        let reflected=PacketInfo::read(&outgoing[0]).unwrap();assert_eq!(reflected.source,original.destination);assert_eq!(reflected.destination,original.source);assert_eq!(reflected.ports.unwrap().1,48053);
        let translated=reflected.ports.unwrap().0;let mapped=n.client(0,original.protocol,translated).unwrap().unwrap();assert_eq!(mapped.source,original.source);assert_eq!(mapped.port,41234);assert_eq!(mapped.destination,original.destination);
        let reply=rewrite(&outgoing[0],reflected.destination,reflected.source,Some((48053,translated))).unwrap();
        let delivered=n.reflect(1,&reply).unwrap().unwrap();assert_eq!(delivered.len(),1);
        let expected=rewrite(&p,original.destination,original.source,Some((53,41234))).unwrap();assert_eq!(delivered[0],expected);
        let at=if v6{40}else{20};assert_eq!(checksum(&pseudo(&delivered[0],at)),0);assert_eq!(&delivered[0][at+if tcp{20}else{8}..],b"payload");
    }}
}
#[test]fn new_tcp_syn_retires_the_old_translated_tuple_and_retransmits_keep_the_same_lease(){
    let mut n=reflector();let first=request(false,true,100);
    let out=n.reflect(0,&first).unwrap().unwrap().remove(0);let info=PacketInfo::read(&out).unwrap();let port=info.ports.unwrap().0;
    assert_eq!(n.reflect(1,&first).unwrap().unwrap()[0],out);
    let next=n.reflect(2,&request(false,true,200)).unwrap().unwrap().remove(0);let next_port=PacketInfo::read(&next).unwrap().ports.unwrap().0;assert_ne!(port,next_port);
    assert!(n.client(2,6,port).unwrap().is_none());assert!(n.client(2,6,next_port).unwrap().is_some());
    let stale=rewrite(&out,info.destination,info.source,Some((48053,port))).unwrap();assert!(n.reflect(3,&stale).unwrap().unwrap().is_empty());
}
#[test]fn alias_packets_never_escape_on_invalid_source_fragment_expiry_clock_or_close(){
    let mut n=reflector();let p=request(false,false,0);
    let external=rewrite(&p,"192.0.2.42".parse().unwrap(),ALIAS4.parse().unwrap(),Some((41234,53))).unwrap();assert!(n.reflect(0,&external).unwrap().unwrap().is_empty());
    let normal=rewrite(&p,"10.89.0.2".parse().unwrap(),"192.0.2.42".parse().unwrap(),Some((41234,53))).unwrap();assert!(n.reflect(0,&normal).unwrap().is_none());
    let out=n.reflect(0,&p).unwrap().unwrap().remove(0);let port=PacketInfo::read(&out).unwrap().ports.unwrap().0;assert!(n.client(40000,17,port).unwrap().is_none());
    assert_eq!(n.reflect(39999,&p),Err(Error::Clock));n.close();assert_eq!(n.reflect(40000,&p),Err(Error::Closed));
}
#[test]fn out_of_order_udp_fragments_are_bounded_reflected_and_reassemble_with_valid_checksum(){
    let mut n=reflector();let mut full=request(false,false,0);full.truncate(28);full.extend(0u8..32);let length=full.len()as u16;
    full[2..4].copy_from_slice(&length.to_be_bytes());full[24..26].copy_from_slice(&(length-20).to_be_bytes());full[26..28].fill(0);let c=checksum(&pseudo(&full,20));full[26..28].copy_from_slice(&c.to_be_bytes());
    let mut first=full[..44].to_vec();first[2..4].copy_from_slice(&44u16.to_be_bytes());first[6..8].copy_from_slice(&0x2000u16.to_be_bytes());
    let mut later=full[..20].to_vec();later.extend_from_slice(&full[44..]);let length=later.len()as u16;later[2..4].copy_from_slice(&length.to_be_bytes());later[6..8].copy_from_slice(&3u16.to_be_bytes());
    assert!(n.reflect(0,&later).unwrap().unwrap().is_empty());let parts=n.reflect(1,&first).unwrap().unwrap();assert_eq!(parts.len(),2);
    let mut reassembled=parts[0][..20].to_vec();reassembled.extend_from_slice(&parts[0][20..]);reassembled.extend_from_slice(&parts[1][20..]);assert_eq!(checksum(&pseudo(&reassembled,20)),0);
    assert_eq!(&reassembled[28..],&full[28..]);
}

#[test]fn longest_admitted_dns_reply_keeps_mapping_for_both_owner_checks(){
 use mcrf_bs_routing_policy::dns_limits::*;
 assert!(RESPONSE>QUERY_MAX+OWNER_QUERY+OWNER_QUERY);
 assert!(UDP_MAPPING_MS>RESPONSE.as_millis()as u64);
 let mut n=reflector();let p=request(false,false,0);let out=n.reflect(0,&p).unwrap().unwrap().remove(0);let info=PacketInfo::read(&out).unwrap();let port=info.ports.unwrap().0;
 let now=RESPONSE.as_millis()as u64;assert!(n.client(now,17,port).unwrap().is_some());
 let reply=rewrite(&out,info.destination,info.source,Some((48053,port))).unwrap();assert_eq!(n.reflect(now,&reply).unwrap().unwrap().len(),1);
 assert!(n.client(now+UDP_MAPPING_MS,17,port).unwrap().is_none());
}
