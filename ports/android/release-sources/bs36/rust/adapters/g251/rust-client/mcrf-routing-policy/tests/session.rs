use mcrf_bs_routing_policy::{Policy,Flow,Target,Error,Observed,NetworkContext,session::{Session,SessionError}};
use serde_json::{json,Value};

fn policy(rules:Value,fallback:&str)->Policy {
    Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":fallback,"rules":rules})).unwrap()).unwrap()
}
fn flow()->Flow<'static> {Flow{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.42".parse().unwrap(),source_port:45678,port:443,network:"tcp",domain:Observed::Known(Some("www.example.org")),protocol:Observed::Known(Some("tls")),packages:None,underlay:Observed::Pending}}
fn session(policy:Policy,capacity:usize)->Session {Session::new(42,policy,Observed::Known(None),capacity,1000).ok().unwrap()}
#[test]
fn native_association_retirement_clears_only_the_corresponding_cached_owner_decisions(){
    let p=policy(json!([{ "match":{"package_name":"org.example.app"},"target":"direct"}]),"block");
    let mut s=session(p,8);let mut f=flow();let packages=vec!["org.example.app".to_owned()];f.packages=Some(&packages);
    s.decide(42,1,0,&f).unwrap();s.decide(42,2,0,&f).unwrap();s.retire_associations(42,1,&[1]).unwrap();f.packages=None;
    assert!(matches!(s.decide(42,1,2,&f),Err(SessionError::Metadata(Error::MissingOwner))));
    assert!(s.decide(42,2,2,&f).unwrap().cached);
    assert!(matches!(s.retire_associations(42,3,&[0]),Err(SessionError::Invalid)));
}

#[test]
fn resolved_packet_decisions_are_reused_without_parsing_or_owner_lookup_again() {
    let p=policy(json!([{ "match":{"domain_suffix":"example.org"},"target":"block"}]),"direct");
    let mut s=session(p,8);let mut f=flow();
    let first=s.decide(42,1,10,&f).ok().unwrap();assert!(first.target==Target::Block&&!first.cached);
    f.domain=Observed::Pending;
    let next=s.decide(42,1,20,&f).ok().unwrap();assert!(next.target==Target::Block&&next.cached);
}

#[test]
fn pending_metadata_is_not_cached_and_never_becomes_direct_fallback() {
    let p=policy(json!([{ "match":{"package_name":"org.example.app"},"target":"block"}]),"direct");
    let mut s=session(p,1);let mut f=flow();
    assert!(matches!(s.decide(42,1,10,&f),Err(SessionError::Metadata(Error::MissingOwner))));
    let names=vec!["org.example.app".to_owned()];f.packages=Some(&names);
    let next=s.decide(42,1,20,&f).ok().unwrap();assert!(next.target==Target::Block&&!next.cached);
}

#[test]
fn generation_change_closed_session_and_rewound_clock_are_rejected_before_cached_hits() {
    let mut s=session(policy(json!([]),"direct"),8);let f=flow();
    assert!(s.decide(42,1,20,&f).is_ok());
    assert!(matches!(s.decide(43,1,21,&f),Err(SessionError::Stale)));
    assert!(matches!(s.decide(42,1,19,&f),Err(SessionError::Clock)));
    s.close();assert!(matches!(s.decide(42,1,21,&f),Err(SessionError::Closed)));
}

#[test]
fn flow_associations_and_protocols_do_not_share_cached_application_decisions() {
    let p=policy(json!([{ "match":{"package_name":"org.example.app"},"target":"direct"}]),"block");
    let mut s=session(p,8);let mut f=flow();let names=vec!["org.example.app".to_owned()];f.packages=Some(&names);
    assert!(s.decide(42,1,10,&f).ok().unwrap().target==Target::Direct);
    f.packages=None;
    assert!(matches!(s.decide(42,2,11,&f),Err(SessionError::Metadata(Error::MissingOwner))));
    f.network="udp";
    assert!(matches!(s.decide(42,1,12,&f),Err(SessionError::Metadata(Error::MissingOwner))));
    f.network="tcp";f.port=444;
    assert!(matches!(s.decide(42,1,13,&f),Err(SessionError::Metadata(Error::MissingOwner))));
}

#[test]
fn capacity_never_evicts_a_live_route_and_expired_entries_can_be_reused() {
    let mut s=session(policy(json!([]),"proxy"),1);let mut f=flow();
    assert!(s.decide(42,1,10,&f).is_ok());f.port=444;
    assert!(matches!(s.decide(42,2,11,&f),Err(SessionError::Capacity)));
    f.port=443;assert!(s.decide(42,1,20,&f).ok().unwrap().cached);
    f.port=444;assert!(s.decide(42,2,1020,&f).is_ok());
}

#[test]
fn owner_retirement_removes_the_decision_and_requires_metadata_again() {
    let p=policy(json!([{ "match":{"domain":"www.example.org"},"target":"direct"}]),"block");
    let mut s=session(p,1);let mut f=flow();assert!(s.decide(42,1,10,&f).is_ok());
    s.retire(42,1,20,&f).unwrap();f.domain=Observed::Pending;
    assert!(matches!(s.decide(42,1,21,&f),Err(SessionError::Metadata(Error::MissingDomain))));
}

#[test]
fn caller_underlay_cannot_override_the_captured_session_network() {
    let p=policy(json!([{ "match":{"network_type":"wifi"},"target":"direct"}]),"block");
    let n=NetworkContext{kind:"cellular",expensive:true,constrained:false,wifi_ssid:"",wifi_bssid:""};
    let mut s=Session::new(42,p,Observed::Known(Some(n)),8,1000).ok().unwrap();
    let mut f=flow();f.underlay=Observed::Known(Some(NetworkContext{kind:"wifi",..n}));
    assert!(s.decide(42,1,10,&f).ok().unwrap().target==Target::Block);
}

#[test]
fn invalid_identity_and_cache_limits_are_not_admitted() {
    for (generation,capacity,idle) in [(0,1,1000),(u64::MAX,1,1000),(42,0,1000),(42,8193,1000),(42,1,999),(42,1,600001)] {
        assert!(matches!(Session::new(generation,policy(json!([]),"proxy"),Observed::Known(None),capacity,idle),Err(SessionError::Invalid)));
    }
    let mut s=session(policy(json!([]),"proxy"),1);let mut f=flow();
    assert!(matches!(s.decide(42,0,10,&f),Err(SessionError::Invalid)));
    f.network="unknown";assert!(matches!(s.decide(42,1,10,&f),Err(SessionError::UnsupportedPacket)));
}

#[test]
fn session_owns_the_underlay_snapshot_after_the_capture_buffer_is_released() {
    let p=policy(json!([{ "match":{"network_type":"wifi","wifi_ssid":"Owned WiFi"},"target":"direct"}]),"block");
    let mut s={
        let kind=String::from("wifi");let ssid=String::from("Owned WiFi");
        let n=NetworkContext{kind:&kind,expensive:false,constrained:false,wifi_ssid:&ssid,wifi_bssid:""};
        Session::new(42,p,Observed::Known(Some(n)),8,1000).ok().unwrap()
    };
    assert!(s.decide(42,1,10,&flow()).ok().unwrap().target==Target::Direct);
}
