use mcrf_bs_routing_policy::{Policy,Flow,Observed,Target,Error};
use serde_json::{json,Value};
fn flow()->Flow<'static>{Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.42".parse().unwrap(),source_port:45678,port:443,network:"tcp",domain:Observed::Known(Some("www.example.org")),protocol:Observed::Known(Some("tls")),packages:Some(&[]),underlay:Observed::Pending}}
fn document(outer:Value,contents:Value)->Value{json!({"schema":1,"defaultTarget":"proxy","ruleSets":{"private-profile-site":{"version":3,"rules":contents}},"rules":[{"match":outer,"target":"block"}]})}
fn parse(v:Value)->Result<Policy,Error>{Policy::parse(&serde_json::to_vec(&v).unwrap())}
#[test]
fn selected_private_category_merges_destination_alternatives_not_an_extra_and(){
    let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"203.0.113.0/24","port":443}),json!([{"domain_suffix":"example.org"}]))).ok().unwrap();
    assert!(p.decide(&flow())==Ok(Target::Block));
    let mut f=flow();f.port=80;assert!(p.decide(&f)==Ok(Target::Tunnel));
    f.port=443;f.domain=Observed::Known(Some("unrelated.test"));assert!(p.decide(&f)==Ok(Target::Tunnel));
    f.destination="203.0.113.1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Block));
    assert!(p.requirements().domain);
}
#[test]
fn all_four_groups_merge_but_protocol_is_still_a_conjunction(){
    let p=parse(document(json!({"rule_set":["private-profile-site"],"source_ip_cidr":"203.0.113.0/24","source_port":1,"port":80,"protocol":"tls"}),json!([{"source_ip_cidr":"198.18.0.0/16","source_port":45678,"port_range":"440:450"}]))).ok().unwrap();
    assert!(p.decide(&flow())==Ok(Target::Block));
    let mut f=flow();f.protocol=Observed::Known(Some("http"));assert!(p.decide(&f)==Ok(Target::Tunnel));
    f.protocol=Observed::Known(Some("tls"));f.source_port=7;assert!(p.decide(&f)==Ok(Target::Tunnel));
}
#[test]
fn outer_inversion_applies_to_the_whole_merged_match(){
    let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"203.0.113.0/24","invert":true}),json!([{"domain_suffix":"example.org"}]))).ok().unwrap();
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
    let mut f=flow();f.domain=Observed::Known(Some("unrelated.test"));assert!(p.decide(&f)==Ok(Target::Block));
    f.domain=Observed::Pending;assert!(p.decide(&f)==Err(Error::MissingDomain));
}
#[test]
fn multiple_source_rows_do_not_merge_with_outer_address_group(){
    let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"203.0.113.0/24"}),json!([{"domain_suffix":"example.org"},{"port":443}]))).ok().unwrap();
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
    let mut f=flow();f.destination="203.0.113.9".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Block));
}
#[test]
fn logical_and_inverted_source_rows_keep_their_separate_semantics(){
    for inner in [json!({"domain_suffix":"example.org","invert":true}),json!({"type":"logical","mode":"or","rules":[{"domain_suffix":"example.org"},{"port":443}]})]{
        let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"203.0.113.0/24"}),json!([inner]))).ok().unwrap();
        assert!(p.decide(&flow())==Ok(Target::Tunnel));
    }
}
#[test]
fn known_ip_alternative_can_resolve_pending_domain_without_leaking_an_inverted_rule(){
    let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"192.0.2.0/24"}),json!([{"domain_suffix":"example.org"}]))).ok().unwrap();
    let mut f=flow();f.domain=Observed::Pending;assert!(p.decide(&f)==Ok(Target::Block));
    f.destination="198.51.100.1".parse().unwrap();assert!(p.decide(&f)==Err(Error::MissingDomain));
}
#[test]
fn disabled_false_flags_do_not_turn_into_empty_extra_conditions(){
    let p=parse(document(json!({"rule_set":"private-profile-site","network_is_expensive":false,"ip_is_private":false}),json!([{"domain_suffix":"example.org","source_ip_is_private":false}]))).ok().unwrap();
    assert!(p.decide(&flow())==Ok(Target::Block));
}
#[test]
fn missing_ambiguous_unsupported_and_recursive_snapshots_fail_closed(){
    let base=document(json!({"rule_set":"private-profile-site"}),json!([{"domain_suffix":"example.org"}]));
    let mut v=base.clone();v["rules"][0]["match"]["rule_set"]=json!("standard-global-site");assert!(parse(v).is_err());
    for row in [json!({"domain_suffix":"example.org","unknown_filter":true}),json!({"rule_set":"private-profile-site"}),json!({"type":"logical","mode":"or","rules":[{"rule_set":"private-profile-site"}]})]{assert!(parse(document(json!({"rule_set":"private-profile-site"}),json!([row]))).is_err())}
    let duplicate=serde_json::to_string(&base).unwrap().replace("\"private-profile-site\":{","\"private-profile-site\":{},\"private-profile-site\":{");assert!(Policy::parse(duplicate.as_bytes()).is_err());
    let mut v=base.clone();v["ruleSets"]["private-profile-site"]["url"]=json!("https://not-opened.invalid/site");assert!(parse(v).is_err());
    let mut v=base;v["ruleSets"]["private-profile-site"]["version"]=json!(6);assert!(parse(v).is_err());
}
#[test]
fn repeated_large_references_are_bounded_before_expansion_can_multiply_memory(){
    let domains=(0..1000).map(|i|format!("{i}.{}.test","a".repeat(180))).collect::<Vec<_>>();
    let v=document(json!({"rule_set":vec!["private-profile-site";128]}),json!([{"domain":domains}]));
    assert!(matches!(parse(v),Err(Error::Limit)));
}

#[test]
fn all_known_srs_versions_keep_known_fields_and_reject_unknown_semantics(){
 for version in 1..=5{
  let mut v=document(json!({"rule_set":"private-profile-site"}),json!([{"domain_suffix":"example.org"}]));v["ruleSets"]["private-profile-site"]["version"]=json!(version);
  let p=parse(v.clone()).unwrap();assert_eq!(p.decide(&flow()),Ok(Target::Block));
  v["ruleSets"]["private-profile-site"]["rules"][0]["future_condition"]=json!(true);assert!(parse(v).is_err());
 }
}
#[test]
fn recovered_adguard_domains_keep_destination_or_group_with_outer_ip(){
 let p=parse(document(json!({"rule_set":"private-profile-site","ip_cidr":"192.0.2.0/24"}),json!([{"adguard_domain":["||blocked.invalid^"]}]))).unwrap();
 assert_eq!(p.decide(&flow()),Ok(Target::Block));
 let mut f=flow();f.destination="203.0.113.7".parse().unwrap();assert_eq!(p.decide(&f),Ok(Target::Tunnel));
 f.domain=Observed::Known(Some("sub.blocked.invalid"));assert_eq!(p.decide(&f),Ok(Target::Block));
 f.domain=Observed::Known(Some("blocked.invalid.evil"));assert_eq!(p.decide(&f),Ok(Target::Tunnel));
}
