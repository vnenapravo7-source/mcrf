//! Owned loopback only. No provider traffic, VK calls, accounts or speed tests.
use super::*;
use tokio::time::{timeout,Duration};

fn relay()->(DirectRelay,Arc<PacketPool>,mpsc::Receiver<PacketBuf>) {
    let pool=PacketPool::new(64);let (tx,rx)=mpsc::channel(64);
    let relay=DirectRelay::new(Arc::new(ArcSwap::from_pointee(crate::dispatcher::DomainRoutingConfig::default())),
        Arc::new(ArcSwap::from_pointee(std::collections::HashSet::new())),Arc::new(crate::sni::DnsDomainMap::default()),pool.clone(),tx).unwrap();
    (relay,pool,rx)
}
fn packet(pool:&Arc<PacketPool>,bytes:&[u8])->PacketBuf {
    let mut p=pool.try_acquire().unwrap();p.extend_tail(bytes.len()).unwrap().copy_from_slice(bytes);p
}

#[tokio::test]
async fn forced_direct_udp_uses_owned_peers_without_enabling_legacy_domain_bypass() {
    let (relay,pool,mut inbound)=relay();
    let first=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let second=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let unrelated=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let source:SocketAddr="10.66.67.2:45678".parse().unwrap();
    let mut peers=Vec::new();let mut buffer=[0;128];
    for server in [&first,&second] {
        let bytes=build_udp_packet(source,server.local_addr().unwrap(),b"owned-routing-request").unwrap();
        assert!(relay.mcrf_policy_direct(&packet(&pool,&bytes)).await);
        let (n,address)=timeout(Duration::from_secs(2),server.recv_from(&mut buffer)).await.unwrap().unwrap();
        assert_eq!(&buffer[..n],b"owned-routing-request");peers.push(address);
    }
    assert_ne!(peers[0],peers[1]);
    unrelated.send_to(b"unsolicited",peers[0]).await.unwrap();
    assert!(timeout(Duration::from_millis(150),inbound.recv()).await.is_err());
    for (server,address) in [(&first,peers[0]),(&second,peers[1])] {
        server.send_to(b"owned-routing-reply",address).await.unwrap();
        let reply=timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();
        assert_eq!(crate::sni::extract_src_addr(reply.as_slice()).unwrap(),server.local_addr().unwrap());
        assert_eq!(crate::sni::extract_dst_addr(reply.as_slice()).unwrap(),source);
        assert_eq!(&reply.as_slice()[28..],b"owned-routing-reply");
    }
}

#[tokio::test]
async fn forced_direct_tcp_reaches_only_an_owned_loopback_listener() {
    use tokio::io::{AsyncReadExt,AsyncWriteExt};
    let (relay,pool,mut inbound)=relay();
    let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let destination=listener.local_addr().unwrap();let source:SocketAddr="10.66.67.2:51001".parse().unwrap();
    let syn=build_tcp_packet(source,destination,1000,0,2,&[]).unwrap();
    assert!(relay.mcrf_policy_direct(&packet(&pool,&syn)).await);
    let syn_ack=timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();let bytes=syn_ack.as_slice();
    assert_eq!(bytes[33]&0x12,0x12);let sequence=u32::from_be_bytes(bytes[24..28].try_into().unwrap());
    let ack=build_tcp_packet(source,destination,1001,sequence.wrapping_add(1),0x10,&[]).unwrap();
    assert!(relay.mcrf_policy_direct(&packet(&pool,&ack)).await);
    let data=build_tcp_packet(source,destination,1001,sequence.wrapping_add(1),0x18,b"owned-tcp-request").unwrap();
    assert!(relay.mcrf_policy_direct(&packet(&pool,&data)).await);
    let (mut server,_)=timeout(Duration::from_secs(2),listener.accept()).await.unwrap().unwrap();
    let mut request=[0;17];timeout(Duration::from_secs(2),server.read_exact(&mut request)).await.unwrap().unwrap();assert_eq!(&request,b"owned-tcp-request");
    server.write_all(b"owned-tcp-reply").await.unwrap();
    timeout(Duration::from_secs(2),async {
        loop {
            let response=inbound.recv().await.unwrap();let bytes=response.as_slice();
            let header=(bytes[0]&15) as usize*4;let tcp_header=(bytes[header+12]>>4) as usize*4;
            if bytes.len()>header+tcp_header {
                assert_eq!(&bytes[header+tcp_header..],b"owned-tcp-reply");break
            }
        }
    }).await.unwrap();
}


