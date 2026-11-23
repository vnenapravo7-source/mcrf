use mcrf_bs_routing_policy::{packet::{PacketInfo,PacketError},Observed};

fn transport(protocol:u8)->Vec<u8> {
    let mut body=vec![0;if protocol==6{24}else{16}];
    body[..2].copy_from_slice(&45678u16.to_be_bytes());body[2..4].copy_from_slice(&443u16.to_be_bytes());
    if protocol==6 {body[4..8].copy_from_slice(&0x12345678u32.to_be_bytes());body[12]=0x50;body[13]=0x18}
    if protocol==17 {let len=body.len() as u16;body[4..6].copy_from_slice(&len.to_be_bytes())}
    body
}
fn ipv4(protocol:u8,body:&[u8],fragment:u16)->Vec<u8> {
    let mut bytes=vec![0;20];bytes[0]=0x45;bytes[2..4].copy_from_slice(&((20+body.len()) as u16).to_be_bytes());
    bytes[4..6].copy_from_slice(&123u16.to_be_bytes());bytes[6..8].copy_from_slice(&fragment.to_be_bytes());bytes[8]=64;bytes[9]=protocol;
    bytes[12..16].copy_from_slice(&[198,18,0,1]);bytes[16..20].copy_from_slice(&[192,0,2,42]);bytes.extend_from_slice(body);bytes
}
fn ipv6(protocol:u8,body:&[u8])->Vec<u8> {
    let mut bytes=vec![0;40];bytes[0]=0x60;bytes[4..6].copy_from_slice(&(body.len() as u16).to_be_bytes());bytes[6]=protocol;bytes[7]=64;
    bytes[8..24].copy_from_slice(&"2001:db8::1".parse::<std::net::Ipv6Addr>().unwrap().octets());
    bytes[24..40].copy_from_slice(&"2001:db8::2".parse::<std::net::Ipv6Addr>().unwrap().octets());bytes.extend_from_slice(body);bytes
}
#[test]
fn reading_a_tun_tcp_packet_preserves_the_exact_original_packet_and_sequence() {
    let bytes=ipv4(6,&transport(6),0x4000);let original=bytes.clone();
    let p=PacketInfo::read(&bytes).ok().unwrap();let f=p.flow().unwrap();
    assert_eq!(f.source.to_string(),"198.18.0.1");assert_eq!(f.destination.to_string(),"192.0.2.42");
    assert_eq!((f.source_port,f.port,f.network),(45678,443,"tcp"));assert!(matches!(f.domain,Observed::Pending));
    assert_eq!(bytes,original);assert_eq!(&bytes[24..28],&0x12345678u32.to_be_bytes());
}
#[test]
fn ipv4_options_and_ipv6_extensions_do_not_shift_the_transport_identity() {
    let mut v4=ipv4(17,&transport(17),0);v4.splice(20..20,[1,1,1,1]);v4[0]=0x46;let len=v4.len() as u16;v4[2..4].copy_from_slice(&len.to_be_bytes());
    assert_eq!(PacketInfo::read(&v4).ok().unwrap().ports,Some((45678,443)));
    let mut body=vec![60,0,0,0,0,0,0,0];body.extend_from_slice(&[17,0,0,0,0,0,0,0]);body.extend_from_slice(&transport(17));
    let p=PacketInfo::read(&ipv6(0,&body)).ok().unwrap();assert_eq!(p.ports,Some((45678,443)));assert_eq!(p.protocol,17);
}
#[test]
fn later_ipv4_fragments_never_supply_fake_zero_ports_to_routing() {
    let mut first=transport(17);first[4..6].copy_from_slice(&32u16.to_be_bytes());
    let p=PacketInfo::read(&ipv4(17,&first,0x2000)).ok().unwrap();
    assert_eq!(p.ports,Some((45678,443)));assert_eq!(p.fragment.unwrap().id,123);assert!(p.flow().is_some());
    let p=PacketInfo::read(&ipv4(17,&[0xab;16],2)).ok().unwrap();
    assert!(p.ports.is_none()&&p.flow().is_none());assert_eq!(p.fragment.unwrap().offset_bytes,16);
}
#[test]
fn ipv6_fragments_retain_the_fragmentable_protocol_for_association() {
    let mut first=vec![60,0,0,1,0,0,0,9];first.extend_from_slice(&[17,0,0,0,0,0,0,0]);
    let mut udp=transport(17);udp[4..6].copy_from_slice(&32u16.to_be_bytes());first.extend_from_slice(&udp);
    let p=PacketInfo::read(&ipv6(44,&first)).ok().unwrap();
    assert_eq!(p.protocol,17);assert_eq!(p.fragment.unwrap().protocol,60);assert_eq!(p.ports,Some((45678,443)));
    let mut next=vec![60,0,0,24,0,0,0,9];next.extend_from_slice(&[0xab;8]);
    let p=PacketInfo::read(&ipv6(44,&next)).ok().unwrap();
    assert_eq!(p.fragment.unwrap().protocol,60);assert!(p.flow().is_none());
}
#[test]
fn malformed_length_fragment_transport_and_active_routing_headers_are_rejected() {
    let mut cases=Vec::new();
    let mut p=ipv4(6,&transport(6),0);p[0]=0x44;cases.push(p);
    let mut p=ipv4(6,&transport(6),0);p.push(0);cases.push(p);
    cases.push(ipv4(17,&[0;7],0));cases.push(ipv4(17,&transport(17),0x6000));
    let mut tcp=transport(6);tcp[12]=0xf0;cases.push(ipv4(6,&tcp,0));
    let mut udp=transport(17);udp[4..6].copy_from_slice(&8u16.to_be_bytes());cases.push(ipv4(17,&udp,0));
    let mut route=vec![17,0,0,1,0,0,0,0];route.extend_from_slice(&transport(17));cases.push(ipv6(43,&route));
    let mut fragment=vec![44,0,0,0,0,0,0,1];fragment.extend_from_slice(&[17,0,0,0,0,0,0,2]);fragment.extend_from_slice(&transport(17));cases.push(ipv6(44,&fragment));
    for p in cases {assert!(PacketInfo::read(&p).is_err())}
}
#[test]
fn truncated_packet_prefixes_and_excessive_extension_chains_do_not_panic() {
    for bytes in [ipv4(6,&transport(6),0),ipv6(17,&transport(17))] {
        for end in 0..bytes.len() {assert!(PacketInfo::read(&bytes[..end]).is_err())}
    }
    let mut body=Vec::new();for _ in 0..9{body.extend_from_slice(&[60,0,0,0,0,0,0,0])}body.extend_from_slice(&transport(17));
    assert!(matches!(PacketInfo::read(&ipv6(60,&body)),Err(PacketError::ExtensionLimit)));
}

#[test]fn udp_payload_extraction_uses_validated_header_lengths_and_rejects_fragments(){
 let body=transport(17);let mut v4=ipv4(17,&body,0);v4.splice(20..20,[1,1,1,1]);v4[0]=0x46;let size=v4.len()as u16;v4[2..4].copy_from_slice(&size.to_be_bytes());assert_eq!(PacketInfo::udp_payload(&v4).unwrap(),&body[8..]);
 let mut ext=vec![60,0,0,0,0,0,0,0];ext.extend_from_slice(&[17,0,0,0,0,0,0,0]);ext.extend_from_slice(&body);assert_eq!(PacketInfo::udp_payload(&ipv6(0,&ext)).unwrap(),&body[8..]);
 let mut first=body.clone();first[4..6].copy_from_slice(&32u16.to_be_bytes());assert!(PacketInfo::udp_payload(&ipv4(17,&first,0x2000)).is_err());assert!(PacketInfo::udp_payload(&ipv4(6,&transport(6),0)).is_err());for size in 0..v4.len(){assert!(PacketInfo::udp_payload(&v4[..size]).is_err())}
}
