use super::*;
use std::{net::SocketAddr,sync::atomic::{AtomicUsize,Ordering},time::Duration};
use tokio::io::{AsyncReadExt,AsyncWriteExt};
use crate::mcrf_bs_dns_transport::{self as transport,Transport};
struct Sockets(AtomicUsize);impl SocketPolicy for Sockets{fn prepare(&self,_:u8,_:i32,_:u16,_:SocketAddr,_:Direction)->Result<Box<dyn Send>,Failure>{self.0.fetch_add(1,Ordering::SeqCst);Ok(Box::new(()))}}
fn encoded(bytes:&[u8])->Value{serde_json::json!({"store":"system","roots":[base64::engine::general_purpose::STANDARD.encode(bytes)]})}
#[test]fn captured_roots_are_validated_and_profiles_keep_distinct_immutable_stores(){
 let first=rcgen::generate_simple_self_signed(vec!["first.owned".into()]).unwrap();let second=rcgen::generate_simple_self_signed(vec!["second.owned".into()]).unwrap();let sockets=Arc::new(Sockets(AtomicUsize::new(0)));
 let a=wrap(Some(&encoded(first.cert.der().as_ref())),sockets.clone()).unwrap();let b=wrap(Some(&encoded(second.cert.der().as_ref())),sockets.clone()).unwrap();let ar=a.tls_roots().unwrap();let br=b.tls_roots().unwrap();assert_eq!(ar.len(),1);assert_eq!(br.len(),1);assert!(!Arc::ptr_eq(&ar,&br));assert_eq!(sockets.0.load(Ordering::SeqCst),0);
 let mut duplicate=encoded(first.cert.der().as_ref());let item=duplicate["roots"][0].clone();duplicate["roots"].as_array_mut().unwrap().push(item);assert!(roots(&duplicate).is_err());
 for bad in [serde_json::json!({"store":"system","roots":[]}),serde_json::json!({"store":"other","roots":["AA=="]}),encoded(b"invalid DER"),serde_json::json!({"store":"system","roots":["not-base64"]}),serde_json::json!({"store":"system","roots":["AA=="],"fallback":true})]{assert!(roots(&bad).is_err());}
 assert!(Arc::ptr_eq(&wrap(None,sockets.clone()).unwrap(),&(sockets as Arc<dyn SocketPolicy>)));
}
#[tokio::test]async fn dns_tls_uses_only_the_captured_ca_and_still_checks_server_name(){tokio::time::timeout(Duration::from_secs(10),async{
 for (trusted,right_name)in [(true,true),(true,false),(false,true)]{
  let cert=rcgen::generate_simple_self_signed(vec!["owned-dns.test".into()]).unwrap();let other=rcgen::generate_simple_self_signed(vec!["owned-dns.test".into()]).unwrap();
  let key=turn_rustls::pki_types::PrivateKeyDer::Pkcs8(turn_rustls::pki_types::PrivatePkcs8KeyDer::from(cert.key_pair.serialize_der()));let config=turn_rustls::ServerConfig::builder().with_no_client_auth().with_single_cert(vec![cert.cert.der().clone()],key).unwrap();
  let listener=tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();let peer=listener.local_addr().unwrap();let success=trusted&&right_name;
  let server=tokio::spawn(async move{let(stream,_)=listener.accept().await.unwrap();let accepted=turn_tokio_rustls::TlsAcceptor::from(Arc::new(config)).accept(stream).await;if !success{assert!(accepted.is_err());return}let mut stream=accepted.unwrap();let size=stream.read_u16().await.unwrap();let mut query=vec![0;size as usize];stream.read_exact(&mut query).await.unwrap();let answer=mcrf_bs_routing_policy::dns_wire::answer(&query,0,&["192.0.2.9".parse().unwrap()],60).unwrap();stream.write_u16(answer.len()as u16).await.unwrap();stream.write_all(&answer).await.unwrap();stream.shutdown().await.unwrap();});
  let original=Arc::new(Sockets(AtomicUsize::new(0)));let policy=wrap(Some(&encoded(if trusted{cert.cert.der().as_ref()}else{other.cert.der().as_ref()})),original.clone()).unwrap();let query=mcrf_bs_routing_policy::dns_wire::query(7,"answer.invalid",1).unwrap();let name=if right_name{"owned-dns.test"}else{"wrong-owned.test"};
  let answer=transport::exchange(policy.as_ref(),peer,Direction::Direct,&Transport::Tls{name:name.into()},&query,tokio::time::Instant::now()+Duration::from_secs(3),&tokio_util::sync::CancellationToken::new()).await;
  if success{assert_eq!(mcrf_bs_routing_policy::dns_wire::addresses(&query,&answer.unwrap()).unwrap()[0].ip,"192.0.2.9".parse::<std::net::IpAddr>().unwrap())}else{assert!(matches!(answer,Err(Failure::Tls)))}assert_eq!(original.0.load(Ordering::SeqCst),1);server.await.unwrap();
 }
}).await.unwrap()}
