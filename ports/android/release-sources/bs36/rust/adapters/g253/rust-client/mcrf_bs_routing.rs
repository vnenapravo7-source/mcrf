//! Staged IP/port and explicit owned-pipe application metadata attachment.
//! Not packaged/advertised: Main owner attachment and domain/DNS remain required.
use anyhow::{Result,bail};
use mcrf_bs_routing_policy::{Observed,Policy,Target,packet::PacketInfo,session::Session,associations::Tracker};
use std::sync::{OnceLock,Mutex,Arc};
use tokio_util::sync::CancellationToken;

struct State {session:Session,tracker:Tracker,service_tcp:std::collections::HashSet<u64>,require_owner:bool}
struct Active {
    owner_only:bool,
    generation:u64,started:std::time::Instant,state:Mutex<State>,owner:Option<Arc<crate::mcrf_bs_owner::Broker>>,
    pending:Mutex<tokio::task::JoinSet<()>>,pending_count:std::sync::atomic::AtomicUsize,
}
static ACTIVE:OnceLock<Active>=OnceLock::new();
const SHA_ENV:&str="MCRF_BS_ROUTING_POLICY_SHA256";
const OWNER_ONLY_POLICY:&[u8]=br#"{"schema":1,"defaultTarget":"proxy","rules":[]}"#;

fn policy(bytes:&[u8],generation:u64)->Result<Session> {
    policy_with_owner(bytes,generation,false)
}
fn policy_with_owner(bytes:&[u8],generation:u64,owner_ready:bool)->Result<Session> {
    let policy=Policy::parse(bytes).map_err(|_|anyhow::anyhow!("BS_ROUTING_POLICY_INVALID"))?;
    if !policy.egress_tags().is_empty(){bail!("BS_ROUTING_EGRESS_NOT_ATTACHED")}
    let needed=policy.requirements();
    // Do not accept settings whose metadata producer has not been attached.
    // In particular a domain list must not silently become an IP-only route.
    if needed.domain||needed.protocol||needed.owner&&!owner_ready||needed.underlay {bail!("BS_ROUTING_METADATA_NOT_ATTACHED")}
    Session::new(generation,policy,Observed::Pending,8192,120_000)
        .map_err(|_|anyhow::anyhow!("BS_ROUTING_SESSION_INVALID"))
}

#[cfg(unix)]
fn read_private(expected:&str)->Result<Vec<u8>> {
    read_private_at(std::path::Path::new("."),expected)
}
#[cfg(unix)]
fn read_private_at(path:&std::path::Path,expected:&str)->Result<Vec<u8>> {
    read_private_named(path,c"routing-policy.json",expected,16*1024*1024)
}
#[cfg(unix)]
pub fn read_bundle(expected:&str)->Result<Vec<u8>>{
    if expected.len()!=64||!expected.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){bail!("BS_ROUTING_DIGEST_INVALID")}
    read_private_named(std::path::Path::new("."),c"routing-session.json",expected,24*1024*1024)
}
#[cfg(unix)]
fn read_private_named(path:&std::path::Path,name:&std::ffi::CStr,expected:&str,maximum:usize)->Result<Vec<u8>> {
    use std::{io::Read,os::unix::{fs::{OpenOptionsExt,MetadataExt},io::{AsRawFd,FromRawFd}}};
    use sha2::{Digest,Sha256};
    let directory_fd=std::fs::OpenOptions::new().read(true).custom_flags(libc::O_DIRECTORY|libc::O_NOFOLLOW|libc::O_CLOEXEC).open(path)?;
    let directory=directory_fd.metadata()?;
    if !directory.is_dir()||directory.uid()!=unsafe{libc::getuid()}||directory.mode()&0o077!=0 {bail!("BS_ROUTING_DIRECTORY_OWNER")}
    let raw=unsafe{libc::openat(directory_fd.as_raw_fd(),name.as_ptr(),libc::O_RDONLY|libc::O_NOFOLLOW|libc::O_CLOEXEC|libc::O_NONBLOCK)};
    if raw<0{return Err(std::io::Error::last_os_error().into())}
    let mut file=unsafe{std::fs::File::from_raw_fd(raw)};
    let before=file.metadata()?;
    if !before.is_file()||before.uid()!=unsafe{libc::getuid()}||before.mode()&0o777!=0o600||before.nlink()!=1||!(1..=maximum as u64).contains(&before.len()) {bail!("BS_ROUTING_FILE_OWNER")}
    let flags=unsafe{libc::fcntl(file.as_raw_fd(),libc::F_GETFD)};
    if flags<0||flags&libc::FD_CLOEXEC==0 {bail!("BS_ROUTING_FILE_FLAGS")}
    let mut bytes=Vec::with_capacity(before.len() as usize);
    file.by_ref().take(maximum as u64+1).read_to_end(&mut bytes)?;
    let after=file.metadata()?;
    if bytes.len() as u64!=before.len()||before.len()!=after.len()||before.ino()!=after.ino()||before.dev()!=after.dev()||before.mtime()!=after.mtime()||before.mtime_nsec()!=after.mtime_nsec()||before.uid()!=after.uid()||before.mode()!=after.mode()||before.ctime()!=after.ctime()||before.ctime_nsec()!=after.ctime_nsec()||after.nlink()!=1 {bail!("BS_ROUTING_FILE_CHANGED")}
    if hex::encode(Sha256::digest(&bytes))!=expected {bail!("BS_ROUTING_FILE_DIGEST")}
    Ok(bytes)
}
#[cfg(not(unix))]
fn read_private(_:&str)->Result<Vec<u8>> {bail!("BS_ROUTING_UNSUPPORTED_HOST")}

