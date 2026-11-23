use mcrf_bs_routing_policy::{dns_cache::{Cache,Error},dns_wire};
fn query(id:u16)->Vec<u8>{dns_wire::query(id,"owned.invalid",1).unwrap()}
fn reply(q:&[u8],ttl:u32)->Vec<u8>{dns_wire::answer(q,0,&["192.0.2.1".parse().unwrap()],ttl).unwrap()}
#[test]fn response_identity_age_expiry_and_resolver_isolation_are_preserved(){
 let mut c=Cache::new(1,2,false).unwrap();let first=query(1);c.store(1,0,"private",&first,&reply(&first,60)).unwrap();
 let next=query(99);let hit=c.get(1,1500,"private",&next).unwrap().unwrap();assert_eq!(&hit[..2],&99u16.to_be_bytes());assert_eq!(dns_wire::addresses(&next,&hit).unwrap()[0].ttl_ms,58000);
 assert!(c.get(1,1500,"other",&next).unwrap().is_none());assert!(c.get(1,60000,"private",&next).unwrap().is_none());
}
#[test]fn zero_ttl_never_cached_and_lru_bounded_capacity_does_not_cross_queries(){
 let mut c=Cache::new(1,1,false).unwrap();let q=query(1);c.store(1,0,"s",&q,&reply(&q,0)).unwrap();assert!(c.get(1,0,"s",&q).unwrap().is_none());
 c.store(1,0,"s",&q,&reply(&q,60)).unwrap();let other=dns_wire::query(1,"other.invalid",1).unwrap();c.store(1,1,"s",&other,&reply(&other,60)).unwrap();assert!(c.get(1,2,"s",&q).unwrap().is_none());assert!(c.get(1,2,"s",&other).unwrap().is_some());
}
#[test]fn disable_expire_is_explicit_and_session_clock_and_close_are_enforced(){
 let mut c=Cache::new(7,1,true).unwrap();let q=query(1);c.store(7,0,"s",&q,&reply(&q,1)).unwrap();let hit=c.get(7,90000,"s",&q).unwrap().unwrap();assert_eq!(dns_wire::addresses(&q,&hit).unwrap()[0].ttl_ms,1000);
 assert_eq!(c.get(8,90000,"s",&q),Err(Error::Stale));assert_eq!(c.get(7,89999,"s",&q),Err(Error::Clock));c.close();assert_eq!(c.get(7,90001,"s",&q),Err(Error::Closed));
}
#[test]fn negative_soa_uses_minimum_ttl_and_malformed_reply_never_enters_cache(){
 let q=query(1);let mut r=dns_wire::answer(&q,3,&[],0).unwrap();r[9]=1;
 // SOA authority: owner compressed to question, root MNAME/RNAME,
 // serial/refresh/retry/expire zero, MINIMUM 30, record TTL 60.
 r.extend([0xc0,0x0c,0,6,0,1,0,0,0,60,0,22,0,0]);r.extend([0;16]);r.extend(30u32.to_be_bytes());
 assert_eq!(dns_wire::cache_ttl(&q,&r).unwrap(),30);let mut c=Cache::new(1,1,false).unwrap();c.store(1,0,"s",&q,&r).unwrap();assert!(c.get(1,29000,"s",&q).unwrap().is_some());assert!(c.get(1,30000,"s",&q).unwrap().is_none());
 r.pop();assert_eq!(c.store(1,30000,"s",&q,&r),Err(Error::Wire));assert!(c.get(1,30000,"s",&q).unwrap().is_none());
}
