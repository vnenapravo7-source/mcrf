//! Bounded per-tuple packet actors. New owner/policy IO for one TCP connection
//! cannot pause the TUN reader or other flows. Raw mode never accepts a stream.
use std::{collections::HashMap,net::SocketAddr,sync::{Arc,atomic::{AtomicBool,Ordering}},time::Duration,future::Future};
use tokio::{sync::{mpsc,oneshot},task::JoinSet,time::Instant};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{packet::PacketInfo,tcp_lifetime::{Frame,Lifetime,Direction,Signal}};
use crate::{packet::PacketBuf,mcrf_bs_session::Session,mcrf_bs_raw_flow::{Admission,Lease,Probe,Wake}};
type Key=(SocketAddr,SocketAddr);
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Packet,Admission,State,Output,Cancelled}
pub trait Output:Send+Sync{
 /// Preserve the lease until the original worker queue owns the packet.
 fn raw<'a>(&'a self,lease:Arc<Lease>,packet:PacketBuf,cancel:&'a CancellationToken)->impl Future<Output=Result<(),Failure>>+Send+'a;
 /// Original native accepted-stream stack + same-session ordered executor.
 /// It must observe the packet's tracker epoch before dispatching the SYN.
 fn advanced<'a>(&'a self,packet:PacketBuf,cancel:&'a CancellationToken)->impl Future<Output=Result<(),Failure>>+Send+'a;
}
pub struct Peer{
 pub source:SocketAddr,pub destination:SocketAddr,pub epoch:u64,pub frame:Frame,
 /// Writer retains the returned guard through TUN write. A close/new SYN may
 /// remove the actor's cache, but must not revoke this already admitted frame.
 pub admitted:oneshot::Sender<Option<Arc<Lease>>>,
}
pub enum Input{App(PacketBuf),Peer(Peer)}
enum Event{App(PacketBuf),Peer(Peer)}
struct Flow{id:u64,sender:mpsc::Sender<Event>,raw:Arc<AtomicBool>}
pub const QUEUE_WAIT:Duration=Duration::from_millis(20);
#[derive(Debug)]pub enum Notice{InvalidPacket,UnknownFlow,QueueFull,Capacity,Flow(Failure),TaskFailed,ProbeUnavailable}
pub type Report=Arc<dyn Fn(Notice)+Send+Sync>;
async fn flow<O:Output+'static>(session:Arc<Session>,output:Arc<O>,mut input:mpsc::Receiver<Event>,raw_active:Arc<AtomicBool>,probe_after:Duration,report:Report,cancel:CancellationToken)->Result<(),Failure>{
 let mut lifetime:Option<Lifetime>=None;let mut lease:Option<Arc<Lease>>=None;let mut key:Option<Key>=None;let mut deadline=Instant::now()+probe_after;
 loop{
  tokio::select!{biased;
   _=cancel.cancelled()=>return Err(Failure::Cancelled),
   _=tokio::time::sleep_until(deadline)=>{
    if let Some(raw)=&lease{match raw.probe().await.map_err(|_|Failure::State)?{
     Probe::Retired=>return Ok(()),Probe::Live=>deadline=Instant::now()+probe_after,
     Probe::Unavailable=>{report(Notice::ProbeUnavailable);deadline=Instant::now()+probe_after.min(Duration::from_secs(5))},
    }}else if let Some((source,destination))=key{
     match session.tcp.owner.query(6,source,destination,&cancel).await{
      Ok(_)=>deadline=Instant::now()+probe_after,
      Err(crate::mcrf_bs_owner::Failure::Denied)=>return Ok(()),
      Err(crate::mcrf_bs_owner::Failure::Cancelled)=>return Err(Failure::Cancelled),
      Err(crate::mcrf_bs_owner::Failure::Timeout|crate::mcrf_bs_owner::Failure::Busy)=>{report(Notice::ProbeUnavailable);deadline=Instant::now()+probe_after.min(Duration::from_secs(5))},
      Err(_)=>return Err(Failure::State),
     }
    }else{return Ok(())}
   },
   event=async{match lease.clone(){Some(lease)=>lease.wait_event().await,None=>std::future::pending::<Wake>().await}}=>match event{Wake::Closed=>return Ok(()),Wake::Probe=>{deadline=deadline.min(Instant::now()+Duration::from_millis(250))}},
   event=input.recv()=>{
    let Some(event)=event else{return Ok(())};
    match event{
     Event::App(packet)=>{
      let frame=Frame::read(packet.as_slice()).map_err(|_|Failure::Packet)?;
      let signal=if let Some(raw)=&lease{raw.observe(Direction::App,frame).map_err(|_|Failure::State)?}else{match &mut lifetime{Some(state)=>state.observe(Direction::App,frame),None=>Signal::NewSyn}};
      if signal==Signal::NewSyn{
       // Drop old permission BEFORE reserving this reused tuple. The new SYN
       // gets a fresh Android owner check and cannot inherit the old route.
       raw_active.store(false,Ordering::Release);lease=None;
       lifetime=Some(Lifetime::new(frame).map_err(|_|Failure::Packet)?);
       let info=PacketInfo::read(packet.as_slice()).map_err(|_|Failure::Packet)?;let(a,b)=info.ports.ok_or(Failure::Packet)?;key=Some((SocketAddr::new(info.source,a),SocketAddr::new(info.destination,b)));
       match session.raw_flows.admit(packet.as_slice()).await.map_err(|_|Failure::Admission)?{
        Admission::Tunnel(raw)=>lease=Some(raw),Admission::Advanced|Admission::Direct=>{},Admission::Block=>return Ok(()),
       }
      }
      if let Some(raw)=&lease{
       // SYN retries/ACK/data touch the existing epoch; a changed tuple/owner
       // can never turn an old authorization into a new grant.
       // Translation refreshes the association under the same tracker lock it uses to authorize the packet.
       output.raw(raw.clone(),packet,&cancel).await?;
       if signal==Signal::NewSyn{raw_active.store(true,Ordering::Release);}
      }else{output.advanced(packet,&cancel).await?}
      deadline=Instant::now()+probe_after;
      if matches!(signal,Signal::Closed|Signal::AppReset){return Ok(())}
     },
     Event::Peer(peer)=>{
      let raw=lease.as_ref().filter(|lease|lease.proof.epoch()==peer.epoch).cloned();
      if let Some(raw)=raw{
       let current={let tracker=session.tcp.tracker.lock().map_err(|_|Failure::State)?;raw.proof.current(&tracker,(session.tcp.clock)().map_err(|_|Failure::State)?).map_err(|_|Failure::State)?};
       if !current{let _=peer.admitted.send(None);return Ok(())}
       let signal=raw.observe(Direction::Peer,peer.frame).map_err(|_|Failure::State)?;
       let _=peer.admitted.send(Some(raw));deadline=Instant::now()+probe_after;
       if signal==Signal::Closed{return Ok(())}
       if signal==Signal::PeerResetProbe{deadline=deadline.min(Instant::now()+Duration::from_millis(250))}
      }else{let _=peer.admitted.send(None);}
     },
    }
   }
  }
 }
}
/// The caller feeds one immutable session's packets/replies. Every child is
/// joined on stop. Give an already admitted raw flow a bounded chance to drain
/// before dropping for TCP retransmission. An unresolved owner must not stall
/// admission for unrelated flows.
pub async fn run<O:Output+'static>(session:Arc<Session>,output:Arc<O>,mut input:mpsc::Receiver<Input>,capacity:usize,queue:usize,probe_after:Duration,report:Report,cancel:&CancellationToken)->Result<(),Failure>{
 if !(1..=8192).contains(&capacity)||!(1..=64).contains(&queue)||probe_after<Duration::from_millis(10)||probe_after>Duration::from_secs(300){return Err(Failure::State)}
 let stopped=cancel.child_token();let mut tasks=JoinSet::<(Key,u64,Result<(),Failure>)>::new();let mut flows=HashMap::<Key,Flow>::with_capacity(capacity);let mut next=0u64;
 let complete=|result:Result<(Key,u64,Result<(),Failure>),tokio::task::JoinError>,flows:&mut HashMap<Key,Flow>|->Result<(),Failure>{match result{
  Ok((key,id,result))=>{if flows.get(&key).is_some_and(|flow|flow.id==id){flows.remove(&key);}if let Err(error)=result{let fatal=error==Failure::State;if error!=Failure::Cancelled{report(Notice::Flow(error))}if fatal{return Err(Failure::State)}}Ok(())},
  Err(_)=>{report(Notice::TaskFailed);Err(Failure::State)},
 }};
 let result=async{loop{
  tokio::select!{biased;
   _=stopped.cancelled()=>return Ok(()),
   result=tasks.join_next(),if !tasks.is_empty()=>if let Some(result)=result{complete(result,&mut flows)?},
   packet=input.recv()=>{
    let Some(packet)=packet else{return Ok(())};while let Some(result)=tasks.try_join_next(){complete(result,&mut flows)?}
    let(key,event)=match packet{
     Input::App(packet)=>{let info=match PacketInfo::read(packet.as_slice()){Ok(info)if info.protocol==6&&info.fragment.is_none()=>info,_=>{report(Notice::InvalidPacket);continue}};let(a,b)=info.ports.ok_or(Failure::Packet)?;let key=(SocketAddr::new(info.source,a),SocketAddr::new(info.destination,b));
      if !flows.contains_key(&key){if !info.tcp.is_some_and(|tcp|tcp.flags&0x17==2){report(Notice::UnknownFlow);continue}if tasks.len()>=capacity{report(Notice::Capacity);continue}
       next=next.checked_add(1).ok_or(Failure::State)?;let id=next;let(sender,receiver)=mpsc::channel(queue);let raw=Arc::new(AtomicBool::new(false));flows.insert(key,Flow{id,sender,raw:raw.clone()});let session=session.clone();let output=output.clone();let report=report.clone();let token=stopped.child_token();tasks.spawn(async move{let result=flow(session,output,receiver,raw,probe_after,report,token).await;(key,id,result)});
      }(key,Event::App(packet))
     },
     Input::Peer(peer)=>{let key=(peer.destination,peer.source);if !flows.contains_key(&key){let _=peer.admitted.send(None);continue}(key,Event::Peer(peer))},
    };
    if let Some(row)=flows.get(&key){
     match row.sender.try_send(event){
      Ok(())=>{},
      Err(mpsc::error::TrySendError::Full(event)) if matches!(&event,Event::App(_))&&row.raw.load(Ordering::Acquire)=>{
       tokio::select!{biased;
        _=stopped.cancelled()=>return Ok(()),
        outcome=row.sender.send_timeout(event,QUEUE_WAIT)=>match outcome{
         Ok(())=>{},
         Err(mpsc::error::SendTimeoutError::Timeout(_))=>report(Notice::QueueFull),
         Err(mpsc::error::SendTimeoutError::Closed(_))=>report(Notice::UnknownFlow),
        }
       }
      },
      Err(mpsc::error::TrySendError::Full(_))=>report(Notice::QueueFull),
      Err(mpsc::error::TrySendError::Closed(_))=>report(Notice::UnknownFlow),
     }
    }
   }
  }
 }}.await;
 stopped.cancel();flows.clear();tasks.abort_all();while tasks.join_next().await.is_some(){}input.close();while input.try_recv().is_ok(){}if result.is_err(){session.stop()}result
}

