//! Ordered, selected UDP metadata inspection. No sockets, routing or payload
//! forwarding here; the flow owner retains datagrams until one decision.
use crate::mcrf_bs_datagram::Datagram;
use mcrf_bs_routing_policy::inspect::{self,Inspection};
#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid}
pub struct Inspector{names:Vec<String>,quic:crate::mcrf_bs_quic_inspect::Inspector,done:Option<Inspection>}
impl Inspector{
 pub fn new(names:&[String])->Result<Self,Error>{
  if names.len()>16||names.iter().any(|name|!inspect::supports(name)){return Err(Error::Invalid)}
  let mut selected=Vec::new();for name in if names.is_empty(){inspect::UDP_SNIFFERS.iter().map(|v|v.to_string()).collect()}else{names.to_vec()}{if inspect::datagram_sniffer(&name)&&!selected.contains(&name){selected.push(name)}}
  Ok(Self{names:selected,quic:crate::mcrf_bs_quic_inspect::Inspector::new(),done:None})
 }
 pub fn feed(&mut self,bytes:&[u8])->Inspection{
  if let Some(done)=&self.done{return done.clone()}
  let mut pending=false;
  for name in &self.names{
   let result=if name=="quic"{self.quic.feed(bytes)}else{inspect::datagram(bytes,name)};
   match result{Inspection::Known{..}=>{self.done=Some(result.clone());return result},Inspection::Pending=>pending=true,Inspection::Unknown=>{}}
  }
  if pending{Inspection::Pending}else{self.done=Some(Inspection::Unknown);Inspection::Unknown}
 }
 pub fn finish(&mut self)->Inspection{
  if let Some(done)=&self.done{return done.clone()}
  let result=if self.names.iter().any(|name|name=="quic"){self.quic.finish()}else{Inspection::Unknown};self.done=Some(result.clone());result
 }
}
/// Retain original datagram boundaries and bytes for exactly-once forwarding.
/// Callers feed a bounded channel owned by one authenticated association.
#[derive(Debug,PartialEq,Eq)]pub enum CaptureError{Invalid,Cancelled,Capacity}
#[derive(Default)]pub struct Captured{pub packets:Vec<Datagram>,pub protocol:Option<&'static str>,pub domain:Option<String>}
impl Captured{
 pub fn push(&mut self,packet:impl Into<Datagram>)->Result<(),CaptureError>{
  let packet=packet.into();
  if packet.len()>65535{return Err(CaptureError::Invalid)}
  if self.packets.len()>=32||self.packets.iter().map(|p|p.len()).sum::<usize>()+packet.len()>131072{return Err(CaptureError::Capacity)}
  self.packets.push(packet);Ok(())
 }
 fn metadata(&mut self,result:Inspection){if let Inspection::Known{protocol,domain}=result{self.protocol=Some(protocol);self.domain=domain}else{self.protocol=None;self.domain=None}}
}
pub async fn capture(input:&mut tokio::sync::mpsc::Receiver<Datagram>,mut saved:Captured,names:&[String],timeout:std::time::Duration,cancel:&tokio_util::sync::CancellationToken)->Result<Captured,CaptureError>{
 if timeout.is_zero()||timeout>std::time::Duration::from_secs(5)||saved.packets.len()>32||saved.packets.iter().any(|p|p.len()>65535)||saved.packets.iter().map(|p|p.len()).sum::<usize>()>131072{return Err(CaptureError::Invalid)}
 if cancel.is_cancelled(){return Err(CaptureError::Cancelled)}
 let mut inspector=Inspector::new(names).map_err(|_|CaptureError::Invalid)?;
 // Ordered effects inspect previously captured data, without reading it again.
 for packet in &saved.packets{let result=inspector.feed(packet);if !matches!(result,Inspection::Pending){saved.metadata(result);return Ok(saved)}}
 let deadline=tokio::time::Instant::now()+timeout;
 loop{
  if saved.packets.len()>=32||saved.packets.iter().map(|p|p.len()).sum::<usize>()>=131072{saved.metadata(inspector.finish());return Ok(saved)}
  let packet=tokio::select!{biased;
   _=cancel.cancelled()=>return Err(CaptureError::Cancelled),
   result=tokio::time::timeout_at(deadline,input.recv())=>match result{Ok(Some(packet))=>packet,_=>{saved.metadata(inspector.finish());return Ok(saved)}},
  };
  saved.push(packet)?;
  let result=inspector.feed(saved.packets.last().expect("pushed packet"));
  if !matches!(result,Inspection::Pending){saved.metadata(result);return Ok(saved)}
 }
}
#[cfg(test)]mod tests{
 use super::*;
 fn known(protocol:&'static str)->Inspection{Inspection::Known{protocol,domain:None}}
 #[test]fn default_prioritizes_wireguard_over_ambiguous_utp_and_keeps_warp_reserved_bytes(){let mut packet=vec![0;148];packet[..4].copy_from_slice(&[1,0x12,0x34,0x56]);assert_eq!(Inspector::new(&[]).unwrap().feed(&packet),known("wireguard"));}
 #[test]fn explicit_packet_sniffer_order_is_not_replaced_by_default_order(){let mut packet=vec![0;148];packet[0]=1;assert_eq!(Inspector::new(&["bittorrent".into(),"wireguard".into()]).unwrap().feed(&packet),known("bittorrent"));}
 #[test]fn stream_only_selection_never_leaks_udp_metadata(){let query=mcrf_bs_routing_policy::dns_wire::query(1,"owned.invalid",1).unwrap();assert_eq!(Inspector::new(&["tls".into(),"http".into()]).unwrap().feed(&query),Inspection::Unknown);assert_eq!(Inspector::new(&[]).unwrap().feed(&query),known("dns"));}
 #[test]fn malformed_selector_is_rejected_before_flow_admission(){assert!(matches!(Inspector::new(&["unknown".into()]),Err(Error::Invalid)));}
}

