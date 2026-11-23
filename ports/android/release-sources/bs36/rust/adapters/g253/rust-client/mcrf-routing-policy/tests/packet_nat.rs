use mcrf_bs_routing_policy::packet_nat::{rewrite,Error};
mod support;use support::*;
#[test]fn in_place_failure_never_partially_changes_a_caller_packet(){
 for v6 in [false,true]{let mut p=packet(v6,false);let before=p.clone();assert!(mcrf_bs_routing_policy::packet_nat::rewrite_in_place(&mut p,"127.0.0.1".parse().unwrap(),"::1".parse().unwrap(),Some((1,2))).is_err());assert_eq!(p,before);
  let source=if v6{"fd00::53"}else{"198.19.255.53"}.parse().unwrap();let destination=if v6{"fd00::2"}else{"10.89.0.2"}.parse().unwrap();assert!(mcrf_bs_routing_policy::packet_nat::rewrite_in_place(&mut p,source,destination,Some((0,2))).is_err());assert_eq!(p,before);
  if v6{p[46..48].fill(0);let invalid=p.clone();assert!(mcrf_bs_routing_policy::packet_nat::rewrite_in_place(&mut p,source,destination,Some((1,2))).is_err());assert_eq!(p,invalid)}
 }
}
#[test]fn dual_stack_tcp_and_udp_roundtrip_preserves_payload_sequence_and_checksums(){
    for v6 in [false,true]{for tcp in [false,true]{
        let p=packet(v6,tcp);let before=p.clone();let at=if v6{40}else{20};
        let (source,destination)=if v6{("fd00::53","fd00::2")}else{("198.19.255.53","10.89.0.2")};
        let reflected=rewrite(&p,source.parse().unwrap(),destination.parse().unwrap(),Some((32768,48053))).unwrap();
        assert_eq!(checksum(&pseudo(&reflected,at)),0);if !v6{assert_eq!(checksum(&reflected[..20]),0)}
        if tcp{assert_eq!(&p[at+4..at+16],&reflected[at+4..at+16]);assert_eq!(&p[at+18..],&reflected[at+18..])}
        else{assert_eq!(&p[at+8..],&reflected[at+8..])}
        let (original_source,original_destination)=if v6{("fd00::2","fd00::53")}else{("10.89.0.2","198.19.255.53")};
        assert_eq!(rewrite(&reflected,original_source.parse().unwrap(),original_destination.parse().unwrap(),Some((41234,53))).unwrap(),p);
        assert_eq!(p,before);
    }}
}
#[test]fn ipv4_udp_zero_checksum_is_preserved_and_ipv6_zero_is_rejected(){
    for v6 in [false,true]{let mut p=packet(v6,false);let at=if v6{40}else{20};p[at+6..at+8].fill(0);
        let (source,destination)=if v6{("fd00::53","fd00::2")}else{("198.19.255.53","10.89.0.2")};
        let result=rewrite(&p,source.parse().unwrap(),destination.parse().unwrap(),Some((32768,48053)));
        if v6{assert_eq!(result,Err(Error::Transport))}else{let out=result.unwrap();assert_eq!(&out[26..28],&[0,0]);assert_eq!(checksum(&out[..20]),0)}
    }
}
#[test]fn fragment_delta_updates_first_transport_and_later_ip_header_only(){
    let mut full=packet(false,false);let payload:Vec<_>=(0..32).collect();full.truncate(28);full.extend_from_slice(&payload);let length=full.len()as u16;
    full[2..4].copy_from_slice(&length.to_be_bytes());full[24..26].copy_from_slice(&(length-20).to_be_bytes());full[26..28].fill(0);
    let c=checksum(&pseudo(&full,20));full[26..28].copy_from_slice(&c.to_be_bytes());
    let mut first=full[..44].to_vec();first[2..4].copy_from_slice(&44u16.to_be_bytes());first[6..8].copy_from_slice(&0x2000u16.to_be_bytes());
    let mut later=full[..20].to_vec();later.extend_from_slice(&full[44..]);let len=later.len()as u16;later[2..4].copy_from_slice(&len.to_be_bytes());later[6..8].copy_from_slice(&3u16.to_be_bytes());
    let a=rewrite(&first,"198.19.255.53".parse().unwrap(),"10.89.0.2".parse().unwrap(),Some((32768,48053))).unwrap();
    let b=rewrite(&later,"198.19.255.53".parse().unwrap(),"10.89.0.2".parse().unwrap(),None).unwrap();
    assert_eq!(checksum(&a[..20]),0);assert_eq!(checksum(&b[..20]),0);assert_eq!(&b[20..],&later[20..]);
    let mut combined=a[..20].to_vec();combined.extend_from_slice(&a[20..]);combined.extend_from_slice(&b[20..]);assert_eq!(checksum(&pseudo(&combined,20)),0);
    assert!(rewrite(&later,"198.19.255.53".parse().unwrap(),"10.89.0.2".parse().unwrap(),Some((1,2))).is_err());
}

