//! Test-only real Android TUN + ORIGINAL native TCP stack + ordered policy.
//! Only fixed owned DNS destinations/Hosts are admitted. No provider or upload.
use crate::{mcrf_direct_tun,mcrf_bs_network,mcrf_bs_owner,mcrf_bs_service,mcrf_bs_dns,mcrf_bs_dns_transport,mcrf_bs_dns_system,mcrf_bs_dns_plan,mcrf_bs_dns_resolver,mcrf_bs_dns_engine,mcrf_bs_dns_rpc,mcrf_bs_dns_handler,mcrf_bs_dns_ingress,mcrf_bs_dns_frontend};
use std::{sync::{Arc,atomic::{AtomicUsize,Ordering}},os::fd::{OwnedFd,FromRawFd,AsFd},io::{Read,Write,BufRead},net::{IpAddr,SocketAddr},time::Duration};
use tokio::{io::unix::AsyncFd,task::JoinSet};use tokio_util::sync::CancellationToken;
struct NoUpstreams(AtomicUsize);
impl mcrf_bs_dns_transport::SocketPolicy for NoUpstreams{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,mcrf_bs_dns_transport::Failure>{self.0.fetch_add(1,Ordering::SeqCst);Err(mcrf_bs_dns_transport::Failure::Bind)}}
fn listener(name:&str)->OwnedFd{
 assert!(name.starts_with("mcrf-native-tcp-")&&name.len()<90&&name.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'));
 let raw=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK,0)};assert!(raw>=0);let fd=unsafe{OwnedFd::from_raw_fd(raw)};
 let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;for(target,byte)in address.sun_path[1..].iter_mut().zip(name.bytes()){*target=byte as _}
 assert_eq!(unsafe{libc::bind(raw,(&address as *const libc::sockaddr_un).cast(),(std::mem::offset_of!(libc::sockaddr_un,sun_path)+1+name.len())as _)},0);assert_eq!(unsafe{libc::listen(raw,1)},0);fd
}
pub async fn run(){
 let args:Vec<_>=std::env::args().collect();assert_eq!(args.len(),4);let name=&args[1];let physical=mcrf_bs_network::handle(&args[2]).unwrap();let vpn=mcrf_bs_network::handle(&args[3]).unwrap();assert_ne!(physical,vpn);
 assert_eq!(std::env::var("MCRF_DIRECT_TUN_GENERATION").unwrap(),"42");mcrf_direct_tun::configure(name).unwrap();mcrf_bs_network::bind_network(physical).unwrap();
 let cancel=CancellationToken::new();let listener=AsyncFd::new(listener(name)).unwrap();println!("OWNED_NATIVE_TCP_FD_READY");std::io::stdout().flush().unwrap();
 let tun=mcrf_direct_tun::receive(&listener,&cancel).await.unwrap();drop(listener);let index=mcrf_bs_dns_ingress::interface_index(tun.as_fd()).unwrap();let tun=Arc::new(AsyncFd::new(tun).unwrap());
 let locals:Vec<IpAddr>=vec!["10.89.0.2".parse().unwrap(),"fd00::2".parse().unwrap()];let owner=mcrf_bs_owner::configure(42).unwrap();let policy=Arc::new(NoUpstreams(AtomicUsize::new(0)));
 let engine=Arc::new(mcrf_bs_dns_engine::Engine::new(42,physical,&serde_json::json!({"servers":[{"type":"hosts","tag":"owned","predefined":{"owned.invalid":"198.51.100.9"}}],"final":"owned","reverse_mapping":true}),&serde_json::json!({}),&serde_json::json!({}),std::collections::HashMap::new(),&locals).unwrap());
 let underlay=mcrf_bs_dns_handler::Underlay{kind:"other".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()};
 let resolve=mcrf_bs_dns_handler::handler(42,engine.clone(),owner.clone(),policy.clone(),underlay.clone(),&cancel).unwrap();
 let server=mcrf_bs_dns_frontend::start(vpn,index,&locals,1500,resolve,&cancel).unwrap();mcrf_bs_dns::configure(42,&locals,Some(locals[0]),Some(locals[1]),server.port).unwrap();
 let tracker=Arc::new(std::sync::Mutex::new(mcrf_bs_routing_policy::associations::Tracker::new(42,256).unwrap()));let started=std::time::Instant::now();
 let bundle=mcrf_bs_routing_policy::bundle::Bundle::parse(&serde_json::to_vec(&serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":[]},"controls":[{"beforeRule":0,"operation":{"action":"sniff"}},{"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}}],"dns":null,"routeOptions":{},"tunInbounds":[],"dnsDialers":{}})).unwrap()).unwrap();
 let router=Arc::new(crate::mcrf_bs_tcp_policy::PolicyRouter::new(bundle,engine.clone(),policy.clone(),underlay.clone()).unwrap());
 let pool=crate::packet::PacketPool::new(128);let(tx,mut tcp_packets)=tokio::sync::mpsc::channel(128);let config=crate::dispatcher::DomainRoutingConfig::default();assert!(!config.enabled);
 let relay=Arc::new(crate::direct_relay::DirectRelay::new(Arc::new(arc_swap::ArcSwap::from_pointee(config)),Arc::new(arc_swap::ArcSwap::from_pointee(std::collections::HashSet::new())),Arc::new(crate::sni::DnsDomainMap::default()),pool.clone(),tx).unwrap());
 let session=Arc::new(crate::mcrf_bs_tcp_dispatch::owned::Session{generation:42,tracker:tracker.clone(),clock:Arc::new(move||u64::try_from(started.elapsed().as_millis()).map_err(|_|())),owner:owner.clone(),engine:engine.clone(),sockets:policy.clone(),underlay,router,retire:Arc::new(|_|Ok(()))});
 let errors=Arc::new(AtomicUsize::new(0));let failures=errors.clone();let fatal=cancel.clone();relay.mcrf_attach_tcp_handler(session.handler(Arc::new(move|error|{eprintln!("OWNED_TCP_FLOW_ERROR {error:?}");failures.fetch_add(1,Ordering::SeqCst);fatal.cancel();}))).unwrap();
 let token=cancel.clone();let input=std::thread::spawn(move||{
  let mut input=std::io::BufReader::new(std::io::stdin());loop{let mut bytes=Vec::new();let result=(&mut input).take(16384).read_until(b'\n',&mut bytes);
   if !matches!(result,Ok(n)if n>0&&n<16384)||bytes.last()!=Some(&b'\n'){token.cancel();break}
   let Ok(line)=std::str::from_utf8(&bytes)else{token.cancel();break};let line=line.trim();if line=="OWNED_TCP_STOP"{token.cancel();break}
   if mcrf_bs_owner::control_response(line)!=Ok(true){token.cancel();break}
  }
 });
 let reflected=Arc::new(AtomicUsize::new(0));let counter=reflected.clone();let io=tun.clone();let token=cancel.clone();let mut tasks=JoinSet::new();let tcp=relay.clone();
 let write_io=tun.clone();let write_stop=cancel.clone();let write_count=reflected.clone();tasks.spawn(async move{loop{let packet=tokio::select!{biased;_=write_stop.cancelled()=>break,packet=tcp_packets.recv()=>packet.unwrap()};loop{let mut ready=write_io.writable().await.unwrap();match ready.try_io(|fd|{let mut file=fd.get_ref();file.write(packet.as_slice())}){Ok(Ok(size))=>{assert_eq!(size,packet.len());write_count.fetch_add(1,Ordering::SeqCst);break},Ok(Err(error))=>panic!("owned TCP TUN write: {error:?}"),Err(_)=>{}}}}});
 tasks.spawn(async move{let mut bytes=[0u8;65535];loop{
  let count=tokio::select!{biased;_=token.cancelled()=>break,
   result=async{loop{let mut ready=io.readable().await?;match ready.try_io(|fd|{let mut file=fd.get_ref();file.read(&mut bytes)}){Ok(result)=>break result,Err(_)=>{}}}}=>result.unwrap(),
  };assert!(count>0);let packet=&bytes[..count];let info=mcrf_bs_routing_policy::packet::PacketInfo::read(packet).unwrap();
  let public=info.destination=="198.51.100.53".parse::<IpAddr>().unwrap()||info.destination=="2001:db8::53".parse::<IpAddr>().unwrap();
  let owned=public||info.destination=="198.19.255.53".parse::<IpAddr>().unwrap()||info.destination=="fdfe:dcba:9876:dd54::53".parse::<IpAddr>().unwrap();
  if info.protocol==6{
   if owned&&info.ports.is_some_and(|(_,port)|matches!(port,53|5353)){
    let now=u64::try_from(started.elapsed().as_millis()).unwrap();let association=tracker.lock().unwrap().observe(42,now,&info).unwrap();
    let mut input=pool.try_acquire().unwrap();input.extend_tail(packet.len()).unwrap().copy_from_slice(packet);assert!(tcp.mcrf_policy_direct(&input).await);
    if association.retire_after_packet{tracker.lock().unwrap().retire(42,u64::try_from(started.elapsed().as_millis()).unwrap(),association.id).unwrap();}
   }continue
  }
  let packets=if public{mcrf_bs_dns::hijack(packet).unwrap()}else{mcrf_bs_dns::reflect(packet).unwrap()};
  if let Some(packets)=packets{for packet in packets{loop{let mut ready=io.writable().await.unwrap();match ready.try_io(|fd|{let mut file=fd.get_ref();file.write(&packet)}){Ok(Ok(size))=>{assert_eq!(size,packet.len());counter.fetch_add(1,Ordering::SeqCst);break},Ok(Err(error))=>panic!("owned TUN write failed: {:?}",error.kind()),Err(_)=>{}}}}}
 }});
 mcrf_direct_tun::adopted().unwrap();println!("OWNED_NATIVE_TCP_READY");std::io::stdout().flush().unwrap();
 tokio::time::timeout(Duration::from_secs(60),cancel.cancelled()).await.unwrap();server.close().await.unwrap();while let Some(result)=tasks.join_next().await{result.unwrap();}drop(tun);drop(relay);owner.close();input.join().unwrap();
 assert_eq!(errors.load(Ordering::SeqCst),0);assert_eq!(policy.0.load(Ordering::SeqCst),0);assert!(reflected.load(Ordering::SeqCst)>=8);println!("OWNED_NATIVE_TCP_DONE packets={}",reflected.load(Ordering::SeqCst));
}
