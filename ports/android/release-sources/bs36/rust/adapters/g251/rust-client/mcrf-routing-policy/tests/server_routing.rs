use mcrf_bs_routing_policy::{server_routing::{compile,State,Mode},bundle::Bundle,pipeline::{Pipeline,Instruction,Kind},Flow,Observed,Target,DestinationView};
use serde_json::{json,Value};
use std::sync::Arc;
fn bytes(value:Value)->Vec<u8>{serde_json::to_vec(&value).unwrap()}
fn flow<'a>(domain:Option<&'a str>,packages:&'a [String])->Flow<'a>{Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.1".parse().unwrap(),source_port:45000,port:443,network:"tcp",domain:Observed::Known(domain),protocol:Observed::Known(None),packages:Some(packages),underlay:Observed::Known(None)}}
fn bundle()->Bundle{Bundle::parse(&bytes(json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[{"match":{"domain_suffix":"local.invalid"},"target":"block"}]},"dns":null,"controls":[],"routeOptions":{},"tunInbounds":[],"dnsDialers":{}}))).unwrap()}

#[test]fn complete_large_v2_policy_compiles_without_truncation(){
 let raw=bytes(json!({"version":2,"name":"fixture","mode":"blacklist","is_whitelist":false,"packages":(0..286).map(|i|format!("org.example.app{i}")).collect::<Vec<_>>(),"domains_mode":"blacklist","domains_is_whitelist":false,"domains":(0..492).map(|i|format!("d{i}.example.invalid")).collect::<Vec<_>>(),"template_domains":false,"ip_mode":"blacklist","ip_is_whitelist":false,"ips":[]}));
 assert!(raw.len()>4096);let p=compile(&raw,"").unwrap();
 assert_eq!(p.decide(&flow(Some("www.d491.example.invalid"),&[])),Ok(Target::Direct));
 assert_eq!(p.decide(&flow(Some("attacker-d491.example.invalid"),&[])),Ok(Target::Tunnel));
 assert_eq!(p.decide(&flow(None,&["org.example.app285".into()])),Ok(Target::Direct));
}
#[test]fn package_whitelist_gates_domain_and_ip_whitelists(){
 let p=compile(&bytes(json!({"mode":"whitelist","packages":["org.allowed"],"domains":["allowed.invalid"],"ip_mode":"whitelist","ips":["192.0.2.1"]})),"").unwrap();
 assert_eq!(p.decide(&flow(Some("allowed.invalid"),&["org.other".into()])),Ok(Target::Direct));
 assert_eq!(p.decide(&flow(Some("other.invalid"),&["org.allowed".into()])),Ok(Target::Tunnel));
 let mut f=flow(Some("other.invalid"),&[]);f.packages=None;
 assert!(p.decide(&f).is_err());
}
#[test]fn direct_exceptions_precede_whitelist_and_templates_keep_label_boundaries(){
 let p=compile(&bytes(json!({"domains_is_whitelist":false,"domains":["direct.invalid"],"template_domains":true,"ip_is_whitelist":true,"ips":["192.0.2.0/24"]})),"template.invalid\n").unwrap();
 assert_eq!(p.decide(&flow(Some("direct.invalid"),&[])),Ok(Target::Direct));
 assert_eq!(p.decide(&flow(Some("www.template.invalid"),&[])),Ok(Target::Direct));
 assert_eq!(p.decide(&flow(Some("eviltemplate.invalid"),&[])),Ok(Target::Tunnel));
 let p=compile(&bytes(json!({"domains_is_whitelist":true,"domains":["allowed.invalid"],"template_domains":true})),"template.invalid").unwrap();
 assert_eq!(p.decide(&flow(Some("template.invalid"),&[])),Ok(Target::Direct));
 assert_eq!(p.decide(&flow(Some("allowed.invalid"),&[])),Ok(Target::Tunnel));
}
#[test]fn local_priority_validates_server_without_overriding_local(){
 let state=Arc::new(State::new(false));state.update(&bytes(json!({"domains":["local.invalid"]})),"").unwrap();
 let pipeline=Pipeline::new(bundle()).unwrap().with_server(state.clone());
 assert!(matches!(pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,pipeline.start()).unwrap(),Instruction::Route(Target::Block)));
 assert!(state.update(br#"{"domains":[7]}"#,"").is_err());
}
#[test]fn publication_is_atomic_duplicates_stable_and_clear_restores_local(){
 let state=Arc::new(State::with_mode(Mode::ServerFirst));let raw=bytes(json!({"domains":["local.invalid"]}));
 let first=state.update(&raw,"").unwrap();assert_eq!(first,state.update(&raw,"").unwrap());
 assert!(state.update(&raw[..raw.len()-1],"").is_err());assert_eq!(first,state.revision().unwrap());
 let pipeline=Pipeline::new(bundle()).unwrap().with_server(state.clone());
 let next=match pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,pipeline.start()).unwrap(){Instruction::Effect{operation,next}=>{assert_eq!(operation.kind,Kind::Sniff);next},_=>panic!("server domains need sniff admission")};
 assert!(matches!(pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,next).unwrap(),Instruction::Route(Target::Direct)));
 state.update(b"","").unwrap();assert!(pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,next).is_err());
 let next=match pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,pipeline.start()).unwrap(){Instruction::Effect{next,..}=>next,_=>panic!()};
 assert!(matches!(pipeline.next(&flow(Some("local.invalid"),&[]),DestinationView::Address,next).unwrap(),Instruction::Route(Target::Block)));
}
#[test]fn invalid_documents_are_rejected(){
 for raw in [br#"{"domains":["good.invalid"],"domains":[]}"#.as_slice(),br#"{"mode":"unknown"}"#,br#"{"version":3}"#,br#"{"ips":["example.invalid"]}"#,br#"{"packages":["not a package"]}"#,br#"{"domains":[7]}"#]{assert!(compile(raw,"").is_err());}
 assert!(compile(&bytes(json!({"domains":vec!["example.invalid";4097]})),"").is_err());
}

