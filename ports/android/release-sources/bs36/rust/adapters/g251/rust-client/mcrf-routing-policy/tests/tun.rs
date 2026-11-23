use mcrf_bs_routing_policy::tun::{Plan,Network};use serde_json::json;
fn document()->serde_json::Value{json!({"type":"tun","tag":"tun-in","address":["172.19.0.1/30","fdfe:dcba:9876::1/126"],"mtu":1400,"stack":"gvisor","auto_route":true,"strict_route":true,"dns_mode":"hijack"})}
#[test]fn native_projection_matches_shared_android_contract(){let fixture:serde_json::Value=serde_json::from_str(include_str!("fixtures/tun-projection.json")).unwrap();assert_eq!(Plan::parse(&fixture["source"]).unwrap().projection(),fixture["projection"]);}
#[test]fn default_addresses_keep_host_bits_and_capture_both_families(){
 let p=Plan::parse(&document()).unwrap();assert_eq!(p.addresses[0].address,"172.19.0.1".parse::<std::net::IpAddr>().unwrap());assert_eq!(p.addresses[0].prefix,30);assert_eq!(p.mtu,1400);
 assert!(p.captures("8.8.8.8".parse().unwrap()));assert!(p.captures("2001:db8::1".parse().unwrap()));assert!(p.captures_host("org.mcrf.client"));
}
#[test]fn explicit_routes_and_exclusions_preserve_prefix_and_family(){
 let mut d=document();d["route_address"]=json!(["10.0.0.0/8","2001:db8::/32"]);d["route_exclude_address"]=json!(["10.4.0.0/16","2001:db8:4::/48"]);let p=Plan::parse(&d).unwrap();
 for text in ["10.3.0.1","2001:db8:3::1"]{assert!(p.captures(text.parse().unwrap()))}for text in ["10.4.0.1","2001:db8:4::1","8.8.8.8","2001:4860::1"]{assert!(!p.captures(text.parse().unwrap()))}
}
#[test]fn app_scope_never_silently_broadens_or_excludes_control_host(){
 let mut d=document();d["include_package"]=json!(["org.allowed"]);let p=Plan::parse(&d).unwrap();assert_eq!(p.included_packages,vec!["org.allowed"]);assert!(!p.captures_host("org.mcrf.client"));
 d["exclude_package"]=json!(["org.other"]);assert!(Plan::parse(&d).is_err());d.as_object_mut().unwrap().remove("include_package");d["exclude_package"]=json!(["org.mcrf.client"]);assert!(!Plan::parse(&d).unwrap().captures_host("org.mcrf.client"));
 for values in [json!(["not a package"]),json!(["org.same","org.same"]),json!([false])]{d["exclude_package"]=values;assert!(Plan::parse(&d).is_err())}
}
#[test]fn unsupported_capture_nat_and_platform_options_fail_closed(){
 for(key,value)in [("auto_route",json!(false)),("strict_route",json!(false)),("gso",json!(true)),("endpoint_independent_nat",json!(true)),("platform",json!({"http_proxy":{"enabled":true}})),("interface_name",json!("tun0")),("dns_mode",json!("disabled")),("unknown",json!(false))]{let mut d=document();d[key]=value;assert!(Plan::parse(&d).is_err(),"{key}")}
}
#[test]fn numeric_bounds_families_and_mtu_are_checked_without_dns(){
 for text in ["host.invalid/24","::ffff:192.0.2.1/128","192.0.2.1/33","2001:db8::1/129","0.0.0.0/32","127.0.0.1/32","ff02::1/128"]{let mut d=document();d["address"]=json!([text]);assert!(Plan::parse(&d).is_err(),"{text}")}
 for mtu in [0,1279,9001]{let mut d=document();d["mtu"]=json!(mtu);assert!(Plan::parse(&d).is_err())}
 let mut d=document();d["address"]=json!(["172.19.0.1/30"]);d["mtu"]=json!(576);let p=Plan::parse(&d).unwrap();assert!(!p.captures("2001:db8::1".parse().unwrap()));d["route_address"]=json!(["::/0"]);assert!(Plan::parse(&d).is_err());
 assert!(Network::parse("::/0").unwrap().contains("ffff::1".parse().unwrap()));
}
#[test]fn empty_partial_and_oversized_documents_are_rejected(){
 let mut d=document();d["address"]=json!([]);assert!(Plan::parse(&d).is_err());d=document();d["route_address"]=json!([]);assert!(Plan::parse(&d).is_err());d=document();d["auto_route"]=json!("true");assert!(Plan::parse(&d).is_err());d=document();d["include_package"]=json!((0..513).map(|n|format!("org.app{n}")).collect::<Vec<_>>());assert!(Plan::parse(&d).is_err());
}
