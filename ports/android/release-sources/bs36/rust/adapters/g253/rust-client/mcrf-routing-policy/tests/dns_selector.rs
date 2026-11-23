use mcrf_bs_routing_policy::{dns_rules::Question,dns_selector::{Selector,Action},Flow,Observed,Error};
use serde_json::json;
fn q(query_type:u16)->Question<'static>{Question{flow:Flow{source:"10.89.0.2".parse().unwrap(),destination:"198.19.255.53".parse().unwrap(),source_port:41234,port:53,network:"udp",domain:Observed::Known(Some("example.org")),protocol:Observed::Known(Some("dns")),packages:None,underlay:Observed::Known(None)},query_type,preferred_by:Some(&[])}}
#[test]fn ordered_options_and_private_geosite_select_the_captured_resolver(){
    let document=json!({"servers":[{"tag":"direct","type":"local"},{"tag":"remote","type":"https","server":"192.0.2.53","tls":{"server_name":"owned.invalid","enabled":true},"detour":"own-bs"}],"final":"direct","rules":[
        {"query_type":"A","action":"route-options","disable_cache":true},
        {"rule_set":"private-list","action":"route","server":"remote","rewrite_ttl":60}
    ]});
    let sets=json!({"private-list":{"version":1,"rules":[{"domain_suffix":"example.org"}]}});
    let selector=Selector::parse(&document,&sets).unwrap();let selected=selector.select(&q(1)).unwrap();assert_eq!(selected.preceding_options,vec![&json!({"disable_cache":true})]);
    match selected.action{Action::Route{server,options}=>{assert_eq!(server,"remote");assert_eq!(options,&json!({"rewrite_ttl":60}))},_=>panic!("wrong action")}
    assert_eq!(selector.server("remote").unwrap()["tls"]["server_name"],"owned.invalid");assert_eq!(selector.server("remote").unwrap()["detour"],"own-bs");
    assert!(Selector::parse(&document,&json!({})).is_err());
}
#[test]fn predefined_and_missing_owner_do_not_fall_through_to_direct(){
    let document=json!({"servers":[{"tag":"direct","type":"local"}],"final":"direct","rules":[{"query_type":"AAAA","action":"predefined","rcode":"NOERROR"},{"package_name":"org.example.app","server":"direct"}]});
    let selector=Selector::parse(&document,&json!({})).unwrap();
    assert!(matches!(selector.select(&q(28)).unwrap().action,Action::Predefined(v)if v["rcode"]=="NOERROR"));
    assert!(matches!(selector.select(&q(1)),Err(Error::MissingOwner)));
}
#[test]fn immutable_server_snapshot_and_invalid_references_unknown_actions_are_explicit(){
    let mut document=json!({"servers":[{"tag":"remote","type":"https","tls":{"insecure":false}}],"final":"remote"});
    let selector=Selector::parse(&document,&json!({})).unwrap();document["servers"][0]["tls"]["insecure"]=json!(true);assert_eq!(selector.server("remote").unwrap()["tls"]["insecure"],false);
    for broken in [json!({"servers":[{"tag":"one"},{"tag":"one"}],"final":"one"}),json!({"servers":[{"tag":"one"}],"final":"missing"}),json!({"servers":[{"tag":"one"}],"final":"one","rules":[{"action":"unknown"}]})]{assert!(Selector::parse(&broken,&json!({})).is_err())}
}
