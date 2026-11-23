use mcrf_bs_routing_policy::{associations::{Tracker,AssociationError as E},packet::{PacketInfo,TcpControl}};
fn packet(protocol:u8)->PacketInfo {
    PacketInfo{source:"198.18.0.1".parse().unwrap(),destination:"192.0.2.42".parse().unwrap(),protocol,
        ports:Some((45678,443)),fragment:None,tcp:if protocol==6{Some(TcpControl{sequence:100,flags:2})}else{None}}
}
#[test]fn owned_udp_uses_runtime_lifetime_without_eviction_or_inheriting_a_changed_uid(){
 let mut t=Tracker::new(42,1).unwrap();let p=packet(17);let first=t.observe(42,0,&p).unwrap();
 t.confirm_owner(42,0,&p,first.id,10001,vec![]).unwrap();
 let source=std::net::SocketAddr::new(p.source,p.ports.unwrap().0);let destination=std::net::SocketAddr::new(p.destination,p.ports.unwrap().1);
 t.own_udp(42,0,source,destination,first.id).unwrap();assert_eq!(t.udp_identity(42,300_000,source,destination),Ok(Some(first.id)));
 let mut other=p;other.ports=Some((45679,443));assert!(matches!(t.observe(42,300_000,&other),Err(E::Capacity)));
 assert_eq!(t.observe(42,300_000,&p).unwrap().id,first.id);
 let changed=t.confirm_owner(42,300_001,&p,first.id,10002,vec![]).unwrap();assert_ne!(changed.id,first.id);
 assert_eq!(t.udp_identity(42,330_001,source,destination),Ok(None));
 t.retire(42,330_001,changed.id).unwrap();assert!(t.observe(42,330_001,&other).is_ok());
}
#[test]fn owned_raw_tcp_is_not_an_accepted_stream_and_a_new_syn_invalidates_it(){
 let mut tracker=Tracker::new(42,1).unwrap();let mut p=packet(6);let source=std::net::SocketAddr::new(p.source,45678);let destination=std::net::SocketAddr::new(p.destination,443);
 let id=tracker.observe(42,0,&p).unwrap().id;assert!(tracker.own_raw_tcp(42,0,source,destination,id).is_err());tracker.confirm_owner(42,0,&p,id,10001,vec![]).unwrap();tracker.own_raw_tcp(42,0,source,destination,id).unwrap();
 assert_eq!(tracker.tcp_epoch(42,300_000,source,destination),Ok(Some(id)));assert_eq!(tracker.observe(42,300_000,&p).unwrap().id,id);
 p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,300_001,&p).unwrap();assert_eq!(tracker.tcp_candidate(42,300_001,source,destination),Ok(None));assert!(tracker.pin_tcp(42,300_001,source,destination,id).is_err());
 p.tcp.as_mut().unwrap().flags=2;let next=tracker.observe(42,300_002,&p).unwrap();assert_ne!(next.id,id);assert_eq!(next.retired,vec![id]);tracker.retire(42,300_002,id).unwrap();assert_eq!(tracker.tcp_epoch(42,300_002,source,destination),Ok(Some(next.id)));
}
#[test]fn a_missing_owner_remains_retryable_and_a_confirmed_cached_tcp_owner_uses_the_fast_path(){
    let mut t=Tracker::new(42,4).unwrap();let p=packet(6);assert!(t.needs_owner(42,0,&p));
    let association=t.observe(42,0,&p).unwrap();assert!(t.needs_owner(42,1,&p));
    t.confirm_owner(42,1,&p,association.id,10001,vec![]).unwrap();assert!(!t.needs_owner(42,2,&p));
    assert!(t.needs_owner(42,2,&packet(17)));assert!(t.needs_owner(43,2,&p));
}
#[test]fn a_syn_reusing_the_old_initial_sequence_after_establishment_is_still_a_new_connection(){
    let mut t=Tracker::new(42,4).unwrap();let mut p=packet(6);let first=t.observe(42,0,&p).unwrap();
    p.tcp.as_mut().unwrap().flags=16;t.observe(42,1,&p).unwrap();p.tcp.as_mut().unwrap().flags=2;
    let next=t.observe(42,2,&p).unwrap();assert_ne!(first.id,next.id);assert_eq!(next.retired,vec![first.id]);
}
#[test]fn udp_uid_changes_and_shared_uid_package_changes_invalidate_the_previous_decision(){
    let mut t=Tracker::new(42,4).unwrap();let p=packet(17);let first=t.observe(42,0,&p).unwrap();
    let own=t.confirm_owner(42,0,&p,first.id,10001,vec!["org.example.one".into()]).unwrap();assert_eq!(own.id,first.id);
    let next=t.confirm_owner(42,1,&p,first.id,10002,vec!["org.example.two".into()]).unwrap();
    assert_ne!(next.id,first.id);assert_eq!(next.retired,vec![first.id]);assert!(t.owner(&p,first.id).is_none());
    assert!(matches!(t.confirm_owner(42,2,&p,first.id,10001,vec![]),Err(E::Stale)));
    let extra=t.confirm_owner(42,3,&p,next.id,10002,vec!["org.example.two".into(),"org.example.shared".into()]).unwrap();
    assert_ne!(extra.id,next.id);assert_eq!(t.owner(&p,extra.id).unwrap().0,10002);
    let same=t.confirm_owner(42,4,&p,extra.id,10002,vec!["org.example.shared".into(),"org.example.two".into()]).unwrap();assert_eq!(same.id,extra.id);
}
#[test]fn syn_retransmission_and_cached_tcp_data_keep_one_owner_association(){
    let mut t=Tracker::new(42,4).unwrap();let mut p=packet(6);
    let first=t.observe(42,0,&p).unwrap();assert!(first.created&&first.refresh_owner);
    let retry=t.observe(42,1,&p).unwrap();assert_eq!(first.id,retry.id);assert!(!retry.created&&!retry.refresh_owner);
    p.tcp.as_mut().unwrap().flags=16;p.tcp.as_mut().unwrap().sequence=101;
    let data=t.observe(42,2,&p).unwrap();assert_eq!(first.id,data.id);assert!(data.retired.is_empty());
}
#[test]fn a_new_tcp_syn_on_the_same_tuple_cannot_reuse_previous_application_metadata(){
    let mut t=Tracker::new(42,4).unwrap();let mut p=packet(6);let first=t.observe(42,0,&p).unwrap();
    p.tcp.as_mut().unwrap().sequence=200;let next=t.observe(42,1,&p).unwrap();
    assert_ne!(first.id,next.id);assert!(next.created&&next.refresh_owner);assert_eq!(next.retired,vec![first.id]);
}
#[test]fn fin_does_not_retire_a_half_closed_connection_but_rst_requires_retirement_after_forwarding(){
    let mut t=Tracker::new(42,4).unwrap();let mut p=packet(6);let first=t.observe(42,0,&p).unwrap();
    p.tcp.as_mut().unwrap().flags=17;assert!(!t.observe(42,1,&p).unwrap().retire_after_packet);
    p.tcp.as_mut().unwrap().flags=20;let rst=t.observe(42,2,&p).unwrap();assert_eq!(rst.id,first.id);assert!(rst.retire_after_packet);
    t.retire(42,3,rst.id).unwrap();p.tcp.as_mut().unwrap().flags=16;
    assert_ne!(t.observe(42,4,&p).unwrap().id,first.id);
}
#[test]fn udp_owner_is_not_assumed_to_survive_socket_reuse_and_idle_creates_a_fresh_association(){
    let mut t=Tracker::new(42,4).unwrap();let p=packet(17);let first=t.observe(42,0,&p).unwrap();
    let next=t.observe(42,1,&p).unwrap();assert_eq!(first.id,next.id);assert!(next.refresh_owner);
    let expired=t.observe(42,30001,&p).unwrap();assert_ne!(first.id,expired.id);assert_eq!(expired.retired,vec![first.id]);
}
#[test]fn live_entries_are_not_evicted_and_capacity_sweeps_report_all_retired_ids(){
    let mut t=Tracker::new(42,1).unwrap();let mut p=packet(17);let first=t.observe(42,0,&p).unwrap();p.ports=Some((45679,443));
    assert!(matches!(t.observe(42,1,&p),Err(E::Capacity)));
    let new=t.observe(42,30000,&p).unwrap();assert_eq!(new.retired,vec![first.id]);assert_ne!(new.id,first.id);
}
#[test]fn generations_clock_closed_trackers_and_unassociated_fragments_are_not_accepted(){
    let mut t=Tracker::new(42,4).unwrap();let mut p=packet(6);t.observe(42,10,&p).unwrap();
    assert!(matches!(t.observe(43,11,&p),Err(E::Stale)));assert!(matches!(t.observe(42,9,&p),Err(E::Clock)));
    p.fragment=Some(mcrf_bs_routing_policy::packet::Fragment{id:1,offset_bytes:0,more:true,protocol:6});
    assert!(matches!(t.observe(42,11,&p),Err(E::Fragment)));p.fragment=None;t.close();
    assert!(matches!(t.observe(42,12,&p),Err(E::Closed)));
}

