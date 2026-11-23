use super::*;
use std::sync::{Mutex,atomic::{AtomicUsize,Ordering}};
use base64::Engine as _;
use crate::{mcrf_bs_session::Dependencies,mcrf_bs_dns_transport::{Direction,SocketPolicy,Failure as SocketFailure},mcrf_bs_udp_dispatch::{Reply,Task,Failure as UdpFailure}};
struct Sockets{opened:AtomicUsize,dropped:Arc<AtomicUsize>}
struct Lease(Arc<AtomicUsize>);impl Drop for Lease{fn drop(&mut self){self.0.fetch_add(1,Ordering::SeqCst);}}
impl SocketPolicy for Sockets{fn prepare(&self,protocol:u8,_:i32,_:u16,_:SocketAddr,direction:Direction)->Result<Box<dyn Send>,SocketFailure>{assert_eq!(protocol,6);assert_eq!(direction,Direction::Vpn,"explicit probe must NEVER take the Direct route");self.opened.fetch_add(1,Ordering::SeqCst);Ok(Box::new(Lease(self.dropped.clone())))}}
struct NoReply;impl Reply for NoReply{fn write<'a>(&'a self,_:SocketAddr,_:SocketAddr,_:&'a [u8],_:&'a CancellationToken)->Task<'a,()>{Box::pin(async{Err(UdpFailure::Reply)})}}
fn session()->(Arc<Session>,Arc<Sockets>){
 let sockets=Arc::new(Sockets{opened:AtomicUsize::new(0),dropped:Arc::new(AtomicUsize::new(0))});let doc=serde_json::json!({"schema":1,"policy":{"schema":1,"defaultTarget":"block","rules":[]},"controls":[],"dns":{"servers":[{"type":"hosts","tag":"h","predefined":{"owned.invalid":"127.0.0.1"}}],"final":"h"},"routeOptions":{"default_domain_resolver":"h"},"tunInbounds":[{"type":"tun","address":["10.89.0.2/32"],"mtu":1500}],"dnsDialers":{}});
 let deps=Dependencies{generation:42,capacity:4,owner:Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(|_|panic!("control-plane origin must not assert ownership of a user tuple"))).unwrap()),physical:1,hosts:std::collections::HashMap::new(),excluded:Vec::new(),sockets:sockets.clone(),underlay:crate::mcrf_bs_dns_handler::Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},retire:Arc::new(|_|Ok(())),connector:Arc::new(crate::mcrf_bs_udp_transport::Selected{policy:sockets.clone()}),reply:Arc::new(NoReply)};
 let session=Arc::new(Session::prepare(&serde_json::to_vec(&doc).unwrap(),deps,&CancellationToken::new()).unwrap());
 session.attach_carrier(&["192.0.2.7".parse().unwrap()]).unwrap();
 (session,sockets)
}
#[test]fn command_requires_exact_generation_canonical_ids_limits_and_safe_url(){
 let url=base64::engine::general_purpose::STANDARD.encode(b"https://owned.invalid/path?q=1");let good=format!("BS_NATIVE_HEALTH|42|1|3000|{url}");let c=command(&good,42).unwrap();assert_eq!(c.id,1);assert_eq!(c.timeout,3000);assert_eq!(c.url,"https://owned.invalid/path?q=1");
 for bad in [good.replace("|42|","|41|"),good.replace("|1|","|01|"),good.replace("|3000|","|30001|"),format!("{good}|extra"),format!("{good}\n")]{assert!(command(&bad,42).is_err())}
 for url in ["ftp://owned.invalid","http://u:p@owned.invalid/","http://owned.invalid:0/","https://owned.invalid/#fragment"]{assert!(validate_url(url).is_err())}
}
#[tokio::test]async fn explicit_probe_keeps_captured_dns_and_forces_vpn_even_when_normal_policy_blocks(){tokio::time::timeout(Duration::from_secs(5),async{
 let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let port=listener.local_addr().unwrap().port();let server=tokio::spawn(async move{let(mut socket,_)=listener.accept().await.unwrap();let mut request=vec![0;4096];let count=socket.read(&mut request).await.unwrap();let text=std::str::from_utf8(&request[..count]).unwrap();assert!(text.starts_with("GET /owned?q=1 HTTP/1.1\r\n"));assert!(text.contains("Host: owned.invalid:"));socket.write_all(b"HTTP/1.1 103 Early Hints\r\nLink: </owned>\r\n\r\nHTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n").await.unwrap();});
 let(session,sockets)=session();assert!(probe(session,&format!("http://owned.invalid:{port}/owned?q=1"),3000,&CancellationToken::new()).await.is_ok());server.await.unwrap();assert_eq!(sockets.opened.load(Ordering::SeqCst),1);assert_eq!(sockets.dropped.load(Ordering::SeqCst),1);
}).await.unwrap()}
#[tokio::test]async fn cancellation_timeout_and_invalid_http_close_scoped_socket_without_fallback(){tokio::time::timeout(Duration::from_secs(5),async{
 let(session,sockets)=session();let cancel=CancellationToken::new();cancel.cancel();assert_eq!(probe(session.clone(),"http://owned.invalid/",100,&cancel).await,Err(Failure::Cancelled));assert_eq!(sockets.opened.load(Ordering::SeqCst),0);
 let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let port=listener.local_addr().unwrap().port();let server=tokio::spawn(async move{let(mut socket,_)=listener.accept().await.unwrap();let mut data=[0;4096];assert!(socket.read(&mut data).await.unwrap()>0);assert_eq!(socket.read(&mut data).await.unwrap(),0);});
 assert_eq!(probe(session.clone(),&format!("http://owned.invalid:{port}/"),100,&CancellationToken::new()).await,Err(Failure::Timeout));server.await.unwrap();assert_eq!(sockets.opened.load(Ordering::SeqCst),sockets.dropped.load(Ordering::SeqCst));
 let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let port=listener.local_addr().unwrap().port();let server=tokio::spawn(async move{let(mut socket,_)=listener.accept().await.unwrap();let mut data=[0;4096];socket.read(&mut data).await.unwrap();socket.write_all(b"HTTP/1.1 200 OK\r\nunfinished").await.unwrap();});
 assert_eq!(probe(session,&format!("http://owned.invalid:{port}/"),1000,&CancellationToken::new()).await,Err(Failure::Http));server.await.unwrap();assert_eq!(sockets.opened.load(Ordering::SeqCst),sockets.dropped.load(Ordering::SeqCst));
}).await.unwrap()}
