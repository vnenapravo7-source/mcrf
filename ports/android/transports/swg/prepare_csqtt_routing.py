"""Strict staged dispatcher hook. No Main activation or production capability.

Apply only after the existing direct-TUN overlay in a NEW owned source tree.
IP/port admission and optional owned owner-channel reject unattached metadata.
Full domain/DNS/owner/geo/lifecycle acceptance is still required before packaging.
"""
from pathlib import Path
import hashlib
import re
import shutil

HERE=Path(__file__).resolve().parent

def once(text,old,new):
    if text.count(old)!=1:
        raise ValueError('BS routing source boundary changed: '+old[:64])
    return text.replace(old,new,1)

def atomic_tun_enqueue(text):
    return once(text,'''        let mut packet = packet;
        if let Err(returned) = self.inbound.push(packet) {
            packet = returned;
            let evicted = self.inbound.pop();
            drop(evicted);
            let _ = self.inbound.push(packet);''','''        // An atomic replacement cannot lose the new packet to another producer
        // between dropping the old packet and publishing the new one.
        if self.inbound.force_push(packet).is_some() {''')

def runtime_dispatcher(text,generation='2.5.3'):
    if generation not in ('2.5.1','2.5.2','2.5.3'):raise ValueError('Unsupported native routing generation')
    text=once(text,'    termination: Arc<OnceLock<Termination>>,\n}\n\nimpl Dispatcher {','    termination: Arc<OnceLock<Termination>>,\n    #[cfg(unix)]\n    mcrf_native_runtime: OnceLock<Arc<crate::mcrf_bs_dispatch_runtime::Runtime>>,\n}\n\nimpl Dispatcher {')
    constructor='            termination: Arc::new(OnceLock::new()),'
    if text.count(constructor)!=(6 if generation=='2.5.1' else 7):raise ValueError('Native dispatcher constructors changed')
    text=text.replace(constructor,constructor+'\n            #[cfg(unix)]\n            mcrf_native_runtime: OnceLock::new(),')
    start=text.index('    pub async fn dispatch(&self, packet: PacketBuf) {')
    tail=text.index('        let workers = self.workers.load();',start)
    end=text.index('    #[cfg(unix)]\n    async fn write_tun(',tail)
    # Move, rather than duplicate, the original cipher/fragment/worker path.
    text=text[:tail]+'        self.mcrf_native_tunnel(packet).await;\n    }\n\n    pub async fn mcrf_native_tunnel(&self, packet: PacketBuf) {\n'+text[tail:end]+text[end:]
    text=once(text,'    pub async fn dispatch(&self, packet: PacketBuf) {','    pub async fn dispatch(&self, packet: PacketBuf) {\n        #[cfg(unix)]\n        if let Some(runtime) = self.mcrf_native_runtime.get() { runtime.consume(packet).await; return; }\n        #[cfg(unix)]\n        if crate::mcrf_bs_bootstrap::required() { return; }')
    text=once(text,'    pub fn push_inbound(&self, packet: PacketBuf) {','''    #[cfg(unix)]
    pub fn mcrf_attach_native_runtime(&self, runtime: Arc<crate::mcrf_bs_dispatch_runtime::Runtime>) -> Result<(), ()> { self.mcrf_native_runtime.set(runtime).map_err(|_| ()) }
    pub fn mcrf_native_relay(&self) -> Option<Arc<crate::direct_relay::DirectRelay>> { self.direct_relay.clone() }
    pub fn mcrf_native_pool(&self) -> Arc<PacketPool> { self.pool.clone() }
    pub fn mcrf_native_fault(&self) { if !self.cancel.is_cancelled() { self.terminate(Termination::TunLost); } }
    pub fn push_inbound(&self, packet: PacketBuf) {
        #[cfg(unix)]
        if let Some(runtime) = self.mcrf_native_runtime.get() {
            let mut packet = packet;
            strip_legacy_downlink_prefix(&mut packet);
            match runtime.incoming(packet) { Ok(Some(packet)) => self.mcrf_native_inbound(packet), Ok(None) => {}, Err(error) => runtime.reject(error) }
            return;
        }
        #[cfg(unix)]
        if crate::mcrf_bs_bootstrap::required() { return; }
        self.mcrf_native_inbound(packet);
    }
    pub fn mcrf_native_inbound(&self, packet: PacketBuf) {''')
    text=atomic_tun_enqueue(text)
    text=once(text,'pump_dispatcher.push_inbound(pkt);','pump_dispatcher.mcrf_native_inbound(pkt);')
    text=once(text,'    pub async fn shutdown(&self) {\n        self.cancel.cancel();','    pub async fn shutdown(&self) {\n        self.cancel.cancel();\n        #[cfg(unix)]\n        if let Some(runtime) = self.mcrf_native_runtime.get() {\n            if runtime.close().await.is_err() { crate::log_error!("BS_NATIVE_SCOPE_CLOSE_FAILED"); }\n        }')
    text=once(text,'            let _ = task.await;\n        }\n    }','            let _ = task.await;\n        }\n        #[cfg(unix)]\n        if self.mcrf_native_runtime.get().is_some() { while self.inbound.pop().is_some() {} }\n    }')
    text=once(text,'        for task in self.tasks.lock().await.drain(..) {','        #[cfg(unix)]\n        crate::mcrf_bs_bootstrap::stop();\n        for task in self.tasks.lock().await.drain(..) {')
    text=once(text,'                        if self.workers.load().is_empty() {','                        if self.workers.load().is_empty() && self.mcrf_native_runtime.get().is_none() {')
    text=once(text,'            let generation_cancel = self.cancel.child_token();','            if let Err(error) = crate::mcrf_bs_bootstrap::attach(self, &self.cancel).await {\n                crate::log_error!("{error:#}"); self.terminate(Termination::TunLost); return;\n            }\n            let generation_cancel = self.cancel.child_token();')
    return text+'\n#[cfg(all(test, unix))]\n#[path="mcrf_native_runtime_tests.rs"]\nmod mcrf_native_runtime_tests;\n'