#[tokio::test]
async fn captured_private_geoip_policy_selects_owned_direct_peer_and_blocks_another_port() {
    use mcrf_bs_routing_policy::Target;
    let (relay,pool,mut inbound)=relay();
    let allowed=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let denied=tokio::net::UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let source:SocketAddr="10.66.67.2:45678".parse().unwrap();
    let snapshot=serde_json::to_vec(&serde_json::json!({"schema":1,"defaultTarget":"block",
        "ruleSets":{"owned-private-geoip":{"version":3,"rules":[{"ip_cidr":["127.0.0.0/8"]}]}},
        "rules":[{"match":{"rule_set":"owned-private-geoip","port":allowed.local_addr().unwrap().port()},"target":"direct"}]})).unwrap();
    for (peer,expect_direct) in [(&allowed,true),(&denied,false)] {
        let bytes=build_udp_packet(source,peer.local_addr().unwrap(),b"captured-private-route").unwrap();
        let original=bytes.clone();
        let target=crate::mcrf_bs_routing::test_policy_packet(&snapshot,&bytes);
        assert_eq!(bytes,original);
        if expect_direct {assert!(target==Target::Direct);assert!(relay.mcrf_policy_direct(&packet(&pool,&bytes)).await)}
        else {assert!(target==Target::Block)}
    }
    let mut buffer=[0;128];let (n,address)=timeout(Duration::from_secs(2),allowed.recv_from(&mut buffer)).await.unwrap().unwrap();
    assert_eq!(&buffer[..n],b"captured-private-route");
    assert!(timeout(Duration::from_millis(100),denied.recv_from(&mut buffer)).await.is_err());
    allowed.send_to(b"captured-private-reply",address).await.unwrap();
    let reply=timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();
    assert_eq!(&reply.as_slice()[28..],b"captured-private-reply");
    assert_eq!(crate::sni::extract_dst_addr(reply.as_slice()).unwrap(),source);
}

