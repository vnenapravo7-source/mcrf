use mcrf_bs_routing_policy::{Policy,Flow,Observed,DestinationView,Target};use serde_json::{Value,json};use std::net::IpAddr;
#[test]fn matches_selected_primary_for_all_288_numeric_domain_multi_ip_and_inversion_cases(){
 let rows:Value=serde_json::from_str(include_str!("fixtures/sing-resolved-reference.json")).unwrap();let rows=rows.as_array().unwrap();assert_eq!(rows.len(),288);
 for(index,row)in rows.iter().enumerate(){
  let policy=Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":"proxy","rules":[{"match":row["rule"],"target":"block"}]})).unwrap()).unwrap();
  let f=Flow{source:"10.89.0.2".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),source_port:41234,port:443,network:"tcp",domain:Observed::Known(Some("owned.invalid")),protocol:Observed::Known(Some("tls")),packages:Some(&[]),underlay:Observed::Known(None)};
  let addresses:Vec<IpAddr>=row["addresses"].as_array().unwrap().iter().map(|v|v.as_str().unwrap().parse().unwrap()).collect();let version=row["ipVersion"].as_u64().unwrap()as u8;
  let destination=if row["domain"].as_bool().unwrap(){DestinationView::Domain{addresses:&addresses,ip_version:if version==0{None}else{Some(version)}}}else{DestinationView::Address};
  assert_eq!(policy.decide_with_destination(&f,destination).unwrap()==Target::Block,row["match"].as_bool().unwrap(),"reference case {index}: {row}");
 }
}