def runtime_packet(text):
    text=once(text,'    pool: Arc<PacketPool>,\n}', '    pool: Arc<PacketPool>,\n    #[cfg(unix)]\n    mcrf_raw_guard: Option<Arc<crate::mcrf_bs_raw_flow::Lease>>,\n}')
    text=once(text,'            pool: self.clone(),','            pool: self.clone(),\n            #[cfg(unix)]\n            mcrf_raw_guard: None,')
    text=once(text,'impl PacketBuf {','''impl PacketBuf {
    #[cfg(unix)]
    pub fn mcrf_keep_raw(&mut self, guard: Arc<crate::mcrf_bs_raw_flow::Lease>) -> Result<(), ()> {
        if self.mcrf_raw_guard.is_some() { return Err(()); } self.mcrf_raw_guard = Some(guard); Ok(())
    }
    ''')
    return once(text,'        self.pool.release(mem::take(&mut self.storage));','        #[cfg(unix)]\n        { self.mcrf_raw_guard.take(); }\n        self.pool.release(mem::take(&mut self.storage));')

def transform(main,dispatcher,direct,cargo,generation='2.5.3'):
    main=once(main,'mod mcrf_direct_tun;','mod mcrf_direct_tun;\nmod mcrf_bs_routing;\nmod mcrf_bs_owner;\nmod mcrf_bs_network;\nmod mcrf_bs_service;\nmod mcrf_bs_dns;')
    main=once(main,'    mcrf_direct_tun::configure(&arguments.tun_uds)?;',
              '    mcrf_direct_tun::configure(&arguments.tun_uds)?;\n    mcrf_bs_network::configure(mcrf_direct_tun::active())?;\n    mcrf_bs_routing::configure()?;')
    main=once(main,'            let line = line.trim();',
              '            let line = line.trim();\n            match mcrf_bs_service::control(line) {\n                Ok(true) => continue,\n                Ok(false) => {},\n                Err(_) => { cancel.cancel(); return; }\n            }\n            match mcrf_bs_owner::control_response(line) {\n                Ok(true) => continue,\n                Ok(false) => {},\n                Err(_) => { cancel.cancel(); return; }\n            }')
    main=once(main,'            match mcrf_bs_service::control(line) {',
              '            match mcrf_bs_dns::control(line) {\n                Ok(true) => continue,\n                Ok(false) => {},\n                Err(_) => { cancel.cancel(); return; }\n            }\n            match mcrf_bs_service::control(line) {')
    main=once(main,'mod mcrf_bs_dns;','mod mcrf_bs_dns;\nmod mcrf_bs_dns_transport;')
    main=once(main,'mod mcrf_bs_dns_transport;','mod mcrf_bs_dns_transport;\n#[cfg(unix)]\nmod mcrf_bs_dns_rpc;')
    main=once(main,'mod mcrf_bs_dns_transport;','mod mcrf_bs_dns_transport;\nmod mcrf_bs_dns_plan;\nmod mcrf_bs_dns_system;\nmod mcrf_bs_dns_resolver;')
    main=once(main,'mod mcrf_bs_dns_resolver;','mod mcrf_bs_dns_resolver;\nmod mcrf_bs_tcp_inspect;')
    main=once(main,'mod mcrf_bs_tcp_inspect;','mod mcrf_bs_tcp_inspect;\nmod mcrf_bs_quic_inspect;')
    main=once(main,'mod mcrf_bs_quic_inspect;','mod mcrf_bs_quic_inspect;\nmod mcrf_bs_udp_inspect;')
    main=once(main,'mod mcrf_bs_udp_inspect;','mod mcrf_bs_udp_inspect;\n#[cfg(unix)]\nmod mcrf_bs_udp_policy;')
    main=once(main,'mod mcrf_bs_udp_inspect;','mod mcrf_bs_udp_inspect;\nmod mcrf_bs_datagram;')
    main=once(main,'mod mcrf_bs_udp_policy;','mod mcrf_bs_udp_policy;\n#[cfg(unix)]\nmod mcrf_bs_datagram_slots;')
    main=once(main,'mod mcrf_bs_datagram_slots;','mod mcrf_bs_datagram_slots;\n#[cfg(unix)]\nmod mcrf_bs_udp_dispatch;')
    main=once(main,'mod mcrf_bs_udp_dispatch;','mod mcrf_bs_udp_dispatch;\n#[cfg(unix)]\nmod mcrf_bs_udp_ingress;')
    main=once(main,'mod mcrf_bs_udp_ingress;','mod mcrf_bs_udp_ingress;\n#[cfg(unix)]\nmod mcrf_bs_raw_flow;')
    main=once(main,'mod mcrf_bs_raw_flow;','mod mcrf_bs_raw_flow;\n#[cfg(unix)]\nmod mcrf_bs_tcp_ingress;')
    main=once(main,'mod mcrf_bs_tcp_ingress;','mod mcrf_bs_tcp_ingress;\n#[cfg(unix)]\nmod mcrf_bs_dispatch_runtime;')
    main=once(main,'mod mcrf_bs_udp_dispatch;','mod mcrf_bs_udp_dispatch;\n#[cfg(unix)]\nmod mcrf_bs_udp_transport;')
    main=once(main,'mod mcrf_bs_udp_transport;','mod mcrf_bs_udp_transport;\n#[cfg(unix)]\nmod mcrf_bs_session;')
    main=once(main,'mod mcrf_bs_session;','mod mcrf_bs_session;\n#[cfg(unix)]\nmod mcrf_bs_carrier_nat;')
    main=once(main,'mod mcrf_bs_carrier_nat;','mod mcrf_bs_carrier_nat;\n#[cfg(unix)]\nmod mcrf_bs_service_ingress;')
    main=once(main,'mod mcrf_bs_dns_resolver;','mod mcrf_bs_dns_resolver;\nmod mcrf_bs_dns_engine;')
    main=once(main,'mod mcrf_bs_dns_engine;','mod mcrf_bs_dns_engine;\n#[cfg(unix)]\nmod mcrf_bs_dns_handler;')
    main=once(main,'mod mcrf_bs_dns_handler;','mod mcrf_bs_dns_handler;\n#[cfg(unix)]\nmod mcrf_bs_dns_ingress;')
    main=once(main,'mod mcrf_bs_dns_handler;','mod mcrf_bs_dns_handler;\n#[cfg(unix)]\nmod mcrf_bs_stream_slots;')
    main=once(main,'mod mcrf_bs_dns_ingress;','mod mcrf_bs_dns_ingress;\n#[cfg(unix)]\nmod mcrf_bs_dns_frontend;')
    main=once(main,'mod mcrf_bs_tcp_inspect;','mod mcrf_bs_tcp_inspect;\nmod mcrf_bs_tcp_dispatch;')
    main=once(main,'mod mcrf_bs_tcp_dispatch;','mod mcrf_bs_tcp_dispatch;\n#[cfg(unix)]\nmod mcrf_bs_tcp_policy;')
    direct=once(direct,'    shutdown: CancellationToken,','    shutdown: CancellationToken,\n    mcrf_tcp_handler: Arc<std::sync::OnceLock<crate::mcrf_bs_tcp_dispatch::Handler>>,')
    direct=once(direct,'        let active_tcp = Arc::new(RwLock::new(HashMap::new()));','        let mcrf_tcp_handler = Arc::new(std::sync::OnceLock::<crate::mcrf_bs_tcp_dispatch::Handler>::new());\n        let stream_handler = mcrf_tcp_handler.clone();\n        let active_tcp = Arc::new(RwLock::new(HashMap::new()));')
    direct=once(direct,'                    tokio::spawn(async move {\n                        let connect_res','                    let handler = stream_handler.get().cloned();\n                    let stopped = shutdown_listener.child_token();\n                    tokio::spawn(async move {\n                        if let Some(handler) = handler {\n                            tokio::select! { biased;\n                                _ = stopped.cancelled() => {},\n                                _ = handler(Box::new(client_stream), local_addr, remote_addr, stopped.clone()) => {},\n                            }\n                        } else {\n                        let connect_res')
    direct=once(direct,'                        if let Some(handler) = handler {\n                            tokio::select! { biased;', '                        if let Some(handler) = handler {\n                            let established = tokio::select! { biased;\n                                _ = stopped.cancelled() => false,\n                                result = tokio::time::timeout(std::time::Duration::from_secs(5), client_stream.mcrf_wait_established()) => matches!(result, Ok(Ok(()))),\n                            };\n                            if !established { return; }\n                            tokio::select! { biased;')
    direct=once(direct,'                        drop(client_stream);','                        } // No native-handler failure may enter the legacy connector.')
    direct=once(direct,'            preresolve_generation,\n            shutdown,','            preresolve_generation,\n            shutdown,\n            mcrf_tcp_handler,')
    direct=once(direct,'    pub fn sync_domain_routing(','    pub fn mcrf_attach_tcp_handler(&self, handler: crate::mcrf_bs_tcp_dispatch::Handler) -> Result<(), ()> {\n        self.mcrf_tcp_handler.set(handler).map_err(|_| ())\n    }\n\n    pub fn sync_domain_routing(')
    anchor='    pub async fn dispatch(&self, packet: PacketBuf) {\n        self.inspect_domain_routing(&packet);'
    hook='''    pub async fn dispatch(&self, packet: PacketBuf) {
        let mcrf_route = crate::mcrf_bs_routing::classify(packet.as_slice(), &self.cancel).await;
        match mcrf_route {
            Some(mcrf_bs_routing_policy::Target::Block) => return,
            Some(mcrf_bs_routing_policy::Target::Direct) => {
                if let Some(relay) = &self.direct_relay { relay.mcrf_policy_direct(&packet).await; }
                return; // A failed direct send must NEVER enter the tunnel.
            }
            _ => {}
        }
        if mcrf_route.is_none() { self.inspect_domain_routing(&packet); }'''
    dispatcher=once(dispatcher,anchor,hook)
    ingress='                        self.dispatch(packet).await;'
    if dispatcher.count(ingress)!=2:raise ValueError('BS routed ingress task boundary changed')
    dispatcher=dispatcher.replace(ingress,'                        if let Some(packet)=crate::mcrf_bs_routing::defer(&self,packet,&self.cancel) { self.dispatch(packet).await; }')
    dispatcher=once(dispatcher,'        if let Some(relay) = &self.direct_relay\n            && relay.try_handle(&packet).await',
                    '        if mcrf_route.is_none()\n            && let Some(relay) = &self.direct_relay\n            && relay.try_handle(&packet).await')
    # Force direct only after the MCRF decision; never reuse the original
    # global domain/IP verdict or let an unsupported direct packet tunnel.
    method='''    pub async fn mcrf_policy_direct(&self, packet: &PacketBuf) -> bool {
        let slice = packet.as_slice();
        let Ok(info) = mcrf_bs_routing_policy::packet::PacketInfo::read(slice) else { return false; };
        if info.fragment.is_some() { return false; }
        let Some((source_port, port)) = info.ports else { return false; };
        let source = SocketAddr::new(info.source, source_port);
        let destination = SocketAddr::new(info.destination, port);
        match info.protocol {
            6 => self.direct_pkt_tx.try_send(slice.to_vec()).is_ok(),
            17 => {
                // This first attachment admits the ordinary IP header shape.
                // Extension-aware UDP payload extraction is a separate gate.
                let offset = if info.source.is_ipv4() { (slice[0] & 15) as usize * 4 } else { 40 };
                if !info.source.is_ipv4() && slice[6] != 17 { return false; }
                self.mcrf_direct_udp_payload(&slice[offset+8..], source, destination).await
            }
            _ => false,
        }
    }

'''
    direct=once(direct,'    pub async fn try_handle(&self, packet: &PacketBuf) -> bool {',method+'    pub async fn try_handle(&self, packet: &PacketBuf) -> bool {')
    direct=once(direct,'        let existing_sock = {','''        self.mcrf_direct_udp_payload(payload, src_addr, dst_addr).await
    }

    async fn mcrf_direct_udp_payload(&self, payload: &[u8], src_addr: SocketAddr, dst_addr: SocketAddr) -> bool {
        let session_key = (src_addr, dst_addr);
        let existing_sock = {''')
    direct=once(direct,'udp_sessions: Arc<RwLock<HashMap<SocketAddr, Arc<tokio::net::UdpSocket>>>>,',
                'udp_sessions: Arc<RwLock<HashMap<(SocketAddr, SocketAddr), Arc<tokio::net::UdpSocket>>>>,')
    # One authenticated peer per direct UDP association. Neither another
    # address family nor an unsolicited source shares the old source-only key.
    for old,new in [('guard.get(&src_addr)','guard.get(&session_key)'),('guard.insert(src_addr, new_sock.clone())','guard.insert(session_key, new_sock.clone())'),('.get(&client_addr)','.get(&session_key)'),('guard.remove(&client_addr)','guard.remove(&session_key)')]:
        count=direct.count(old)
        if count != (2 if old=='guard.get(&src_addr)' else 1):
            raise ValueError('BS UDP association boundary changed')
        direct=direct.replace(old,new)
    direct=once(direct,'            let socket_ref = socket2::SockRef::from(&new_sock);',
                '            if new_sock.connect(dst_addr).await.is_err() { return false; }\n            let socket_ref = socket2::SockRef::from(&new_sock);')
    cargo=once(cargo,'[dependencies]','[dependencies]\nmcrf-bs-routing-policy = { path = "mcrf-routing-policy" }')
    direct+='\n#[cfg(test)]\n#[path="mcrf_routing_relay_tests.rs"]\nmod mcrf_routing_relay_tests;\n'
    main=once(main,'mod mcrf_bs_dispatch_runtime;','mod mcrf_bs_dispatch_runtime;\n#[cfg(unix)]\nmod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_perf;')
    main=once(main,'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_health;')
    main=once(main,'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_certificates;')
    main=once(main,'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_proxy_protocol;')
    main=once(main,'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_proxy_stream;')
    main=once(main,'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_proxy_udp;')
    if generation=='2.5.3':
        main+='\n#[cfg(unix)]\npub fn mcrf_native_router_run() { mcrf_bs_bootstrap::native_router_main(); }\n'
    main=once(main,'mod mcrf_bs_proxy_stream;','mod mcrf_bs_proxy_stream;\n#[cfg(unix)]\nmod mcrf_bs_proxy_backend;')
    main=once(main,'mod mcrf_bs_proxy_protocol;','mod mcrf_bs_proxy_protocol;\n#[cfg(unix)]\nmod mcrf_bs_proxy_listener;')
    main=once(main,'async fn run(arguments: Arguments) -> Result<i32> {','async fn run(arguments: Arguments) -> Result<i32> {\n    #[cfg(unix)]\n    if mcrf_bs_bootstrap::admit_only()? { return Ok(0); }')
    main=once(main,'    mcrf_bs_routing::configure()?;','    mcrf_bs_routing::configure()?;\n    #[cfg(unix)]\n    mcrf_bs_bootstrap::configure()?;\n    #[cfg(unix)]\n    if let Some(code) = mcrf_bs_bootstrap::run_router_only(&arguments.tun_uds, !arguments.peer.is_empty() || !arguments.vk.is_empty()).await? { return Ok(code); }')
    main=once(main,'            match mcrf_bs_dns::control(line) {','            #[cfg(unix)]\n            match mcrf_bs_bootstrap::control(line) {\n                Ok(true) => continue,\n                Ok(false) => {},\n                Err(_) => { cancel.cancel(); return; }\n            }\n            match mcrf_bs_dns::control(line) {')
    main=once(main,'            config_events.config(&config);','            #[cfg(unix)]\n            if let Err(error) = mcrf_bs_bootstrap::carrier_config(&config) {\n                crate::log_error!("{error:#}"); config_cancel.cancel(); return;\n            }\n            config_events.config(&config);')
    main=once(main,'    let config_events = events.clone();','    let config_events = events.clone();\n    #[cfg(unix)]\n    let config_cancel = cancel.clone();')
    return main,runtime_dispatcher(dispatcher,generation),direct,cargo

