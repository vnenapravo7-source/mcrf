//! Optional built-in Gooby in the SAME BS process. No local proxy listener.
use anyhow::{Result,bail};
use mcrf_gooby_builtin::{Preset,GoobyStream,StrategyEvent};
use std::{sync::{OnceLock,Arc,atomic::{AtomicU8,Ordering}},pin::Pin,task::{Context,Poll},io};
use tokio::{net::TcpStream,io::{AsyncRead,AsyncWrite,ReadBuf}};
static SELECTED:OnceLock<Option<Preset>>=OnceLock::new();
static EVENTS:AtomicU8=AtomicU8::new(0);
fn choice(enabled:bool,strategy:&str,transport:&str)->Result<Option<Preset>>{
    let preset:Preset=strategy.parse().map_err(|_|anyhow::anyhow!("GOOBY_INVALID_STRATEGY"))?;
    if !enabled {
        if preset!=Preset::Current{bail!("GOOBY_STRATEGY_REQUIRES_ENABLED")}
        return Ok(None)
    }
    if !matches!(crate::turn_endpoint::TurnTransportMode::parse(transport)?,crate::turn_endpoint::TurnTransportMode::TcpTls){bail!("GOOBY_REQUIRES_TCP")}
    Ok(Some(preset))
}
pub(crate) fn configure(enabled:bool,strategy:&str,transport:&str)->Result<()> {
    SELECTED.set(choice(enabled,strategy,transport)?).map_err(|_|anyhow::anyhow!("GOOBY_ALREADY_CONFIGURED"))
}
pub(crate) fn selected()->Option<Preset>{SELECTED.get().copied().flatten()}
pub(crate) enum Socket {Plain(TcpStream),Gooby(GoobyStream)}
pub(crate) fn wrap(socket:TcpStream,preset:Option<Preset>)->Socket {
    match preset {
        None=>Socket::Plain(socket),
        Some(preset)=>{
            let observer=Arc::new(|event|{
                let(bit,label)=match event{StrategyEvent::TurnAllocateSplit=>(1,"TURN setup"),StrategyEvent::TlsClientHelloSplit=>(2,"TLS ClientHello"),StrategyEvent::Passthrough=>(4,"passthrough")};
                if EVENTS.fetch_or(bit,Ordering::Relaxed)&bit==0 {crate::log_error!("[Gooby] {}: {}",preset_name_for_log(),label)}
            });
            Socket::Gooby(mcrf_gooby_builtin::wrap_tcp(socket,preset,true,Some(observer)))
        }
    }
}
fn preset_name_for_log()->&'static str{selected().map(Preset::name).unwrap_or("test")}
impl AsyncRead for Socket{
    fn poll_read(self:Pin<&mut Self>,cx:&mut Context<'_>,buf:&mut ReadBuf<'_>)->Poll<io::Result<()>>{
        match self.get_mut(){Self::Plain(s)=>Pin::new(s).poll_read(cx,buf),Self::Gooby(s)=>Pin::new(s).poll_read(cx,buf)}
    }
}
impl AsyncWrite for Socket{
    fn poll_write(self:Pin<&mut Self>,cx:&mut Context<'_>,buf:&[u8])->Poll<io::Result<usize>>{match self.get_mut(){Self::Plain(s)=>Pin::new(s).poll_write(cx,buf),Self::Gooby(s)=>Pin::new(s).poll_write(cx,buf)}}
    fn poll_flush(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<io::Result<()>>{match self.get_mut(){Self::Plain(s)=>Pin::new(s).poll_flush(cx),Self::Gooby(s)=>Pin::new(s).poll_flush(cx)}}
    fn poll_shutdown(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<io::Result<()>>{match self.get_mut(){Self::Plain(s)=>Pin::new(s).poll_shutdown(cx),Self::Gooby(s)=>Pin::new(s).poll_shutdown(cx)}}
}
#[cfg(test)]mod tests{
    use super::*;
    #[test]fn off_preserves_ordinary_udp_and_tcp_without_a_preset(){for transport in ["udp","tcp","tcp_tls"]{assert!(choice(false,"current",transport).unwrap().is_none())}}
    #[test]fn enabled_presets_require_tcp_and_unknown_values_never_select_a_default(){
        for strategy in ["current","gentle","extended"]{assert!(choice(true,strategy,"tcp").unwrap().is_some());assert!(choice(true,strategy,"udp").is_err())}
        assert!(choice(true,"unknown","tcp").is_err());assert!(choice(false,"extended","tcp").is_err());assert!(choice(false,"","udp").is_err());
    }
}
