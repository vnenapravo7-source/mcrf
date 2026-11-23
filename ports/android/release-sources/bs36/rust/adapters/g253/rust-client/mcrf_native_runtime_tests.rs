//! Compiled inside the original dispatcher module: actual dispatcher methods,
//! owned native routing/DNS, no kernel TUN/provider or production activation.
use super::*;
use crate::mcrf_bs_dispatch_runtime::{Runtime,Reply};
struct NoSockets;
impl crate::mcrf_bs_dns_transport::SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{panic!("owned Hosts query must never open an outbound")}}
#[tokio::test]async fn native_dispatcher_dns_origin_separation_and_shutdown_release_buffers(){
 tokio::time::timeout(Duration::from_secs(5),async{
  crate::mcrf_bs_service::configure(42,10001).unwrap();
  for carrier in [true,false]{dispatcher_dns_case(carrier).await}
 }).await.unwrap()
}
async fn dispatcher_dns_case(carrier:bool){
  let pool=PacketPool::new(32);let cancel=CancellationToken::new();let config=Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default()));let direct_ips=Arc::new(ArcSwap::from_pointee(HashSet::new()));let domains=Arc::new(crate::sni::DnsDomainMap::default());let(tx,_rx)=mpsc::channel(32);
  let relay=Arc::new(crate::direct_relay::DirectRelay::new(config.clone(),direct_ips.clone(),domains.clone(),pool.clone(),tx).unwrap());
  let dispatcher=Arc::new(Dispatcher{workers:ArcSwap::from_pointee(Vec::new()),inbound:ArrayQueue::new(32),outbound_capacity:32,tun_wake:Arc::new(Notify::new()),tun_io_lost:Arc::new(Notify::new()),cancel:cancel.clone(),tasks:tokio::sync::Mutex::new(Vec::new()),domain_routing:config,direct_ips,dns_domain_map:domains,direct_relay:Some(relay),uplink_state:Mutex::new(UplinkState::new()),pool:pool.clone(),reassembler:crate::wire_protocol::ShardedReassembler::new(),next_frag_id:std::sync::atomic::AtomicU16::new(1),termination:Arc::new(OnceLock::new()),mcrf_native_runtime:OnceLock::new()});
  let(tx,mut requests)=mpsc::unbounded_channel();let owner=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let broker=owner.clone();let responder=tokio::spawn(async move{while let Some(line)=requests.recv().await{let id=line.split_whitespace().nth(2).unwrap();broker.respond(&format!("BS_OWNER|42|{id}|10001|W10=")).unwrap();}});
  let mut document=serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[]},"controls":[{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}],"dns":{"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.9"}}],"final":"h"},"routeOptions":{},"tunInbounds":[{"type":"tun","address":["10.89.0.2/32"],"mtu":1500}],"dnsDialers":{}});
  if !carrier{
   let tag=format!("mcrf-route-{:064x}",1);
   document["policy"]=serde_json::json!({"schema":2,"defaultTarget":tag,"rules":[],"egressTags":[tag]});
   document["egressBindings"]=serde_json::json!([{"tag":tag,"port":10809,"username":"fixture-socks-user","password":"fixture-socks-pass"}]);
  }
  let sockets=Arc::new(NoSockets);let session=Arc::new(crate::mcrf_bs_session::Session::prepare(&serde_json::to_vec(&document).unwrap(),crate::mcrf_bs_session::Dependencies{generation:42,capacity:32,owner:owner.clone(),physical:1,hosts:HashMap::new(),excluded:Vec::new(),sockets:sockets.clone(),underlay:crate::mcrf_bs_dns_handler::Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets}),reply:Arc::new(Reply::new(&dispatcher))},&cancel).unwrap());
  assert_eq!(session.needs_carrier(),carrier);
  if carrier{assert!(Runtime::start(session.clone(),&[],&dispatcher).await.is_err())}
  let assigned=if carrier{vec!["192.0.2.7".parse().unwrap()]}else{Vec::new()};
  let runtime=Runtime::start(session.clone(),&assigned,&dispatcher).await.unwrap();
  let query=mcrf_bs_routing_policy::dns_wire::query(9,"owned.invalid",1).unwrap();let source:SocketAddr="10.89.0.2:45000".parse().unwrap();let destination:SocketAddr="198.19.255.53:53".parse().unwrap();let mut packet=pool.try_acquire().unwrap();assert!(crate::direct_relay::craft_udp_packet_into(source,destination,&query,packet.extend_tail(28+query.len()).unwrap()));dispatcher.dispatch(packet).await;
  let response=loop{if let Some(packet)=dispatcher.pop_inbound(){break packet}tokio::time::sleep(Duration::from_millis(1)).await};let info=mcrf_bs_routing_policy::packet::PacketInfo::read(response.as_slice()).unwrap();assert_eq!((info.source,info.destination),(destination.ip(),source.ip()));let answer=mcrf_bs_routing_policy::packet::PacketInfo::udp_payload(response.as_slice()).unwrap();assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,answer).unwrap()[0].ip,"192.0.2.9".parse::<IpAddr>().unwrap());drop(response);
  // A carrier packet without a live NAT permission is consumed. A genuine
  // local response bypasses carrier NAT through the explicit origin API.
  let make=||{let mut packet=pool.try_acquire().unwrap();assert!(crate::direct_relay::craft_udp_packet_into(destination,source,&[7],packet.extend_tail(29).unwrap()));packet};dispatcher.push_inbound(make());assert!(dispatcher.pop_inbound().is_none());dispatcher.mcrf_native_inbound(make());assert!(dispatcher.pop_inbound().is_some());
  dispatcher.shutdown().await;assert_eq!(pool.available(),32);assert!(dispatcher.termination.get().is_none());assert!(runtime.dispatch(make()).await.is_err());assert!(!session.tcp.tracker.lock().unwrap().is_full());owner.close();responder.abort();
}
