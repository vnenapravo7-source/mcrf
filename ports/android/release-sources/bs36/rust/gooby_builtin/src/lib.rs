mod bridge;
pub use bridge::{GoobyStream,wrap_tcp};
mod preset;
pub use preset::Preset;
mod config;
mod plan;
mod rules;
mod socks5;
mod tls;
mod turn;

pub use config::ConfigBundle;
use anyhow::Result;
use std::{net::SocketAddr, sync::{Arc, atomic::{AtomicU64, Ordering}}};
use tokio::{net::TcpListener, task::JoinHandle};

/// Owns the listener and its accepted connections. No detached helpers.
pub struct Runtime {
    address: SocketAddr,
    task: JoinHandle<()>,
    diagnostics: Arc<Diagnostics>,
}

#[derive(Debug, Clone, Copy)]
pub enum StrategyEvent { TurnAllocateSplit, TlsClientHelloSplit, Passthrough }

#[derive(Debug, Default, Clone, Copy)]
pub struct StrategyStats {
    pub turn_allocate_requests: u64,
    pub tls_client_hellos: u64,
    pub passthrough_connections: u64,
}

pub type StrategyObserver = Arc<dyn Fn(StrategyEvent) + Send + Sync>;

#[derive(Default)]
pub(crate) struct Diagnostics {
    turn: AtomicU64,
    tls: AtomicU64,
    passthrough: AtomicU64,
    observer: Option<StrategyObserver>,
}

impl Diagnostics {
    pub(crate) fn record(&self, event: StrategyEvent) {
        let counter = match event {
            StrategyEvent::TurnAllocateSplit => &self.turn,
            StrategyEvent::TlsClientHelloSplit => &self.tls,
            StrategyEvent::Passthrough => &self.passthrough,
        };
        // Log each kind only once per runtime, never once per packet/worker.
        if counter.fetch_add(1, Ordering::Relaxed) == 0 {
            if let Some(observer) = &self.observer { observer(event); }
        }
    }
    fn snapshot(&self) -> StrategyStats {
        StrategyStats {
            turn_allocate_requests: self.turn.load(Ordering::Relaxed),
            tls_client_hellos: self.tls.load(Ordering::Relaxed),
            passthrough_connections: self.passthrough.load(Ordering::Relaxed),
        }
    }
}

impl Runtime {
    pub async fn start(bundle: ConfigBundle, disorder: bool) -> Result<Self> {
        Self::start_with_observer(bundle, disorder, None).await
    }

    pub async fn start_with_observer(bundle: ConfigBundle, disorder: bool, observer: Option<StrategyObserver>) -> Result<Self> {
        let listener = TcpListener::bind("127.0.0.1:0").await?;
        let address = listener.local_addr()?;
        let diagnostics = Arc::new(Diagnostics { observer, ..Diagnostics::default() });
        let state = Arc::new(socks5::ServerState {
            bundle: Arc::new(bundle), disorder, initial_record_limit: 65_535,
            diagnostics: diagnostics.clone(),
        });
        let task = tokio::spawn(async move {
            if let Err(error) = socks5::serve_listener(listener, state).await {
                tracing::error!(%error, "embedded Gooby listener stopped");
            }
        });
        Ok(Self { address, task, diagnostics })
    }

    pub fn address(&self) -> SocketAddr { self.address }

    pub fn stats(&self) -> StrategyStats { self.diagnostics.snapshot() }

    pub async fn shutdown(mut self) {
        self.task.abort();
        let _ = (&mut self.task).await;
    }
}

impl Drop for Runtime {
    fn drop(&mut self) { self.task.abort(); }
}
