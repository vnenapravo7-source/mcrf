use std::{io, net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr}, sync::Arc, time::Duration};

use anyhow::{bail, Context, Result};
use tokio::{
    io::{copy_bidirectional, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    time::{sleep, timeout},
    task::JoinSet,
};
use tracing::{debug, info};

use crate::{
    config::ConfigBundle,
    plan::TransformPlan,
    rules::Strategy,
    tls::fragment_tls_sni_record,
    Diagnostics, StrategyEvent,
};

pub struct ServerState {
    pub bundle: Arc<ConfigBundle>,
    pub disorder: bool,
    pub initial_record_limit: usize,
    pub diagnostics: Arc<Diagnostics>,
}

pub async fn serve_listener(listener: TcpListener, state: Arc<ServerState>) -> Result<()> {
    let mut connections = JoinSet::new();
    loop {
        tokio::select! {
            result = listener.accept() => {
                let (client, peer) = result?;
                if connections.len() >= 256 { drop(client); continue; }
                let state = Arc::clone(&state);
                connections.spawn(async move {
                    if let Err(error) = handle_client(client, peer, state).await {
                        debug!(%peer, error = %error, "connection closed with error");
                    }
                });
            }
            _ = connections.join_next(), if !connections.is_empty() => {}
        }
    }
}

async fn handle_client(mut client: TcpStream, peer: SocketAddr, state: Arc<ServerState>) -> Result<()> {
    let target = timeout(Duration::from_secs(10), async {
        negotiate_no_auth(&mut client).await?;
        read_connect_request(&mut client).await
    }).await.context("Gooby SOCKS negotiation timed out")??;

    let strategy = target.strategy(&state.bundle);

    let mut remote = match timeout(Duration::from_secs(10), target.connect()).await.context("Gooby remote connect timed out")? {
        Ok(stream) => stream,
        Err(error) => {
            let _ = send_reply(&mut client, 0x01, None).await;
            return Err(error);
        }
    };
    remote.set_nodelay(true)?;
    client.set_nodelay(true)?;

    send_reply(&mut client, 0x00, remote.local_addr().ok()).await?;
    info!(%peer, target = %target.display(), strategy = strategy_name(&strategy), "CONNECT");

    match strategy {
        Strategy::Directly => {
            let _ = copy_bidirectional(&mut client, &mut remote).await?;
        }
        Strategy::Base | Strategy::Turn => {
            builtin_tunnel(client,remote,strategy==Strategy::Turn,state.bundle.preset,state.disorder,state.initial_record_limit,state.diagnostics.clone()).await?;
        }
        Strategy::Custom { .. } => bail!("MCRF Gooby supports built-in presets only"),
    }

    Ok(())
}

/// Shared original preset path for reference SOCKS and the in-memory MCRF bridge.
pub(crate) async fn builtin_tunnel<C:AsyncRead+AsyncWrite+Unpin>(
    mut client:C,mut remote:TcpStream,turn:bool,preset:crate::Preset,disorder:bool,
    initial_record_limit:usize,diagnostics:Arc<Diagnostics>,
)->Result<()> {
            if let Some(first) = timeout(Duration::from_secs(10), read_initial_record(&mut client, initial_record_limit)).await.context("Gooby initial record timed out")?? {
                if turn && first.first() != Some(&0x16) {
                    return crate::turn::tunnel(client, remote, first, diagnostics.clone(), preset).await;
                }
                let mut applied = StrategyEvent::Passthrough;
                let plan = if let Some(chunks) = fragment_tls_sni_record(&first, preset.tls_chunk()) {
                    applied = StrategyEvent::TlsClientHelloSplit;
                    TransformPlan {
                        chunks,
                        low_ttl_first: preset.low_ttl(),
                        delay_ms: preset.tls_delay_ms(),
                    }
                } else {
                    TransformPlan::passthrough(&first)
                };
                send_plan(&mut remote, plan, disorder).await?;
                diagnostics.record(applied);
            }
            let _ = copy_bidirectional(&mut client, &mut remote).await?;

    Ok(())
}

async fn negotiate_no_auth(client: &mut TcpStream) -> Result<()> {
    let version = client.read_u8().await?;
    if version != 0x05 {
        bail!("unsupported SOCKS version {version}");
    }
    let count = client.read_u8().await? as usize;
    let mut methods = vec![0u8; count];
    client.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        client.write_all(&[0x05, 0xff]).await?;
        bail!("client does not offer SOCKS5 no-auth method");
    }
    client.write_all(&[0x05, 0x00]).await?;
    Ok(())
}

async fn read_connect_request(client: &mut TcpStream) -> Result<Target> {
    let version = client.read_u8().await?;
    let command = client.read_u8().await?;
    let reserved = client.read_u8().await?;
    let atyp = client.read_u8().await?;

    if version != 0x05 || reserved != 0x00 {
        bail!("invalid SOCKS5 request header");
    }
    if command != 0x01 {
        let _ = send_reply(client, 0x07, None).await;
        bail!("only SOCKS5 CONNECT is supported");
    }

    let address = match atyp {
        0x01 => {
            let mut octets = [0u8; 4];
            client.read_exact(&mut octets).await?;
            TargetAddress::Ip(IpAddr::V4(Ipv4Addr::from(octets)))
        }
        0x03 => {
            let len = client.read_u8().await? as usize;
            if len == 0 {
                bail!("empty SOCKS5 domain");
            }
            let mut bytes = vec![0u8; len];
            client.read_exact(&mut bytes).await?;
            let domain = String::from_utf8(bytes).context("SOCKS5 domain is not UTF-8")?;
            TargetAddress::Domain(domain.trim_end_matches('.').to_ascii_lowercase())
        }
        0x04 => {
            let mut octets = [0u8; 16];
            client.read_exact(&mut octets).await?;
            TargetAddress::Ip(IpAddr::V6(Ipv6Addr::from(octets)))
        }
        _ => {
            let _ = send_reply(client, 0x08, None).await;
            bail!("unsupported SOCKS5 address type {atyp}");
        }
    };

    let port = client.read_u16().await?;
    Ok(Target { address, port })
}