def atomic_log_lines(text):
    # Control messages also use stdout. Hold its internal lock across the
    # whole line so another writer cannot append a marker before the newline.
    return once(text,'''            self.writer.write_all(value.as_bytes())?;
            if repetitions > 1 {
                write!(self.writer, " (x{repetitions})")?;
            }
            self.writer.write_all(b"\\n")''','''            let mut line = String::with_capacity(value.len() + 24);
            line.push_str(value);
            if repetitions > 1 {
                use std::fmt::Write as _;
                write!(&mut line, " (x{repetitions})").expect("writing to String");
            }
            line.push('\\n');
            self.writer.write_all(line.as_bytes())''')

def stable_worker_snapshot(dispatcher):
    dispatcher=once(dispatcher,'pub struct UplinkState {','pub struct UplinkState {\n    worker_snapshot: Option<std::sync::Weak<Vec<WorkerEntry>>>,')
    dispatcher=once(dispatcher,'impl UplinkState {','''impl UplinkState {
    fn use_worker_snapshot(&mut self, workers: &Arc<Vec<WorkerEntry>>) -> bool {
        if self.worker_snapshot.as_ref().is_some_and(|old| old.as_ptr() == Arc::as_ptr(workers)) {
            return false;
        }
        // Rates and flow pins are indexed into this immutable worker vector.
        // Equal length does not imply equal positions or incarnations.
        *self = Self { worker_snapshot: Some(Arc::downgrade(workers)), ..Self::default() };
        true
    }
''')
    dispatcher=once(dispatcher,'async fn send_to_workers(&self, mut packet: PacketBuf, workers: &[WorkerEntry])',
        'async fn send_to_workers(&self, mut packet: PacketBuf, workers: &Arc<Vec<WorkerEntry>>)')
    dispatcher=once(dispatcher,'        fragments: Vec<PacketBuf>,\n        workers: &[WorkerEntry],',
        '        fragments: Vec<PacketBuf>,\n        workers: &Arc<Vec<WorkerEntry>>,')
    anchor='            state.select_deficit_waterfall('
    if dispatcher.count(anchor)!=2:raise ValueError('Worker selection boundaries changed')
    dispatcher=dispatcher.replace(anchor,'''            if state.use_worker_snapshot(workers) {
                #[cfg(unix)]
                crate::mcrf_bs_perf::WORKER_SET_CHANGES.fetch_add(1, Ordering::Relaxed);
            }
'''+anchor)
    # Errors from a worker snapshot already replaced must not penalize its successor.
    dispatcher=dispatcher.replace('self.mark_uplink_congested(selected_idx);','self.mark_uplink_congested(workers, selected_idx);')
    dispatcher=once(dispatcher,'    fn mark_uplink_congested(&self, idx: usize) {',
        '    fn mark_uplink_congested(&self, workers: &Arc<Vec<WorkerEntry>>, idx: usize) {')
    dispatcher=once(dispatcher,'        if let Some(controller) = state.rates.get_mut(idx) {',
        '        if state.worker_snapshot.as_ref().is_none_or(|snapshot| snapshot.as_ptr() != Arc::as_ptr(workers)) { return; }\n        if let Some(controller) = state.rates.get_mut(idx) {')
    return dispatcher+'''
#[cfg(test)]
#[test]
fn worker_snapshot_change_resets_old_rate_slots_even_at_equal_length() {
    let first = Arc::new(Vec::new());
    let replacement = Arc::new(Vec::new());
    let mut state = UplinkState::default();
    assert!(state.use_worker_snapshot(&first));
    state.tokens.push(-4000.0);
    assert!(!state.use_worker_snapshot(&first));
    assert_eq!(state.tokens, [-4000.0]);
    assert!(state.use_worker_snapshot(&replacement));
    assert!(state.tokens.is_empty());
}
'''

