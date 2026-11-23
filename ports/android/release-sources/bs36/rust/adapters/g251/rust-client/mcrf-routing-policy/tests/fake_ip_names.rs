use mcrf_bs_routing_policy::fake_ip::{names::Table,Error};use std::net::IpAddr;
fn ip(s:&str)->IpAddr{s.parse().unwrap()}
#[test]fn domain_only_answers_have_stable_identity_without_resolving_any_real_address(){
 let mut t=Table::new(1,8,Some("198.18.0.0/24"),Some("fd00:1234::/120"),&[ip("198.18.0.1")]).unwrap();
 let v4=t.allocate(1,"Owned.INVALID.",false).unwrap();assert_eq!(v4,ip("198.18.0.2"));assert_eq!(t.allocate(1,"owned.invalid",false).unwrap(),v4);
 let v6=t.allocate(1,"owned.invalid",true).unwrap();assert!(v6.is_ipv6());assert_eq!(t.lookup(1,v6).unwrap(),Some("owned.invalid"));
 assert_eq!(t.pin(1,10,v4).unwrap(),"owned.invalid");assert_eq!(t.pin(1,10,v4).unwrap(),"owned.invalid");assert_eq!(t.pin(1,10,v6),Err(Error::Invalid));
 t.release(1,10).unwrap();assert_eq!(t.lookup(1,v4).unwrap(),Some("owned.invalid"));assert_eq!(t.pin(1,11,ip("198.18.0.99")),Err(Error::Unknown));
}
#[test]fn capacity_never_reassigns_cached_addresses_and_families_and_generations_are_explicit(){
 let mut t=Table::new(7,1,Some("198.18.0.0/30"),None,&[]).unwrap();let first=t.allocate(7,"one.invalid",false).unwrap();
 assert_eq!(t.allocate(7,"two.invalid",false),Err(Error::Capacity));assert_eq!(t.allocate(7,"one.invalid",false).unwrap(),first);assert_eq!(t.lookup(7,first).unwrap(),Some("one.invalid"));
 assert_eq!(t.lookup(8,first),Err(Error::Stale));assert!(!t.enabled(true));t.close();assert_eq!(t.lookup(7,first),Err(Error::Closed));
}
#[test]fn overlapping_fake_servers_are_detected_before_runtime_activation(){
 let a=Table::new(1,8,Some("198.18.0.0/15"),None,&[]).unwrap();let b=Table::new(1,8,Some("198.19.0.0/24"),None,&[]).unwrap();let c=Table::new(1,8,None,Some("fd00:1234::/120"),&[]).unwrap();
 assert!(a.overlaps(&b)&&b.overlaps(&a));assert!(!a.overlaps(&c));assert!(a.contains(ip("198.19.255.53")));assert!(!a.contains(ip("192.0.2.1")));
}
