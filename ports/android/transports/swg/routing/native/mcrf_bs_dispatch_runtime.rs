//! Native session orchestration on the ORIGINAL BS dispatcher. No global
//! activation, second traffic engine or fallback route. Captured local proxies are session-owned.
use std::{net::{IpAddr,SocketAddr},sync::{Arc,Weak},time::Duration};
use tokio::{sync::mpsc,task::JoinSet};use tokio_util::sync::CancellationToken;
use crate::{dispatcher::Dispatcher,packet::{PacketBuf,PacketPool},mcrf_bs_session::Session,mcrf_bs_carrier_nat::{CarrierNat,Incoming},mcrf_bs_tcp_dispatch::HandlerGroup,mcrf_bs_tcp_ingress,mcrf_bs_udp_ingress,mcrf_bs_udp_dispatch};
use mcrf_bs_routing_policy::packet::PacketInfo;
use futures::FutureExt;
#[derive(Debug,PartialEq,Eq)]pub enum Failure{Admission,Packet,QueueTcp,QueueUdp,QueueService,State,Cancelled}
pub struct Runtime{proxy:Option<crate::mcrf_bs_proxy_listener::Group>,pub session:Arc<Session>,carrier:Option<Arc<CarrierNat>>,tcp:mpsc::Sender<mcrf_bs_tcp_ingress::Input>,udp:mpsc::Sender<PacketBuf>,service:mpsc::Sender<PacketBuf>,handlers:Arc<HandlerGroup>,tasks:tokio::sync::Mutex<JoinSet<()>>,cancel:CancellationToken,dropped:std::sync::atomic::AtomicU64,dispatcher:Weak<Dispatcher>}
struct Output{session:Arc<Session>,carrier:Option<Arc<CarrierNat>>,dispatcher:Weak<Dispatcher>}
fn supervise<F:std::future::Future<Output=()>+Send+'static>(tasks:&mut JoinSet<()>,session:Arc<Session>,dispatcher:Weak<Dispatcher>,future:F){
 tasks.spawn(async move{
  let result=std::panic::AssertUnwindSafe(future).catch_unwind().await;
  // Any root pump ending retires this owned dispatcher. During ordinary
  // shutdown its cancellation is already set, so this cannot overwrite STOP.
  session.stop();if let Some(dispatcher)=dispatcher.upgrade(){dispatcher.mcrf_native_fault()}
  if result.is_err(){crate::log_error!("BS_NATIVE_TASK_PANIC")}
 });
}
impl mcrf_bs_tcp_ingress::Output for Output{
 fn raw<'a>(&'a self,lease:Arc<crate::mcrf_bs_raw_flow::Lease>,mut packet:PacketBuf,cancel:&'a CancellationToken)->impl std::future::Future<Output=Result<(),mcrf_bs_tcp_ingress::Failure>>+Send+'a{async move{
  if cancel.is_cancelled(){return Err(mcrf_bs_tcp_ingress::Failure::Cancelled)}
  self.carrier.as_ref().ok_or(mcrf_bs_tcp_ingress::Failure::State)?.outbound_raw(lease.proof,packet.as_mut_slice()).map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?;
  self.dispatcher.upgrade().ok_or(mcrf_bs_tcp_ingress::Failure::Output)?.mcrf_native_tunnel(packet).await;
  // The queued wire packet is already translated/admitted. It carries bytes,
  // not authority; do not retain closed-flow metadata in worker TX queues.
  drop(lease);Ok(())
 }}
 fn advanced<'a>(&'a self,packet:PacketBuf,cancel:&'a CancellationToken)->impl std::future::Future<Output=Result<(),mcrf_bs_tcp_ingress::Failure>>+Send+'a{async move{
  if cancel.is_cancelled(){return Err(mcrf_bs_tcp_ingress::Failure::Cancelled)}
  let info=PacketInfo::read(packet.as_slice()).map_err(|_|mcrf_bs_tcp_ingress::Failure::Packet)?;
  let association={let mut state=self.session.tcp.tracker.lock().map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?;state.observe(self.session.tcp.generation,(self.session.tcp.clock)().map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?,&info).map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?};
  if !association.retired.is_empty(){(self.session.tcp.retire)(&association.retired).map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?}
  let dispatcher=self.dispatcher.upgrade().ok_or(mcrf_bs_tcp_ingress::Failure::Output)?;let relay=dispatcher.mcrf_native_relay().ok_or(mcrf_bs_tcp_ingress::Failure::Output)?;
  // A full bounded native-stack queue drops this packet for TCP retry. It must
  // never send it through a different connector or terminate a healthy stream.
  if !relay.mcrf_policy_direct(&packet).await{crate::log_error!("BS_NATIVE_TCP_QUEUE_FULL");}
  if association.retire_after_packet{self.session.tcp.tracker.lock().map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?.retire(self.session.tcp.generation,(self.session.tcp.clock)().map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?,association.id).map_err(|_|mcrf_bs_tcp_ingress::Failure::State)?}
  Ok(())
 }}
}
pub struct Reply{dispatcher:Weak<Dispatcher>,pool:Arc<PacketPool>}
impl Reply{pub fn new(dispatcher:&Arc<Dispatcher>)->Self{Self{dispatcher:Arc::downgrade(dispatcher),pool:dispatcher.mcrf_native_pool()}}}
impl mcrf_bs_udp_dispatch::Reply for Reply{
 fn write<'a>(&'a self,source:SocketAddr,destination:SocketAddr,bytes:&'a[u8],cancel:&'a CancellationToken)->mcrf_bs_udp_dispatch::Task<'a,()>{Box::pin(async move{
  if cancel.is_cancelled(){return Err(mcrf_bs_udp_dispatch::Failure::Cancelled)}
  let mut packet=self.pool.try_acquire().ok_or(mcrf_bs_udp_dispatch::Failure::Reply)?;let size=if source.is_ipv4(){28}else{48};let target=packet.extend_tail(size+bytes.len()).map_err(|_|mcrf_bs_udp_dispatch::Failure::Reply)?;
  if !crate::direct_relay::craft_udp_packet_into(source,destination,bytes,target){return Err(mcrf_bs_udp_dispatch::Failure::Reply)}
  self.dispatcher.upgrade().ok_or(mcrf_bs_udp_dispatch::Failure::Reply)?.mcrf_native_inbound(packet);Ok(())
 })}
}
impl Runtime{
 pub async fn start(session:Arc<Session>,assigned:&[IpAddr],dispatcher:&Arc<Dispatcher>)->Result<Arc<Self>,Failure>{
  let cancel=session.cancellation();if cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let relay=dispatcher.mcrf_native_relay().ok_or(Failure::Admission)?;
  let carrier=if assigned.is_empty(){
   if session.needs_carrier()||session.tun.is_none(){return Err(Failure::Admission)}
   None
  }else{Some(session.attach_carrier(assigned).map_err(|_|Failure::Admission)?)};
  let capacity=session.capacity();let handlers=HandlerGroup::new(capacity,&cancel).map_err(|_|Failure::Admission)?;
  let stopped=cancel.clone();let failure_session=session.clone();let failure_dispatcher=Arc::downgrade(dispatcher);
  let handler=session.tcp.clone().handler(Arc::new(move|error|{crate::log_error!("BS_NATIVE_TCP_FLOW {error:?}");if stopped.is_cancelled(){return}if matches!(error,crate::mcrf_bs_tcp_dispatch::owned::Failure::Retirement){failure_session.stop();if let Some(dispatcher)=failure_dispatcher.upgrade(){dispatcher.mcrf_native_fault()}}}));
  let handler=handlers.wrap(handler,Arc::new(||crate::log_error!("BS_NATIVE_TCP_CAPACITY")));
  relay.mcrf_attach_tcp_handler(handler).map_err(|_|{session.stop();Failure::Admission})?;
  let(tcp,tcp_rx)=mpsc::channel(256);let(udp,udp_rx)=mpsc::channel(128);let(service,mut service_rx)=mpsc::channel::<PacketBuf>(128);let mut tasks=JoinSet::new();
  let output=Arc::new(Output{session:session.clone(),carrier:carrier.clone(),dispatcher:Arc::downgrade(dispatcher)});let root=session.clone();let token=cancel.clone();let report_token=token.clone();
  supervise(&mut tasks,session.clone(),Arc::downgrade(dispatcher),async move{let result=mcrf_bs_tcp_ingress::run(root.clone(),output,tcp_rx,capacity,64,Duration::from_secs(30),Arc::new(move|notice|{if !report_token.is_cancelled(){crate::log_error!("BS_NATIVE_TCP_EVENT {notice:?}")}}),&token).await;if result.is_err(){root.stop()}});
  let root=session.clone();let token=cancel.clone();let report_token=token.clone();
  supervise(&mut tasks,session.clone(),Arc::downgrade(dispatcher),async move{let result=mcrf_bs_udp_ingress::run(root.udp.clone(),udp_rx,capacity,32,Arc::new(move|notice|{if !report_token.is_cancelled(){crate::log_error!("BS_NATIVE_UDP_EVENT {notice:?}")}}),&token).await;if result.is_err(){root.stop()}});
  let root=session.clone();let translator=carrier.clone();let weak=Arc::downgrade(dispatcher);let tcp_fallback=tcp.clone();let udp_fallback=udp.clone();let token=cancel.clone();
  supervise(&mut tasks,session.clone(),Arc::downgrade(dispatcher),async move{loop{
   let mut packet=tokio::select!{biased;_=token.cancelled()=>return,packet=service_rx.recv()=>match packet{Some(packet)=>packet,None=>return}};
   let decision=match root.service.admit(packet.as_slice()).await{Ok(value)=>value,Err(_)if token.is_cancelled()=>return,Err(error)=>{crate::log_error!("BS_NATIVE_SERVICE_ERROR {error:?}");root.stop();return}};
   match decision{
    crate::mcrf_bs_service_ingress::Decision::Drop=>{},
    crate::mcrf_bs_service_ingress::Decision::NotService=>{let result=match PacketInfo::read(packet.as_slice()){Ok(info)if info.protocol==6=>tcp_fallback.try_send(mcrf_bs_tcp_ingress::Input::App(packet)).map_err(|_|()),Ok(info)if info.protocol==17=>udp_fallback.try_send(packet).map_err(|_|()),_=>Err(())};if result.is_err(){crate::log_error!("BS_NATIVE_SERVICE_ADMISSION_QUEUE")}},
    crate::mcrf_bs_service_ingress::Decision::Forward(proof)=>{
     let Some(translator)=&translator else{root.stop();return};
     match translator.outbound(proof,packet.as_mut_slice()){Ok(())=>{},Err(crate::mcrf_bs_carrier_nat::Failure::Translation(mcrf_bs_routing_policy::service_nat::Error::Denied))=>continue,Err(_)if token.is_cancelled()=>return,Err(error)=>{crate::log_error!("BS_NATIVE_SERVICE_NAT {error:?}");root.stop();return}}
     let Some(dispatcher)=weak.upgrade()else{root.stop();return};dispatcher.mcrf_native_tunnel(packet).await;
    },
   }
  }});
  let plans=session.listeners.lock().map_err(|_|Failure::State)?.take().ok_or(Failure::State)?;
  let proxy=if plans.is_empty(){None}else{
   let group=match crate::mcrf_bs_proxy_listener::Group::start(session.tcp.generation,plans,capacity.min(64),crate::mcrf_bs_proxy_backend::mixed_handler(session.tcp.clone(),session.udp.clone()),Arc::new(|event|crate::log_error!("BS_NATIVE_PROXY_EVENT {event:?}")),&cancel){Ok(group)=>group,Err(_)=>{
    session.stop();handlers.close().await;let deadline=tokio::time::Instant::now()+Duration::from_secs(3);
    while !tasks.is_empty(){if tokio::time::timeout_at(deadline,tasks.join_next()).await.is_err(){tasks.abort_all();while tasks.join_next().await.is_some(){}break}}
    return Err(Failure::Admission)
   }};
   let stopped=group.cancellation();supervise(&mut tasks,session.clone(),Arc::downgrade(dispatcher),async move{stopped.cancelled().await});Some(group)
  };
  let runtime=Arc::new(Self{proxy,session,carrier,tcp,udp,service,handlers,tasks:tokio::sync::Mutex::new(tasks),cancel,dropped:std::sync::atomic::AtomicU64::new(0),dispatcher:Arc::downgrade(dispatcher)});
  if dispatcher.mcrf_attach_native_runtime(runtime.clone()).is_err(){let _=runtime.close().await;return Err(Failure::Admission)}Ok(runtime)
 }
 /// TUN-origin only. Pressure/unsupported input is consumed, never rerouted.
 pub async fn consume(&self,packet:PacketBuf){if let Err(error)=self.dispatch(packet).await{self.reject(error)}}
 pub fn reject(&self,error:Failure){
  if error==Failure::State{self.session.stop();if let Some(dispatcher)=self.dispatcher.upgrade(){dispatcher.mcrf_native_fault()}}
  if error==Failure::Cancelled{return}
  let count=self.dropped.fetch_add(1,std::sync::atomic::Ordering::Relaxed)+1;if count<=8||count%1000==0{crate::log_error!("BS_NATIVE_PACKET_DROP {error:?} count={count}")}
 }
 pub async fn dispatch(&self,packet:PacketBuf)->Result<(),Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let info=PacketInfo::read(packet.as_slice()).map_err(|_|Failure::Packet)?;if info.fragment.is_some(){return Err(Failure::Packet)}
  if self.session.service.may_handle(&info).map_err(|_|Failure::State)?{return self.service.try_send(packet).map_err(|_|Failure::QueueService)}
  match info.protocol{
   6=>match self.tcp.try_send(mcrf_bs_tcp_ingress::Input::App(packet)){
    Ok(())=>Ok(()),
    Err(mpsc::error::TrySendError::Closed(_))=>Err(Failure::State),
    Err(mpsc::error::TrySendError::Full(event))=>tokio::select!{biased;
     _=self.cancel.cancelled()=>Err(Failure::Cancelled),
     result=self.tcp.send_timeout(event,mcrf_bs_tcp_ingress::QUEUE_WAIT)=>match result{
      Ok(())=>Ok(()),
      Err(mpsc::error::SendTimeoutError::Timeout(_))=>Err(Failure::QueueTcp),
      Err(mpsc::error::SendTimeoutError::Closed(_))=>Err(Failure::State),
     },
    },
   },
   17=>self.udp.try_send(packet).map_err(|_|Failure::QueueUdp),
   _=>Err(Failure::Packet),
  }
 }
 /// Carrier-origin only. Native local replies MUST use native_inbound instead.
 pub fn incoming(&self,mut packet:PacketBuf)->Result<Option<PacketBuf>,Failure>{
  if self.cancel.is_cancelled(){return Err(Failure::Cancelled)}
  let carrier=self.carrier.as_ref().ok_or(Failure::Packet)?;
  let translated=carrier.incoming(packet.as_mut_slice()).map_err(|error|match error{crate::mcrf_bs_carrier_nat::Failure::Cancelled=>Failure::Cancelled,crate::mcrf_bs_carrier_nat::Failure::Translation(_)=>Failure::Packet,_=>Failure::State})?;
  match translated{
   None=>{crate::mcrf_bs_perf::CARRIER_REJECTS.fetch_add(1,std::sync::atomic::Ordering::Relaxed);Ok(None)},Some(Incoming::Service(_))=>Ok(Some(packet)),Some(Incoming::Raw(proof))=>{
    let frame=mcrf_bs_routing_policy::tcp_lifetime::Frame::read(packet.as_slice()).map_err(|_|Failure::Packet)?;
    let Some(guard)=self.session.raw_flows.reply(proof,frame).map_err(|_|Failure::State)?else{
     crate::mcrf_bs_perf::CARRIER_REJECTS.fetch_add(1,std::sync::atomic::Ordering::Relaxed);return Ok(None)
    };packet.mcrf_keep_raw(guard).map_err(|_|Failure::State)?;Ok(Some(packet))
   },
  }
 }
 pub async fn close(&self)->Result<(),Failure>{
  self.cancel.cancel();self.session.stop();crate::mcrf_bs_bootstrap::close_health().await;let proxy_failed=if let Some(proxy)=&self.proxy{proxy.close().await.is_err()}else{false};self.handlers.close().await;
  // Let each manager cancel/join its own flow actors and drain packet queues.
  // Aborting a manager first would only schedule its children for cancellation.
  let mut tasks=self.tasks.lock().await;let deadline=tokio::time::Instant::now()+Duration::from_secs(3);let mut failed=proxy_failed;
  while !tasks.is_empty(){match tokio::time::timeout_at(deadline,tasks.join_next()).await{
   Ok(Some(Ok(())))=>{},Ok(Some(Err(error)))=>{if !error.is_cancelled(){failed=true}},Ok(None)=>break,
   Err(_)=>{failed=true;tasks.abort_all();while tasks.join_next().await.is_some(){}break},
  }}
  if failed{Err(Failure::State)}else{Ok(())}
 }
}
impl Drop for Runtime{fn drop(&mut self){self.cancel.cancel();self.session.stop();}}
