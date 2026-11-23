use mcrf_bs_routing_policy::{Policy,Flow,Target,Error,Observed,NetworkContext};
use serde_json::{json,Value};

fn policy(rules:Value,fallback:&str)->Policy {
    Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":fallback,"rules":rules})).unwrap()).unwrap()
}
fn flow()->Flow<'static> {Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.42".parse().unwrap(),source_port:45678,port:443,network:"tcp",domain:Observed::Known(Some("www.example.org")),protocol:Observed::Known(Some("tls")),packages:None,underlay:Observed::Known(None)}}

#[test]
fn named_egresses_keep_distinct_ids_and_rule_precedence(){
    let tags:Vec<String>=(0..4).map(|id|format!("mcrf-route-{id:064x}")).collect();
    let document=json!({"schema":2,"egressTags":tags,"defaultTarget":tags[3],"rules":[
        {"match":{"package_name":"org.example.one"},"target":tags[0]},
        {"match":{"domain_suffix":"example.org"},"target":tags[1]},
        {"match":{"ip_cidr":"192.0.2.0/24"},"target":tags[2]},
        {"match":{"port":53},"target":"block"}
    ]});
    let policy=Policy::parse(&serde_json::to_vec(&document).unwrap()).unwrap();
    assert_eq!(policy.egress_tags(),tags);
    let first=["org.example.one".to_owned()];let mut packet=flow();packet.packages=Some(&first);
    assert_eq!(policy.decide(&packet),Ok(Target::Egress(0)));
    assert_eq!(policy.decide(&flow()),Err(Error::MissingOwner));
    packet.packages=Some(&[]);assert_eq!(policy.decide(&packet),Ok(Target::Egress(1)));
    packet.domain=Observed::Known(None);assert_eq!(policy.decide(&packet),Ok(Target::Egress(2)));
    packet.destination="2001:db8::42".parse().unwrap();assert_eq!(policy.decide(&packet),Ok(Target::Egress(3)));
    packet.network="udp";packet.port=53;assert_eq!(policy.decide(&packet),Ok(Target::Block));
}

#[test]
fn missing_duplicate_and_unversioned_egress_bindings_are_rejected(){
    let tag=format!("mcrf-route-{:064x}",1);
    let base=json!({"schema":2,"egressTags":[tag],"defaultTarget":tag,"rules":[]});
    assert!(Policy::parse(&serde_json::to_vec(&base).unwrap()).is_ok());
    for (key,value) in [
        ("schema",json!(1)),("egressTags",json!([])),("egressTags",json!([tag,tag])),
        ("egressTags",json!(["direct"])),("defaultTarget",json!("mcrf-route-missing")),
    ]{
        let mut invalid=base.clone();invalid[key]=value;
        assert!(Policy::parse(&serde_json::to_vec(&invalid).unwrap()).is_err(),"{key}");
    }
}

