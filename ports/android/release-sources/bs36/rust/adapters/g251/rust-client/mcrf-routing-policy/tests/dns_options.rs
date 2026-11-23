use mcrf_bs_routing_policy::dns_options::*;use serde_json::json;use std::time::Duration;
#[test]fn ordered_options_inherit_strategy_and_cannot_undo_disabled_cache(){
 let root=RootOptions::parse(&json!({"strategy":"prefer_ipv4","disable_cache":false,"timeout":"10s","cache_capacity":0})).unwrap();assert_eq!(root.capacity,1024);let mut q=root.query;
 q.apply(&json!({"strategy":"ipv6_only","disable_cache":true,"rewrite_ttl":0,"client_subnet":"192.0.2.0/24"})).unwrap();
 q.apply(&json!({"strategy":"","disable_cache":false,"timeout":"250ms","remove_client_subnet":true})).unwrap();assert_eq!(q.strategy,"ipv6_only");assert!(q.disable_cache);assert_eq!(q.rewrite_ttl,Some(0));assert!(q.subnet.is_none()&&q.remove_subnet);assert_eq!(q.timeout,Duration::from_millis(250));
}
#[test]fn root_options_validate_types_and_retain_optimistic_expiration_and_mapping(){
 let r=RootOptions::parse(&json!({"disable_expire":true,"reverse_mapping":true,"optimistic":{"enabled":true,"timeout":"2m"},"timeout":"30s","cache_capacity":65536})).unwrap();assert!(r.disable_expire&&r.reverse_mapping);assert_eq!(r.optimistic,Some(Duration::from_secs(120)));assert_eq!(r.capacity,65536);
 for doc in [json!({"disable_cache":"true"}),json!({"timeout":"0s"}),json!({"timeout":"999999999999999999999m"}),json!({"cache_capacity":65537}),json!({"optimistic":{"enabled":true,"unknown":1}})]{assert!(RootOptions::parse(&doc).is_err())}
}
#[test]fn malformed_and_unknown_action_options_are_not_dropped(){
 let root=RootOptions::parse(&json!({})).unwrap();for value in [json!({"rewrite_ttl":-1}),json!({"rewrite_ttl":4294967296u64}),json!({"disable_cache":1}),json!({"client_subnet":"wrong"}),json!({"unknown":true})]{let mut q=root.query.clone();assert!(q.apply(&value).is_err())}
}
