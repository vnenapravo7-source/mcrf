use mcrf_bs_routing_policy::tcp_lifetime::{Frame,Lifetime,Direction::{App,Peer},Signal};
mod support;
fn frame(sequence:u32,acknowledgement:u32,flags:u8,payload:u32)->Frame{Frame{sequence,acknowledgement,flags,payload}}
#[test]fn both_fin_bytes_must_be_acknowledged_before_retirement(){
 let mut state=Lifetime::new(frame(10,0,2,0)).unwrap();assert_eq!(state.observe(App,frame(11,50,16,0)),Signal::Continue);
 assert_eq!(state.observe(App,frame(11,50,17,7)),Signal::Continue); // FIN consumes sequence 18.
 assert_eq!(state.observe(Peer,frame(50,18,17,0)),Signal::Continue); // ACK is one byte short.
 assert_eq!(state.observe(App,frame(19,51,16,0)),Signal::Continue);
 assert_eq!(state.observe(Peer,frame(51,19,16,0)),Signal::Closed);
}
#[test]fn a_half_close_stays_live_while_the_peer_sends_its_response(){
 let mut state=Lifetime::new(frame(10,0,2,0)).unwrap();state.observe(App,frame(11,50,16,0));state.observe(App,frame(11,50,17,0));
 assert_eq!(state.observe(Peer,frame(50,12,24,10_000)),Signal::Continue);assert_eq!(state.observe(App,frame(12,10_050,16,0)),Signal::Continue);
 assert_eq!(state.observe(Peer,frame(10_050,12,17,0)),Signal::Continue);assert_eq!(state.observe(App,frame(12,10_051,16,0)),Signal::Closed);
}
#[test]fn syn_retries_keep_epoch_but_tuple_reuse_after_handshake_starts_a_new_one(){
 let syn=frame(10,0,2,0);let mut state=Lifetime::new(syn).unwrap();assert_eq!(state.observe(App,syn),Signal::Continue);assert_eq!(state.observe(App,frame(20,0,2,0)),Signal::NewSyn);
 state.observe(App,frame(11,50,16,0));assert_eq!(state.observe(App,syn),Signal::NewSyn);
}
#[test]fn a_peer_reset_requires_kernel_owner_confirmation_before_revocation(){
 let mut state=Lifetime::new(frame(10,0,2,0)).unwrap();assert_eq!(state.observe(Peer,frame(500,0,4,0)),Signal::PeerResetProbe);assert_eq!(state.observe(App,frame(11,50,16,0)),Signal::Continue);assert_eq!(state.observe(App,frame(11,50,20,0)),Signal::AppReset);
}
#[test]fn fin_acknowledgements_wrap_without_early_close_on_duplicate_fin(){
 let mut state=Lifetime::new(frame(u32::MAX-2,0,2,0)).unwrap();state.observe(App,frame(u32::MAX-1,20,16,0));
 assert_eq!(state.observe(App,frame(u32::MAX-1,20,17,2)),Signal::Continue); // FIN end wraps to 1.
 assert_eq!(state.observe(App,frame(u32::MAX-1,20,17,2)),Signal::Continue);
 assert_eq!(state.observe(Peer,frame(20,0,17,0)),Signal::Continue);assert_eq!(state.observe(App,frame(1,21,16,0)),Signal::Continue);assert_eq!(state.observe(Peer,frame(21,1,16,0)),Signal::Closed);
}
#[test]fn frame_reader_keeps_original_segment_bytes_for_ipv4_and_ipv6(){
 for v6 in [false,true]{let bytes=support::packet(v6,true);let before=bytes.clone();let got=Frame::read(&bytes).unwrap();assert_eq!(got.sequence,0x01020304);assert_eq!(got.acknowledgement,0x05060708);assert_eq!(got.flags,24);assert_eq!(got.payload,7);assert_eq!(bytes,before)}
 assert!(Lifetime::new(frame(1,2,16,0)).is_err());assert!(Frame::read(&support::packet(false,false)).is_err());
}
#[test]fn frame_reader_follows_ipv4_options_ipv6_extensions_and_atomic_fragments(){
 let mut v4=support::packet(false,true);v4.splice(20..20,[1,1,1,0]);v4[0]=0x46;let size=v4.len()as u16;v4[2..4].copy_from_slice(&size.to_be_bytes());assert_eq!(Frame::read(&v4).unwrap().sequence,0x01020304);
 for extension in [60,44]{let mut v6=support::packet(true,true);v6.splice(40..40,[6,0,0,0,0,0,0,0]);v6[6]=extension;let size=(v6.len()-40)as u16;v6[4..6].copy_from_slice(&size.to_be_bytes());assert_eq!(Frame::read(&v6).unwrap().payload,7);if extension==44{v6[43]=1;assert!(Frame::read(&v6).is_err())}}
}
