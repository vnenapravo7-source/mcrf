//! Owned loopback protocol/lifecycle checks, never a provider or speed test.
use mcrf_gooby_builtin::{ConfigBundle,Preset,Runtime};
use tokio::{net::{TcpListener,TcpStream},io::{AsyncReadExt,AsyncWriteExt},time::{timeout,Duration}};
async fn connect(proxy:std::net::SocketAddr,remote:std::net::SocketAddr)->TcpStream{
    let std::net::IpAddr::V4(ip)=remote.ip()else{panic!("owned IPv4 fixture")};
    let mut socket=TcpStream::connect(proxy).await.unwrap();socket.write_all(&[5,1,0]).await.unwrap();
    let mut method=[0;2];socket.read_exact(&mut method).await.unwrap();assert_eq!(method,[5,0]);
    let mut request=vec![5,1,0,1];request.extend(ip.octets());request.extend(remote.port().to_be_bytes());socket.write_all(&request).await.unwrap();
    let mut reply=[0;10];socket.read_exact(&mut reply).await.unwrap();assert_eq!(&reply[..4],&[5,0,0,1]);socket
}
fn allocate()->Vec<u8>{let mut bytes=vec![0,3,0,8,0x21,0x12,0xa4,0x42];bytes.extend(0u8..12);bytes.extend([0,0x19,0,4,17,0,0,0]);bytes}
#[tokio::test]
async fn all_presets_preserve_allocate_challenge_full_duplex_and_established_payload(){
    for preset in [Preset::Current,Preset::Gentle,Preset::Extended]{timeout(Duration::from_secs(5),async{
        let runtime=Runtime::start(ConfigBundle::builtin_turn().unwrap().with_preset(preset),true).await.unwrap();
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();
        let remote=listener.local_addr().unwrap();let request=allocate();let expected=request.clone();
        let mut payload=allocate();payload.extend((0..128*1024).map(|n|(n%251)as u8));let expected_payload=payload.clone();
        let server=tokio::spawn(async move{
            let(mut socket,_)=listener.accept().await.unwrap();
            for _ in 0..2{let mut received=vec![0;expected.len()];socket.read_exact(&mut received).await.unwrap();assert_eq!(received,expected);socket.write_all(b"401-owned").await.unwrap()}
            socket.write_all(b"server-first").await.unwrap();
            let mut received=vec![0;expected_payload.len()];socket.read_exact(&mut received).await.unwrap();assert_eq!(received,expected_payload);
            socket.write_all(b"payload-ok").await.unwrap();socket.shutdown().await.unwrap();
        });
        let mut client=connect(runtime.address(),remote).await;
        for _ in 0..2{client.write_all(&request).await.unwrap();let mut response=[0;9];client.read_exact(&mut response).await.unwrap();assert_eq!(&response,b"401-owned")}
        let mut downlink=[0;12];client.read_exact(&mut downlink).await.unwrap();assert_eq!(&downlink,b"server-first");
        client.write_all(&payload).await.unwrap();let mut response=[0;10];client.read_exact(&mut response).await.unwrap();assert_eq!(&response,b"payload-ok");
        client.shutdown().await.unwrap();server.await.unwrap();assert_eq!(runtime.stats().turn_allocate_requests,2);runtime.shutdown().await;
    }).await.unwrap()}
}
#[tokio::test]
async fn shutdown_closes_an_accepted_stalled_client_and_the_owned_listener(){
    timeout(Duration::from_secs(3),async{
        let runtime=Runtime::start(ConfigBundle::builtin_turn().unwrap(),true).await.unwrap();let address=runtime.address();
        let mut client=TcpStream::connect(address).await.unwrap();client.write_all(&[5,1,0]).await.unwrap();let mut reply=[0;2];client.read_exact(&mut reply).await.unwrap();assert_eq!(reply,[5,0]);
        runtime.shutdown().await;let mut byte=[0];assert!(matches!(client.read(&mut byte).await,Ok(0)|Err(_)));assert!(TcpStream::connect(address).await.is_err());
    }).await.unwrap();
}

#[tokio::test]
async fn in_memory_presets_keep_challenges_downlink_first_and_large_payload_exact(){
    for preset in [Preset::Current,Preset::Gentle,Preset::Extended]{timeout(Duration::from_secs(5),async{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let request=allocate();let expected=request.clone();let payload=vec![0x6d;128*1024];let size=payload.len();
        let server=tokio::spawn(async move {
            let(mut socket,_)=listener.accept().await.unwrap();
            for _ in 0..2{let mut received=vec![0;expected.len()];socket.read_exact(&mut received).await.unwrap();assert_eq!(received,expected);socket.write_all(b"401-owned").await.unwrap()}
            socket.write_all(b"server-first").await.unwrap();let mut received=vec![0;size];socket.read_exact(&mut received).await.unwrap();assert!(received.iter().all(|b|*b==0x6d));socket.write_all(b"payload-ok").await.unwrap();
        });
        let remote=TcpStream::connect(address).await.unwrap();remote.set_nodelay(true).unwrap();
        let mut client=mcrf_gooby_builtin::wrap_tcp(remote,preset,true,None);
        for _ in 0..2{client.write_all(&request).await.unwrap();let mut response=[0;9];client.read_exact(&mut response).await.unwrap();assert_eq!(&response,b"401-owned")}
        let mut downlink=[0;12];client.read_exact(&mut downlink).await.unwrap();assert_eq!(&downlink,b"server-first");client.write_all(&payload).await.unwrap();
        let mut response=[0;10];client.read_exact(&mut response).await.unwrap();assert_eq!(&response,b"payload-ok");server.await.unwrap();assert_eq!(client.stats().turn_allocate_requests,2);
    }).await.unwrap()}
}
#[tokio::test]
async fn dropping_in_memory_stream_cancels_initial_setup_and_closes_its_remote_socket(){
    timeout(Duration::from_secs(2),async{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let remote=TcpStream::connect(listener.local_addr().unwrap()).await.unwrap();let(mut server,_)=listener.accept().await.unwrap();
        let client=mcrf_gooby_builtin::wrap_tcp(remote,Preset::Current,true,None);drop(client);
        let mut byte=[0];assert!(matches!(server.read(&mut byte).await,Ok(0)|Err(_)));
    }).await.unwrap();
}
#[tokio::test]
async fn in_memory_half_close_preserves_a_reply_after_the_client_finished_writing(){
    timeout(Duration::from_secs(2),async{
        let listener=TcpListener::bind("127.0.0.1:0").await.unwrap();let address=listener.local_addr().unwrap();
        let server=tokio::spawn(async move{let(mut socket,_)=listener.accept().await.unwrap();let mut bytes=Vec::new();socket.read_to_end(&mut bytes).await.unwrap();assert_eq!(bytes,b"owned half close");socket.write_all(b"reply-after-eof").await.unwrap();socket.shutdown().await.unwrap()});
        let mut client=mcrf_gooby_builtin::wrap_tcp(TcpStream::connect(address).await.unwrap(),Preset::Gentle,true,None);client.write_all(b"owned half close").await.unwrap();client.shutdown().await.unwrap();
        let mut reply=Vec::new();client.read_to_end(&mut reply).await.unwrap();assert_eq!(reply,b"reply-after-eof");server.await.unwrap();
    }).await.unwrap();
}
