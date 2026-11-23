use serde_json::{Value,json};use mcrf_bs_routing_policy::{bundle::Bundle,pipeline::{Pipeline,Instruction,Kind},Flow,Observed,DestinationView,Target,Error};
fn bundle(rules:Value,controls:Value)->Value{json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":rules},"controls":controls,"dns":null,"routeOptions":{},"tunInbounds":[],"dnsDialers":{}})}
fn pipeline(doc:Value)->Pipeline{Pipeline::new(Bundle::parse(&serde_json::to_vec(&doc).unwrap()).unwrap()).unwrap()}
fn flow()->Flow<'static>{Flow{source:"10.89.0.2".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),source_port:41234,port:443,network:"tcp",domain:Observed::Pending,protocol:Observed::Pending,packages:Some(&[]),underlay:Observed::Known(None)}}
#[test]fn sniff_resumes_before_the_same_route_and_never_runs_later_resolve_after_terminal_route(){
 let p=pipeline(bundle(json!([{"match":{"domain":"owned.invalid"},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff","network":"tcp","timeout":"300ms"}},{"beforeRule":1,"operation":{"action":"resolve","strategy":"ipv4_only"}}])));
 let mut f=flow();let next=match p.next(&f,DestinationView::Address,p.start()).unwrap(){Instruction::Effect{operation,next}=>{assert_eq!(operation.kind,Kind::Sniff);assert_eq!(operation.options["timeout"],"300ms");next},_=>panic!()};
 f.domain=Observed::Known(Some("owned.invalid"));f.protocol=Observed::Known(Some("tls"));assert!(matches!(p.next(&f,DestinationView::Address,next).unwrap(),Instruction::Route(Target::Direct)));assert_eq!(p.operations().count(),2);
}
#[test]fn resolved_addresses_apply_to_later_rules_without_restarting_prior_controls(){
 let p=pipeline(bundle(json!([{"match":{"domain":"first.invalid"},"target":"block"},{"match":{"ip_cidr":"8.8.8.0/24"},"target":"direct"}]),json!([{"beforeRule":1,"operation":{"action":"resolve","server":"private","strategy":"ipv4_only"}}])));
 let mut f=flow();f.domain=Observed::Known(Some("owned.invalid"));let next=match p.next(&f,DestinationView::Domain{addresses:&[],ip_version:None},p.start()).unwrap(){Instruction::Effect{operation,next}=>{assert_eq!(operation.kind,Kind::Resolve);assert_eq!(operation.options["server"],"private");next},_=>panic!()};
 let ips=["8.8.8.8".parse().unwrap(),"9.9.9.9".parse().unwrap()];assert!(matches!(p.next(&f,DestinationView::Domain{addresses:&ips,ip_version:None},next).unwrap(),Instruction::Route(Target::Direct)));
}
#[test]fn conditional_dns_and_route_options_keep_order_and_all_arguments(){
 let p=pipeline(bundle(json!([]),json!([{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}},{"beforeRule":0,"operation":{"action":"route-options","network":"tcp","override_port":8443,"udp_timeout":"1m"}}])));
 let mut f=flow();f.protocol=Observed::Known(Some("dns"));assert!(matches!(p.next(&f,DestinationView::Address,p.start()).unwrap(),Instruction::Effect{operation,..}if operation.kind==Kind::HijackDns));
 f.protocol=Observed::Known(Some("tls"));let next=match p.next(&f,DestinationView::Address,p.start()).unwrap(){Instruction::Effect{operation,next}=>{assert_eq!(operation.kind,Kind::RouteOptions);assert_eq!(operation.options["override_port"],8443);assert_eq!(operation.options["udp_timeout"],"1m");next},_=>panic!()};
 assert!(matches!(p.next(&f,DestinationView::Address,next).unwrap(),Instruction::Route(Target::Block)));
}
#[test]fn pending_metadata_is_not_a_false_condition_or_a_direct_fallback(){
 let p=pipeline(bundle(json!([{"match":{"domain":"owned.invalid","invert":true},"target":"direct"}]),json!([])));assert!(matches!(p.next(&flow(),DestinationView::Address,p.start()),Err(Error::MissingDomain)));
}
#[test]fn private_rule_sets_in_conditional_controls_are_expanded_from_that_bundle_only(){
 let mut doc=bundle(json!([]),json!([{"beforeRule":0,"operation":{"action":"sniff","rule_set":"private"}}]));doc["policy"]["ruleSets"]=json!({"private":{"version":3,"rules":[{"domain":"owned.invalid"}]}});let p=pipeline(doc.clone());let mut f=flow();f.domain=Observed::Known(Some("owned.invalid"));assert!(matches!(p.next(&f,DestinationView::Address,p.start()).unwrap(),Instruction::Effect{operation,..}if operation.kind==Kind::Sniff));
 doc["policy"].as_object_mut().unwrap().remove("ruleSets");assert!(Pipeline::new(Bundle::parse(&serde_json::to_vec(&doc).unwrap()).unwrap()).is_err());
}

#[test]fn unresolved_domain_yields_lazy_resolution_without_restarting_prior_effects(){
 let p=pipeline(bundle(json!([{"match":{"ip_cidr":"192.0.2.0/24","invert":true},"target":"direct"}]),json!([{"beforeRule":0,"operation":{"action":"sniff"}}])));
 let mut f=flow();f.domain=Observed::Known(Some("owned.invalid"));
 let next=match p.next(&f,DestinationView::UnresolvedDomain{ip_version:None},p.start()).unwrap(){Instruction::Effect{next,..}=>next,_=>panic!("first effect")};
 let retry=match p.next(&f,DestinationView::UnresolvedDomain{ip_version:None},next).unwrap(){Instruction::ResolveNeeded{retry}=>retry,_=>panic!("inversion must not bypass pending resolution")};
 assert!(matches!(p.next(&f,DestinationView::Domain{addresses:&["192.0.2.1".parse().unwrap()],ip_version:None},retry).unwrap(),Instruction::Route(Target::Block)));
 assert!(matches!(p.next(&f,DestinationView::Domain{addresses:&[],ip_version:None},retry).unwrap(),Instruction::Route(Target::Direct)));
}
#[test]fn unrelated_source_and_port_conditions_do_not_force_domain_dns_queries(){
 let p=pipeline(bundle(json!([{"match":{"ip_cidr":"192.0.2.0/24","port":8443},"target":"direct"},{"match":{"source_ip_cidr":"10.0.0.0/8"},"target":"block"}]),json!([])));
 let mut f=flow();f.port=443;f.source="10.1.2.3".parse().unwrap();
 assert!(matches!(p.next(&f,DestinationView::UnresolvedDomain{ip_version:None},p.start()).unwrap(),Instruction::Route(Target::Block)));
}
