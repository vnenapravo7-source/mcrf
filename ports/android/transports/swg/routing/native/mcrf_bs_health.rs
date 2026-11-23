//! Privileged explicit-carrier reachability, not an ordinary routed URL test.
//! DNS keeps the captured policy and the TCP leg uses the owned default worker.
use std::{net::{IpAddr,Ipv4Addr,SocketAddr},sync::Arc,time::Duration};
use tokio::{io::{AsyncReadExt,AsyncWriteExt,AsyncBufReadExt,BufReader},time::Instant};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{Flow,Observed};
use crate::{mcrf_bs_session::Session,mcrf_bs_tcp_dispatch::Stream};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Input,Resolve,Connect,Tls,Io,Http,Cancelled,Timeout}
pub struct Command{pub id:u64,pub timeout:u64,pub url:String}
pub fn command(line:&str,generation:u64)->Result<Command,Failure>{
 use base64::Engine as _;
 if line.len()>12000||line.chars().any(char::is_control){return Err(Failure::Input)}let fields=line.split('|').collect::<Vec<_>>();if fields.len()!=5||fields[0]!="BS_NATIVE_HEALTH"{return Err(Failure::Input)}
 let token=|value:&str|crate::mcrf_direct_tun::parse_token(value).map_err(|_|Failure::Input);
 if token(fields[1])?!=generation{return Err(Failure::Input)}let id=token(fields[2])?;let timeout=token(fields[3])?;if timeout>30000{return Err(Failure::Input)}
 let bytes=base64::engine::general_purpose::STANDARD.decode(fields[4]).map_err(|_|Failure::Input)?;if bytes.len()>8192||base64::engine::general_purpose::STANDARD.encode(&bytes)!=fields[4]{return Err(Failure::Input)}
 let url=String::from_utf8(bytes).map_err(|_|Failure::Input)?;validate_url(&url)?;Ok(Command{id,timeout,url})
}
fn validate_url(text:&str)->Result<url::Url,Failure>{if text.len()>8192||text.chars().any(char::is_control){return Err(Failure::Input)}let url=url::Url::parse(text).map_err(|_|Failure::Input)?;
 if !matches!(url.scheme(),"http"|"https")||url.host_str().is_none()||!url.username().is_empty()||url.password().is_some()||url.fragment().is_some()||url.port_or_known_default().is_none_or(|v|v==0){return Err(Failure::Input)}Ok(url)}
pub async fn probe(session:Arc<Session>,url:&str,timeout:u64,cancel:&CancellationToken)->Result<u64,Failure>{
 if !(1..=30000).contains(&timeout){return Err(Failure::Input)}let url=validate_url(url)?;let started=Instant::now();
 tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),value=tokio::time::timeout(Duration::from_millis(timeout),execute(&session,url,cancel))=>value.map_err(|_|Failure::Timeout)?.map(|_|started.elapsed().as_millis()as u64)}
}
async fn execute(session:&Session,url:url::Url,cancel:&CancellationToken)->Result<(),Failure>{
 let name=url.host_str().ok_or(Failure::Input)?.trim_start_matches('[').trim_end_matches(']');let port=url.port_or_known_default().ok_or(Failure::Input)?;
 let source=session.tun.as_ref().and_then(|p|p.addresses.first()).ok_or(Failure::Input)?.address;let destination=name.parse::<IpAddr>().unwrap_or(Ipv4Addr::UNSPECIFIED.into());
 // This request originates in the authenticated control plane, not an app flow.
 // It therefore has no application attribution; never fabricate a caller UID.
 let flow=Flow{source,destination,source_port:0,port,network:"tcp",domain:Observed::Known(Some(name)),protocol:Observed::Known(Some(if url.scheme()=="https"{"tls"}else{"http"})),packages:Some(&[]),underlay:Observed::Known(Some(session.tcp.underlay.borrowed()))};
 let peers=if let Ok(ip)=name.parse::<IpAddr>(){vec![SocketAddr::new(ip,port)]}else{session.tcp.engine.lookup(session.tcp.sockets.as_ref(),name,&flow,&session.raw.default_resolver,Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE,cancel).await.map_err(|_|Failure::Resolve)?.into_iter().map(|ip|SocketAddr::new(ip,port)).collect::<Vec<_>>()};
 if peers.is_empty(){return Err(Failure::Resolve)}for peer in &peers{if session.tcp.engine.virtual_address(peer.ip()).map_err(|_|Failure::Resolve)?{return Err(Failure::Resolve)}}
 let direction=session.probe_direction().ok_or(Failure::Connect)?;
 let socket=crate::mcrf_bs_tcp_inspect::connect_candidates(&peers,session.tcp.sockets.as_ref(),direction,cancel).await.map_err(|_|Failure::Connect)?;
 let mut stream:Stream=if url.scheme()=="https"{let config=crate::mcrf_bs_dns_transport::tls_config(session.tcp.sockets.as_ref(),true);let name=turn_rustls::pki_types::ServerName::try_from(name.to_owned()).map_err(|_|Failure::Input)?;Box::new(turn_tokio_rustls::TlsConnector::from(config).connect(name,socket).await.map_err(|_|Failure::Tls)?)}else{Box::new(socket)};
 let path=if let Some(query)=url.query(){format!("{}?{query}",url.path())}else{url.path().to_owned()};let authority=if let Some(port)=url.port(){format!("{}:{port}",url.host_str().ok_or(Failure::Input)?)}else{url.host_str().ok_or(Failure::Input)?.to_owned()};
 let request=format!("GET {path} HTTP/1.1\r\nHost: {authority}\r\nConnection: close\r\nUser-Agent: MCRF\r\n\r\n");stream.write_all(request.as_bytes()).await.map_err(|_|Failure::Io)?;stream.flush().await.map_err(|_|Failure::Io)?;
 let mut input=BufReader::new(stream).take(16385);let mut total=0usize;
 for _ in 0..5{let mut line=Vec::new();let n=input.read_until(b'\n',&mut line).await.map_err(|_|Failure::Io)?;total+=n;if n==0||total>16384||!line.ends_with(b"\r\n"){return Err(Failure::Http)}
  let text=std::str::from_utf8(&line[..line.len()-2]).map_err(|_|Failure::Http)?;let fields=text.split(' ').collect::<Vec<_>>();if fields.len()<2||!matches!(fields[0],"HTTP/1.1"|"HTTP/1.0")||fields[1].len()!=3||!fields[1].bytes().all(|v|v.is_ascii_digit()){return Err(Failure::Http)}let status=fields[1].parse::<u16>().map_err(|_|Failure::Http)?;
  loop{line.clear();let n=input.read_until(b'\n',&mut line).await.map_err(|_|Failure::Io)?;total+=n;if n==0||total>16384||!line.ends_with(b"\r\n"){return Err(Failure::Http)}if line==b"\r\n"{break}if line[0].is_ascii_whitespace()||!line.contains(&b':'){return Err(Failure::Http)}}
  if (200..400).contains(&status){return Ok(())}if !(100..200).contains(&status){return Err(Failure::Http)}
 }Err(Failure::Http)
}
#[cfg(test)]#[path="mcrf_bs_health_tests.rs"]mod tests;
