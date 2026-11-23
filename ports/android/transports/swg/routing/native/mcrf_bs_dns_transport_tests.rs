use super::*;
use std::sync::{Mutex,atomic::{AtomicUsize,Ordering}};
use std::time::Duration;
struct Ticket(Arc<AtomicUsize>);impl Drop for Ticket{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
struct OwnedPolicy{prepared:Mutex<Vec<Direction>>,retired:Arc<AtomicUsize>}
impl OwnedPolicy{fn new()->Self{Self{prepared:Mutex::new(Vec::new()),retired:Arc::new(AtomicUsize::new(0))}}}
impl SocketPolicy for OwnedPolicy{
    fn prepare(&self,_:u8,fd:i32,port:u16,peer:SocketAddr,direction:Direction)->Result<Box<dyn Send>,Failure>{
        let borrowed=unsafe{std::os::fd::BorrowedFd::borrow_raw(fd)};let socket=socket2::SockRef::from(&borrowed);
        assert!(port>0);assert_eq!(socket.local_addr().unwrap().as_socket().unwrap().port(),port);assert!(socket.peer_addr().is_err());if direction==Direction::Direct&&peer.ip().is_loopback(){assert!(socket.local_addr().unwrap().as_socket().unwrap().ip().is_loopback())}
        self.prepared.lock().unwrap().push(direction);Ok(Box::new(Ticket(self.retired.clone())))
    }
}
fn query()->Vec<u8>{vec![0x12,0x34,1,0,0,1,0,0,0,0,0,0,0,0,2,0,1]}
fn reply()->Vec<u8>{let mut value=query();value[2]=0x81;value[3]=0x80;value}
#[tokio::test]
async fn named_tcp_egresses_send_each_flow_to_its_authenticated_worker(){
 tokio::time::timeout(Duration::from_secs(5),async{
  let listener0=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
  let listener1=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
  let ports=[listener0.local_addr().unwrap().port(),listener1.local_addr().unwrap().port()];
  let base=Arc::new(OwnedPolicy::new());
  let bindings=serde_json::json!([
   {"port":ports[0],"username":"owned-slot-zero","password":"fixture-password-zero"},
   {"port":ports[1],"username":"owned-slot-one","password":"fixture-password-one"}
  ]);
  let policy=with_egresses(Some(&bindings),base.clone()).unwrap();
  let peer="192.0.2.24:443".parse().unwrap();
  let server=|listener:tokio::net::TcpListener,user:&'static [u8],pass:&'static [u8],marker:u8|tokio::spawn(async move{
   let(mut stream,_)=listener.accept().await.unwrap();
   let mut hello=[0;3];stream.read_exact(&mut hello).await.unwrap();assert_eq!(hello,[5,1,2]);
   stream.write_all(&[5,2]).await.unwrap();
   assert_eq!(stream.read_u8().await.unwrap(),1);
   for secret in [user,pass]{
    let size=stream.read_u8().await.unwrap() as usize;
    let mut bytes=vec![0;size];stream.read_exact(&mut bytes).await.unwrap();assert_eq!(bytes,secret);
   }
   stream.write_all(&[1,0]).await.unwrap();
   stream.read_exact(&mut hello).await.unwrap();assert_eq!(hello,[5,1,0]);
   assert_eq!(read_socks_address(&mut stream).await.unwrap(),Some(peer));
   stream.write_all(&[5,0,0,1,127,0,0,1,0,0,marker]).await.unwrap();
  });
  let zero=server(listener0,b"owned-slot-zero",b"fixture-password-zero",0);
  let one=server(listener1,b"owned-slot-one",b"fixture-password-one",1);
  let (first,second)=tokio::join!(tcp(policy.as_ref(),peer,Direction::Egress(0)),tcp(policy.as_ref(),peer,Direction::Egress(1)));
  assert_eq!(first.unwrap().read_u8().await.unwrap(),0);
  assert_eq!(second.unwrap().read_u8().await.unwrap(),1);
  assert!(matches!(tcp(policy.as_ref(),peer,Direction::Egress(2)).await,Err(Failure::Lease)));
  zero.await.unwrap();one.await.unwrap();
  assert_eq!(*base.prepared.lock().unwrap(),vec![Direction::Direct,Direction::Direct]);
  assert_eq!(base.retired.load(Ordering::SeqCst),2);
 }).await.unwrap()
}
#[tokio::test]async fn foreign_question_is_rejected_and_missing_vpn_family_never_binds(){
 let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();
 let server=tokio::spawn(async move{let(mut stream,_)=listener.accept().await.unwrap();read_query(&mut stream).await;let mut value=reply();value[14]=1;stream.write_u16(value.len()as u16).await.unwrap();stream.write_all(&value).await.unwrap();});
 let policy=OwnedPolicy::new();assert_eq!(exchange(&policy,peer,Direction::Direct,&Transport::Tcp,&query(),Instant::now()+Duration::from_secs(2),&CancellationToken::new()).await,Err(Failure::Wire));server.await.unwrap();
 let policy=NativePolicy{physical:1,vpn:2,vpn_ipv4:true,vpn_ipv6:false};
 assert!(matches!(policy.prepare(17,-1,10000,"[2001:db8::1]:53".parse().unwrap(),Direction::Vpn),Err(Failure::Bind)));
 let policy=OwnedPolicy::new();let mut invalid=query();invalid[5]=0;
 assert_eq!(exchange(&policy,peer,Direction::Direct,&Transport::Tcp,&invalid,Instant::now()+Duration::from_secs(2),&CancellationToken::new()).await,Err(Failure::Invalid));assert!(policy.prepared.lock().unwrap().is_empty());
}
async fn read_query<T:AsyncRead+Unpin>(stream:&mut T){let size=stream.read_u16().await.unwrap();assert_eq!(size as usize,query().len());let mut value=vec![0;size as usize];stream.read_exact(&mut value).await.unwrap();assert_eq!(value,query());}
async fn write_reply<T:AsyncWrite+Unpin>(stream:&mut T){let value=reply();stream.write_u16(value.len()as u16).await.unwrap();stream.write_all(&value).await.unwrap();stream.flush().await.unwrap();}
#[tokio::test]
async fn udp_truncation_uses_same_endpoint_tcp_and_retires_both_preconnect_leases(){
    tokio::time::timeout(Duration::from_secs(5),async{
        let tcp=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=tcp.local_addr().unwrap();let udp=UdpSocket::bind(peer).await.unwrap();
        let server=tokio::spawn(async move{let mut packet=[0;1024];let(size,client)=udp.recv_from(&mut packet).await.unwrap();assert_eq!(&packet[..size],query());let mut truncated=reply();truncated[2]|=2;udp.send_to(&truncated,client).await.unwrap();let(mut stream,_)=tcp.accept().await.unwrap();read_query(&mut stream).await;write_reply(&mut stream).await;});
        let policy=OwnedPolicy::new();let answer=exchange(&policy,peer,Direction::Vpn,&Transport::Udp,&query(),Instant::now()+Duration::from_secs(3),&CancellationToken::new()).await.unwrap();assert_eq!(answer,reply());server.await.unwrap();
        assert_eq!(*policy.prepared.lock().unwrap(),vec![Direction::Vpn,Direction::Vpn]);assert_eq!(policy.retired.load(Ordering::SeqCst),2);
    }).await.unwrap()
}
#[tokio::test]
async fn connected_udp_ignores_an_unsolicited_other_peer(){
    tokio::time::timeout(Duration::from_secs(5),async{
        let server=UdpSocket::bind("127.0.0.1:0").await.unwrap();let peer=server.local_addr().unwrap();
        let server=tokio::spawn(async move{let mut bytes=[0;1024];let(count,client)=server.recv_from(&mut bytes).await.unwrap();assert_eq!(&bytes[..count],query());
            let other=UdpSocket::bind("127.0.0.1:0").await.unwrap();let mut forged=reply();forged[3]=0x83;other.send_to(&forged,client).await.unwrap();
            tokio::time::sleep(Duration::from_millis(10)).await;server.send_to(&reply(),client).await.unwrap();
        });
        let policy=OwnedPolicy::new();assert_eq!(exchange(&policy,peer,Direction::Vpn,&Transport::Udp,&query(),Instant::now()+Duration::from_secs(3),&CancellationToken::new()).await.unwrap(),reply());server.await.unwrap();assert_eq!(policy.retired.load(Ordering::SeqCst),1);
    }).await.unwrap()
}
#[tokio::test]
async fn deadline_and_stop_close_kernel_socket_and_lease_without_retry(){
    for cancelled in [false,true]{tokio::time::timeout(Duration::from_secs(5),async{
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let cancel=CancellationToken::new();let signal=cancel.clone();
        let server=tokio::spawn(async move{let(mut stream,_)=listener.accept().await.unwrap();read_query(&mut stream).await;if cancelled{signal.cancel()}let mut byte=[0];assert_eq!(stream.read(&mut byte).await.unwrap(),0);});
        let policy=OwnedPolicy::new();let result=exchange(&policy,peer,Direction::Direct,&Transport::Tcp,&query(),Instant::now()+Duration::from_millis(200),&cancel).await;
        assert_eq!(result,Err(if cancelled{Failure::Cancelled}else{Failure::Deadline}));server.await.unwrap();assert_eq!(policy.prepared.lock().unwrap().len(),1);assert_eq!(policy.retired.load(Ordering::SeqCst),1);
    }).await.unwrap()}
}
#[tokio::test]
async fn strict_tls_accepts_owned_root_and_name_but_not_wrong_name_or_untrusted_cert(){
    for (trusted,correct) in [(true,true),(true,false),(false,true)]{tokio::time::timeout(Duration::from_secs(5),async{
        let certificate=rcgen::generate_simple_self_signed(vec!["owned-dns.test".to_owned()]).unwrap();
        let key=turn_rustls::pki_types::PrivatePkcs8KeyDer::from(certificate.key_pair.serialize_der());
        let server=turn_rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(vec![certificate.cert.der().clone()],key.into()).unwrap();
        let mut roots=turn_rustls::RootCertStore::empty();roots.add(certificate.cert.der().clone()).unwrap();
        let client=Arc::new(turn_rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth());
        let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let success=trusted&&correct;
        let server=tokio::spawn(async move{let(stream,_)=listener.accept().await.unwrap();let accepted=turn_tokio_rustls::TlsAcceptor::from(Arc::new(server)).accept(stream).await;if !success{assert!(accepted.is_err());return}let mut stream=accepted.unwrap();read_query(&mut stream).await;write_reply(&mut stream).await;});
        let policy=OwnedPolicy::new();let name=if correct{"owned-dns.test"}else{"wrong-owned.test"};
        let result=if trusted{tls_with_config(&policy,peer,Direction::Vpn,name,client).await}else{tls(&policy,peer,Direction::Vpn,name,false).await};
        if success{let mut stream=result.unwrap();assert_eq!(framed(&mut stream,&query()).await.unwrap(),reply());drop(stream)}else{assert!(matches!(result,Err(Failure::Tls)))}
        server.await.unwrap();assert_eq!(policy.retired.load(Ordering::SeqCst),1);
    }).await.unwrap()}
}
#[tokio::test]
async fn doh_http_framing_accepts_bounded_length_and_chunked_only_without_redirect_or_smuggling(){
    let mut cases=Vec::new();let body=reply();
    for chunked in [false,true]{let mut response=if chunked{b"HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nTransfer-Encoding: chunked\r\n\r\n".to_vec()}else{format!("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: {}\r\n\r\n",body.len()).into_bytes()};
        if chunked{response.extend_from_slice(format!("{:x}\r\n",body.len()).as_bytes());response.extend_from_slice(&body);response.extend_from_slice(b"\r\n0\r\n\r\n")}else{response.extend_from_slice(&body)}cases.push((response,true));}
    for invalid in ["HTTP/1.1 302 Found\r\nLocation: https://example.invalid/\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 17\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 17\r\nContent-Length: 17\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 17\r\nTransfer-Encoding: chunked\r\n\r\n","HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nTransfer-Encoding: chunked\r\n\r\nffffffffffffffff\r\n"]{cases.push((invalid.as_bytes().to_vec(),false));}
    for (response,valid) in cases{tokio::time::timeout(Duration::from_secs(3),async{
        let(mut client,mut server)=tokio::io::duplex(4096);
        let peer=tokio::spawn(async move{let mut headers=Vec::new();while !headers.ends_with(b"\r\n\r\n"){headers.push(server.read_u8().await.unwrap());assert!(headers.len()<2048)}let mut q=vec![0;query().len()];server.read_exact(&mut q).await.unwrap();assert_eq!(q,query());let _=server.write_all(&response).await;});
        let result=http(&mut client,"127.0.0.1:443".parse().unwrap(),"owned-dns.test","/dns-query",&query()).await;
        if valid{assert_eq!(result.unwrap(),reply())}else{assert!(result.is_err())}drop(client);peer.await.unwrap();
    }).await.unwrap()}
}

#[test]fn dns_scope_revocation_runs_before_owned_descriptor_close(){
 use std::os::fd::{FromRawFd,OwnedFd};
 struct Check{i:i32,checked:std::sync::Arc<std::sync::atomic::AtomicBool>}
 impl Drop for Check{fn drop(&mut self){assert!(unsafe{libc::fcntl(self.i,libc::F_GETFD)}>=0);self.checked.store(true,std::sync::atomic::Ordering::SeqCst);}}
 let raw=unsafe{libc::socket(libc::AF_INET,libc::SOCK_DGRAM|libc::SOCK_CLOEXEC,0)};assert!(raw>=0);let fd=unsafe{OwnedFd::from_raw_fd(raw)};let checked=std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));let leased=Leased{io:fd,_lease:Box::new(Check{i:raw,checked:checked.clone()})};drop(leased);assert!(checked.load(std::sync::atomic::Ordering::SeqCst));
}