#[cfg(test)]mod tests{
 use super::*;use serde_json::json;use base64::Engine as _;
 mod packets{include!("mcrf-routing-policy/tests/support/mod.rs");}
 struct NoSockets;
 impl crate::mcrf_bs_dns_transport::SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{panic!("raw actor must not open a TCP connector")}}
 struct NoReply;
 impl crate::mcrf_bs_udp_dispatch::Reply for NoReply{fn write<'a>(&'a self,_:SocketAddr,_:SocketAddr,_:&'a [u8],_:&'a CancellationToken)->crate::mcrf_bs_udp_dispatch::Task<'a,()>{Box::pin(async{panic!("TCP actor cannot emit UDP")})}}
 struct Sink{session:Arc<Session>,sent:mpsc::UnboundedSender<(u64,Frame)>,delay:Duration}
 impl Output for Sink{
  fn raw<'a>(&'a self,lease:Arc<Lease>,packet:PacketBuf,_:&'a CancellationToken)->impl Future<Output=Result<(),Failure>>+Send+'a{async move{if !self.delay.is_zero(){tokio::time::sleep(self.delay).await;}let tracker=self.session.tcp.tracker.lock().unwrap();assert!(lease.proof.current(&tracker,(self.session.tcp.clock)().unwrap()).unwrap());self.sent.send((lease.proof.epoch(),Frame::read(packet.as_slice()).unwrap())).unwrap();Ok(())}}
  fn advanced<'a>(&'a self,_:PacketBuf,_:&'a CancellationToken)->impl Future<Output=Result<(),Failure>>+Send+'a{async{panic!("numeric profile must remain raw")}}
 }
 struct Fixture{session:Arc<Session>,requests:mpsc::UnboundedReceiver<String>,sent:mpsc::UnboundedReceiver<(u64,Frame)>,input:mpsc::Sender<Input>,worker:tokio::task::JoinHandle<Result<(),Failure>>,stop:CancellationToken,pool:Arc<crate::packet::PacketPool>}
 fn fixture(probe:Duration)->Fixture{fixture_with_delay(probe,Duration::ZERO,8)}
 fn fixture_with_delay(probe:Duration,delay:Duration,buffers:usize)->Fixture{
  let(tx,requests)=mpsc::unbounded_channel();let owner=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let sockets=Arc::new(NoSockets);let document=json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[]},"controls":[],"dns":{"servers":[{"type":"hosts","tag":"h"}],"final":"h"},"routeOptions":{},"tunInbounds":[],"dnsDialers":{}});
  let stop=CancellationToken::new();let session=Arc::new(Session::prepare(&serde_json::to_vec(&document).unwrap(),crate::mcrf_bs_session::Dependencies{generation:42,capacity:4,owner,physical:1,hosts:std::collections::HashMap::new(),excluded:Vec::new(),sockets:sockets.clone(),underlay:crate::mcrf_bs_dns_handler::Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets}),reply:Arc::new(NoReply)},&stop).unwrap());
  let(tx,sent)=mpsc::unbounded_channel();let output=Arc::new(Sink{session:session.clone(),sent:tx,delay});let(input,rx)=mpsc::channel(16);let current=session.clone();let token=stop.clone();let worker=tokio::spawn(async move{run(current,output,rx,4,8,probe,Arc::new(|notice|{if let Notice::TaskFailed=notice{panic!("actor task failed")}}),&token).await});Fixture{session,requests,sent,input,worker,stop,pool:crate::packet::PacketPool::new(buffers)}
 }
 fn packet(f:&Fixture,sequence:u32,acknowledgement:u32,flags:u8)->PacketBuf{
  let mut bytes=packets::packet(false,true);bytes.truncate(40);bytes[16..20].copy_from_slice(&[192,0,2,53]);bytes[2..4].copy_from_slice(&40u16.to_be_bytes());bytes[10..12].fill(0);let ip=packets::checksum(&bytes[..20]);bytes[10..12].copy_from_slice(&ip.to_be_bytes());bytes[24..28].copy_from_slice(&sequence.to_be_bytes());bytes[28..32].copy_from_slice(&acknowledgement.to_be_bytes());bytes[33]=flags;bytes[36..38].fill(0);let tcp=packets::checksum(&packets::pseudo(&bytes,20));bytes[36..38].copy_from_slice(&tcp.to_be_bytes());let mut packet=f.pool.try_acquire().unwrap();packet.extend_tail(bytes.len()).unwrap().copy_from_slice(&bytes);packet
 }
 async fn owner(f:&mut Fixture,uid:Option<u32>){let line=f.requests.recv().await.unwrap();let id=line.split_whitespace().nth(2).unwrap();let value=match uid{Some(uid)=>format!("BS_OWNER|42|{id}|{uid}|{}",base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#)),None=>format!("BS_OWNER|42|{id}|-1|")};f.session.tcp.owner.respond(&value).unwrap();}
 async fn start(f:&mut Fixture)->u64{f.input.send(Input::App(packet(f,10,0,2))).await.unwrap();owner(f,Some(10001)).await;f.sent.recv().await.unwrap().0}
 #[tokio::test]
 async fn admitted_tcp_burst_waits_for_wire_and_preserves_order(){
  tokio::time::timeout(Duration::from_secs(3),async{
   let mut f=fixture_with_delay(Duration::from_secs(30),Duration::from_millis(2),64);
   let epoch=start(&mut f).await;
   for sequence in 11..59{f.input.send(Input::App(packet(&f,sequence,50,16))).await.unwrap();}
   for sequence in 11..59{let(id,frame)=f.sent.recv().await.unwrap();assert_eq!((id,frame.sequence),(epoch,sequence));}
   assert!(f.requests.try_recv().is_err());
   f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));assert_eq!(f.pool.available(),64);
  }).await.unwrap();
 }
 fn peer(epoch:u64,frame:Frame)->(Input,oneshot::Receiver<Option<Arc<Lease>>>){let(tx,rx)=oneshot::channel();(Input::Peer(Peer{source:"192.0.2.53:53".parse().unwrap(),destination:"10.89.0.2:41234".parse().unwrap(),epoch,frame,admitted:tx}),rx)}
 async fn drained(f:&Fixture){tokio::time::timeout(Duration::from_secs(2),async{loop{let now=(f.session.tcp.clock)().unwrap();if f.session.tcp.tracker.lock().unwrap().tcp_epoch(42,now,"10.89.0.2:41234".parse().unwrap(),"192.0.2.53:53".parse().unwrap()).unwrap().is_none(){return}tokio::time::sleep(Duration::from_millis(1)).await}}).await.unwrap()}
 #[tokio::test]async fn active_raw_packets_keep_one_owner_query_and_original_sequence(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));let epoch=start(&mut f).await;for sequence in 11..111{f.input.send(Input::App(packet(&f,sequence,50,16))).await.unwrap();let(id,frame)=f.sent.recv().await.unwrap();assert_eq!(id,epoch);assert_eq!(frame.sequence,sequence)}assert!(f.requests.try_recv().is_err());f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));assert!(!f.session.tcp.tracker.lock().unwrap().is_full());let mut returned=Vec::new();while let Some(packet)=f.pool.try_acquire(){returned.push(packet)}assert_eq!(returned.len(),8);}).await.unwrap()
 }
 #[tokio::test]async fn final_ack_retires_only_after_the_tun_writer_releases_its_guard(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));let epoch=start(&mut f).await;f.input.send(Input::App(packet(&f,11,50,17))).await.unwrap();f.sent.recv().await.unwrap();let(event,reply)=peer(epoch,Frame{sequence:50,acknowledgement:12,flags:17,payload:0});f.input.send(event).await.unwrap();let guard=reply.await.unwrap().unwrap();f.input.send(Input::App(packet(&f,12,51,16))).await.unwrap();f.sent.recv().await.unwrap();tokio::task::yield_now().await;assert!(guard.proof.current(&f.session.tcp.tracker.lock().unwrap(),(f.session.tcp.clock)().unwrap()).unwrap());drop(guard);drained(&f).await;f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));}).await.unwrap()
 }
 #[tokio::test]async fn a_new_syn_gets_fresh_owner_and_rejects_old_epoch_replies(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));let old=start(&mut f).await;f.input.send(Input::App(packet(&f,11,50,16))).await.unwrap();f.sent.recv().await.unwrap();f.input.send(Input::App(packet(&f,100,0,2))).await.unwrap();owner(&mut f,Some(10002)).await;let fresh=f.sent.recv().await.unwrap().0;assert_ne!(fresh,old);let(event,reply)=peer(old,Frame{sequence:50,acknowledgement:12,flags:16,payload:0});f.input.send(event).await.unwrap();assert!(reply.await.unwrap().is_none());f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));}).await.unwrap()
 }
 #[tokio::test]async fn quiet_closed_kernel_socket_is_reaped_without_a_fixed_tcp_timeout(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_millis(10));start(&mut f).await;owner(&mut f,None).await;drained(&f).await;f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));}).await.unwrap()
 }
 #[tokio::test]async fn stop_during_pending_owner_joins_and_returns_packet_pool(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));f.input.send(Input::App(packet(&f,10,0,2))).await.unwrap();f.requests.recv().await.unwrap();f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));assert!(!f.session.tcp.tracker.lock().unwrap().is_full());let mut returned=Vec::new();while let Some(packet)=f.pool.try_acquire(){returned.push(packet)}assert_eq!(returned.len(),8);}).await.unwrap()
 }
 fn proof(f:&Fixture,epoch:u64)->mcrf_bs_routing_policy::raw_tcp::Authorization{
  let packet=packet(f,10,0,2);let info=PacketInfo::read(packet.as_slice()).unwrap();mcrf_bs_routing_policy::raw_tcp::Authorization::admit(&f.session.tcp.tracker.lock().unwrap(),42,(f.session.tcp.clock)().unwrap(),&info,epoch,10001,"192.0.2.53:53".parse().unwrap()).unwrap()
 }
 #[tokio::test]async fn synchronous_reply_guard_wakes_close_without_a_per_packet_actor_round_trip(){
  tokio::time::timeout(Duration::from_secs(5),async{
   let mut f=fixture(Duration::from_secs(30));let epoch=start(&mut f).await;let proof=proof(&f,epoch);
   f.input.send(Input::App(packet(&f,11,50,17))).await.unwrap();f.sent.recv().await.unwrap();
   let guard=f.session.raw_flows.reply(proof,Frame{sequence:50,acknowledgement:11,flags:17,payload:0}).unwrap().unwrap();drop(guard);
   f.input.send(Input::App(packet(&f,12,51,16))).await.unwrap();f.sent.recv().await.unwrap();
   let final_write=f.session.raw_flows.reply(proof,Frame{sequence:51,acknowledgement:12,flags:16,payload:0}).unwrap().unwrap();
   tokio::task::yield_now().await;assert!(final_write.proof.current(&f.session.tcp.tracker.lock().unwrap(),(f.session.tcp.clock)().unwrap()).unwrap());
   assert!(f.session.raw_flows.reply(proof,Frame{sequence:51,acknowledgement:12,flags:16,payload:0}).unwrap().is_none());drop(final_write);drained(&f).await;f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));
  }).await.unwrap()
 }
 #[tokio::test]async fn synchronous_reply_guard_does_not_authorize_a_replaced_epoch(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));let epoch=start(&mut f).await;let old=proof(&f,epoch);f.input.send(Input::App(packet(&f,11,50,16))).await.unwrap();f.sent.recv().await.unwrap();f.input.send(Input::App(packet(&f,100,0,2))).await.unwrap();owner(&mut f,Some(10002)).await;assert_ne!(f.sent.recv().await.unwrap().0,epoch);assert!(f.session.raw_flows.reply(old,Frame{sequence:50,acknowledgement:12,flags:16,payload:0}).unwrap().is_none());f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));}).await.unwrap()
 }
 #[tokio::test]async fn reset_cannot_keep_a_retired_flow_alive_through_a_pending_tun_guard(){
  tokio::time::timeout(Duration::from_secs(5),async{let mut f=fixture(Duration::from_secs(30));let epoch=start(&mut f).await;let proof=proof(&f,epoch);let frame=Frame{sequence:50,acknowledgement:11,flags:16,payload:0};let pending_write=f.session.raw_flows.reply(proof,frame).unwrap().unwrap();f.input.send(Input::App(packet(&f,11,50,20))).await.unwrap();f.sent.recv().await.unwrap();assert!(f.session.raw_flows.reply(proof,frame).unwrap().is_none());drop(pending_write);drained(&f).await;f.stop.cancel();assert_eq!(f.worker.await.unwrap(),Ok(()));}).await.unwrap()
 }
}
