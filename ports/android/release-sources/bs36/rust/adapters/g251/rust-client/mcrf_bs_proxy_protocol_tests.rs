use super::*;
use serde_json::json;
fn plan(auth:bool)->Plan{let mut value=json!([{"type":"mixed","tag":"local-proxy","listen":"127.0.0.1","listen_port":10808}]);if auth{value[0]["users"]=json!([{"username":"owned","password":"fixture"}])}mcrf_bs_routing_policy::listeners::parse(Some(&value)).unwrap().remove(0)}
async fn exchange(bytes:&[u8],auth:bool)->(Result<Negotiated,Failure>,Vec<u8>,Vec<u8>){
 let(mut client,server)=tokio::io::duplex(262144);client.write_all(bytes).await.unwrap();client.shutdown().await.unwrap();let mut server=BufReader::new(server);let result=negotiate(&mut server,&plan(auth),&CancellationToken::new()).await;
 let mut remaining=Vec::new();server.read_to_end(&mut remaining).await.unwrap();drop(server);let mut response=Vec::new();client.read_to_end(&mut response).await.unwrap();(result,response,remaining)
}
#[tokio::test]async fn socks5_authentication_domain_and_prefetched_payload_remain_separate(){
 let mut bytes=vec![5,2,0,2,1,5];bytes.extend_from_slice(b"owned");bytes.push(7);bytes.extend_from_slice(b"fixture");bytes.extend_from_slice(&[5,1,0,3,13]);bytes.extend_from_slice(b"Owned.Invalid");bytes.extend_from_slice(&443u16.to_be_bytes());bytes.extend_from_slice(b"first payload");
 let(value,response,remaining)=exchange(&bytes,true).await;let value=value.unwrap();assert!(value.protocol==Protocol::Socks5);assert!(matches!(value.command,Command::Connect(Destination::Domain(ref name,443))if name=="Owned.Invalid"));assert_eq!(response,[5,2,1,0]);assert_eq!(remaining,b"first payload");assert!(value.prefix.is_empty());
 assert_eq!(success(Protocol::Socks5,None),[5,0,0,1,0,0,0,0,0,0]);
}
#[tokio::test]async fn socks5_wrong_auth_or_missing_method_never_yields_a_destination(){
 let(value,response,_)=exchange(&[5,1,0],true).await;assert!(matches!(value,Err(Failure::Auth)));assert_eq!(response,[5,255]);
 let mut bytes=vec![5,1,2,1,5];bytes.extend_from_slice(b"owned");bytes.push(3);bytes.extend_from_slice(b"bad");let(value,response,_)=exchange(&bytes,true).await;assert!(matches!(value,Err(Failure::Auth)));assert_eq!(response,[5,2,1,1]);
}
#[tokio::test]async fn socks5_ipv6_and_udp_association_are_distinct_commands(){
 let mut bytes=vec![5,1,0,5,1,0,4];bytes.extend_from_slice(&"2001:db8::7".parse::<Ipv6Addr>().unwrap().octets());bytes.extend_from_slice(&443u16.to_be_bytes());let(value,response,_)=exchange(&bytes,false).await;assert_eq!(response,[5,0]);assert!(matches!(value.unwrap().command,Command::Connect(Destination::Address(v))if v=="[2001:db8::7]:443".parse().unwrap()));
 let(value,_,_)=exchange(&[5,1,0,5,3,0,1,0,0,0,0,0,0],false).await;assert!(matches!(value.unwrap().command,Command::Associate(Destination::Address(v))if v.port()==0&&v.ip().is_unspecified()));
}
#[tokio::test]async fn socks4a_works_only_without_configured_password_auth(){
 let mut bytes=vec![4,1,1,187,0,0,0,1,0];bytes.extend_from_slice(b"owned.invalid\0payload");let(value,_,remaining)=exchange(&bytes,false).await;assert!(matches!(value.unwrap().command,Command::Connect(Destination::Domain(ref name,443))if name=="owned.invalid"));assert_eq!(remaining,b"payload");
 let(value,response,_)=exchange(&bytes,true).await;assert!(matches!(value,Err(Failure::Auth)));assert_eq!(response,[0,91,0,0,0,0,0,0]);
}
#[tokio::test]async fn http_connect_requires_auth_and_does_not_ack_before_backend_admission(){
 let auth=base64::engine::general_purpose::STANDARD.encode(b"owned:fixture");let request=format!("CONNECT [2001:db8::7]:443 HTTP/1.1\r\nProxy-Authorization: Basic {auth}\r\n\r\nTLS payload");let(value,response,remaining)=exchange(request.as_bytes(),true).await;
 let value=value.unwrap();assert!(value.protocol==Protocol::HttpConnect);assert!(matches!(value.command,Command::Connect(Destination::Address(v))if v=="[2001:db8::7]:443".parse().unwrap()));assert!(response.is_empty());assert_eq!(remaining,b"TLS payload");assert_eq!(success(value.protocol,None),b"HTTP/1.1 200 Connection Established\r\n\r\n");
 let(value,response,_)=exchange(b"CONNECT owned.invalid:443 HTTP/1.1\r\n\r\n",true).await;assert!(matches!(value,Err(Failure::Auth)));assert!(response.starts_with(b"HTTP/1.1 407 "));
}
#[tokio::test]async fn http_forward_preserves_raw_path_body_and_strips_proxy_credentials(){
 let auth=base64::engine::general_purpose::STANDARD.encode(b"owned:fixture");let request=format!("POST http://Owned.Invalid:8080/a/../b?x=%23 HTTP/1.1\r\nProxy-Authorization: Basic {auth}\r\nContent-Length: 4\r\nConnection: keep-alive, x-hop\r\nX-Hop: private\r\n\r\nbody");let(value,response,remaining)=exchange(request.as_bytes(),true).await;
 let value=value.unwrap();assert!(value.protocol==Protocol::HttpForward);assert!(matches!(value.command,Command::Connect(Destination::Domain(ref name,8080))if name=="Owned.Invalid"));assert!(response.is_empty());assert_eq!(remaining,b"body");let prefix=String::from_utf8(value.prefix).unwrap();assert!(prefix.starts_with("POST /a/../b?x=%23 HTTP/1.1\r\n"));assert!(prefix.contains("host: Owned.Invalid:8080\r\n"));assert!(prefix.ends_with("connection: close\r\n\r\n"));assert!(!prefix.to_ascii_lowercase().contains("proxy-authorization"));assert!(!prefix.contains("x-hop"));assert!(!prefix.contains(&auth));
}
#[tokio::test]async fn malformed_or_oversized_http_cannot_smuggle_framing_or_grow_unbounded(){
 for request in ["GET http://owned.invalid/ HTTP/1.1\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n","GET http://owned.invalid/ HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n","GET http://owned.invalid/%XX HTTP/1.1\r\n\r\n","GET http://owned.invalid/ HTTP/1.1\r\n folded: value\r\n\r\n"]{let(value,_,_)=exchange(request.as_bytes(),false).await;assert!(matches!(value,Err(Failure::Protocol)))}
 let(value,_,remaining)=exchange(&vec![b'x';131072],false).await;assert!(matches!(value,Err(Failure::Limit)));assert!(remaining.len()>=65535);
}
#[tokio::test]async fn udp_frames_preserve_zero_payload_and_reject_fragments_or_truncation(){
 for destination in [Destination::Address("198.51.100.7:443".parse().unwrap()),Destination::Address("[2001:db8::7]:53".parse().unwrap()),Destination::Domain("owned.invalid".into(),5353)]{
  for size in [0,17,1200]{let payload=vec![7;size];let bytes=datagram_reply(&destination,&payload).unwrap();let(decoded,data)=datagram(&bytes).await.unwrap();assert!(decoded==destination);assert_eq!(data,payload)}
 }
 assert!(matches!(datagram(&[0,0,1,1,0,0,0,0,0,53]).await,Err(Failure::Unsupported)));assert!(datagram(&[0,0,0,3,255]).await.is_err());assert!(datagram_reply(&Destination::Domain("x".repeat(256),53),&[]).is_err());
}
#[tokio::test]async fn cancelled_or_incomplete_handshake_has_a_bounded_lifetime(){
 let(_client,server)=tokio::io::duplex(1024);let mut server=BufReader::new(server);let cancel=CancellationToken::new();cancel.cancel();assert!(matches!(negotiate(&mut server,&plan(false),&cancel).await,Err(Failure::Cancelled)));
}
#[tokio::test(start_paused=true)]async fn idle_handshake_times_out_without_a_live_unbounded_reader(){let(_client,server)=tokio::io::duplex(1024);let mut server=BufReader::new(server);assert!(matches!(negotiate(&mut server,&plan(false),&CancellationToken::new()).await,Err(Failure::Timeout)))}