#[cfg(unix)]
#[tokio::test]
async fn native_inspected_tcp_dns_returns_over_existing_tun_stack_without_opening_remote(){
 use std::sync::atomic::{AtomicUsize,Ordering};
 let(relay,pool,mut inbound)=relay();let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
 let destination=listener.local_addr().unwrap();let source:SocketAddr="10.66.67.2:51002".parse().unwrap();let calls=Arc::new(AtomicUsize::new(0));let counted=calls.clone();
 let handler:crate::mcrf_bs_tcp_dispatch::Handler=Arc::new(move|mut stream,local,remote,cancel|{let calls=counted.clone();Box::pin(async move{
  assert_eq!((local,remote),(source,destination));
  let captured=crate::mcrf_bs_tcp_inspect::prefix(&mut stream,Duration::from_secs(1),&cancel).await.unwrap();assert_eq!(captured.protocol,Some("dns"));assert!(captured.domain.is_none());
  // Controlled test resolver, not a substitute for production fresh ownership.
  let resolve:crate::mcrf_bs_dns_rpc::Resolver=Arc::new(move|request|{let calls=calls.clone();Box::pin(async move{calls.fetch_add(1,Ordering::SeqCst);mcrf_bs_routing_policy::dns_wire::answer(&request.query,0,&["192.0.2.77".parse().unwrap()],60).map_err(|_|1u8)})});
  crate::mcrf_bs_dns_frontend::serve_stream(&mut stream,captured.bytes,local.port(),resolve,&cancel).await.unwrap();
 })});
 relay.mcrf_attach_tcp_handler(handler.clone()).unwrap();assert!(relay.mcrf_attach_tcp_handler(handler).is_err());
 let syn=build_tcp_packet(source,destination,1000,0,2,&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&syn)).await);
 let syn_ack=timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();let sequence=u32::from_be_bytes(syn_ack.as_slice()[24..28].try_into().unwrap());
 let ack=build_tcp_packet(source,destination,1001,sequence.wrapping_add(1),0x10,&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&ack)).await);
 let query=mcrf_bs_routing_policy::dns_wire::query(99,"owned.invalid",1).unwrap();let mut framed=(query.len()as u16).to_be_bytes().to_vec();framed.extend(&query);
 let mut sent=0;for chunk in framed.chunks(9){let data=build_tcp_packet(source,destination,1001+sent,sequence.wrapping_add(1),0x18,chunk).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&data)).await);sent+=chunk.len()as u32;}
 let fin=build_tcp_packet(source,destination,1001+sent,sequence.wrapping_add(1),0x11,&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&fin)).await);
 let answer=timeout(Duration::from_secs(3),async{let mut captured=Vec::new();loop{let response=inbound.recv().await.unwrap();let bytes=response.as_slice();let header=(bytes[0]&15)as usize*4;let tcp_header=(bytes[header+12]>>4)as usize*4;captured.extend(&bytes[header+tcp_header..]);if captured.len()>=2{let size=u16::from_be_bytes([captured[0],captured[1]])as usize;if captured.len()>=size+2{break captured[2..2+size].to_vec()}}}}).await.unwrap();
 assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap()[0].ip,"192.0.2.77".parse::<IpAddr>().unwrap());assert_eq!(calls.load(Ordering::SeqCst),1);
 assert!(timeout(Duration::from_millis(100),listener.accept()).await.is_err());
}