async fn send_reply(client: &mut TcpStream, rep: u8, bound: Option<SocketAddr>) -> io::Result<()> {
    let bound = bound.unwrap_or_else(|| SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0));
    let mut out = vec![0x05, rep, 0x00];
    match bound.ip() {
        IpAddr::V4(ip) => {
            out.push(0x01);
            out.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            out.push(0x04);
            out.extend_from_slice(&ip.octets());
        }
    }
    out.extend_from_slice(&bound.port().to_be_bytes());
    client.write_all(&out).await
}

async fn read_initial_record<R:AsyncRead+Unpin>(client: &mut R, max_record: usize) -> Result<Option<Vec<u8>>> {
    let mut head = [0u8; 5];
    let mut filled = 0usize;
    while filled < head.len() {
        let n = client.read(&mut head[filled..]).await?;
        if n == 0 {
            if filled == 0 {
                return Ok(None);
            }
            return Ok(Some(head[..filled].to_vec()));
        }
        filled += n;
    }

    if head[0] != 0x16 {
        return Ok(Some(head.to_vec()));
    }

    let record_len = u16::from_be_bytes([head[3], head[4]]) as usize;
    if record_len > max_record {
        bail!("initial TLS record is {record_len} bytes, limit is {max_record}");
    }

    let mut data = Vec::with_capacity(5 + record_len);
    data.extend_from_slice(&head);
    data.resize(5 + record_len, 0);
    client.read_exact(&mut data[5..]).await?;
    Ok(Some(data))
}

async fn send_plan(remote: &mut TcpStream, plan: TransformPlan, disorder_enabled: bool) -> Result<()> {
    if plan.chunks.is_empty() {
        return Ok(());
    }

    if disorder_enabled && plan.low_ttl_first && plan.chunks.len() >= 2 {
        let first_count = plan.chunks.len().min(2);
        let mut first = Vec::with_capacity(
            plan.chunks[..first_count].iter().map(Vec::len).sum(),
        );
        for chunk in &plan.chunks[..first_count] {
            first.extend_from_slice(chunk);
        }

        let restore_ttl = match remote.ttl() {
            Ok(ttl) if remote.set_ttl(1).is_ok() => Some(ttl),
            _ => None,
        };

        let low_ttl_result: std::io::Result<()> = async {
            remote.write_all(&first).await?;
            remote.flush().await?;
            Ok(())
        }
        .await;

        if plan.delay_ms > 0 {
            sleep(Duration::from_millis(plan.delay_ms)).await;
        }

        if let Some(ttl) = restore_ttl {
            remote.set_ttl(ttl).context("Gooby failed to restore socket TTL")?;
        }
        low_ttl_result?;

        for chunk in &plan.chunks[first_count..] {
            remote.write_all(chunk).await?;
        }
        remote.flush().await?;
        return Ok(());
    }

    for (index, chunk) in plan.chunks.iter().enumerate() {
        remote.write_all(chunk).await?;
        if plan.delay_ms > 0 && index + 1 < plan.chunks.len() {
            remote.flush().await?;
            sleep(Duration::from_millis(plan.delay_ms)).await;
        }
    }
    remote.flush().await?;
    Ok(())
}

fn strategy_name(strategy: &Strategy) -> &'static str {
    match strategy {
        Strategy::Base => "base",
        Strategy::Turn => "turn-tcp",
        Strategy::Directly => "directly",
        Strategy::Custom { .. } => "custom",
    }
}

#[derive(Debug, Clone)]
struct Target {
    address: TargetAddress,
    port: u16,
}

#[derive(Debug, Clone)]
enum TargetAddress {
    Domain(String),
    Ip(IpAddr),
}

impl Target {
    fn strategy(&self, bundle: &ConfigBundle) -> Strategy {
        if bundle.turn_tcp { return Strategy::Turn; }
        self.domain_for_rules().map(|host| bundle.rules().resolve(host)).unwrap_or(Strategy::Directly)
    }
    async fn connect(&self) -> Result<TcpStream> {
        let stream = match &self.address {
            TargetAddress::Domain(domain) => TcpStream::connect((domain.as_str(), self.port)).await?,
            TargetAddress::Ip(ip) => TcpStream::connect(SocketAddr::new(*ip, self.port)).await?,
        };
        Ok(stream)
    }

    fn domain_for_rules(&self) -> Option<&str> {
        match &self.address {
            TargetAddress::Domain(domain) => Some(domain),
            TargetAddress::Ip(_) => None,
        }
    }

    fn display(&self) -> String {
        match &self.address {
            TargetAddress::Domain(domain) => format!("{domain}:{}", self.port),
            TargetAddress::Ip(ip) => format!("{ip}:{}", self.port),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn turn_preset_includes_ipv4_ipv6_and_domains_without_changing_base() {
        let base = ConfigBundle::builtin_base().unwrap();
        let turn = ConfigBundle::builtin_turn().unwrap();
        for address in [
            TargetAddress::Ip("90.156.236.74".parse().unwrap()),
            TargetAddress::Ip("2001:db8::1".parse().unwrap()),
            TargetAddress::Domain("turn.example".into()),
        ] {
            let target = Target { address, port: 19302 };
            assert_eq!(target.strategy(&turn), Strategy::Turn);
            assert_eq!(target.strategy(&base), if target.domain_for_rules().is_some() { Strategy::Base } else { Strategy::Directly });
        }
    }
}
