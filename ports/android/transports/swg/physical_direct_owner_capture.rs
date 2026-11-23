// Appended only to the generated Direct-owner fixture dispatcher. The TUN
// reader and owner/dispatch admission come from the frozen production adapter.
use std::sync::atomic::{AtomicU8, Ordering as OwnedOrdering};

const OWNED_POSITIVE4: u8 = 1;
const OWNED_POSITIVE6: u8 = 2;
const OWNED_EXCLUDED4: u8 = 4;
const OWNED_EXCLUDED6: u8 = 8;
const OWNED_MISSING4: u8 = 16;
static OWNED_DIRECT_CAPTURE: OnceLock<(AtomicU8, AtomicU8)> = OnceLock::new();

fn owned_direct_case(bytes: &[u8]) -> Option<(u8, std::net::SocketAddr, std::net::SocketAddr, &[u8])> {
    use mcrf_bs_routing_policy::packet::PacketInfo;
    use std::net::{IpAddr, SocketAddr};
    let info = PacketInfo::read(bytes).ok()?;
    let (source_port, destination_port) = info.ports?;
    if info.protocol != 17 || info.fragment.is_some() || destination_port != 18443 {
        return None;
    }
    let ipv4 = info.destination.is_ipv4();
    let (source, destination) = if ipv4 {
        ("172.19.0.1", "198.18.0.2")
    } else {
        ("fdfe:dcba:9876::1", "2001:db8::2")
    };
    if info.source != source.parse::<IpAddr>().ok()? || info.destination != destination.parse::<IpAddr>().ok()? {
        return None;
    }
    let ip_header = if ipv4 { (bytes[0] as usize & 15) * 4 } else {
        if bytes[6] != 17 { return None }
        40
    };
    let payload = bytes.get(ip_header + 8..)?;
    let case = match payload {
        b"MCRF-OWNED-DIRECT-V4" if ipv4 => OWNED_POSITIVE4,
        b"MCRF-OWNED-DIRECT-V6" if !ipv4 => OWNED_POSITIVE6,
        b"MCRF-OWNED-DIRECT-MISSING" if ipv4 => OWNED_MISSING4,
        nonce if nonce.len() == 32 && nonce.iter().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte)) =>
            if ipv4 { OWNED_EXCLUDED4 } else { OWNED_EXCLUDED6 },
        _ => return None,
    };
    Some((case, SocketAddr::new(info.source, source_port), SocketAddr::new(info.destination, destination_port), payload))
}

async fn owned_direct_observe_ingress(packet: &PacketBuf) {
    let Some((seen, _)) = OWNED_DIRECT_CAPTURE.get() else { return };
    if let Some((case, _, _, _)) = owned_direct_case(packet.as_slice()) {
        seen.fetch_or(case, OwnedOrdering::AcqRel);
        if case == OWNED_MISSING4 {
            // The fixture controller closes this real UDP socket and confirms
            // Android has retired its owner before the ordinary broker query.
            tokio::time::sleep(Duration::from_millis(600)).await;
        }
    }
}

impl Dispatcher {
    fn owned_direct_capture_egress(&self, packet: &PacketBuf) {
        let Some((_, forwarded)) = OWNED_DIRECT_CAPTURE.get() else { return };
        let Some((case, local, destination, payload)) = owned_direct_case(packet.as_slice()) else { return };
        forwarded.fetch_or(case, OwnedOrdering::AcqRel);
        if case != OWNED_POSITIVE4 && case != OWNED_POSITIVE6 {
            return;
        }
        let Some(bytes) = crate::direct_relay::build_udp_packet(destination, local, payload) else { return };
        let Some(mut response) = self.pool.try_acquire() else { return };
        response.read_area()[..bytes.len()].copy_from_slice(&bytes);
        if response.set_read_len(bytes.len()).is_ok() {
            self.push_inbound(response);
        }
    }
}

pub async fn mcrf_owned_direct_owner_fixture(name: &str) -> anyhow::Result<()> {
    OWNED_DIRECT_CAPTURE.set((AtomicU8::new(0), AtomicU8::new(0)))
        .map_err(|_| anyhow::anyhow!("OWNED_DIRECT_CAPTURE_DUPLICATE"))?;
    let pool = PacketPool::new(256);
    let cancel = CancellationToken::new();
    let dispatcher = Arc::new(Dispatcher {
        workers: ArcSwap::from_pointee(Vec::new()), inbound: ArrayQueue::new(128),
        outbound_capacity: 128, tun_wake: Arc::new(Notify::new()), tun_io_lost: Arc::new(Notify::new()),
        cancel: cancel.clone(), tasks: tokio::sync::Mutex::new(Vec::new()),
        domain_routing: Arc::new(ArcSwap::from_pointee(DomainRoutingConfig::default())),
        direct_ips: Arc::new(ArcSwap::from_pointee(HashSet::new())),
        dns_domain_map: Arc::new(crate::sni::DnsDomainMap::default()), direct_relay: None,
        uplink_state: Mutex::new(UplinkState::new()), pool: pool.clone(),
        reassembler: crate::wire_protocol::ShardedReassembler::new(),
        next_frag_id: std::sync::atomic::AtomicU16::new(1), termination: Arc::new(OnceLock::new()),
        mcrf_native_runtime: OnceLock::new(),
    });
    let stopped = cancel.clone();
    std::thread::Builder::new().name("owned-direct-owner-input".into()).spawn(move || {
        use std::io::BufRead;
        for line in std::io::stdin().lock().lines() {
            let Ok(line) = line else { break };
            if line == "STOP" { break }
            match crate::mcrf_bs_owner::control_response(&line) {
                Ok(true) => continue,
                Ok(false) => if crate::mcrf_bs_service::control(&line).ok() != Some(true) { break },
                Err(_) => break,
            }
        }
        stopped.cancel();
    })?;
    let receiver = crate::tun::FdReceiver::bind(name)?;
    let configuration = "TUNCONF:172.19.0.1:172.19.0.2:0:owned-direct";
    println!("__CSQTT_EVENT__|CONFIG|{}", serde_json::json!({"config": configuration}));
    println!("__CSQTT_EVENT__|READY|{{}}");
    let file = receiver.receive(&cancel).await?;
    let (_sender, mut replacements) = mpsc::channel(1);
    dispatcher.run_tun(file, pool, Arc::new(Stats::default()), &mut replacements).await;
    dispatcher.shutdown().await;
    let (ingress, egress) = OWNED_DIRECT_CAPTURE.get().unwrap();
    let positives = OWNED_POSITIVE4 | OWNED_POSITIVE6;
    let expected = positives | OWNED_EXCLUDED4 | OWNED_EXCLUDED6 | OWNED_MISSING4;
    if dispatcher.termination.get().is_some() ||
        ingress.load(OwnedOrdering::Acquire) != expected || egress.load(OwnedOrdering::Acquire) != positives {
        anyhow::bail!("OWNED_DIRECT_CAPTURE_MISMATCH ingress={} egress={}",
            ingress.load(OwnedOrdering::Acquire), egress.load(OwnedOrdering::Acquire));
    }
    println!("OWNED_DIRECT_CAPTURE_CLOSED");
    Ok(())
}