fn target(p:&Pipeline,name:&str)->Target{
 let f=flow(Some(name),&[]);let mut cursor=p.start();
 loop{match p.next(&f,DestinationView::Address,cursor).unwrap(){Instruction::Route(t)=>return t,Instruction::Effect{next,..}=>cursor=next,Instruction::ResolveNeeded{..}=>panic!("literal destination")}}
}
#[test]fn server_first_checks_rules_when_local_policy_has_no_routes(){
 let state=Arc::new(State::with_mode(Mode::ServerFirst));
 state.update(&bytes(json!({"domains":["ru-service.invalid"]})),"").unwrap();
 let mut document=bundle().document().clone();document["policy"]["rules"]=json!([]);
 let p=Pipeline::new(Bundle::parse(&serde_json::to_vec(&document).unwrap()).unwrap()).unwrap().with_server(state);
 assert_eq!(target(&p,"ru-service.invalid"),Target::Direct);
 assert_eq!(target(&p,"other.invalid"),Target::Tunnel);
}
#[test]fn four_modes_resolve_conflicts_and_keep_secondary_unmatched_rules(){
 for(mode,conflict,secondary)in [(Mode::LocalOnly,Target::Block,Target::Tunnel),(Mode::ServerOnly,Target::Direct,Target::Direct),(Mode::LocalFirst,Target::Block,Target::Direct),(Mode::ServerFirst,Target::Direct,Target::Direct)]{
  let state=Arc::new(State::with_mode(mode));state.update(&bytes(json!({"domains":["local.invalid","server.invalid"]})),"").unwrap();
  let p=Pipeline::new(bundle()).unwrap().with_server(state);
  assert_eq!(target(&p,"local.invalid"),conflict);assert_eq!(target(&p,"server.invalid"),secondary);
 }
}
#[test]fn server_only_never_falls_back_to_local_before_or_after_server_clear(){
 let state=Arc::new(State::with_mode(Mode::ServerOnly));let p=Pipeline::new(bundle()).unwrap().with_server(state.clone());
 assert_eq!(target(&p,"local.invalid"),Target::Tunnel);
 state.update(&bytes(json!({"domains":["local.invalid"]})),"").unwrap();assert_eq!(target(&p,"local.invalid"),Target::Direct);
 state.update(b"","").unwrap();assert_eq!(target(&p,"local.invalid"),Target::Tunnel);
}
#[test]fn empty_package_whitelist_is_an_explicit_scope_exclusion(){
 let state=Arc::new(State::with_mode(Mode::ServerFirst));
 state.update(&bytes(json!({"mode":"whitelist","packages":[]})),"").unwrap();
 let p=Pipeline::new(bundle()).unwrap().with_server(state);
 assert_eq!(target(&p,"local.invalid"),Target::Direct);
}

#[test]fn dns_scoped_local_sniff_does_not_hide_server_https_domain_inspection(){
 for mode in [Mode::ServerOnly,Mode::ServerFirst,Mode::LocalFirst]{
  let state=Arc::new(State::with_mode(mode));state.update(&bytes(json!({"domains":["ru-service.invalid"]})),"").unwrap();
  let mut document=bundle().document().clone();document["policy"]["rules"]=json!([]);
  document["controls"]=json!([{"beforeRule":0,"operation":{"action":"sniff","port":53}}]);
  let p=Pipeline::new(Bundle::parse(&serde_json::to_vec(&document).unwrap()).unwrap()).unwrap().with_server(state);
  let f=flow(None,&[]);
  let next=match p.next(&f,DestinationView::Address,p.start()).unwrap(){Instruction::Effect{operation,next}=>{assert_eq!(operation.kind,Kind::Sniff);next},_=>panic!("HTTPS must inspect server domains despite DNS-only local sniff")};
  assert!(p.sniff_needed(&f,DestinationView::Address,next).unwrap());
  assert_eq!(target(&p,"ru-service.invalid"),Target::Direct);
 }
}
