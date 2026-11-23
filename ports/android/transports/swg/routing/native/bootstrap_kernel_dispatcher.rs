// Test-only inclusion INSIDE the original dispatcher module. This exercises
// its real run_tun/read/write/shutdown and the production bootstrap functions.
// The carrier address is synthetic; no provider authentication/worker is used.
pub async fn mcrf_owned_bootstrap_fixture(name:&str)->anyhow::Result<()>{
 let pool=PacketPool::new(256);let cancel=CancellationToken::new();let config=Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default()));let direct_ips=Arc::new(ArcSwap::from_pointee(HashSet::new()));let domains=Arc::new(crate::sni::DnsDomainMap::default());let(tx,mut rx)=mpsc::channel(128);
 let relay=Arc::new(crate::direct_relay::DirectRelay::new(config.clone(),direct_ips.clone(),domains.clone(),pool.clone(),tx)?);
 let dispatcher=Arc::new(Dispatcher{workers:ArcSwap::from_pointee(Vec::new()),inbound:ArrayQueue::new(128),outbound_capacity:128,tun_wake:Arc::new(Notify::new()),tun_io_lost:Arc::new(Notify::new()),cancel:cancel.clone(),tasks:tokio::sync::Mutex::new(Vec::new()),domain_routing:config,direct_ips,dns_domain_map:domains,direct_relay:Some(relay),uplink_state:Mutex::new(UplinkState::new()),pool:pool.clone(),reassembler:crate::wire_protocol::ShardedReassembler::new(),next_frag_id:std::sync::atomic::AtomicU16::new(1),termination:Arc::new(OnceLock::new()),mcrf_native_runtime:OnceLock::new()});
 let mut premature=pool.try_acquire().unwrap();assert!(crate::direct_relay::craft_udp_packet_into("192.0.2.7:53".parse().unwrap(),"10.89.0.2:45000".parse().unwrap(),&[1],premature.extend_tail(29).unwrap()));dispatcher.push_inbound(premature);assert!(dispatcher.pop_inbound().is_none(),"carrier ingress bypassed startup admission");
 let stopped=cancel.clone();std::thread::Builder::new().name("owned-bootstrap-input".into()).spawn(move||{use std::io::BufRead;for line in std::io::stdin().lock().lines(){let Ok(line)=line else{break};if line=="STOP"{break}
  let result=(||->Result<(),()>{if crate::mcrf_bs_bootstrap::control(&line)?{return Ok(())}if crate::mcrf_bs_owner::control_response(&line).map_err(|_|())?{return Ok(())}if crate::mcrf_bs_service::control(&line).map_err(|_|())?{return Ok(())}Err(())})();if result.is_err(){break}
 }stopped.cancel();})?;
 let pump=dispatcher.clone();let stopped=cancel.clone();dispatcher.tasks.lock().await.push(tokio::spawn(async move{loop{let packet=tokio::select!{biased;_=stopped.cancelled()=>break,packet=rx.recv()=>match packet{Some(packet)=>packet,None=>break}};pump.mcrf_native_inbound(packet)}}));
 let receiver=crate::tun::FdReceiver::bind(name)?;
 let configuration="TUNCONF:192.0.2.7:192.0.2.53:0:owned-fixture";
 crate::mcrf_bs_bootstrap::carrier_config(configuration)?;
 println!("__CSQTT_EVENT__|CONFIG|{}",serde_json::json!({"config":configuration}));
 println!("__CSQTT_EVENT__|READY|{{}}");
 let file=receiver.receive(&cancel).await?;let(_replacements,mut replacement_rx)=mpsc::channel(1);
 dispatcher.run_tun(file,pool.clone(),Arc::new(Stats::default()),&mut replacement_rx).await;
 dispatcher.shutdown().await;
 if !cancel.is_cancelled()||dispatcher.termination.get().is_some(){anyhow::bail!("OWNED_BOOTSTRAP_UNEXPECTED_STOP")}
 let mut reclaimed=Vec::new();while let Some(packet)=pool.try_acquire(){reclaimed.push(packet)}
 if reclaimed.len()!=256{anyhow::bail!("OWNED_BOOTSTRAP_BUFFER_LEAK")}drop(reclaimed);
 println!("OWNED_BOOTSTRAP_CLOSED");Ok(())
}
