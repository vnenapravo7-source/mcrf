use mcrf_bs_routing_policy::{bundle::Bundle,decode_document};use serde_json::{Value,json};
fn doc()->Value{json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[{"match":{"domain":"owned.invalid"},"target":"direct"}]},"dns":{"servers":[{"type":"hosts","tag":"h"}],"final":"h"},"controls":[{"beforeRule":0,"operation":{"action":"sniff","network":"tcp"}},{"beforeRule":1,"operation":{"action":"resolve","strategy":"ipv4_only"}}],"routeOptions":{"default_domain_resolver":"h"},"tunInbounds":[{"type":"tun","include_package":["org.only"],"address":["10.89.0.2/30"],"route_exclude_address":["192.0.2.0/24"]}],"dnsDialers":{"chosen":"proxy","direct":"direct"}})}
#[test]fn preserves_entire_private_dns_tun_and_ordered_controls(){
 let doc=doc();let b=Bundle::parse(&serde_json::to_vec(&doc).unwrap()).unwrap();assert_eq!(b.document(),&doc);assert!(b.policy.requirements().domain);assert!(b.sets().is_none());
}
#[test]fn duplicate_keys_unknown_fields_and_wrong_control_positions_never_change_policy_implicitly(){
 assert!(decode_document(br#"{"dns":{"final":"a","final":"b"}}"#,1024).is_err());
 for change in 0..4{let mut d=doc();match change{0=>{d["extra"]=json!(true);},1=>{d["controls"][0]["beforeRule"]=json!(2);},2=>{d["controls"][0]["beforeRule"]=json!(1);d["controls"][1]["beforeRule"]=json!(0);},_=>{d["dnsDialers"]["chosen"]=json!("unknown");}}assert!(Bundle::parse(&serde_json::to_vec(&d).unwrap()).is_err());}
}
#[test]fn private_sets_are_retained_and_multiple_tuns_and_oversize_documents_are_rejected(){
 let mut d=doc();d["policy"]["ruleSets"]=json!({"private":{"version":3,"rules":[{"domain":"private.invalid"}]}});let b=Bundle::parse(&serde_json::to_vec(&d).unwrap()).unwrap();assert_eq!(b.sets().unwrap()["private"]["version"],3);
 d["tunInbounds"].as_array_mut().unwrap().push(json!({"type":"tun"}));assert!(Bundle::parse(&serde_json::to_vec(&d).unwrap()).is_err());assert!(decode_document(b"{}",1).is_err());assert!(decode_document(b"{}",25*1024*1024).is_err());
}

#[test]fn a_local_proxy_requirement_is_preserved_and_unknown_listener_features_are_rejected(){
 let mut d=doc();d["listeners"]=json!([{"type":"mixed","tag":"local-proxy","listen":"127.0.0.1","listen_port":10808}]);let bundle=Bundle::parse(&serde_json::to_vec(&d).unwrap()).unwrap();assert_eq!(bundle.document()["listeners"],d["listeners"]);d["listeners"][0]["sniff"]=json!(true);assert!(Bundle::parse(&serde_json::to_vec(&d).unwrap()).is_err());
}

#[test]fn captured_certificate_store_is_preserved_and_cannot_select_an_implicit_fallback(){
 let mut d=doc();d["certificates"]=json!({"store":"system","roots":["AA=="]});let b=Bundle::parse(&serde_json::to_vec(&d).unwrap()).unwrap();assert_eq!(b.document()["certificates"],d["certificates"]);
 for value in [json!({"store":"unknown","roots":["AA=="]}),json!({"store":"mozilla","roots":[]}),json!({"store":"system","roots":["AA=="],"fallback":true})]{d["certificates"]=value;assert!(Bundle::parse(&serde_json::to_vec(&d).unwrap()).is_err());}
}

#[test]
fn named_egress_requires_an_exact_non_conflicting_worker_binding(){
 let mut d=doc();
 let one=format!("mcrf-route-{:064x}",1);
 let two=format!("mcrf-route-{:064x}",2);
 d["policy"]["schema"]=json!(2);
 d["policy"]["egressTags"]=json!([one,two]);
 d["policy"]["defaultTarget"]=json!(one);
 let parse=|doc:&Value|Bundle::parse(&serde_json::to_vec(doc).unwrap());
 assert!(parse(&d).is_err());
 d["egressBindings"]=json!([
  {"tag":one,"port":10808,"username":"fixture-user-0001","password":"fixture-password-0001"},
  {"tag":two,"port":10809,"username":"fixture-user-0002","password":"fixture-password-0002"}
 ]);
 d["dnsDialers"]["second"]=json!(two);
 assert_eq!(parse(&d).unwrap().runtime_dialers()["second"],1);
 let mut unknown=d.clone();unknown["dnsDialers"]["second"]=json!("mcrf-route-missing");
 assert!(parse(&unknown).is_err());
 for (field,value) in [("tag",json!(two)),("port",json!(10809)),("username",json!("short")),("password",json!("not\nvalid-fixture-password"))]{
  let mut bad=d.clone();
  bad["egressBindings"][0][field]=value;
  assert!(parse(&bad).is_err(),"{field}");
 }
 d["listeners"]=json!([{"type":"mixed","tag":"local-proxy","listen":"127.0.0.1","listen_port":10808}]);
 assert!(parse(&d).is_err());
}
