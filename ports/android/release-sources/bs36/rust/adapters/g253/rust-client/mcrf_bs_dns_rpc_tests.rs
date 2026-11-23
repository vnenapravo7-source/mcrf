use super::*;
use std::sync::atomic::{AtomicUsize,Ordering};
fn query()->Vec<u8>{vec![0x12,0x34,1,0,0,1,0,0,0,0,0,0,0,0,16,0,1]}
fn name()->String{format!("mcrf-bs-dns-{}",uuid::Uuid::new_v4())}
fn own_peer()->Peer{Arc::new(|fd|{
    let mut peer:libc::ucred=unsafe{std::mem::zeroed()};let mut length=std::mem::size_of_val(&peer)as libc::socklen_t;
    let result=unsafe{libc::getsockopt(fd,libc::SOL_SOCKET,libc::SO_PEERCRED,(&mut peer as *mut libc::ucred).cast(),&mut length)};
    result==0&&peer.uid==unsafe{libc::getuid()}&&peer.pid==unsafe{libc::getpid()}
})}
fn connect(name:&str)->UnixStream{
    let fd=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK,0)};assert!(fd>=0);let owned=unsafe{std::os::unix::net::UnixStream::from_raw_fd(fd)};
    let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;
    for(target,byte)in address.sun_path[1..].iter_mut().zip(name.bytes()){*target=byte as _}
    assert_eq!(unsafe{libc::connect(fd,(&address as *const libc::sockaddr_un).cast(),(std::mem::offset_of!(libc::sockaddr_un,sun_path)+1+name.len())as _)},0);
    UnixStream::from_std(owned).unwrap()
}
async fn request(socket:&mut UnixStream,generation:u64){socket.write_u32(0x42444e31).await.unwrap();socket.write_u64(generation).await.unwrap();socket.write_u8(17).await.unwrap();socket.write_u16(32769).await.unwrap();socket.write_u16(query().len()as u16).await.unwrap();socket.write_all(&query()).await.unwrap();socket.flush().await.unwrap();}
#[tokio::test]
async fn authenticated_private_frame_carries_the_maximum_dns_reply_without_stdout(){
    tokio::time::timeout(Duration::from_secs(5),async{
        let mut reply=query();reply[2]=0x81;reply[3]=0x80;reply[6..8].copy_from_slice(&1u16.to_be_bytes());reply.extend([0xc0,12,0,16,0,1,0,0,0,60]);reply.extend(65506u16.to_be_bytes());
        let mut left=65506;while left>0{let count=(left-1).min(255);reply.push(count as u8);reply.extend(std::iter::repeat_n(b'x',count));left-=1+count}assert_eq!(reply.len(),65535);
        let response=reply.clone();let resolver:Resolver=Arc::new(move|request|{assert_eq!(request.protocol,17);assert_eq!(request.translated_port,32769);assert_eq!(request.query,query());let reply=response.clone();Box::pin(async move{Ok(reply)})});
        let cancel=CancellationToken::new();assert!(matches!(start(&name(),42,&cancel,resolver.clone()),Err(Failure::Disabled)));
        let name=name();let server=start_owned(&name,42,&cancel,resolver,own_peer()).unwrap();let mut socket=connect(&name);request(&mut socket,42).await;
        let length=socket.read_u32().await.unwrap();assert_eq!(length,65535);let mut actual=vec![0;length as usize];socket.read_exact(&mut actual).await.unwrap();assert_eq!(actual,reply);server.close().await.unwrap();
    }).await.unwrap()
}
#[tokio::test]
async fn wrong_generation_and_malformed_magic_do_not_invoke_the_resolver(){
    tokio::time::timeout(Duration::from_secs(5),async{
        let calls=Arc::new(AtomicUsize::new(0));let count=calls.clone();let resolver:Resolver=Arc::new(move|_|{count.fetch_add(1,Ordering::SeqCst);Box::pin(async{Err(9)})});
        let name=name();let server=start_owned(&name,42,&CancellationToken::new(),resolver,own_peer()).unwrap();
        let mut socket=connect(&name);request(&mut socket,43).await;assert_eq!(socket.read_i32().await.unwrap(),-2);
        let mut malformed=connect(&name);malformed.write_u32(0).await.unwrap();assert_eq!(malformed.read_i32().await.unwrap(),-1);assert_eq!(calls.load(Ordering::SeqCst),0);server.close().await.unwrap();
    }).await.unwrap()
}
#[tokio::test]
async fn denied_peer_is_closed_without_reading_query_and_stop_closes_a_partial_frame(){
    tokio::time::timeout(Duration::from_secs(5),async{
        let resolver:Resolver=Arc::new(|_|Box::pin(async{panic!("denied/partial requests must not resolve")}));
        let forbidden=name();let server=start_owned(&forbidden,42,&CancellationToken::new(),resolver.clone(),Arc::new(|_|false)).unwrap();let mut socket=connect(&forbidden);let mut byte=[0];assert_eq!(socket.read(&mut byte).await.unwrap(),0);server.close().await.unwrap();
        let partial=name();let server=start_owned(&partial,42,&CancellationToken::new(),resolver,own_peer()).unwrap();let mut socket=connect(&partial);socket.write_u32(0x42444e31).await.unwrap();server.close().await.unwrap();
        assert!(matches!(socket.read(&mut byte).await,Ok(0)|Err(_)));
    }).await.unwrap()
}
#[tokio::test]
async fn stop_aborts_an_inflight_resolution_and_drops_its_owned_resources(){
    tokio::time::timeout(Duration::from_secs(5),async{
        struct Owned(Arc<AtomicUsize>);impl Drop for Owned{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
        let dropped=Arc::new(AtomicUsize::new(0));let started=Arc::new(tokio::sync::Notify::new());let signal=started.clone();let counter=dropped.clone();
        let resolver:Resolver=Arc::new(move|_|{let signal=signal.clone();let counter=counter.clone();Box::pin(async move{let _owned=Owned(counter);signal.notify_one();std::future::pending().await})});
        let name=name();let server=start_owned(&name,42,&CancellationToken::new(),resolver,own_peer()).unwrap();let mut socket=connect(&name);request(&mut socket,42).await;started.notified().await;server.close().await.unwrap();assert_eq!(dropped.load(Ordering::SeqCst),1);
    }).await.unwrap()
}
