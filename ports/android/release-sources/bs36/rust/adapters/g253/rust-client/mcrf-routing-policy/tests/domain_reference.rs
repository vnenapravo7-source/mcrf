use mcrf_bs_routing_policy::{Policy,Flow,Observed,Target};
use serde_json::{json,Value};
#[test]
fn domain_suffix_keyword_match_all_168_pinned_original_reference_cases(){
    let rows:Vec<Value>=serde_json::from_str(include_str!("fixtures/sing-domain-reference.json")).unwrap();assert_eq!(rows.len(),168);
    for row in rows{
        let kind=row["kind"].as_str().unwrap();let pattern=row["pattern"].as_str().unwrap();let host=row["host"].as_str().unwrap();
        let doc=json!({"schema":1,"defaultTarget":"proxy","rules":[{"match":{kind:pattern},"target":"block"}]});
        let p=Policy::parse(&serde_json::to_vec(&doc).unwrap()).unwrap();
        let f=Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),source_port:45678,port:443,network:"tcp",domain:Observed::Known(Some(host)),protocol:Observed::Pending,packages:None,underlay:Observed::Pending};
        assert!(p.decide(&f)==Ok(if row["match"].as_bool().unwrap(){Target::Block}else{Target::Tunnel}),"reference mismatch: {row}");
    }
}
#[test]
fn large_exact_and_suffix_lists_keep_label_boundaries_and_do_not_mutate_the_snapshot(){
    let patterns=(0..50_000).map(|n|format!("{n}.example.org")).collect::<Vec<_>>();
    for key in ["domain","domain_suffix"]{
        let p=Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":"proxy","rules":[{"match":{key:patterns},"target":"block"}]})).unwrap()).unwrap();
        for(host,matched)in [("49999.example.org",true),("x49999.example.org",false),("49999.example.org.attacker.test",false),("www.49999.example.org",key=="domain_suffix")]{
            let f=Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),source_port:45678,port:443,network:"tcp",domain:Observed::Known(Some(host)),protocol:Observed::Pending,packages:None,underlay:Observed::Pending};
            assert!(p.decide(&f)==Ok(if matched{Target::Block}else{Target::Tunnel}));
        }
    }
}