fn endpoints(p:&PacketInfo)->(std::net::SocketAddr,std::net::SocketAddr){let(a,b)=p.ports.unwrap();(std::net::SocketAddr::new(p.source,a),std::net::SocketAddr::new(p.destination,b))}
#[test]fn accepted_tcp_pin_requires_handshake_and_owner_and_survives_idle_without_eviction(){
 let mut tracker=Tracker::new(42,1).unwrap();let mut p=packet(6);let(source,destination)=endpoints(&p);let id=tracker.observe(42,0,&p).unwrap().id;
 assert_eq!(tracker.pin_tcp(42,0,source,destination,id),Err(E::Invalid));
 tracker.confirm_owner(42,0,&p,id,10001,vec![]).unwrap();assert_eq!(tracker.pin_tcp(42,0,source,destination,id),Err(E::Invalid));
 p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,1,&p).unwrap();tracker.pin_tcp(42,1,source,destination,id).unwrap();
 assert_eq!(tracker.tcp_stream_identity(42,270000,source,destination),Ok(Some(id)));assert!(!tracker.needs_owner(42,270000,&p));
 assert!(matches!(tracker.observe(42,270000,&packet(17)),Err(E::Capacity)));
 assert_eq!(tracker.observe(42,270001,&p).unwrap().id,id);
 tracker.unpin_tcp(42,270002,source,destination,id).unwrap();assert_eq!(tracker.tcp_stream_identity(42,270002,source,destination),Ok(None));
 let next=tracker.observe(42,400003,&packet(17)).unwrap();assert!(next.retired.contains(&id));
}
#[test]fn new_syn_retires_pinned_epoch_and_old_cleanup_cannot_unpin_replacement(){
 let mut tracker=Tracker::new(42,2).unwrap();let mut p=packet(6);let(source,destination)=endpoints(&p);let first=tracker.observe(42,0,&p).unwrap().id;
 tracker.confirm_owner(42,0,&p,first,10001,vec![]).unwrap();p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,1,&p).unwrap();tracker.pin_tcp(42,1,source,destination,first).unwrap();
 p.tcp.as_mut().unwrap().flags=2;let second=tracker.observe(42,2,&p).unwrap();assert!(second.retired.contains(&first));
 assert_eq!(tracker.tcp_stream_identity(42,2,source,destination),Ok(None));
 tracker.confirm_owner(42,2,&p,second.id,10001,vec![]).unwrap();p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,3,&p).unwrap();tracker.pin_tcp(42,3,source,destination,second.id).unwrap();
 assert_eq!(tracker.unpin_tcp(42,4,source,destination,first),Err(E::Stale));assert_eq!(tracker.tcp_stream_identity(42,4,source,destination),Ok(Some(second.id)));
 tracker.close();assert_eq!(tracker.tcp_stream_identity(42,4,source,destination),Err(E::Closed));
}
#[test]fn changed_kernel_owner_invalidates_pin_before_any_replacement_stream(){
 let mut tracker=Tracker::new(42,2).unwrap();let mut p=packet(6);let(source,destination)=endpoints(&p);let id=tracker.observe(42,0,&p).unwrap().id;
 tracker.confirm_owner(42,0,&p,id,10001,vec![]).unwrap();p.tcp.as_mut().unwrap().flags=16;tracker.observe(42,1,&p).unwrap();tracker.pin_tcp(42,1,source,destination,id).unwrap();
 let changed=tracker.confirm_owner(42,2,&p,id,10002,vec![]).unwrap();assert_ne!(id,changed.id);assert_eq!(tracker.tcp_stream_identity(42,2,source,destination),Ok(None));
 assert_eq!(tracker.unpin_tcp(42,2,source,destination,id),Err(E::Stale));
}

#[test]fn udp_identity_requires_confirmed_owner_live_epoch_and_correct_generation(){
 let mut tracker=Tracker::new(42,4).unwrap();let p=packet(17);let(source,destination)=(std::net::SocketAddr::new(p.source,45678),std::net::SocketAddr::new(p.destination,443));let first=tracker.observe(42,1,&p).unwrap();assert_eq!(tracker.udp_identity(42,1,source,destination),Ok(None));tracker.confirm_owner(42,1,&p,first.id,10001,vec![]).unwrap();assert_eq!(tracker.udp_identity(42,30000,source,destination),Ok(Some(first.id)));assert_eq!(tracker.udp_identity(42,30001,source,destination),Ok(None));assert_eq!(tracker.udp_identity(43,1,source,destination),Err(E::Stale));assert_eq!(tracker.udp_identity(42,0,source,destination),Err(E::Clock));tracker.retire(42,2,first.id).unwrap();assert_eq!(tracker.udp_identity(42,2,source,destination),Ok(None));
}
