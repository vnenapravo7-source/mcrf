//! Lazy proxy acknowledgement permits the shared sniff pipeline to inspect
//! client data without opening an upstream first. The verified backend alone
//! constructs this wrapper, after authenticating the real accepted socket.
use std::{pin::Pin,task::{Context,Poll,ready}};
use tokio::io::{AsyncRead,AsyncWrite,ReadBuf};
pub struct Stream<S>{inner:S,ack:Vec<u8>,written:usize,flushed:bool}
impl<S> Stream<S>{pub fn new(inner:S,ack:Vec<u8>)->Self{Self{inner,ack,written:0,flushed:false}}}
impl<S:AsyncRead+AsyncWrite+Unpin> Stream<S>{
 fn poll_ack(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<std::io::Result<()>>{
  let value=self.get_mut();
  while value.written<value.ack.len(){let count=ready!(Pin::new(&mut value.inner).poll_write(cx,&value.ack[value.written..]))?;if count==0{return Poll::Ready(Err(std::io::ErrorKind::WriteZero.into()))}value.written+=count}
  if !value.flushed{ready!(Pin::new(&mut value.inner).poll_flush(cx))?;value.flushed=true}Poll::Ready(Ok(()))
 }
}
impl<S:AsyncRead+AsyncWrite+Unpin> AsyncRead for Stream<S>{fn poll_read(mut self:Pin<&mut Self>,cx:&mut Context<'_>,bytes:&mut ReadBuf<'_>)->Poll<std::io::Result<()>>{ready!(self.as_mut().poll_ack(cx))?;Pin::new(&mut self.get_mut().inner).poll_read(cx,bytes)}}
impl<S:AsyncRead+AsyncWrite+Unpin> AsyncWrite for Stream<S>{
 fn poll_write(mut self:Pin<&mut Self>,cx:&mut Context<'_>,bytes:&[u8])->Poll<std::io::Result<usize>>{ready!(self.as_mut().poll_ack(cx))?;Pin::new(&mut self.get_mut().inner).poll_write(cx,bytes)}
 fn poll_flush(mut self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<std::io::Result<()>>{ready!(self.as_mut().poll_ack(cx))?;Pin::new(&mut self.get_mut().inner).poll_flush(cx)}
 // Closing a rejected connection must not send a successful CONNECT reply.
 fn poll_shutdown(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<std::io::Result<()>>{Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)}
}
#[cfg(test)]mod tests{
 use super::*;use tokio::io::{AsyncReadExt,AsyncWriteExt};
 #[tokio::test]async fn first_inspection_read_sends_one_ack_then_preserves_half_close(){
  let(mut client,server)=tokio::io::duplex(64);let mut server=Stream::new(server,b"ack".to_vec());let task=tokio::spawn(async move{let mut bytes=[0;4];server.read_exact(&mut bytes).await.unwrap();assert_eq!(&bytes,b"data");server.write_all(b"reply").await.unwrap();server.flush().await.unwrap();server.shutdown().await.unwrap()});
  let mut ack=[0;3];client.read_exact(&mut ack).await.unwrap();assert_eq!(&ack,b"ack");client.write_all(b"data").await.unwrap();client.shutdown().await.unwrap();let mut reply=Vec::new();client.read_to_end(&mut reply).await.unwrap();assert_eq!(reply,b"reply");task.await.unwrap();
 }
 #[tokio::test]async fn shutdown_before_admission_does_not_announce_success(){let(mut client,server)=tokio::io::duplex(64);let mut server=Stream::new(server,b"forbidden success".to_vec());server.shutdown().await.unwrap();drop(server);let mut bytes=Vec::new();client.read_to_end(&mut bytes).await.unwrap();assert!(bytes.is_empty())}
}
