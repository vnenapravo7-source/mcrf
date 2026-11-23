use mcrf_bs_routing_policy::dns_wire::*;
use std::net::IpAddr;
fn ip(s:&str)->IpAddr{s.parse().unwrap()}
#[test]fn hosts_answers_keep_request_identity_and_only_requested_address_family(){
 for kind in [1,28]{let q=query(123,"Owned.invalid.",kind).unwrap();let r=answer(&q,0,&[ip("192.0.2.1"),ip("2001:db8::1")],600).unwrap();
  let rows=addresses(&q,&r).unwrap();assert_eq!(rows.len(),1);assert_eq!(rows[0].ip.is_ipv4(),kind==1);assert_eq!(rows[0].domain,"owned.invalid");assert_eq!(rows[0].ttl_ms,600000);
 }
}
#[test]fn block_empty_and_other_query_types_are_valid_replies(){
 for kind in [1,28,65,255]{for rcode in [0,3,5]{let q=query(9,"owned.invalid",kind).unwrap();let r=answer(&q,rcode,&[],600).unwrap();assert!(addresses(&q,&r).unwrap().is_empty());assert_eq!(r[3]&15,rcode)}}
 assert!(query(1,"",2).is_ok());assert!(query(1,"a..invalid",1).is_err());assert!(query(1,"a.invalid",0).is_err());
}
#[test]fn ttl_rewrite_drops_ad_preserves_opt_flags_and_zero_is_not_promoted(){
 let q=query(9,"owned.invalid",1).unwrap();let mut r=answer(&q,0,&[ip("192.0.2.1")],600).unwrap();r[3]|=0x20;r[11]=1;
 // Root OPT, UDP payload 1232, DO bit, no options.
 r.extend([0,0,41,4,208,0,0,128,0,0,0]);let opt=r[r.len()-11..].to_vec();
 let rewritten=rewrite_ttl(&q,&r,0).unwrap();assert_eq!(rewritten[3]&0x20,0);assert_eq!(&rewritten[rewritten.len()-11..],&opt);assert_eq!(addresses(&q,&rewritten).unwrap()[0].ttl_ms,0);
 let mut truncated=r;truncated.pop();assert!(rewrite_ttl(&q,&truncated,30).is_err());
}
