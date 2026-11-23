use super::*;
use std::{pin::Pin,future::Future,sync::{Mutex,atomic::{AtomicUsize,Ordering}},time::Duration};
use tokio::io::{AsyncReadExt,AsyncWriteExt};
use mcrf_bs_routing_policy::{Target,associations::Tracker};
use crate::{mcrf_bs_tcp_dispatch::owned::{Router,Failure as RouteFailure},mcrf_bs_dns_transport::{SocketPolicy,Failure as SocketFailure,Direction},mcrf_bs_dns_engine::Engine,mcrf_bs_dns_handler::Underlay,mcrf_bs_proxy_listener::Group};
struct Sockets(AtomicUsize);impl SocketPolicy for Sockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:Direction)->Result<Box<dyn Send>,SocketFailure>{self.0.fetch_add(1,Ordering::SeqCst);Ok(Box::new(()))}}
struct Route{peer:SocketAddr,dns:bool}
impl Router for Route{fn decide<'a>(&'a self,original:Original<'a>,stream:&'a mut Stream,prefix:&'a mut Prefix,cancel:&'a CancellationToken)->Pin<Box<dyn Future<Output=Result<Decision,RouteFailure>>+Send+'a>>{Box::pin(async move{
 assert_eq!(original.uid,10001);assert!(original.source.ip().is_loopback());assert_eq!(original.destination_domain,Some("Owned.Invalid"));assert_eq!(original.destination.port(),5353);
 if self.dns{return Ok(Decision::Dns)}
 *prefix=crate::mcrf_bs_tcp_inspect::capture(stream,Duration::from_secs(1),std::mem::take(&mut prefix.bytes),&[],cancel).await.map_err(|_|RouteFailure::Route)?;assert_eq!(prefix.protocol,Some("ssh"));Ok(Decision::Forward{peer:self.peer,target:Target::Direct})
 })}}
async fn run(changed:bool,dns:bool){tokio::time::timeout(Duration::from_secs(5),async{
 let echo=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=echo.local_addr().unwrap();
 let(tx,mut requests)=tokio::sync::mpsc::unbounded_channel();let owner=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());
 let engine=Arc::new(Engine::new(42,1,&serde_json::json!({"servers":[{"type":"hosts","tag":"h","predefined":{"answer.invalid":"192.0.2.9"}}],"final":"h"}),&serde_json::json!({}),&serde_json::json!({}),std::collections::HashMap::new(),&[]).unwrap());let sockets=Arc::new(Sockets(AtomicUsize::new(0)));
 let session=Arc::new(Session{generation:42,tracker:Arc::new(Mutex::new(Tracker::new(42,8).unwrap())),clock:Arc::new(||Ok(1)),owner:owner.clone(),engine:engine.clone(),sockets:sockets.clone(),underlay:Underlay{kind:"wifi".into(),expensive:false,constrained:false,ssid:String::new(),bssid:String::new()},router:Arc::new(Route{peer,dns}),retire:Arc::new(|_|Ok(()))});
 let mut plan=mcrf_bs_routing_policy::listeners::parse(Some(&serde_json::json!([{"type":"mixed","tag":"p","listen":"127.0.0.1","listen_port":10808}]))).unwrap().remove(0);plan.address.set_port(0);
 let group=Group::start(42,vec![plan],2,handler(session),Arc::new(|_|{}),&CancellationToken::new()).unwrap();let local=group.addresses()[0];let mut client=tokio::net::TcpStream::connect(local).await.unwrap();let source=client.local_addr().unwrap();
 let proof=owner.clone();let checks=Arc::new(AtomicUsize::new(0));let count=checks.clone();let owners=tokio::spawn(async move{while let Some(line)=requests.recv().await{let fields=line.split_whitespace().collect::<Vec<_>>();assert_eq!(fields[4],source.ip().to_string());assert_eq!(fields[5],source.port().to_string());assert_eq!(fields[6],local.ip().to_string());assert_eq!(fields[7],local.port().to_string());let round=count.fetch_add(1,Ordering::SeqCst);let uid=if changed&&round>0{10002}else{10001};proof.respond(&format!("BS_OWNER|42|{}|{uid}|W10=",fields[2])).unwrap();}});
 let mut request=vec![5,1,0,5,1,0,3,13];request.extend_from_slice(b"Owned.Invalid");request.extend_from_slice(&5353u16.to_be_bytes());if !dns{request.extend_from_slice(b"SSH-2.0-owned\r\n")};client.write_all(&request).await.unwrap();let mut hello=[0;2];client.read_exact(&mut hello).await.unwrap();assert_eq!(hello,[5,0]);
 if !dns{client.shutdown().await.unwrap();}
 if changed{let mut bytes=Vec::new();client.read_to_end(&mut bytes).await.unwrap();assert_eq!(sockets.0.load(Ordering::SeqCst),0);assert!(tokio::time::timeout(Duration::from_millis(20),echo.accept()).await.is_err());}
 else if dns{let mut ack=[0;10];client.read_exact(&mut ack).await.unwrap();let query=mcrf_bs_routing_policy::dns_wire::query(1,"answer.invalid",1).unwrap();client.write_u16(query.len()as u16).await.unwrap();client.write_all(&query).await.unwrap();client.shutdown().await.unwrap();let size=client.read_u16().await.unwrap();let mut answer=vec![0;size as usize];client.read_exact(&mut answer).await.unwrap();assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer).unwrap()[0].ip,"192.0.2.9".parse::<std::net::IpAddr>().unwrap());assert_eq!(sockets.0.load(Ordering::SeqCst),0);assert_eq!(checks.load(Ordering::SeqCst),4);}
 else{let(mut server,_)=echo.accept().await.unwrap();let mut body=Vec::new();server.read_to_end(&mut body).await.unwrap();assert_eq!(body,b"SSH-2.0-owned\r\n");server.write_all(b"reply").await.unwrap();server.shutdown().await.unwrap();let mut reply=Vec::new();client.read_to_end(&mut reply).await.unwrap();let mut expected=success(crate::mcrf_bs_proxy_protocol::Protocol::Socks5,None);expected.extend_from_slice(b"reply");assert_eq!(reply,expected);assert_eq!(sockets.0.load(Ordering::SeqCst),1);}
 group.close().await.unwrap();owner.close();owners.abort();let _=owners.await;
 }).await.unwrap()}
#[tokio::test]async fn inspected_proxy_uses_shared_forwarder_and_actual_owner_tuple(){run(false,false).await}
#[tokio::test]async fn owner_change_after_sniff_cannot_open_an_outbound(){run(true,false).await}
#[tokio::test]async fn dns_hijack_refreshes_proxy_socket_owner_for_each_answer(){run(false,true).await}