pub fn configure()->Result<()> {
    let owner_only=match std::env::var_os("MCRF_BS_OWNER_ISOLATION") {
        None=>false,Some(value) if value=="1"=>true,_=>bail!("BS_ROUTING_OWNER_ISOLATION_INVALID"),
    };
    let expected=std::env::var_os(SHA_ENV);
    if !owner_only&&expected.is_none(){return Ok(())}
    if !crate::mcrf_direct_tun::active(){bail!("BS_ROUTING_REQUIRES_OWNED_TUN")}
    if !owner_only&&!crate::mcrf_bs_network::is_bound(){bail!("BS_ROUTING_REQUIRES_PHYSICAL_NETWORK_BINDING")}
    let generation=crate::mcrf_direct_tun::parse_token(&std::env::var("MCRF_DIRECT_TUN_GENERATION")?)?;
    let bytes=if owner_only {
        if expected.is_some()||std::env::var_os("MCRF_BS_ROUTING_SESSION_SHA256").is_some(){bail!("BS_ROUTING_OWNER_ISOLATION_CONFLICT")}
        OWNER_ONLY_POLICY.to_vec()
    }else{
        let expected=expected.unwrap().into_string().map_err(|_|anyhow::anyhow!("BS_ROUTING_DIGEST_INVALID"))?;
        if expected.len()!=64||!expected.bytes().all(|b|b.is_ascii_digit()||(b'a'..=b'f').contains(&b)){bail!("BS_ROUTING_DIGEST_INVALID")}
        read_private(&expected)?
    };
    let require_owner=owner_only||Policy::parse(&bytes).map_err(|_|anyhow::anyhow!("BS_ROUTING_POLICY_INVALID"))?.requirements().owner;
    let owner_ready=match std::env::var_os("MCRF_BS_OWNER_CHANNEL") {
        None=>false,Some(value) if value=="1"=>true,_=>bail!("BS_ROUTING_OWNER_CHANNEL_INVALID"),
    };
    if owner_only&&!owner_ready{bail!("BS_ROUTING_OWNER_ISOLATION_CHANNEL_REQUIRED")}
    let session=policy_with_owner(&bytes,generation,owner_ready)?;
    let owner=if owner_ready {
        let owner=crate::mcrf_bs_owner::configure(generation).map_err(|_|anyhow::anyhow!("BS_ROUTING_OWNER_CHANNEL_FAILED"))?;
        #[cfg(unix)] let uid=unsafe{libc::getuid()};
        #[cfg(not(unix))] let uid=0;
        crate::mcrf_bs_service::configure(generation,uid).map_err(|_|anyhow::anyhow!("BS_ROUTING_SERVICE_CHANNEL_FAILED"))?;
        Some(owner)
    }else{None};
    let tracker=Tracker::new(generation,8192).map_err(|_|anyhow::anyhow!("BS_ROUTING_ASSOCIATIONS_INVALID"))?;
    ACTIVE.set(Active{owner_only,generation,started:std::time::Instant::now(),state:Mutex::new(State{session,tracker,service_tcp:std::collections::HashSet::new(),require_owner}),owner,
        pending:Mutex::new(tokio::task::JoinSet::new()),pending_count:std::sync::atomic::AtomicUsize::new(0)})
        .map_err(|_|anyhow::anyhow!("BS_ROUTING_ALREADY_CONFIGURED"))?;
    use std::io::Write;
    let mut out=std::io::stdout().lock();
    if owner_only{writeln!(out,"MCRF_BS_OWNER_ISOLATION_READY {generation}")?;}
    else{writeln!(out,"MCRF_BS_ROUTING_READY {generation} 1")?;}
    out.flush()?;Ok(())
}

