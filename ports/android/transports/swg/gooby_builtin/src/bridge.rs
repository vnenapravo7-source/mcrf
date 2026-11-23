//! Bounded in-process bridge. No SOCKS listener, DNS lookup, extra TCP socket,
//! script runtime or independent engine. Owns the original configured TCP socket.
use std::{pin::Pin,sync::{Arc,Mutex},task::{Context,Poll},io};
use tokio::{io::{AsyncRead,AsyncWrite,DuplexStream,ReadBuf},net::TcpStream,task::JoinHandle};
use crate::{Diagnostics,Preset,StrategyObserver,StrategyStats};
const BUFFER_BYTES:usize=16*1024;
pub struct GoobyStream {inner:DuplexStream,task:JoinHandle<()>,diagnostics:Arc<Diagnostics>,failure:Arc<Mutex<Option<String>>>}
pub fn wrap_tcp(remote:TcpStream,preset:Preset,disorder:bool,observer:Option<StrategyObserver>)->GoobyStream{
    let(inner,client)=tokio::io::duplex(BUFFER_BYTES);
    let diagnostics=Arc::new(Diagnostics{observer,..Diagnostics::default()});
    let failure=Arc::new(Mutex::new(None));let recorded=failure.clone();let observed=diagnostics.clone();
    let task=tokio::spawn(async move {
        if let Err(error)=crate::socks5::builtin_tunnel(client,remote,true,preset,disorder,65_535,observed).await {
            if let Ok(mut slot)=recorded.lock(){*slot=Some(format!("{error:#}"))}
        }
    });
    GoobyStream{inner,task,diagnostics,failure}
}
impl GoobyStream {
    pub fn stats(&self)->StrategyStats{self.diagnostics.snapshot()}
    fn failure(&self)->Option<io::Error>{self.failure.lock().ok()?.as_ref().map(|s|io::Error::other(format!("Gooby: {s}")))}
}
impl Drop for GoobyStream{fn drop(&mut self){self.task.abort();}}
impl AsyncRead for GoobyStream{
    fn poll_read(self:Pin<&mut Self>,cx:&mut Context<'_>,buf:&mut ReadBuf<'_>)->Poll<io::Result<()>>{
        let this=self.get_mut();if buf.remaining()==0{return Poll::Ready(Ok(()))}let before=buf.filled().len();
        match Pin::new(&mut this.inner).poll_read(cx,buf){
            Poll::Ready(Ok(())) if before==buf.filled().len()=>Poll::Ready(this.failure().map_or(Ok(()),Err)),
            other=>other,
        }
    }
}
impl AsyncWrite for GoobyStream{
    fn poll_write(self:Pin<&mut Self>,cx:&mut Context<'_>,buf:&[u8])->Poll<io::Result<usize>>{
        let this=self.get_mut();match Pin::new(&mut this.inner).poll_write(cx,buf){
            Poll::Ready(Err(error))=>Poll::Ready(Err(this.failure().unwrap_or(error))),other=>other,
        }
    }
    fn poll_flush(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<io::Result<()>>{Pin::new(&mut self.get_mut().inner).poll_flush(cx)}
    fn poll_shutdown(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<io::Result<()>>{Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)}
}
