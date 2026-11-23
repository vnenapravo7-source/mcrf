use mcrf_bs_routing_policy::dns_wire::*;use std::net::IpAddr;
fn q(kind:u16,size:Option<u16>)->Vec<u8>{let mut q=query(9,"owned.invalid",kind).unwrap();if let Some(size)=size{q[11]=1;q.extend([0,0,41]);q.extend(size.to_be_bytes());q.extend([0;6]);}q}
#[test]fn small_answers_remain_exact_and_large_answers_truncate_at_rr_boundary(){
 let query=q(1,None);let reply=answer(&query,0,&["192.0.2.1".parse().unwrap()],60).unwrap();assert_eq!(udp_response(&query,&reply,1280,false).unwrap(),reply);
 let ips:Vec<IpAddr>=(1..=60).map(|n|format!("192.0.2.{n}").parse().unwrap()).collect();let reply=answer(&query,0,&ips,60).unwrap();let short=udp_response(&query,&reply,1280,false).unwrap();assert!(short.len()<=512);assert_eq!(short[2]&2,2);assert_eq!(&short[6..12],&[0;6]);assert_eq!(&short[12..],&query[12..]);assert!(addresses(&query,&short).is_err());
}
#[test]fn edns_and_ipv6_mtu_are_obeyed_without_oversized_or_partial_records(){
 let query=q(28,Some(1232));let ips:Vec<IpAddr>=(1..=60).map(|n|format!("2001:db8::{n:x}").parse().unwrap()).collect();let reply=answer(&query,0,&ips,60).unwrap();let short=udp_response(&query,&reply,1280,true).unwrap();assert!(short.len()<=1232);assert_eq!(short[2]&2,2);
 assert!(udp_response(&query,&reply,1200,true).is_err());let mut wrong=reply.clone();wrong[0]^=1;assert!(udp_response(&query,&wrong,1280,true).is_err());
 let small=answer(&query,0,&ips[..10],60).unwrap();assert_eq!(udp_response(&query,&small,1280,true).unwrap(),small);
}