def traffic_diagnostics(main,dispatcher,turn,stats):
    dispatcher=once(dispatcher,'        if self.inbound.force_push(packet).is_some() {',
        '        if self.inbound.force_push(packet).is_some() {\n            #[cfg(unix)]\n            crate::mcrf_bs_perf::TUN_QUEUE_DROPS.fetch_add(1, Ordering::Relaxed);')
    for statement in ('log_dropped_packet(&mut dropped, "запись вернула 0 байт");',
                      'log_dropped_packet(&mut dropped, &error.to_string());'):
        dispatcher=once(dispatcher,statement,
            'crate::mcrf_bs_perf::TUN_WRITE_DROPS.fetch_add(1, Ordering::Relaxed);\n                        '+statement)
    start=turn.index('async fn outbound_writer_loop(')
    end=turn.index('\nenum TurnInbound',start)
    def count_udp_drop(match):
        indent=match[1][:-4]
        return f"{match[0]}\n{indent}#[cfg(unix)]\n{indent}crate::mcrf_bs_perf::UDP_WRITE_DROPS.fetch_add(1, Ordering::Relaxed);"
    writer,count=re.subn(r'(?m)^([ ]*)\.fetch_add\(1, Ordering::Relaxed\);$',count_udp_drop,turn[start:end])
    if count!=4:raise ValueError('TURN outbound writer counters changed')
    turn=turn[:start]+writer+turn[end:]
    turn=once(turn,'                let mut packet = packet;\n                loop {',
        '                let mut packet = packet;\n                #[cfg(unix)]\n                let mut wait = None;\n                loop {')
    turn=once(turn,'                    let _ = tokio::time::timeout(OUTBOUND_IDLE_POLL, space).await;',
        '                    #[cfg(unix)]\n                    wait.get_or_insert_with(crate::mcrf_bs_perf::TurnWait::start);\n                    let _ = tokio::time::timeout(OUTBOUND_IDLE_POLL, space).await;')
    stats=once(stats,'        let mut last_emitted: Option<Snapshot> = None;',
        '        let mut last_emitted: Option<Snapshot> = None;\n        #[cfg(unix)]\n        let mut traffic = crate::mcrf_bs_perf::Reporter::from_env();')
    stats=once(stats,'                    let now = (active, up, down);',
        '                    let now = (active, up, down);\n                    #[cfg(unix)]\n                    if let Some(traffic) = traffic.as_mut() { traffic.tick(active, up, down); }')
    return main,stable_worker_snapshot(dispatcher),turn,stats