#[test]
fn first_match_and_block_precedence_are_preserved() {
    let p=policy(json!([
        {"match":{"domain":["www.example.org"]},"target":"block"},
        {"match":{"ip_cidr":["0.0.0.0/0"]},"target":"direct"}
    ]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Block));
    let mut f=flow();f.domain=Observed::Known(None);assert!(p.decide(&f)==Ok(Target::Direct));
}
#[test]
fn suffix_matching_respects_label_boundaries_and_domain_case() {
    let p=policy(json!([{ "match":{"domain_suffix":"example.org"},"target":"direct"}]),"proxy");
    for name in ["EXAMPLE.ORG","www.example.org","a.b.example.org"]{let mut f=flow();f.domain=Observed::Known(Some(name));assert!(p.decide(&f)==Ok(Target::Direct));}
    for name in ["notexample.org","example.org.attacker.test","EXAMPLE.ORG."]{let mut f=flow();f.domain=Observed::Known(Some(name));assert!(p.decide(&f)==Ok(Target::Tunnel));}
}
#[test]
fn exact_domains_do_not_become_suffix_rules() {
    let p=policy(json!([{ "match":{"domain":"example.org"},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
}
#[test]
fn ipv4_ipv6_zero_prefix_and_family_separation() {
    let p=policy(json!([{ "match":{"ip_cidr":["2001:db8:1234::/48","192.0.2.0/24"]},"target":"direct"}]),"block");
    assert!(p.decide(&flow())==Ok(Target::Direct));
    let mut f=flow();f.destination="2001:db8:1234:abcd::1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Direct));
    f.destination="2001:db8:1235::1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Block));
    let zero=policy(json!([{ "match":{"ip_cidr":"::/0"},"target":"direct"}]),"block");
    assert!(zero.decide(&flow())==Ok(Target::Block));assert!(zero.decide(&f)==Ok(Target::Direct));
}
#[test]
fn logical_rules_and_source_destination_ports() {
    let p=policy(json!([{ "match":{"type":"logical","mode":"and","rules":[
        {"type":"logical","mode":"or","rules":[{"domain":"other.test"},{"ip_cidr":"192.0.2.42/32"}]},
        {"network":"tcp","port_range":"440:450","source_port_range":"45000:","source_ip_cidr":"198.18.0.0/15"}
    ]},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Direct));let mut f=flow();f.port=80;assert!(p.decide(&f)==Ok(Target::Tunnel));
}
#[test]
fn unknown_application_owner_cannot_invert_into_a_direct_bypass() {
    let p=policy(json!([{ "match":{"package_name":"org.example.app","invert":true},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Err(Error::MissingOwner));
    let packages=vec!["org.example.app".to_owned()];let mut f=flow();f.packages=Some(&packages);assert!(p.decide(&f)==Ok(Target::Tunnel));
    let empty=vec![];f.packages=Some(&empty);assert!(p.decide(&f)==Ok(Target::Direct));
}
#[test]
fn a_known_false_and_condition_does_not_require_an_unrelated_owner() {
    let p=policy(json!([{ "match":{"package_name":"org.example.app","port":80},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
}
#[test]
fn patterns_and_protocols_are_combined_without_ignoring_fields() {
    let p=policy(json!([{ "match":{"domain_regex":"^www\\.example\\.org$","protocol":"tls","network":["tcp","udp"]},"target":"direct"}]),"block");
    assert!(p.decide(&flow())==Ok(Target::Direct));let mut f=flow();f.protocol=Observed::Known(None);assert!(p.decide(&f)==Ok(Target::Block));
}
#[test]
fn unknown_or_unexpanded_filters_and_ambiguous_documents_are_rejected() {
    for field in ["rule_set","geoip","unknown_filter"] {
        let bytes=serde_json::to_vec(&json!({"schema":1,"defaultTarget":"direct","rules":[{"match":{field:"test"},"target":"proxy"}]})).unwrap();
        assert!(matches!(Policy::parse(&bytes),Err(Error::Unsupported)));
    }
    for bytes in [
        br#"{"schema":1,"defaultTarget":"proxy","defaultTarget":"direct","rules":[]}"#.as_slice(),
        br#"{"schema":1,"defaultTarget":"proxy","rules":[{"target":"direct","match":{"port":80,"port":443}}]}"#,
    ] {assert!(matches!(Policy::parse(bytes),Err(Error::Document)));}
}
#[test]
fn invalid_network_values_empty_conditions_and_bad_cidrs_cannot_be_admitted() {
    for matcher in [json!({}),json!({"network":"sctp"}),json!({"domain_suffix":""}),json!({"ip_cidr":"192.0.2.1/33"}),json!({"ip_cidr":"::/129"}),json!({"port_range":"443:80"}),json!({"port":65536}),json!({"domain_regex":"["}),json!({"type":"logical","mode":"or","rules":[]})] {
        let bytes=serde_json::to_vec(&json!({"schema":1,"defaultTarget":"proxy","rules":[{"match":matcher,"target":"direct"}]})).unwrap();assert!(Policy::parse(&bytes).is_err());
    }
}

#[test]
fn destination_domain_and_ip_are_alternatives_but_ports_are_conjunctive() {
    let p=policy(json!([{ "match":{"domain":"different.example","ip_cidr":"192.0.2.0/24","port":443},"target":"direct"}]),"block");
    assert!(p.decide(&flow())==Ok(Target::Direct));
    let mut f=flow();f.destination="203.0.113.1".parse().unwrap();
    assert!(p.decide(&f)==Ok(Target::Block));
    f.domain=Observed::Known(Some("different.example"));assert!(p.decide(&f)==Ok(Target::Direct));
    f.port=80;assert!(p.decide(&f)==Ok(Target::Block));
}

#[test]
fn exact_suffix_keyword_and_regex_share_one_destination_group() {
    let p=policy(json!([{ "match":{"domain":"other.test","domain_suffix":"example.org","domain_keyword":"keyword","domain_regex":"^regex\\.test$"},"target":"direct"}]),"proxy");
    for domain in ["other.test","a.example.org","has-keyword.test","regex.test"] {
        let mut f=flow();f.domain=Observed::Known(Some(domain));assert!(p.decide(&f)==Ok(Target::Direct));
    }
    let mut f=flow();f.domain=Observed::Known(Some("unmatched.test"));assert!(p.decide(&f)==Ok(Target::Tunnel));
}

#[test]
fn exact_and_range_ports_are_alternatives_in_their_own_groups() {
    let p=policy(json!([{ "match":{"port":80,"port_range":"440:450","source_port":123,"source_port_range":"45000:"},"target":"direct"}]),"block");
    assert!(p.decide(&flow())==Ok(Target::Direct));
    let mut f=flow();f.port=80;f.source_port=123;assert!(p.decide(&f)==Ok(Target::Direct));
    f.source_port=124;assert!(p.decide(&f)==Ok(Target::Block));
    f.source_port=123;f.port=81;assert!(p.decide(&f)==Ok(Target::Block));
}

#[test]
fn group_inversion_applies_after_the_complete_default_rule() {
    let p=policy(json!([{ "match":{"domain":"other.test","ip_cidr":"192.0.2.0/24","port":443,"invert":true},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
    let mut f=flow();f.port=80;assert!(p.decide(&f)==Ok(Target::Direct));
    f.port=443;f.destination="203.0.113.1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Direct));
}

#[test]
fn pending_inspection_cannot_skip_a_blocking_rule_into_direct_fallback() {
    let p=policy(json!([{ "match":{"domain_suffix":"example.org"},"target":"block"}]),"direct");
    let mut f=flow();f.domain=Observed::Pending;
    assert!(p.decide(&f)==Err(Error::MissingDomain));
    f.domain=Observed::Known(None);assert!(p.decide(&f)==Ok(Target::Direct));
    f.domain=Observed::Known(Some("www.example.org"));assert!(p.decide(&f)==Ok(Target::Block));
}

#[test]
fn pending_domain_and_protocol_remain_unresolved_under_inversion() {
    for (key,error) in [("domain",Error::MissingDomain),("protocol",Error::MissingProtocol)] {
        let p=policy(json!([{ "match":{key:"tls","invert":true},"target":"direct"}]),"proxy");
        let mut f=flow();f.domain=Observed::Pending;f.protocol=Observed::Pending;
        assert!(p.decide(&f)==Err(error));
    }
}

#[test]
fn known_group_result_can_resolve_an_unrelated_pending_inspection() {
    // The IP alternative already proves the destination group true.
    let p=policy(json!([{ "match":{"domain":"unobserved.test","ip_cidr":"192.0.2.0/24"},"target":"direct"}]),"block");
    let mut f=flow();f.domain=Observed::Pending;
    assert!(p.decide(&f)==Ok(Target::Direct));
    // The destination port proves the complete AND rule false regardless of DNS.
    let p=policy(json!([{ "match":{"domain":"unobserved.test","port":80},"target":"block"}]),"proxy");
    assert!(p.decide(&f)==Ok(Target::Tunnel));
    // A nonmatching known IP cannot turn unresolved DNS into a negative result.
    f.destination="203.0.113.1".parse().unwrap();
    let p=policy(json!([{ "match":{"domain":"unobserved.test","ip_cidr":"192.0.2.0/24"},"target":"block"}]),"direct");
    assert!(p.decide(&f)==Err(Error::MissingDomain));
}

#[test]
fn private_ip_definition_matches_primary_without_cgnat_or_documentation_expansion() {
    let p=policy(json!([{ "match":{"ip_is_private":true},"target":"direct"}]),"proxy");
    for ip in ["0.0.0.0","10.1.2.3","127.1.2.3","169.254.1.2","172.16.1.1","192.168.1.1","224.0.0.1","::","::1","fc00::1","fdff::1","fe80::1","febf::1","ff02::1","::ffff:192.168.1.1"] {
        let mut f=flow();f.destination=ip.parse().unwrap();assert!(p.decide(&f)==Ok(Target::Direct),"{ip}");
    }
    for ip in ["100.64.0.1","192.0.2.1","198.18.0.1","203.0.113.1","8.8.8.8","172.15.1.1","172.32.1.1","2001:db8::1","fec0::1","::ffff:8.8.8.8"] {
        let mut f=flow();f.destination=ip.parse().unwrap();assert!(p.decide(&f)==Ok(Target::Tunnel),"{ip}");
    }
}

#[test]
fn private_and_cidr_selectors_are_alternatives_in_source_and_destination_groups() {
    let p=policy(json!([{ "match":{"ip_is_private":true,"ip_cidr":"192.0.2.0/24","source_ip_is_private":true,"source_ip_cidr":"198.18.0.0/15"},"target":"direct"}]),"block");
    assert!(p.decide(&flow())==Ok(Target::Direct));
    let mut f=flow();f.source="10.1.1.1".parse().unwrap();f.destination="192.168.1.1".parse().unwrap();
    assert!(p.decide(&f)==Ok(Target::Direct));
    f.source="203.0.113.1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Block));
}

#[test]
fn ipv6_version_is_a_conjunction_not_a_destination_address_alternative() {
    let p=policy(json!([{ "match":{"ip_version":6,"port":443},"target":"direct"}]),"proxy");
    assert!(p.decide(&flow())==Ok(Target::Tunnel));
    let mut f=flow();f.destination="2001:db8::1".parse().unwrap();assert!(p.decide(&f)==Ok(Target::Direct));
    f.port=80;assert!(p.decide(&f)==Ok(Target::Tunnel));
}

#[test]
fn network_and_wifi_conditions_use_one_captured_underlay_observation() {
    let p=policy(json!([{ "match":{"network_type":["wifi","ethernet"],"wifi_ssid":"Owned WiFi","wifi_bssid":"02:00:00:00:00:01","network_is_expensive":true,"network_is_constrained":true},"target":"direct"}]),"block");
    let mut f=flow();let mut n=NetworkContext{kind:"wifi",expensive:true,constrained:true,wifi_ssid:"Owned WiFi",wifi_bssid:"02:00:00:00:00:01"};
    f.underlay=Observed::Known(Some(n));assert!(p.decide(&f)==Ok(Target::Direct));
    n.kind="cellular";f.underlay=Observed::Known(Some(n));assert!(p.decide(&f)==Ok(Target::Block));
    n.kind="wifi";n.constrained=false;f.underlay=Observed::Known(Some(n));assert!(p.decide(&f)==Ok(Target::Block));
    f.underlay=Observed::Known(None);assert!(p.decide(&f)==Ok(Target::Block));
}

#[test]
fn unknown_underlay_cannot_invert_into_direct_but_a_known_false_condition_resolves_it() {
    let p=policy(json!([{ "match":{"network_type":"wifi","invert":true},"target":"direct"}]),"proxy");
    let mut f=flow();f.underlay=Observed::Pending;assert!(p.decide(&f)==Err(Error::MissingNetwork));
    let p=policy(json!([{ "match":{"network_type":"wifi","port":80},"target":"block"}]),"proxy");
    assert!(p.decide(&f)==Ok(Target::Tunnel));
}

#[test]
fn false_boolean_flags_are_disabled_not_conditions_for_the_opposite_state() {
    let p=policy(json!([{ "match":{"network_is_expensive":false,"network_is_constrained":false,"ip_is_private":false,"port":443},"target":"direct"}]),"proxy");
    let mut f=flow();f.underlay=Observed::Pending;assert!(p.decide(&f)==Ok(Target::Direct));
    for matcher in [json!({"ip_version":5}),json!({"network_type":"bluetooth"}),json!({"network_is_expensive":"false"}),json!({"ip_is_private":[]}),json!({"network_is_constrained":false})] {
        let bytes=serde_json::to_vec(&json!({"schema":1,"defaultTarget":"proxy","rules":[{"match":matcher,"target":"direct"}]})).unwrap();
        assert!(Policy::parse(&bytes).is_err());
    }
}
