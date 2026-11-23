//! Test-only shared session Android TUN + original TCP/UDP/native socket legs.
//! Only fixed owned DNS destinations/Hosts are admitted. No provider or upload.
use crate::{mcrf_direct_tun,mcrf_bs_network,mcrf_bs_owner,mcrf_bs_service,mcrf_bs_dns,mcrf_bs_dns_transport,mcrf_bs_dns_engine,mcrf_bs_dns_handler,mcrf_bs_dns_ingress,mcrf_bs_dns_frontend};
use std::{sync::{Arc,atomic::{AtomicUsize,Ordering}},os::fd::{OwnedFd,FromRawFd,AsFd},io::{Read,Write,BufRead},net::{IpAddr,SocketAddr},time::Duration};
use tokio::{io::unix::AsyncFd,task::JoinSet};use tokio_util::sync::CancellationToken;
use crate::mcrf_bs_dns_transport::SocketPolicy as _;
// Test-owned physical TCP relay emulates only the remote carrier endpoint.
// Session sockets may open exactly the owned TCP echo; DNS must remain Hosts.
struct OwnedSockets{inner:Arc<mcrf_bs_dns_transport::NativePolicy>,echo_port:u16,allowed:AtomicUsize,unexpected:AtomicUsize}
impl mcrf_bs_dns_transport::SocketPolicy for OwnedSockets{fn prepare(&self,protocol:u8,fd:i32,port:u16,peer:SocketAddr,direction:mcrf_bs_dns_transport::Direction)->Result<Box<dyn Send>,mcrf_bs_dns_transport::Failure>{
 if protocol!=6||peer!=SocketAddr::new("10.0.2.2".parse().unwrap(),self.echo_port)||direction!=mcrf_bs_dns_transport::Direction::Vpn{self.unexpected.fetch_add(1,Ordering::SeqCst);return Err(mcrf_bs_dns_transport::Failure::Bind)}
 self.allowed.fetch_add(1,Ordering::SeqCst);self.inner.prepare(protocol,fd,port,peer,direction)
}}
fn listener(name:&str)->OwnedFd{
 assert!(name.starts_with("mcrf-native-udp-")&&name.len()<90&&name.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'));
 let raw=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK,0)};assert!(raw>=0);let fd=unsafe{OwnedFd::from_raw_fd(raw)};
 let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;for(target,byte)in address.sun_path[1..].iter_mut().zip(name.bytes()){*target=byte as _}
 assert_eq!(unsafe{libc::bind(raw,(&address as *const libc::sockaddr_un).cast(),(std::mem::offset_of!(libc::sockaddr_un,sun_path)+1+name.len())as _)},0);assert_eq!(unsafe{libc::listen(raw,1)},0);fd
}
pub async fn run(){
 let args:Vec<_>=std::env::args().collect();assert_eq!(args.len(),5);let echo_port=args[4].parse::<u16>().unwrap();assert!(echo_port>0);let name=&args[1];let physical=mcrf_bs_network::handle(&args[2]).unwrap();let vpn=mcrf_bs_network::handle(&args[3]).unwrap();assert_ne!(physical,vpn);
 assert_eq!(std::env::var("MCRF_DIRECT_TUN_GENERATION").unwrap(),"42");mcrf_direct_tun::configure(name).unwrap();mcrf_bs_network::configure(true).unwrap();assert_eq!(mcrf_bs_network::physical_network(),Some(physical));
 let cancel=CancellationToken::new();let listener=AsyncFd::new(listener(name)).unwrap();println!("OWNED_NATIVE_UDP_FD_READY");std::io::stdout().flush().unwrap();
 let tun=mcrf_direct_tun::receive(&listener,&cancel).await.unwrap();drop(listener);let index=mcrf_bs_dns_ingress::interface_index(tun.as_fd()).unwrap();let tun=Arc::new(AsyncFd::new(tun).unwrap());
 let locals:Vec<IpAddr>=vec!["10.89.0.2".parse().unwrap(),"fd00::2".parse().unwrap()];let owner=mcrf_bs_owner::configure(42).unwrap();mcrf_bs_service::configure(42,unsafe{libc::getuid()}).unwrap();
 let underlay=mcrf_bs_dns_handler::Underlay{kind:"other".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()};

 let pool=crate::packet::PacketPool::new(128);let(tx,mut tcp_packets)=tokio::sync::mpsc::channel(128);let session_tx=tx.clone();let config=crate::dispatcher::DomainRoutingConfig::default();assert!(!config.enabled);
 let relay=Arc::new(crate::direct_relay::DirectRelay::new(Arc::new(arc_swap::ArcSwap::from_pointee(config)),Arc::new(arc_swap::ArcSwap::from_pointee(std::collections::HashSet::new())),Arc::new(crate::sni::DnsDomainMap::default()),pool.clone(),tx).unwrap());
 let wire_relay=Arc::new(crate::direct_relay::DirectRelay::new(Arc::new(arc_swap::ArcSwap::from_pointee(crate::dispatcher::DomainRoutingConfig::default())),Arc::new(arc_swap::ArcSwap::from_pointee(std::collections::HashSet::new())),Arc::new(crate::sni::DnsDomainMap::default()),pool.clone(),session_tx.clone()).unwrap());
 let token=cancel.clone();let input=std::thread::spawn(move||{
  let mut input=std::io::BufReader::new(std::io::stdin());loop{let mut bytes=Vec::new();let result=(&mut input).take(16384).read_until(b'\n',&mut bytes);
   if !matches!(result,Ok(n)if n>0&&n<16384)||bytes.last()!=Some(&b'\n'){token.cancel();break}
   let Ok(line)=std::str::from_utf8(&bytes)else{token.cancel();break};let line=line.trim();if line=="OWNED_UDP_STOP"{token.cancel();break}
   if mcrf_bs_owner::control_response(line)!=Ok(true){token.cancel();break}
  }
 });
 let reflected=Arc::new(AtomicUsize::new(0));let counter=reflected.clone();let io=tun.clone();let token=cancel.clone();let mut tasks=JoinSet::new();let tcp=relay.clone();
 let sockets=Arc::new(mcrf_bs_dns_transport::NativePolicy::new(physical,vpn,true,true).unwrap());
 let policy=Arc::new(OwnedSockets{inner:sockets.clone(),echo_port,allowed:AtomicUsize::new(0),unexpected:AtomicUsize::new(0)});
 let connector=Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets.clone()});
 let reply=Arc::new(UdpReply{tx:session_tx.clone(),pool:pool.clone()});
 let mut document=serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"proxy","rules":[{"match":{"domain":["owned-direct.invalid"]},"target":"direct"}]},"controls":[
  {"beforeRule":0,"operation":{"action":"sniff"}},
  {"beforeRule":0,"operation":{"action":"hijack-dns","protocol":"dns"}},
  {"beforeRule":0,"operation":{"action":"route-options","network":"tcp","ip_cidr":["198.51.100.44/32"],"override_address":"10.0.2.2","override_port":echo_port}},
  {"beforeRule":0,"operation":{"action":"route-options","network":"udp","ip_cidr":["198.51.100.44/32"],"override_address":"10.0.2.2","override_port":echo_port,"udp_connect":true}},
  {"beforeRule":0,"operation":{"action":"route-options","network":"udp","ip_cidr":["198.51.100.45/32"],"override_address":"owned-direct.invalid","override_port":echo_port,"udp_connect":false}}
 ],"dns":{"servers":[{"type":"hosts","tag":"owned","predefined":{"owned.invalid":"198.51.100.9","owned-direct.invalid":"10.0.2.2"}}],"final":"owned","reverse_mapping":true},"routeOptions":{},"tunInbounds":[],"dnsDialers":{}});
 document["tunInbounds"]=serde_json::json!([{"type":"tun","tag":"owned-shared","mtu":1500,"address":["10.89.0.2/32","fd00::2/128"],"route_address":["198.19.255.53/32","fdfe:dcba:9876:dd54::53/128","198.51.100.53/32","2001:db8::53/128","198.51.100.44/32","198.51.100.45/32","10.0.2.2/32"],"include_package":["org.mcrf.client"],"auto_route":true,"strict_route":true,"dns_mode":"hijack"}]);
 let shared=crate::mcrf_bs_session::Session::prepare(&serde_json::to_vec(&document).unwrap(),crate::mcrf_bs_session::Dependencies{generation:42,capacity:256,owner:owner.clone(),physical,hosts:std::collections::HashMap::new(),excluded:locals.clone(),sockets:policy.clone(),underlay:underlay.clone(),retire:Arc::new(|_|Ok(())),connector,reply},&cancel).unwrap();
 let plan=shared.tun.as_ref().unwrap();assert_eq!(plan.mtu,1500);assert_eq!(plan.addresses.iter().map(|n|n.address).collect::<Vec<_>>(),locals);assert_eq!(plan.included_packages,vec!["org.mcrf.client"]);assert!(!plan.captures("8.8.8.8".parse().unwrap()));
 let carrier=shared.attach_carrier(&["192.0.2.7".parse().unwrap()]).unwrap();let translated_out=Arc::new(AtomicUsize::new(0));let translated_in=Arc::new(AtomicUsize::new(0));
 let session=shared.tcp.clone();let tracker=session.tracker.clone();
 let udp_dns=shared.udp.clone();let udp_tunnel=shared.udp.clone();let udp_direct=shared.udp.clone();
 let resolve=mcrf_bs_dns_handler::handler(42,session.engine.clone(),owner.clone(),policy.clone(),underlay,&cancel).unwrap();
 let server=mcrf_bs_dns_frontend::start(vpn,index,&locals,1500,resolve,&cancel).unwrap();mcrf_bs_dns::configure(42,&locals,Some(locals[0]),Some(locals[1]),server.port).unwrap();
 let errors=Arc::new(AtomicUsize::new(0));let failures=errors.clone();let fatal=cancel.clone();relay.mcrf_attach_tcp_handler(session.clone().handler(Arc::new(move|error|{eprintln!("OWNED_TCP_FLOW_ERROR {error:?}");failures.fetch_add(1,Ordering::SeqCst);fatal.cancel();}))).unwrap();
 let token=shared.cancellation();let service_ingress=shared.service.clone();
 let reply_nat=carrier.clone();let reply_nat_count=translated_in.clone();let write_io=tun.clone();let write_stop=cancel.clone();let write_count=reflected.clone();tasks.spawn(async move{loop{let mut packet=tokio::select!{biased;_=write_stop.cancelled()=>break,packet=tcp_packets.recv()=>packet.unwrap()};if mcrf_bs_routing_policy::packet::PacketInfo::read(packet.as_slice()).unwrap().destination=="192.0.2.7".parse::<IpAddr>().unwrap(){match reply_nat.incoming(packet.as_mut_slice()){Ok(Some(_))=>{},Ok(None)=>continue,Err(crate::mcrf_bs_carrier_nat::Failure::Cancelled)if write_stop.is_cancelled()=>break,Err(error)=>panic!("owned carrier reply: {error:?}")}reply_nat_count.fetch_add(1,Ordering::SeqCst);}loop{let mut ready=write_io.writable().await.unwrap();match ready.try_io(|fd|{let mut file=fd.get_ref();file.write(packet.as_slice())}){Ok(Ok(size))=>{assert_eq!(size,packet.len());write_count.fetch_add(1,Ordering::SeqCst);break},Ok(Err(error))=>panic!("owned TCP TUN write: {error:?}"),Err(_)=>{}}}}});
 let mut udp_inputs=Vec::new();
 for session in [udp_direct,udp_tunnel,udp_dns]{let(tx,rx)=tokio::sync::mpsc::channel(32);udp_inputs.push(tx);let stopped=shared.cancellation();tasks.spawn(async move{let token=stopped.clone();crate::mcrf_bs_udp_ingress::run(session,rx,32,8,Arc::new(move|event|{if !token.is_cancelled(){panic!("owned UDP ingress: {event:?}")}}),&stopped).await.unwrap()});}
 let outbound_count=translated_out.clone();tasks.spawn(async move{let mut bytes=[0u8;65535];loop{
  let count=tokio::select!{biased;_=token.cancelled()=>break,
   result=async{loop{let mut ready=io.readable().await?;match ready.try_io(|fd|{let mut file=fd.get_ref();file.read(&mut bytes)}){Ok(result)=>break result,Err(_)=>{}}}}=>result.unwrap(),
  };assert!(count>0);let packet=&bytes[..count];let info=mcrf_bs_routing_policy::packet::PacketInfo::read(packet).unwrap();
  let public=info.destination=="198.51.100.53".parse::<IpAddr>().unwrap()||info.destination=="2001:db8::53".parse::<IpAddr>().unwrap();
  let owned=public||info.destination=="198.19.255.53".parse::<IpAddr>().unwrap()||info.destination=="fdfe:dcba:9876:dd54::53".parse::<IpAddr>().unwrap();
  let admission=match service_ingress.admit(packet).await{Ok(decision)=>decision,Err(crate::mcrf_bs_service_ingress::Failure::Cancelled)if token.is_cancelled()=>break,Err(error)=>panic!("owned service admission: {error:?}")};
  match admission{
   crate::mcrf_bs_service_ingress::Decision::Drop=>continue,
   crate::mcrf_bs_service_ingress::Decision::NotService=>{},
   crate::mcrf_bs_service_ingress::Decision::Forward(proof)=>{
    assert_eq!(proof.key().peer.ip(),"10.0.2.2".parse::<IpAddr>().unwrap());assert_eq!(proof.key().peer.port(),echo_port);
    let mut input=pool.try_acquire().unwrap();input.extend_tail(packet.len()).unwrap().copy_from_slice(packet);
    match carrier.outbound(proof,input.as_mut_slice()){
     Ok(())=>{},
     Err(crate::mcrf_bs_carrier_nat::Failure::Translation(mcrf_bs_routing_policy::service_nat::Error::Denied))=>continue,
     Err(crate::mcrf_bs_carrier_nat::Failure::Cancelled)if token.is_cancelled()=>break,
     Err(error)=>panic!("owned carrier outbound: {error:?}"),
    }
    outbound_count.fetch_add(1,Ordering::SeqCst);assert!(wire_relay.mcrf_policy_direct(&input).await);continue
   },
  }
  if info.protocol==6{
   let(_,b)=info.ports.unwrap();
   if (owned&&info.ports.is_some_and(|(_,port)|matches!(port,53|5353)))||(info.destination=="198.51.100.44".parse::<IpAddr>().unwrap()&&b==echo_port){
    let now=(session.clock)().unwrap();let association=tracker.lock().unwrap().observe(42,now,&info).unwrap();
    let mut input=pool.try_acquire().unwrap();input.extend_tail(packet.len()).unwrap().copy_from_slice(packet);assert!(tcp.mcrf_policy_direct(&input).await);
    if association.retire_after_packet{tracker.lock().unwrap().retire(42,(session.clock)().unwrap(),association.id).unwrap();}
   }continue
  }
  if info.protocol!=17{continue}let(a,b)=info.ports.unwrap();let _source=SocketAddr::new(info.source,a);let _destination=SocketAddr::new(info.destination,b);
  let direct=info.destination=="198.51.100.45".parse::<IpAddr>().unwrap();let tunneled=info.destination=="198.51.100.44".parse::<IpAddr>().unwrap();
  if !(owned&&matches!(b,53|5353)||direct||tunneled){continue}
  let index=if direct{0}else if tunneled{1}else{2};
  let mut input=pool.try_acquire().unwrap();input.extend_tail(packet.len()).unwrap().copy_from_slice(packet);
  udp_inputs[index].try_send(input).unwrap();counter.fetch_add(1,Ordering::SeqCst);
 }});
 mcrf_direct_tun::adopted().unwrap();println!("OWNED_NATIVE_UDP_READY");std::io::stdout().flush().unwrap();
 tokio::time::timeout(Duration::from_secs(60),cancel.cancelled()).await.unwrap();server.close().await.unwrap();while let Some(result)=tasks.join_next().await{result.unwrap();}drop(tun);drop(relay);owner.close();input.join().unwrap();
 assert!(translated_out.load(Ordering::SeqCst)>=4);assert!(translated_in.load(Ordering::SeqCst)>=4);assert_eq!(errors.load(Ordering::SeqCst),0);assert_eq!(policy.unexpected.load(Ordering::SeqCst),0);assert_eq!(policy.allowed.load(Ordering::SeqCst),1);assert!(reflected.load(Ordering::SeqCst)>=8);println!("OWNED_NATIVE_UDP_DONE packets={} snat_out={} snat_in={}",reflected.load(Ordering::SeqCst),translated_out.load(Ordering::SeqCst),translated_in.load(Ordering::SeqCst));
}

struct UdpReply{tx:tokio::sync::mpsc::Sender<crate::packet::PacketBuf>,pool:Arc<crate::packet::PacketPool>}
impl crate::mcrf_bs_udp_dispatch::Reply for UdpReply{
 fn write<'a>(&'a self,source:SocketAddr,destination:SocketAddr,bytes:&'a [u8],_:&'a CancellationToken)->crate::mcrf_bs_udp_dispatch::Task<'a,()>{Box::pin(async move{
  let mut packet=self.pool.try_acquire().ok_or(crate::mcrf_bs_udp_dispatch::Failure::Reply)?;
  let size=if source.is_ipv4(){28}else{48};let output=packet.extend_tail(size+bytes.len()).map_err(|_|crate::mcrf_bs_udp_dispatch::Failure::Reply)?;
  if !crate::direct_relay::craft_udp_packet_into(source,destination,bytes,output){return Err(crate::mcrf_bs_udp_dispatch::Failure::Reply)}
  self.tx.send(packet).await.map_err(|_|crate::mcrf_bs_udp_dispatch::Failure::Reply)
 })}
}