def prepare(root,generation='2.5.3'):
    root=Path(root)
    paths=[root/name for name in ('main.rs','dispatcher.rs','direct_relay.rs','Cargo.toml')]
    before=[p.read_text(encoding='utf-8') for p in paths]
    after=transform(*before,generation=generation) # Complete preflight BEFORE the first write.
    after=(once(after[0],'mod mcrf_bs_bootstrap;','mod mcrf_bs_bootstrap;\n#[cfg(unix)]\nmod mcrf_bs_server_routing;'),*after[1:])
    events=root/'events.rs'
    events_after=once(events.read_text(encoding='utf-8'),
        '        self.emit("ROUTING", || serde_json::json!({"routing": routing_json}));',
        '''        #[cfg(unix)]
        if crate::mcrf_bs_bootstrap::required() {
            if crate::mcrf_bs_server_routing::announce(routing_json).is_err() {
                self.error("ROUTING_INVALID", "Server routing rejected", true);
            }
            return;
        }
        self.emit("ROUTING", || serde_json::json!({"routing": routing_json}));''')
    session=root/'session.rs'
    session_after=once(session.read_text(encoding='utf-8'),'        let chunk_data = parts.next()?;',
        '        let chunk_data = parts.next()?;\n        if chunk_data.len()>1024 { return Some("INVALID_ROUTING".to_owned()); }')
    session_after=once(session_after,'            let mut gz = GzDecoder::new(&decoded[..]);',
        '            let mut gz = GzDecoder::new(&decoded[..]).take(4*1024*1024+1);')
    session_after=once(session_after,'            if gz.read_to_string(&mut decompressed).is_ok() {',
        '            if gz.read_to_string(&mut decompressed).is_ok() && decompressed.len()<=4*1024*1024 {')
    session_after=once(session_after,'        String::from_utf8(decoded).ok()\n    }\n}',
        '''        if decoded.len()>4*1024*1024 { return Some("INVALID_ROUTING".to_owned()); }
        Some(String::from_utf8(decoded).unwrap_or_else(|_| "INVALID_ROUTING".to_owned()))
    }
}''')
    packet=root/'packet.rs'
    packet_after=runtime_packet(packet.read_text(encoding='utf-8'))
    logging=root/'logging.rs'
    logging_after=atomic_log_lines(logging.read_text(encoding='utf-8'))
    if generation=='2.5.3':
        turn,stats=root/'turn.rs',root/'stats.rs'
        main,dispatcher,turn_after,stats_after=traffic_diagnostics(after[0],after[1],turn.read_text(encoding='utf-8'),stats.read_text(encoding='utf-8'))
        after=(main,dispatcher,*after[2:])
    # Dropping an inspected stream after shutdown must not overwrite the
    # graceful Close/Closing state and discard queued response bytes. This is
    # a staged vendor patch, scoped to the new native routing adapter only.
    stack=root/'vendor/netstack-smoltcp/src/tcp.rs'
    stack_before=stack.read_text(encoding='utf-8')
    stack_after=once(stack_before,'impl Drop for TcpStream {\n    fn drop(&mut self) {\n        let mut control = self.control.lock();\n        control.recv_state = TcpSocketState::Closed;\n        control.send_state = TcpSocketState::Closed;\n','impl Drop for TcpStream {\n    fn drop(&mut self) {\n        let mut control = self.control.lock();\n        control.recv_state = TcpSocketState::Closed;\n        if matches!(control.send_state, TcpSocketState::Normal) {\n            control.send_state = TcpSocketState::Close;\n        }\n')
    # The original listener yields at SYN, before the app's ACK. Do not expose
    # such a stream to verified routing admission until the stack confirms the
    # connection. A dedicated waker also terminates pending admission on RST.
    stack_after=once(stack_after,'    send_state: TcpSocketState,\n}', '    send_state: TcpSocketState,\n    mcrf_established: bool,\n    mcrf_connect_waker: Option<Waker>,\n}')
    stack_after=once(stack_after,'                    send_state: TcpSocketState::Normal,\n', '                    send_state: TcpSocketState::Normal,\n                    mcrf_established: false,\n                    mcrf_connect_waker: None,\n')
    stack_after=once(stack_after,'                let mut control = control.lock();\n\n                if socket.state() == TcpState::Closed', '                let mut control = control.lock();\n                if !control.mcrf_established && matches!(socket.state(), TcpState::Established | TcpState::CloseWait | TcpState::FinWait1 | TcpState::FinWait2 | TcpState::Closing | TcpState::LastAck | TcpState::TimeWait) {\n                    control.mcrf_established = true;\n                    if let Some(waker) = control.mcrf_connect_waker.take() { waker.wake(); }\n                }\n\n                if socket.state() == TcpState::Closed')
    stack_after=once(stack_after,'                    control.send_state = TcpSocketState::Closed;\n                    control.recv_state = TcpSocketState::Closed;\n', '                    control.send_state = TcpSocketState::Closed;\n                    control.recv_state = TcpSocketState::Closed;\n                    if let Some(waker) = control.mcrf_connect_waker.take() { waker.wake(); }\n')
    stack_after=once(stack_after,'impl TcpStream {\n    pub fn local_addr', 'impl TcpStream {\n    pub async fn mcrf_wait_established(&self) -> std::io::Result<()> {\n        std::future::poll_fn(|cx| {\n            let mut control = self.control.lock();\n            if matches!(control.send_state, TcpSocketState::Closed) && matches!(control.recv_state, TcpSocketState::Closed) {\n                return Poll::Ready(Err(std::io::ErrorKind::ConnectionAborted.into()));\n            }\n            if control.mcrf_established { return Poll::Ready(Ok(())); }\n            control.mcrf_connect_waker = Some(cx.waker().clone());\n            self.notify.notify_one();\n            Poll::Pending\n        }).await\n    }\n\n    pub fn local_addr')
    if (root/'mcrf-routing-policy').exists() or (root/'mcrf_bs_routing.rs').exists():
        raise ValueError('Routing stage must be fresh')
    shutil.copytree(HERE/'routing',root/'mcrf-routing-policy',ignore=shutil.ignore_patterns('target','native'))
    shutil.copyfile(HERE/'routing/native/mcrf_bs_routing.rs',root/'mcrf_bs_routing.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_routing_relay_tests.rs',root/'mcrf_routing_relay_tests.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_owner.rs',root/'mcrf_bs_owner.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_network.rs',root/'mcrf_bs_network.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_service.rs',root/'mcrf_bs_service.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dns.rs',root/'mcrf_bs_dns.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dns_transport.rs',root/'mcrf_bs_dns_transport.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dns_transport_tests.rs',root/'mcrf_bs_dns_transport_tests.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dns_rpc.rs',root/'mcrf_bs_dns_rpc.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dns_rpc_tests.rs',root/'mcrf_bs_dns_rpc_tests.rs')
    for name in ('mcrf_bs_dns_plan.rs','mcrf_bs_dns_system.rs','mcrf_bs_dns_resolver.rs','mcrf_bs_tcp_inspect.rs','mcrf_bs_tcp_dispatch.rs','mcrf_bs_tcp_policy.rs','mcrf_bs_quic_inspect.rs','mcrf_bs_udp_inspect.rs','mcrf_bs_udp_policy.rs','mcrf_bs_datagram_slots.rs','mcrf_bs_udp_dispatch.rs','mcrf_bs_udp_transport.rs','mcrf_bs_dns_engine.rs','mcrf_bs_dns_handler.rs','mcrf_bs_stream_slots.rs','mcrf_bs_dns_ingress.rs','mcrf_bs_dns_frontend.rs'):
        shutil.copyfile(HERE/'routing/native'/name,root/name)
    for path,text in zip(paths,after):path.write_text(text,encoding='utf-8')
    stack.write_text(stack_after,encoding='utf-8')
    packet.write_text(packet_after,encoding='utf-8')
    logging.write_text(logging_after,encoding='utf-8')
    events.write_text(events_after,encoding='utf-8')
    session.write_text(session_after,encoding='utf-8')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_server_routing.rs',root/'mcrf_bs_server_routing.rs')
    files=[*paths,stack,packet,logging,root/'mcrf_bs_routing.rs',root/'mcrf_bs_owner.rs',root/'mcrf_bs_network.rs',root/'mcrf_bs_service.rs',root/'mcrf_bs_dns.rs',root/'mcrf_bs_dns_transport.rs',root/'mcrf_bs_dns_transport_tests.rs',root/'mcrf_bs_dns_rpc.rs',root/'mcrf_bs_dns_rpc_tests.rs',root/'mcrf_bs_dns_plan.rs',root/'mcrf_bs_dns_system.rs',root/'mcrf_routing_relay_tests.rs']
    perf=root/'mcrf_bs_perf.rs'
    shutil.copyfile(HERE/'routing/native'/perf.name,perf)
    files.append(perf)
    files.extend((events,session,root/'mcrf_bs_server_routing.rs'))
    if generation=='2.5.3':
        turn.write_text(turn_after,encoding='utf-8')
        stats.write_text(stats_after,encoding='utf-8')
        files.extend((turn,stats))
    files.append(root/'mcrf_bs_dns_resolver.rs')
    files.append(root/'mcrf_bs_tcp_inspect.rs')
    files.append(root/'mcrf_bs_tcp_dispatch.rs')
    files.append(root/'mcrf_bs_tcp_policy.rs')
    files.append(root/'mcrf_bs_quic_inspect.rs')
    files.append(root/'mcrf_bs_udp_inspect.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_datagram.rs',root/'mcrf_bs_datagram.rs')
    files.append(root/'mcrf_bs_datagram.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_udp_ingress.rs',root/'mcrf_bs_udp_ingress.rs')
    files.append(root/'mcrf_bs_udp_ingress.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_raw_flow.rs',root/'mcrf_bs_raw_flow.rs')
    files.append(root/'mcrf_bs_raw_flow.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_tcp_ingress.rs',root/'mcrf_bs_tcp_ingress.rs')
    files.append(root/'mcrf_bs_tcp_ingress.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_dispatch_runtime.rs',root/'mcrf_bs_dispatch_runtime.rs')
    files.append(root/'mcrf_bs_dispatch_runtime.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_bootstrap.rs',root/'mcrf_bs_bootstrap.rs')
    files.append(root/'mcrf_bs_bootstrap.rs')
    for name in ('mcrf_bs_health.rs','mcrf_bs_health_tests.rs','mcrf_bs_certificates.rs','mcrf_bs_certificates_tests.rs','mcrf_bs_proxy_udp.rs','mcrf_bs_proxy_udp_tests.rs','mcrf_bs_proxy_backend.rs','mcrf_bs_proxy_backend_tests.rs','mcrf_bs_proxy_stream.rs','mcrf_bs_proxy_protocol.rs','mcrf_bs_proxy_protocol_tests.rs','mcrf_bs_proxy_listener.rs','mcrf_bs_proxy_listener_tests.rs'):
        shutil.copyfile(HERE/'routing/native'/name,root/name)
        files.append(root/name)
    shutil.copyfile(HERE/'routing/native/mcrf_native_runtime_tests.rs',root/'mcrf_native_runtime_tests.rs')
    files.append(root/'mcrf_native_runtime_tests.rs')
    files.append(root/'mcrf_bs_udp_policy.rs')
    files.append(root/'mcrf_bs_datagram_slots.rs')
    files.append(root/'mcrf_bs_udp_dispatch.rs')
    files.append(root/'mcrf_bs_udp_transport.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_session.rs',root/'mcrf_bs_session.rs')
    files.append(root/'mcrf_bs_session.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_carrier_nat.rs',root/'mcrf_bs_carrier_nat.rs')
    files.append(root/'mcrf_bs_carrier_nat.rs')
    shutil.copyfile(HERE/'routing/native/mcrf_bs_service_ingress.rs',root/'mcrf_bs_service_ingress.rs')
    files.append(root/'mcrf_bs_service_ingress.rs')
    files.append(root/'mcrf_bs_dns_engine.rs')
    files.append(root/'mcrf_bs_dns_handler.rs')
    files.append(root/'mcrf_bs_stream_slots.rs')
    files.append(root/'mcrf_bs_dns_ingress.rs')
    files.append(root/'mcrf_bs_dns_frontend.rs')
    files += [p for p in (root/'mcrf-routing-policy').rglob('*') if p.is_file()]
    return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(files)}