/// None preserves an ordinary, unconfigured original client exactly. With an
/// attached policy every rejection is terminal for this packet: no legacy
/// classifier or serial SOCKS/sing-box fallback is allowed after a decision.
fn route_packet(session:&mut Session,generation:u64,now:u64,bytes:&[u8])->Target {
    (||{
        let info=PacketInfo::read(bytes).ok()?;
        // Fragment associations still require their own verified lifetime.
        if info.fragment.is_some(){return None}
        session.decide(generation,1,now,&info.flow()?).ok().map(|d|d.target)
    })().unwrap_or(Target::Block)
}
async fn associated<F:Fn()->Option<u64>>(state:&Mutex<State>,broker:Option<&crate::mcrf_bs_owner::Broker>,generation:u64,now:F,bytes:&[u8],cancel:&CancellationToken)->Target {
    let Ok(info)=PacketInfo::read(bytes) else{return Target::Block};
    if info.fragment.is_some()||cancel.is_cancelled(){return Target::Block}
    // Preserve the previous non-TCP/UDP policy behavior. Android owner lookup
    // cannot manufacture an application identity for an ICMP control packet.
    if !matches!(info.protocol,6|17){
        return match (state.lock(),now()) {
            (Ok(mut state),Some(time))=>route_packet(&mut state.session,generation,time,bytes),_=>Target::Block,
        }
    }
    let association={
        let Ok(mut state)=state.lock() else{return Target::Block};let Some(time)=now() else{return Target::Block};
        let Ok(association)=state.tracker.observe(generation,time,&info) else{return Target::Block};
        if state.session.retire_associations(generation,time,&association.retired).is_err(){return Target::Block}
        for id in &association.retired{state.service_tcp.remove(id);}
        association
    };
    let mut id=association.id;
    let needs_owner={
        let Ok(state)=state.lock()else{return Target::Block};
        let Some((local_port,port))=info.ports else{return Target::Block};
        let key=mcrf_bs_routing_policy::service_flows::Key{protocol:info.protocol,local_port,peer:std::net::SocketAddr::new(info.destination,port)};
        match crate::mcrf_bs_service::candidate(key){Ok(candidate)=>state.require_owner||state.service_tcp.contains(&id)||candidate,Err(_)=>return Target::Block}
    };
    if needs_owner&&broker.is_none(){return Target::Block}
    let broker=if needs_owner{broker}else{None};
    if let Some(broker)=broker {
        let missing=state.lock().map(|state|state.tracker.owner(&info,id).is_none()).unwrap_or(true);
        if association.refresh_owner||missing {
            let Some((source_port,destination_port))=info.ports else{return Target::Block};
            let source=std::net::SocketAddr::new(info.source,source_port);
            let destination=std::net::SocketAddr::new(info.destination,destination_port);
            let Ok(owner)=broker.query(info.protocol,source,destination,cancel).await else{return Target::Block};
            let Ok(mut state)=state.lock() else{return Target::Block};let Some(time)=now() else{return Target::Block};
            let Ok(confirmed)=state.tracker.confirm_owner(generation,time,&info,id,owner.uid,owner.packages) else{return Target::Block};
            if state.session.retire_associations(generation,time,&confirmed.retired).is_err(){return Target::Block}
            for id in &confirmed.retired{state.service_tcp.remove(id);}
            id=confirmed.id;
        }
    }
    if cancel.is_cancelled(){return Target::Block}
    let Ok(mut state)=state.lock() else{return Target::Block};let Some(time)=now() else{return Target::Block};
    let State{session,tracker,service_tcp,..}=&mut *state;
    let Some(mut flow)=info.flow() else{return Target::Block};
    let mut service=info.protocol==6&&service_tcp.contains(&id);
    if broker.is_some(){
        let Some((uid,packages))=tracker.owner(&info,id) else{return Target::Block};flow.packages=Some(packages);
        let Some((local_port,port))=info.ports else{return Target::Block};
        let key=mcrf_bs_routing_policy::service_flows::Key{protocol:info.protocol,local_port,peer:std::net::SocketAddr::new(info.destination,port)};
        match crate::mcrf_bs_service::admitted(uid,key){Ok(true)=>service=true,Ok(false)=>{},Err(_)=>return Target::Block}
    }
    // TCP's first verified service SYN fixes its path through FIN/RST, even
    // after the resolver closes the socket/removes its registration. A new SYN
    // gets a new association and cannot inherit this authorization. UDP still
    // requires a live registration and fresh owner on every packet.
    if service&&info.protocol==6{service_tcp.insert(id);}
    let verdict=if service{Target::Tunnel}else{session.decide(generation,id,time,&flow).map(|decision|decision.target).unwrap_or(Target::Block)};
    if association.retire_after_packet {
        let _=session.retire_associations(generation,time,&[id]);let _=tracker.retire(generation,time,id);
        service_tcp.remove(&id);
    }
    verdict
}
pub async fn classify(bytes:&[u8],cancel:&CancellationToken)->Option<Target> {
    let active=ACTIVE.get()?;
    if active.owner_only&&!PacketInfo::read(bytes).is_ok_and(|info|matches!(info.protocol,6|17)){return Some(Target::Block)}
    let verdict=associated(&active.state,active.owner.as_deref(),active.generation,||u64::try_from(active.started.elapsed().as_millis()).ok(),bytes,cancel).await;
    if active.owner_only&&verdict==Target::Tunnel{None}else{Some(verdict)}
}
/// New application lookups must not park the shared TUN reader. PacketBuf stays
/// owned by at most 32 tasks; established cached TCP uses the immediate path.
/// None means queued or safely dropped under bounded metadata backpressure.
pub fn defer(dispatcher:&Arc<crate::dispatcher::Dispatcher>,packet:crate::packet::PacketBuf,cancel:&CancellationToken)->Option<crate::packet::PacketBuf>{
    use std::sync::atomic::Ordering;
    let Some(active)=ACTIVE.get() else{return Some(packet)};
    if active.owner.is_none(){return Some(packet)}
    if cancel.is_cancelled(){return None}
    if active.pending_count.load(Ordering::Acquire)!=0 {
        let Ok(mut tasks)=active.pending.lock() else{cancel.cancel();return None};
        while let Some(result)=tasks.try_join_next(){if result.is_err(){cancel.cancel();return None}}
        active.pending_count.store(tasks.len(),Ordering::Release);
    }
    let needed=match (active.state.lock(),PacketInfo::read(packet.as_slice()),u64::try_from(active.started.elapsed().as_millis())){
        (Ok(state),Ok(info),Ok(now))=>{
            let candidate=if let Some((local_port,port))=info.ports{
                let key=mcrf_bs_routing_policy::service_flows::Key{protocol:info.protocol,local_port,peer:std::net::SocketAddr::new(info.destination,port)};
                match crate::mcrf_bs_service::candidate(key){Ok(value)=>value,Err(_)=>{cancel.cancel();return None}}
            }else{false};
            (state.require_owner||candidate)&&state.tracker.needs_owner(active.generation,now,&info)
        },_=>return None,
    };
    if !needed{return Some(packet)}
    let Ok(mut tasks)=active.pending.lock() else{cancel.cancel();return None};
    if tasks.len()>=32{return None}
    let owner=dispatcher.clone();tasks.spawn(async move{owner.dispatch(packet).await});
    active.pending_count.store(tasks.len(),Ordering::Release);None
}
#[cfg(test)]
pub(crate) fn test_policy_packet(snapshot:&[u8],packet:&[u8])->Target {
    let mut session=policy(snapshot,42).expect("owned IP/port policy admission");
    route_packet(&mut session,42,0,packet)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn owner_only_startup_policy_parses_and_preserves_tunnel_fallback() {
        let mut session=policy_with_owner(OWNER_ONLY_POLICY,42,true).unwrap();
        let packet=owned_packet(17,45678,0,0);
        assert!(route_packet(&mut session,42,0,&packet)==Target::Tunnel);
    }
    fn owned_packet(protocol:u8,source_port:u16,sequence:u32,flags:u8)->Vec<u8>{
        let length=if protocol==6{40}else{32};let mut bytes=vec![0;length];bytes[0]=0x45;bytes[2..4].copy_from_slice(&(length as u16).to_be_bytes());bytes[8]=64;bytes[9]=protocol;
        bytes[12..16].copy_from_slice(&[198,18,0,1]);bytes[16..20].copy_from_slice(&[192,0,2,42]);bytes[20..22].copy_from_slice(&source_port.to_be_bytes());bytes[22..24].copy_from_slice(&443u16.to_be_bytes());
        if protocol==6{bytes[24..28].copy_from_slice(&sequence.to_be_bytes());bytes[32]=0x50;bytes[33]=flags}else{bytes[24..26].copy_from_slice(&12u16.to_be_bytes())};bytes
    }
    fn owner_fixture()->(Arc<Mutex<State>>,Arc<crate::mcrf_bs_owner::Broker>,tokio::sync::mpsc::Receiver<String>){
        let policy=br#"{"schema":1,"defaultTarget":"block","rules":[{"match":{"package_name":"org.example.allowed"},"target":"direct"}]}"#;
        let state=Arc::new(Mutex::new(State{session:policy_with_owner(policy,42,true).unwrap(),tracker:Tracker::new(42,8).unwrap(),service_tcp:std::collections::HashSet::new(),require_owner:true}));
        let(tx,rx)=tokio::sync::mpsc::channel(32);
        let broker=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.try_send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Busy))).unwrap());
        (state,broker,rx)
    }
    fn answer(broker:&crate::mcrf_bs_owner::Broker,request:&str,uid:u32,package:&str){
        use base64::Engine as _;
        let id=request.split_whitespace().nth(2).unwrap();let payload=base64::engine::general_purpose::STANDARD.encode(serde_json::to_vec(&vec![package]).unwrap());
        assert_eq!(broker.respond(&format!("BS_OWNER|42|{id}|{uid}|{payload}")),Ok(true));
    }
    fn owned_route(state:Arc<Mutex<State>>,broker:Arc<crate::mcrf_bs_owner::Broker>,packet:Vec<u8>,cancel:CancellationToken)->tokio::task::JoinHandle<Target>{
        tokio::spawn(async move{associated(&state,Some(&broker),42,||Some(0),&packet,&cancel).await})
    }
    #[tokio::test]
    async fn owner_udp_rebind_does_not_reuse_the_previous_apps_cached_verdict(){
        let(state,broker,mut requests)=owner_fixture();let packet=owned_packet(17,45678,0,0);let before=packet.clone();
        let first=owned_route(state.clone(),broker.clone(),packet.clone(),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(first.await.unwrap()==Target::Direct);
        let next=owned_route(state,broker.clone(),packet.clone(),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10002,"org.example.other");assert!(next.await.unwrap()==Target::Block);assert_eq!(packet,before);
    }
    #[tokio::test]
    async fn owner_cached_tcp_uses_no_more_lookups_until_a_new_syn_reuses_the_tuple(){
        let(state,broker,mut requests)=owner_fixture();let first=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,100,2),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(first.await.unwrap()==Target::Direct);
        let data=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,101,16),CancellationToken::new());assert!(tokio::time::timeout(std::time::Duration::from_secs(1),data).await.unwrap().unwrap()==Target::Direct);assert!(requests.try_recv().is_err());
        let next=owned_route(state,broker.clone(),owned_packet(6,45678,200,2),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10002,"org.example.other");assert!(next.await.unwrap()==Target::Block);
    }
    #[tokio::test]
    async fn owner_denial_never_becomes_a_route_or_a_permanently_cached_missing_owner(){
        let(state,broker,mut requests)=owner_fixture();let packet=owned_packet(6,45678,100,2);
        let first=owned_route(state.clone(),broker.clone(),packet.clone(),CancellationToken::new());let request=requests.recv().await.unwrap();let id=request.split_whitespace().nth(2).unwrap();broker.respond(&format!("BS_OWNER|42|{id}|-1|")).unwrap();assert!(first.await.unwrap()==Target::Block);
        let retry=owned_route(state,broker.clone(),packet,CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(retry.await.unwrap()==Target::Direct);
    }
    #[tokio::test]
    async fn owner_service_tcp_pin_survives_socket_close_but_not_a_reused_syn(){
        let(state,broker,mut requests)=owner_fixture();
        let first=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,100,2),CancellationToken::new());
        let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(first.await.unwrap()==Target::Direct);
        state.lock().unwrap().service_tcp.insert(1);
        let fin=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,101,17),CancellationToken::new());assert!(fin.await.unwrap()==Target::Tunnel);assert!(requests.try_recv().is_err());
        let next=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,200,2),CancellationToken::new());
        let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(next.await.unwrap()==Target::Direct);assert!(state.lock().unwrap().service_tcp.is_empty());
    }
    #[tokio::test]
    async fn owner_channel_presence_does_not_query_every_udp_packet_for_an_ip_only_policy(){
        let(state,broker,mut requests)=owner_fixture();{
            let mut state=state.lock().unwrap();state.session=policy_with_owner(br#"{"schema":1,"defaultTarget":"direct","rules":[]}"#,42,true).unwrap();state.require_owner=false;
        }
        for _ in 0..4{let task=owned_route(state.clone(),broker.clone(),owned_packet(17,45679,0,0),CancellationToken::new());assert!(tokio::time::timeout(std::time::Duration::from_secs(1),task).await.unwrap().unwrap()==Target::Direct)}
        assert!(requests.try_recv().is_err());
    }
    #[tokio::test]
    async fn owner_service_candidate_is_not_authorization_without_the_correct_uid(){
        crate::mcrf_bs_service::configure(42,10001).unwrap();
        let key=mcrf_bs_routing_policy::service_flows::Key{protocol:17,local_port:45678,peer:"192.0.2.42:443".parse().unwrap()};
        let lease=crate::mcrf_bs_service::register_internal(key).unwrap();
        let(state,broker,mut requests)=owner_fixture();{
            let mut state=state.lock().unwrap();state.session=policy_with_owner(br#"{"schema":1,"defaultTarget":"block","rules":[]}"#,42,true).unwrap();state.require_owner=false;
        }
        let packet=owned_packet(17,45678,0,0);
        let own=owned_route(state.clone(),broker.clone(),packet.clone(),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(own.await.unwrap()==Target::Tunnel);
        let foreign=owned_route(state.clone(),broker.clone(),packet.clone(),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10002,"org.example.other");assert!(foreign.await.unwrap()==Target::Block);
        drop(lease);let retired=owned_route(state,broker,packet,CancellationToken::new());assert!(retired.await.unwrap()==Target::Block);assert!(requests.try_recv().is_err());
    }
    #[tokio::test]
    async fn native_owned_service_survives_tracker_idle_and_rechecks_owner_without_reclassifying_into_itself(){
        crate::mcrf_bs_service::configure(42,10001).unwrap();
        let key=mcrf_bs_routing_policy::service_flows::Key{protocol:6,local_port:45678,peer:"192.0.2.42:443".parse().unwrap()};let lease=crate::mcrf_bs_service::register_internal(key).unwrap();
        let(state,broker,mut requests)=owner_fixture();{let mut s=state.lock().unwrap();s.session=policy_with_owner(br#"{"schema":1,"defaultTarget":"block","rules":[]}"#,42,true).unwrap();s.require_owner=false;}
        let execute=|time:u64,packet:Vec<u8>|{let(state,broker)=(state.clone(),broker.clone());tokio::spawn(async move{associated(&state,Some(&broker),42,||Some(time),&packet,&CancellationToken::new()).await})};
        let syn=execute(0,owned_packet(6,45678,100,2));let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(syn.await.unwrap()==Target::Tunnel);
        assert!(execute(1,owned_packet(6,45678,101,16)).await.unwrap()==Target::Tunnel);assert!(requests.try_recv().is_err());
        // The tracker deliberately rotates an idle raw service association.
        // Its STILL-OWNED kernel socket must retain service eligibility and
        // pass a new Android ownership query instead of recursively re-entering
        // the ordinary stream-routing handler.
        let idle=execute(130_000,owned_packet(6,45678,101,16));let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(idle.await.unwrap()==Target::Tunnel);
        drop(lease);assert!(execute(130_001,owned_packet(6,45678,101,16)).await.unwrap()==Target::Tunnel);
        assert!(execute(130_002,owned_packet(6,45678,200,2)).await.unwrap()==Target::Block);assert!(requests.try_recv().is_err());
    }
    #[tokio::test]
    async fn owner_slow_lookup_does_not_hold_the_cached_flow_policy_lock_and_cancellation_blocks(){
        let(state,broker,mut requests)=owner_fixture();let first=owned_route(state.clone(),broker.clone(),owned_packet(6,45678,100,2),CancellationToken::new());let request=requests.recv().await.unwrap();answer(&broker,&request,10001,"org.example.allowed");assert!(first.await.unwrap()==Target::Direct);
        let cancel=CancellationToken::new();let slow=owned_route(state.clone(),broker.clone(),owned_packet(6,45679,200,2),cancel.clone());requests.recv().await.unwrap();
        let cached=owned_route(state,broker,owned_packet(6,45678,101,16),CancellationToken::new());assert!(tokio::time::timeout(std::time::Duration::from_secs(1),cached).await.unwrap().unwrap()==Target::Direct);
        cancel.cancel();assert!(slow.await.unwrap()==Target::Block);
    }
    #[test]
    fn metadata_without_a_native_producer_is_rejected_before_startup() {
        for matcher in [r#"{"domain":"example.org"}"#,r#"{"package_name":"org.example.app"}"#,r#"{"protocol":"tls"}"#,r#"{"network_type":"wifi"}"#] {
            let json=format!(r#"{{"schema":1,"defaultTarget":"proxy","rules":[{{"match":{matcher},"target":"block"}}]}}"#);
            assert!(policy(json.as_bytes(),42).is_err());
        }
        assert!(policy(br#"{"schema":1,"defaultTarget":"proxy","rules":[{"match":{"ip_cidr":"192.0.2.0/24","port":443},"target":"block"}]}"#,42).is_ok());
    }
    #[cfg(unix)]
    struct PolicyFileFixture {base:std::path::PathBuf,dir:std::path::PathBuf,file:std::path::PathBuf,digest:String}
    #[cfg(unix)]
    impl PolicyFileFixture {
        fn new()->Self {
            use std::{io::Write,os::unix::fs::{OpenOptionsExt,PermissionsExt}};
            use sha2::{Digest,Sha256};
            let base=std::env::var_os("MCRF_ROUTING_TEST_ROOT").map(std::path::PathBuf::from).unwrap_or_else(std::env::temp_dir).canonicalize().unwrap();
            let dir=base.join(format!("mcrf-routing-owner-{}-{}",std::process::id(),std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
            std::fs::create_dir(&dir).unwrap();std::fs::set_permissions(&dir,std::fs::Permissions::from_mode(0o700)).unwrap();
            let file=dir.join("routing-policy.json");let bytes=br#"{"schema":1,"defaultTarget":"proxy","rules":[]}"#;
            let digest=hex::encode(Sha256::digest(bytes));
            let mut out=std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(&file).unwrap();out.write_all(bytes).unwrap();drop(out);
            Self{base,dir,file,digest}
        }
    }
    #[cfg(unix)]
    impl Drop for PolicyFileFixture {fn drop(&mut self){
        if self.dir.parent()==Some(self.base.as_path())&&self.dir.symlink_metadata().is_ok_and(|m|m.is_dir()&&!m.file_type().is_symlink()) {let _=std::fs::remove_dir_all(&self.dir);}
    }}
    #[cfg(unix)]
    #[test]
    fn private_policy_digest_and_private_modes_are_checked() {
        use std::os::unix::fs::PermissionsExt;
        let f=PolicyFileFixture::new();
        assert_eq!(read_private_at(&f.dir,&f.digest).unwrap(),std::fs::read(&f.file).unwrap());
        assert!(read_private_at(&f.dir,&"0".repeat(64)).is_err());
        std::fs::set_permissions(&f.file,std::fs::Permissions::from_mode(0o644)).unwrap();assert!(read_private_at(&f.dir,&f.digest).is_err());
        std::fs::set_permissions(&f.file,std::fs::Permissions::from_mode(0o600)).unwrap();
        std::fs::set_permissions(&f.dir,std::fs::Permissions::from_mode(0o755)).unwrap();assert!(read_private_at(&f.dir,&f.digest).is_err());
    }
    #[cfg(unix)]#[test]
    fn private_bundle_uses_its_exact_name_digest_bounds_modes_and_no_symlink(){
        use std::os::unix::fs::PermissionsExt;
        let f=PolicyFileFixture::new();let bundle=f.dir.join("routing-session.json");std::fs::rename(&f.file,&bundle).unwrap();
        let expected=std::fs::read(&bundle).unwrap();assert_eq!(read_private_named(&f.dir,c"routing-session.json",&f.digest,24*1024*1024).unwrap(),expected);
        assert!(read_private_at(&f.dir,&f.digest).is_err());assert!(read_private_named(&f.dir,c"routing-session.json",&"0".repeat(64),24*1024*1024).is_err());
        assert!(read_private_named(&f.dir,c"routing-session.json",&f.digest,expected.len()-1).is_err());
        std::fs::set_permissions(&bundle,std::fs::Permissions::from_mode(0o644)).unwrap();assert!(read_private_named(&f.dir,c"routing-session.json",&f.digest,24*1024*1024).is_err());std::fs::set_permissions(&bundle,std::fs::Permissions::from_mode(0o600)).unwrap();
        let saved=f.dir.join("saved-bundle.json");std::fs::rename(&bundle,&saved).unwrap();std::os::unix::fs::symlink(&saved,&bundle).unwrap();assert!(read_private_named(&f.dir,c"routing-session.json",&f.digest,24*1024*1024).is_err());
    }
    #[cfg(unix)]
    #[test]
    fn private_policy_multiple_hardlinks_are_rejected() {
        let f=PolicyFileFixture::new();
        // Fixture creation must really succeed. Android shell may prohibit hard links;
        // report that separately rather than skipping the remaining safety checks.
        std::fs::hard_link(&f.file,f.dir.join("another-link")).expect("hard-link fixture creation requires a permitted owned test UID");
        assert!(read_private_at(&f.dir,&f.digest).is_err());
    }
    #[cfg(unix)]
    #[test]
    fn private_policy_symlinks_are_rejected() {
        let f=PolicyFileFixture::new();let saved=f.dir.join("saved.json");
        std::fs::rename(&f.file,&saved).unwrap();std::os::unix::fs::symlink(&saved,&f.file).unwrap();
        assert!(read_private_at(&f.dir,&f.digest).is_err());
    }
    #[cfg(unix)]
    #[test]
    fn private_policy_fifo_is_rejected_without_blocking() {
        let f=PolicyFileFixture::new();std::fs::remove_file(&f.file).unwrap();
        let name=std::ffi::CString::new(f.file.to_str().unwrap()).unwrap();assert_eq!(unsafe{libc::mkfifo(name.as_ptr(),0o600)},0);
        let started=std::time::Instant::now();assert!(read_private_at(&f.dir,&f.digest).is_err());assert!(started.elapsed()<std::time::Duration::from_secs(3));
    }
}