#[cfg(unix)]
#[tokio::test]
async fn ordered_policy_owned_session_dns_and_fresh_owner_complete_one_native_packet_chain(){
 use std::sync::Mutex;use std::sync::atomic::{AtomicUsize,Ordering};use base64::Engine as _;use serde_json::json;
 struct NoSockets(AtomicUsize);impl crate::mcrf_bs_dns_transport::SocketPolicy for NoSockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:crate::mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,crate::mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(crate::mcrf_bs_dns_transport::Failure::Bind)}}
 let checks=Arc::new(AtomicUsize::new(0));let errors=Arc::new(Mutex::new(Vec::new()));let progress=AtomicUsize::new(0);
 let result=timeout(Duration::from_secs(6),async{
  let(relay,pool,mut inbound)=relay();let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let destination=listener.local_addr().unwrap();let source:SocketAddr="10.66.67.2:51003".parse().unwrap();
  let tracker=Arc::new(Mutex::new(mcrf_bs_routing_policy::associations::Tracker::new(42,8).unwrap()));let sockets=Arc::new(NoSockets(AtomicUsize::new(0)));
  let engine=Arc::new(crate::mcrf_bs_dns_engine::Engine::new(42,1,&json!({"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"192.0.2.77"}}],"final":"h","reverse_mapping":true}),&json!({}),&json!({}),std::collections::HashMap::new(),&[]).unwrap());
  let bundle=mcrf_bs_routing_policy::bundle::Bundle::parse(&serde_json::to_vec(&json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":[]},"controls":[{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}],"dns":null,"routeOptions":{},"tunInbounds":[],"dnsDialers":{}})).unwrap()).unwrap();
  let underlay=crate::mcrf_bs_dns_handler::Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()};
  let router=Arc::new(crate::mcrf_bs_tcp_policy::PolicyRouter::new(bundle,engine.clone(),sockets.clone(),underlay.clone()).unwrap());
  let(tx,mut rx)=mpsc::unbounded_channel::<String>();let owner=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());let verifier=owner.clone();let counted=checks.clone();
  // This seam answers the PRIVATE owner protocol with a controlled identity;
  // the separate Android owned-TUN fixture proves actual kernel ownership.
  let replies=tokio::spawn(async move{for _ in 0..4{let line=rx.recv().await.unwrap();let fields:Vec<_>=line.split_whitespace().collect();assert_eq!(fields[3],"6");assert_eq!(fields[4],source.ip().to_string());assert_eq!(fields[5],source.port().to_string());assert_eq!(fields[6],destination.ip().to_string());assert_eq!(fields[7],destination.port().to_string());let packages=base64::engine::general_purpose::STANDARD.encode(br#"["org.allowed"]"#);verifier.respond(&format!("BS_OWNER|42|{}|10001|{packages}",fields[2])).unwrap();counted.fetch_add(1,Ordering::SeqCst);}});
  let session=Arc::new(crate::mcrf_bs_tcp_dispatch::owned::Session{generation:42,tracker:tracker.clone(),clock:Arc::new(||Ok(1)),owner,engine:engine.clone(),sockets:sockets.clone(),underlay,router,retire:Arc::new(|_|Ok(()))});let reported=errors.clone();
  relay.mcrf_attach_tcp_handler(session.handler(Arc::new(move|error|reported.lock().unwrap().push(error)))).unwrap();
  progress.store(1,Ordering::SeqCst);
  let observe=|bytes:&[u8]|{let info=mcrf_bs_routing_policy::packet::PacketInfo::read(bytes).unwrap();tracker.lock().unwrap().observe(42,1,&info).unwrap();};
  let syn=build_tcp_packet(source,destination,1000,0,2,&[]).unwrap();observe(&syn);assert!(relay.mcrf_policy_direct(&packet(&pool,&syn)).await);
  let syn_ack=inbound.recv().await.unwrap();let sequence=u32::from_be_bytes(syn_ack.as_slice()[24..28].try_into().unwrap());progress.store(2,Ordering::SeqCst);let ack=build_tcp_packet(source,destination,1001,sequence.wrapping_add(1),0x10,&[]).unwrap();observe(&ack);assert!(relay.mcrf_policy_direct(&packet(&pool,&ack)).await);
  let query=mcrf_bs_routing_policy::dns_wire::query(71,"owned.invalid",1).unwrap();let mut framed=(query.len()as u16).to_be_bytes().to_vec();framed.extend(&query);let mut sent=0;
  for chunk in framed.chunks(7){let bytes=build_tcp_packet(source,destination,1001+sent,sequence.wrapping_add(1),0x18,chunk).unwrap();observe(&bytes);assert!(relay.mcrf_policy_direct(&packet(&pool,&bytes)).await);sent+=chunk.len()as u32;}
  let fin=build_tcp_packet(source,destination,1001+sent,sequence.wrapping_add(1),0x11,&[]).unwrap();observe(&fin);assert!(relay.mcrf_policy_direct(&packet(&pool,&fin)).await);
  progress.store(3,Ordering::SeqCst);
  let answer={let mut captured=Vec::new();loop{let response=inbound.recv().await.unwrap();let bytes=response.as_slice();let header=(bytes[0]&15)as usize*4;let tcp_header=(bytes[header+12]>>4)as usize*4;captured.extend(&bytes[header+tcp_header..]);if captured.len()>=2{let size=u16::from_be_bytes([captured[0],captured[1]])as usize;if captured.len()>=size+2{break captured[2..size+2].to_vec()}}}};
  progress.store(4,Ordering::SeqCst);assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap()[0].ip,"192.0.2.77".parse::<IpAddr>().unwrap());replies.await.unwrap();assert_eq!(checks.load(Ordering::SeqCst),4);assert_eq!(engine.domain(10001,"192.0.2.77".parse().unwrap()).unwrap(),Some("owned.invalid".into()));assert_eq!(engine.domain(10002,"192.0.2.77".parse().unwrap()).unwrap(),None);assert_eq!(sockets.0.load(Ordering::SeqCst),0);assert!(timeout(Duration::from_millis(50),listener.accept()).await.is_err());assert!(errors.lock().unwrap().is_empty());
 }).await;assert!(result.is_ok(),"owned chain timeout progress={} owner_checks={} errors={:?}",progress.load(Ordering::SeqCst),checks.load(Ordering::SeqCst),errors.lock().unwrap());
}

#[cfg(unix)]
#[tokio::test]
async fn native_handler_waits_for_ack_and_rst_during_handshake_never_falls_back(){
 use std::sync::atomic::{AtomicUsize,Ordering};
 for acknowledge in [false,true]{let(relay,pool,mut inbound)=relay();let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let destination=listener.local_addr().unwrap();let source:SocketAddr="10.66.67.2:51004".parse().unwrap();let calls=Arc::new(AtomicUsize::new(0));let started=Arc::new(tokio::sync::Notify::new());let counted=calls.clone();let ready=started.clone();
  relay.mcrf_attach_tcp_handler(Arc::new(move|_,_,_,_|{let(counted,ready)=(counted.clone(),ready.clone());Box::pin(async move{counted.fetch_add(1,Ordering::SeqCst);ready.notify_one();})})).unwrap();
  let syn=build_tcp_packet(source,destination,1000,0,2,&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&syn)).await);let syn_ack=timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();let sequence=u32::from_be_bytes(syn_ack.as_slice()[24..28].try_into().unwrap());
  assert!(timeout(Duration::from_millis(50),started.notified()).await.is_err());assert_eq!(calls.load(Ordering::SeqCst),0);
  let packet_bytes=build_tcp_packet(source,destination,1001,sequence.wrapping_add(1),if acknowledge{0x10}else{0x14},&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&packet_bytes)).await);
  if acknowledge{timeout(Duration::from_secs(1),started.notified()).await.unwrap();assert_eq!(calls.load(Ordering::SeqCst),1)}else{assert!(timeout(Duration::from_millis(50),started.notified()).await.is_err());assert_eq!(calls.load(Ordering::SeqCst),0)}
  assert!(timeout(Duration::from_millis(30),listener.accept()).await.is_err());
 }
}
#[cfg(unix)]
#[tokio::test]
async fn stop_during_native_handshake_releases_waiter_and_never_enters_handler(){
 use std::sync::atomic::{AtomicUsize,Ordering};
 struct Lifetime(Arc<tokio::sync::Notify>);impl Drop for Lifetime{fn drop(&mut self){self.0.notify_one();}}
 let(relay,pool,mut inbound)=relay();let source:SocketAddr="10.66.67.2:51005".parse().unwrap();let destination:SocketAddr="127.0.0.1:9".parse().unwrap();let released=Arc::new(tokio::sync::Notify::new());let lifetime=Arc::new(Lifetime(released.clone()));let weak=Arc::downgrade(&lifetime);let calls=Arc::new(AtomicUsize::new(0));let counted=calls.clone();
 relay.mcrf_attach_tcp_handler(Arc::new(move|_,_,_,_|{let held=lifetime.clone();let counted=counted.clone();Box::pin(async move{let _held=held;counted.fetch_add(1,Ordering::SeqCst);})})).unwrap();
 let syn=build_tcp_packet(source,destination,1000,0,2,&[]).unwrap();assert!(relay.mcrf_policy_direct(&packet(&pool,&syn)).await);timeout(Duration::from_secs(2),inbound.recv()).await.unwrap().unwrap();drop(relay);timeout(Duration::from_secs(1),released.notified()).await.unwrap();assert!(weak.upgrade().is_none());assert_eq!(calls.load(Ordering::SeqCst),0);
}
