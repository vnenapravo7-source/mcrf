//! Private, generation-scoped DNS frames. No raw DNS/profile/exception enters
//! stdout. Large answers stay on an authenticated local Unix connection.
use std::{sync::Arc,pin::Pin,future::Future,time::Duration,os::fd::{AsRawFd,FromRawFd}};
use tokio::{io::{AsyncReadExt,AsyncWriteExt},net::{UnixListener,UnixStream},task::{JoinHandle,JoinSet}};
use tokio_util::sync::CancellationToken;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Invalid,Disabled,Io,Worker}
pub struct Request{pub protocol:u8,pub translated_port:u16,pub query:Vec<u8>}
pub type Resolver=Arc<dyn Fn(Request)->Pin<Box<dyn Future<Output=Result<Vec<u8>,u8>>+Send>>+Send+Sync>;
type Peer=Arc<dyn Fn(i32)->bool+Send+Sync>;
pub struct Server{cancel:CancellationToken,task:Option<JoinHandle<Result<(),Failure>>>}
impl Drop for Server{fn drop(&mut self){self.cancel.cancel();if let Some(task)=&self.task{task.abort()}}}
impl Server{
    pub async fn close(mut self)->Result<(),Failure>{
        self.cancel.cancel();let mut task=self.task.take().ok_or(Failure::Worker)?;
        match tokio::time::timeout(Duration::from_secs(2),&mut task).await{
            Ok(result)=>result.map_err(|_|Failure::Worker)?,
            Err(_)=>{task.abort();Err(Failure::Worker)},
        }
    }
}
fn listener(name:&str)->Result<UnixListener,Failure>{
    if !name.starts_with("mcrf-bs-dns-")||name.len()>90||!name.bytes().all(|v|v.is_ascii_alphanumeric()||matches!(v,b'-'|b'_')){return Err(Failure::Invalid)}
    let fd=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK,0)};
    if fd<0{return Err(Failure::Io)}let owned=unsafe{std::os::unix::net::UnixListener::from_raw_fd(fd)};
    let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;
    for(target,byte)in address.sun_path[1..].iter_mut().zip(name.bytes()){*target=byte as _}
    let size=std::mem::offset_of!(libc::sockaddr_un,sun_path)+1+name.len();
    if unsafe{libc::bind(fd,(&address as *const libc::sockaddr_un).cast(),size as _)}!=0||unsafe{libc::listen(fd,16)}!=0{return Err(Failure::Io)}
    UnixListener::from_std(owned).map_err(|_|Failure::Io)
}
pub fn start(name:&str,generation:u64,cancel:&CancellationToken,resolver:Resolver)->Result<Server,Failure>{
    if !crate::mcrf_direct_tun::active(){return Err(Failure::Disabled)}
    let peer:Peer=Arc::new(|fd|crate::mcrf_direct_tun::validate_peer(unsafe{std::os::fd::BorrowedFd::borrow_raw(fd)}).is_ok());
    start_owned(name,generation,cancel,resolver,peer)
}
fn start_owned(name:&str,generation:u64,cancel:&CancellationToken,resolver:Resolver,peer:Peer)->Result<Server,Failure>{
    if generation==0||generation>i64::MAX as u64{return Err(Failure::Invalid)}
    let listener=listener(name)?;let child=cancel.child_token();let stopped=child.clone();let fatal=cancel.clone();
    let task=tokio::spawn(async move{
        let mut tasks=JoinSet::new();
        loop{
            while let Some(result)=tasks.try_join_next(){if result.is_err(){fatal.cancel();return Err(Failure::Worker)}}
            tokio::select!{biased;
                _=stopped.cancelled()=>{tasks.abort_all();while tasks.join_next().await.is_some(){}return Ok(())},
                joined=tasks.join_next(),if !tasks.is_empty()=>{if matches!(joined,Some(Err(_))){fatal.cancel();return Err(Failure::Worker)}},
                accepted=listener.accept()=>{
                    let (mut socket,_)=match accepted{Ok(value)=>value,Err(_)=>{fatal.cancel();return Err(Failure::Io)}};
                    if tasks.len()>=16||!peer(socket.as_raw_fd()){continue}
                    let resolver=resolver.clone();let cancelled=stopped.clone();
                    tasks.spawn(async move{tokio::select!{biased;
                        _=cancelled.cancelled()=>{},
                        _=tokio::time::timeout(mcrf_bs_routing_policy::dns_limits::RESPONSE,handle(&mut socket,generation,resolver))=>{},
                    }});
                }
            }
        }
    });Ok(Server{cancel:child,task:Some(task)})
}
async fn handle(socket:&mut UnixStream,generation:u64,resolver:Resolver)->Result<(),Failure>{
    let request=async{
        if socket.read_u32().await.map_err(|_|1u8)?!=0x42444e31{return Err(1)}
        if socket.read_u64().await.map_err(|_|1u8)?!=generation{return Err(2)}
        let protocol=socket.read_u8().await.map_err(|_|1u8)?;let translated_port=socket.read_u16().await.map_err(|_|1u8)?;let size=socket.read_u16().await.map_err(|_|1u8)?as usize;
        if !matches!(protocol,6|17)||translated_port==0||!(12..=4096).contains(&size){return Err(1)}
        let mut query=vec![0;size];socket.read_exact(&mut query).await.map_err(|_|1u8)?;
        mcrf_bs_routing_policy::dns_wire::question(&query).map_err(|_|1u8)?;
        Ok(Request{protocol,translated_port,query})
    }.await;
    let result=match request{Ok(request)=>resolver(request).await,Err(error)=>Err(error)};
    match result{
        Ok(reply)=>{if !(12..=65535).contains(&reply.len()){return Err(Failure::Invalid)}socket.write_u32(reply.len()as u32).await.map_err(|_|Failure::Io)?;socket.write_all(&reply).await.map_err(|_|Failure::Io)?;},
        Err(code)=>{let code=if (1..=9).contains(&code){code}else{9};socket.write_i32(-i32::from(code)).await.map_err(|_|Failure::Io)?;},
    }socket.flush().await.map_err(|_|Failure::Io)?;Ok(())
}
#[cfg(test)]#[path="mcrf_bs_dns_rpc_tests.rs"]mod tests;
