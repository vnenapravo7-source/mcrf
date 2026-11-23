use mcrf_bs_routing_policy::{dns_rules::{DnsMatcher,Question},Flow,Observed,Error};
use serde_json::json;
fn question<'a>(query_type:u16,packages:Option<&'a [String]>)->Question<'a>{
    Question{flow:Flow{source:"10.89.0.2".parse().unwrap(),destination:"198.19.255.53".parse().unwrap(),source_port:41234,port:53,network:"udp",domain:Observed::Known(Some("example.org")),protocol:Observed::Known(Some("dns")),packages,underlay:Observed::Known(None)},query_type,preferred_by:Some(&[])}
}
#[test]fn dns_query_type_and_package_are_conjoined_before_whole_rule_inversion(){
    let allowed=vec!["org.example.allowed".to_owned()];let other=vec!["org.example.other".to_owned()];
    let rule=DnsMatcher::parse(&json!({"domain":"example.org","query_type":["AAAA"],"package_name":["org.example.allowed"],"invert":true})).unwrap();
    assert_eq!(rule.matches(&question(28,Some(&allowed))),Ok(false));assert_eq!(rule.matches(&question(1,Some(&allowed))),Ok(true));
    assert_eq!(rule.matches(&question(28,Some(&other))),Ok(true));assert_eq!(rule.matches(&question(28,None)),Err(Error::MissingOwner));
    // A false conjunct does not need caller identity to determine the result.
    assert_eq!(rule.matches(&question(1,None)),Ok(true));
}
#[test]fn logical_dns_or_and_unknown_metadata_preserve_conditional_meaning(){
    let rule=DnsMatcher::parse(&json!({"type":"logical","mode":"or","rules":[{"package_name":"org.example.allowed"},{"query_type":1,"domain_suffix":"org"}]})).unwrap();
    assert_eq!(rule.matches(&question(1,None)),Ok(true));assert_eq!(rule.matches(&question(28,None)),Err(Error::MissingOwner));
    let rule=DnsMatcher::parse(&json!({"type":"logical","mode":"and","rules":[{"package_name":"org.example.allowed"},{"query_type":1}]})).unwrap();
    assert_eq!(rule.matches(&question(28,None)),Ok(false));assert_eq!(rule.matches(&question(1,None)),Err(Error::MissingOwner));
}
#[test]fn preferred_hosts_are_explicit_observations_not_assumed_absent(){
    let rule=DnsMatcher::parse(&json!({"preferred_by":"mcrf-dns-hosts","query_type":["A","AAAA"]})).unwrap();
    let tags=vec!["mcrf-dns-hosts".to_owned()];let mut q=question(1,None);q.preferred_by=Some(&tags);assert_eq!(rule.matches(&q),Ok(true));
    q.preferred_by=Some(&[]);assert_eq!(rule.matches(&q),Ok(false));q.preferred_by=None;assert_eq!(rule.matches(&q),Err(Error::MissingDomain));
}
#[test]fn unsupported_response_filters_actions_and_unknown_types_are_not_erased(){
    for row in [json!({"ip_cidr":"192.0.2.0/24"}),json!({"query_type":"UNKNOWN"}),json!({"action":"predefined","rcode":"NXDOMAIN"}),json!({"query_type":0}),json!({"type":"logical","mode":"or","rules":[]})]{assert!(DnsMatcher::parse(&row).is_err())}
    let rule=DnsMatcher::parse(&json!({"query_type":["HTTPS",64,257]})).unwrap();assert_eq!(rule.matches(&question(65,None)),Ok(true));assert_eq!(rule.matches(&question(1,None)),Ok(false));
}
