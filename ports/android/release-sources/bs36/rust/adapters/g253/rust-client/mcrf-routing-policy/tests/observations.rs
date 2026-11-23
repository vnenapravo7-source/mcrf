use mcrf_bs_routing_policy::observations::{DomainCache,DomainObservation as D,ObservationError as E};
fn ip()->std::net::IpAddr{"192.0.2.42".parse().unwrap()}
#[test]fn zero_ttl_delivery_retires_old_attribution_only_for_its_original_uid(){
    let mut c=DomainCache::new(42,4).unwrap();for uid in [10001,10002]{c.admit(42,0,uid,"old.invalid",&[ip()],5000).unwrap();}
    c.invalidate(42,1,10001,&[ip()]).unwrap();assert_eq!(c.lookup(42,1,10001,ip()),Ok(D::Missing));assert_eq!(c.lookup(42,1,10002,ip()),Ok(D::Unique("old.invalid")));
    assert_eq!(c.invalidate(43,1,10002,&[ip()]),Err(E::Stale));assert_eq!(c.lookup(42,2,10002,ip()),Ok(D::Unique("old.invalid")));
}
#[test]
fn names_are_owned_by_one_session_and_one_android_uid(){
    let mut c=DomainCache::new(42,4).unwrap();c.admit(42,0,10001,"WWW.Example.org.",&[ip()],5000).unwrap();
    assert_eq!(c.lookup(42,0,10001,ip()),Ok(D::Unique("www.example.org")));
    assert_eq!(c.lookup(42,0,10002,ip()),Ok(D::Missing));
    assert_eq!(c.lookup(43,0,10001,ip()),Err(E::Stale));
}
#[test]
fn shared_cdn_addresses_never_pick_a_first_or_last_domain(){
    let mut c=DomainCache::new(42,4).unwrap();c.admit(42,0,10001,"one.example",&[ip()],1000).unwrap();
    c.admit(42,0,10001,"two.example",&[ip()],2000).unwrap();
    assert_eq!(c.lookup(42,999,10001,ip()),Ok(D::Ambiguous));
    assert_eq!(c.lookup(42,1000,10001,ip()),Ok(D::Unique("two.example")));
    assert_eq!(c.lookup(42,2000,10001,ip()),Ok(D::Missing));
}
#[test]
fn too_many_shared_names_remain_ambiguous_until_their_ttl_expires(){
    let mut c=DomainCache::new(42,1).unwrap();
    for i in 0..9 {c.admit(42,0,10001,&format!("d{i}.example"),&[ip()],if i==8{3000}else{1000}).unwrap()}
    assert_eq!(c.lookup(42,1000,10001,ip()),Ok(D::Ambiguous));
    assert_eq!(c.lookup(42,3000,10001,ip()),Ok(D::Missing));
}
#[test]
fn capacity_does_not_evict_another_live_owners_domain_or_partially_admit_an_answer(){
    let mut c=DomainCache::new(42,1).unwrap();c.admit(42,0,10001,"one.example",&[ip()],1000).unwrap();
    let other="192.0.2.43".parse().unwrap();
    assert_eq!(c.admit(42,1,10001,"two.example",&[ip(),other],1000),Err(E::Capacity));
    assert_eq!(c.lookup(42,1,10001,ip()),Ok(D::Unique("one.example")));
    c.admit(42,1000,10002,"new.example",&[other],1000).unwrap();
    assert_eq!(c.lookup(42,1000,10001,ip()),Ok(D::Missing));
}
#[test]
fn repeated_answers_refresh_ttl_but_zero_ttl_requires_separate_flow_attribution(){
    let mut c=DomainCache::new(42,1).unwrap();c.admit(42,0,10001,"one.example",&[ip(),ip()],1000).unwrap();
    c.admit(42,500,10001,"ONE.EXAMPLE",&[ip()],1000).unwrap();
    assert_eq!(c.admit(42,500,10001,"two.example",&[ip()],0),Err(E::Uncacheable));
    assert_eq!(c.lookup(42,1000,10001,ip()),Ok(D::Unique("one.example")));
    assert_eq!(c.lookup(42,1500,10001,ip()),Ok(D::Missing));
}
#[test]
fn invalid_values_rewound_clocks_and_closed_generations_are_rejected(){
    let mut c=DomainCache::new(42,1).unwrap();
    for name in ["","a..example","bad/example","a.example..","ю.example"] {
        assert_eq!(c.admit(42,0,10001,name,&[ip()],1000),Err(E::Invalid));
    }
    assert_eq!(c.admit(42,0,u32::MAX,"a.example",&[ip()],1000),Err(E::Invalid));
    assert_eq!(c.admit(42,0,10001,"a.example",&[ip()],86_400_001),Err(E::Invalid));
    assert_eq!(c.lookup(42,10,10001,ip()),Ok(D::Missing));
    assert_eq!(c.lookup(42,9,10001,ip()),Err(E::Clock));
    c.close();assert_eq!(c.lookup(42,11,10001,ip()),Err(E::Closed));
}