#[cfg(test)]mod capture_tests{
 use super::*;use std::time::Duration;use tokio_util::sync::CancellationToken;
 #[tokio::test]async fn ordered_effects_reuse_datagrams_without_consuming_next_packet(){
  let(tx,mut rx)=tokio::sync::mpsc::channel(2);let mut packet=vec![0;148];packet[0]=1;tx.send(packet.clone().into()).await.unwrap();tx.send(vec![9].into()).await.unwrap();let stop=CancellationToken::new();
  let saved=capture(&mut rx,Captured::default(),&["dns".into()],Duration::from_millis(50),&stop).await.unwrap();assert_eq!(saved.protocol,None);
  let saved=capture(&mut rx,saved,&["wireguard".into()],Duration::from_millis(50),&stop).await.unwrap();assert_eq!(saved.protocol,Some("wireguard"));assert_eq!(saved.packets,vec![packet]);assert_eq!(rx.try_recv().unwrap(),vec![9]);
 }
 #[tokio::test]async fn no_payload_timeout_and_closed_input_finish_without_creating_bytes(){
  let(tx,mut rx)=tokio::sync::mpsc::channel(1);let stop=CancellationToken::new();let saved=capture(&mut rx,Captured::default(),&[],Duration::from_millis(10),&stop).await.unwrap();assert!(saved.packets.is_empty());drop(tx);
  let saved=capture(&mut rx,saved,&[],Duration::from_millis(10),&stop).await.unwrap();assert!(saved.packets.is_empty());assert_eq!(saved.protocol,None);
 }
 #[tokio::test]async fn zero_length_datagram_is_preserved_and_cancellation_prevents_reads(){
  let(tx,mut rx)=tokio::sync::mpsc::channel(1);tx.send(Vec::new().into()).await.unwrap();let stop=CancellationToken::new();let saved=capture(&mut rx,Captured::default(),&[],Duration::from_millis(20),&stop).await.unwrap();assert_eq!(saved.packets,vec![Vec::<u8>::new()]);tx.send(vec![7].into()).await.unwrap();stop.cancel();assert!(matches!(capture(&mut rx,saved,&[],Duration::from_millis(20),&stop).await,Err(CaptureError::Cancelled)));assert_eq!(rx.try_recv().unwrap(),vec![7]);
 }
 #[test]fn count_and_bytes_are_bounded_before_enqueue(){
  let mut saved=Captured::default();for _ in 0..32{saved.push(Vec::new()).unwrap()}assert_eq!(saved.push(Vec::new()),Err(CaptureError::Capacity));let mut saved=Captured::default();saved.push(vec![0;65535]).unwrap();saved.push(vec![0;65535]).unwrap();assert_eq!(saved.push(vec![0;3]),Err(CaptureError::Capacity));assert_eq!(saved.push(vec![0;65536]),Err(CaptureError::Invalid));
 }
}
