use mcrf_bs_routing_policy::fake_ip::{Table,Error};
use std::net::IpAddr;
fn ip(s:&str)->IpAddr{s.parse().unwrap()}
#[test]fn active_flows_pin_original_destination_and_addresses_are_never_reused(){
 let mut t=Table::new(7,2,Some("198.18.0.0/30"),None,&[]).unwrap();
 let first=t.allocate(7,0,"ONE.invalid.",&[ip("192.0.2.1")],0).unwrap()[0];
 assert_eq!(t.pin(7,1,10,first).unwrap().domain,"one.invalid");
 assert_eq!(t.pin(7,2,10,first).unwrap().address,ip("192.0.2.1"));
 assert!(t.resolve(7,30001,first).unwrap().is_some());
 t.release(7,30002,10).unwrap();t.release(7,30002,10).unwrap();
 assert!(t.resolve(7,30002,first).unwrap().is_none());
 let second=t.allocate(7,30002,"two.invalid",&[ip("192.0.2.2")],1).unwrap()[0];
 assert_ne!(first,second);assert!(t.resolve(7,30003,first).unwrap().is_none());
 assert_eq!(t.allocate(7,60003,"three.invalid",&[ip("192.0.2.3")],1),Err(Error::Capacity));
}
#[test]fn failed_mixed_family_allocation_does_not_consume_pool(){
 let mut t=Table::new(1,3,Some("198.18.0.0/30"),None,&[]).unwrap();
 assert_eq!(t.allocate(1,0,"owned.invalid",&[ip("192.0.2.1"),ip("2001:db8::1")],1),Err(Error::Family));
 assert_eq!(t.allocate(1,0,"owned.invalid",&[ip("192.0.2.1")],1).unwrap(),vec![ip("198.18.0.1")]);
}
#[test]fn capacity_never_evicts_live_flow_and_flow_ids_cannot_change_destination(){
 let mut t=Table::new(1,1,Some("198.18.0.0/24"),None,&[]).unwrap();
 let first=t.allocate(1,0,"one.invalid",&[ip("192.0.2.1")],1).unwrap()[0];
 t.pin(1,0,2,first).unwrap();
 assert_eq!(t.allocate(1,40000,"two.invalid",&[ip("192.0.2.2")],1),Err(Error::Capacity));
 assert!(matches!(t.pin(1,40000,2,ip("198.18.0.9")),Err(Error::Invalid)));
 t.release(1,40001,2).unwrap();assert!(t.allocate(1,40001,"two.invalid",&[ip("192.0.2.2")],1).is_ok());
}
#[test]fn dual_stack_exclusions_case_and_duplicate_answers_are_consistent(){
 let mut t=Table::new(1,8,Some("198.18.0.0/24"),Some("fd00:1234::/120"),&[ip("198.18.0.1"),ip("fd00:1234::1")]).unwrap();
 let answer=t.allocate(1,0,"Owned.INVALID.",&[ip("192.0.2.1"),ip("192.0.2.1"),ip("2001:db8::1")],60000).unwrap();
 assert_eq!(answer,vec![ip("198.18.0.2"),ip("fd00:1234::2")]);
 assert_eq!(t.allocate(1,1,"owned.invalid",&[ip("192.0.2.1"),ip("2001:db8::1")],60000).unwrap(),answer);
 assert!(t.contains(answer[0])&&t.contains(answer[1]));assert!(!t.contains(ip("192.0.2.1")));
}
#[test]fn generation_clock_close_and_bad_prefixes_fail_explicitly(){
 for prefix in ["0.0.0.0/8","127.0.0.0/8","224.0.0.0/8","198.18.0.0/31"]{assert!(Table::new(1,1,Some(prefix),None,&[]).is_err())}
 let mut t=Table::new(1,1,Some("198.18.0.0/24"),None,&[]).unwrap();
 assert_eq!(t.allocate(2,0,"a.invalid",&[ip("192.0.2.1")],1),Err(Error::Stale));
 t.allocate(1,10,"a.invalid",&[ip("192.0.2.1")],1).unwrap();
 assert!(matches!(t.resolve(1,9,ip("198.18.0.1")),Err(Error::Clock)));
 t.close();assert_eq!(t.allocate(1,11,"a.invalid",&[ip("192.0.2.1")],1),Err(Error::Closed));
}
