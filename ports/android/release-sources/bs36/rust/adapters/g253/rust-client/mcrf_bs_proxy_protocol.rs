//! Bounded local mixed-proxy negotiation. No DNS lookup or outbound socket is
//! opened here. The session backend must authenticate the accepted kernel
//! tuple before acknowledging or forwarding a negotiated request.
use std::{net::{IpAddr,Ipv4Addr,Ipv6Addr,SocketAddr},time::Duration};
use tokio::io::{AsyncBufReadExt,AsyncRead,AsyncReadExt,AsyncWrite,AsyncWriteExt,BufReader};
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::listeners::{Plan,Kind};
use base64::Engine as _;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Io,Protocol,Auth,Unsupported,Limit,Cancelled,Timeout}
#[derive(Clone,PartialEq,Eq,Hash)]pub enum Destination{Address(SocketAddr),Domain(String,u16)}
pub enum Command{Connect(Destination),Associate(Destination)}
#[derive(Clone,Copy,PartialEq,Eq)]pub enum Protocol{Socks5,Socks4,HttpConnect,HttpForward}
pub struct Negotiated{pub protocol:Protocol,pub command:Command,pub prefix:Vec<u8>}
fn io(_:std::io::Error)->Failure{Failure::Io}
fn domain(name:&str,port:u16)->Result<Destination,Failure>{
 if let Ok(ip)=name.parse::<IpAddr>(){return Ok(Destination::Address(SocketAddr::new(ip,port)))}
 let query=mcrf_bs_routing_policy::dns_wire::query(0,name,1).map_err(|_|Failure::Protocol)?;
 let _=mcrf_bs_routing_policy::dns_wire::question(&query).map_err(|_|Failure::Protocol)?;
 // Preserve the caller's hostname for case-sensitive routing expressions.
 // The selected DNS resolver normalizes its question separately.
 Ok(Destination::Domain(name.to_owned(),port))
}
async fn address<S:AsyncRead+Unpin>(stream:&mut S)->Result<Destination,Failure>{
 let kind=stream.read_u8().await.map_err(io)?;
 let value=match kind{
  1=>{let mut bytes=[0;4];stream.read_exact(&mut bytes).await.map_err(io)?;Destination::Address(SocketAddr::new(IpAddr::V4(Ipv4Addr::from(bytes)),0))},
  4=>{let mut bytes=[0;16];stream.read_exact(&mut bytes).await.map_err(io)?;Destination::Address(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(bytes)),0))},
  3=>{let size=stream.read_u8().await.map_err(io)?;if size==0{return Err(Failure::Protocol)}let mut bytes=vec![0;usize::from(size)];stream.read_exact(&mut bytes).await.map_err(io)?;let name=std::str::from_utf8(&bytes).map_err(|_|Failure::Protocol)?;domain(name,0)?},
  _=>return Err(Failure::Unsupported),
 };
 let port=stream.read_u16().await.map_err(io)?;
 Ok(match value{Destination::Address(mut value)=>{value.set_port(port);Destination::Address(value)},Destination::Domain(value,_)=>Destination::Domain(value,port)})
}
fn port(destination:&Destination)->u16{match destination{Destination::Address(v)=>v.port(),Destination::Domain(_,p)=>*p}}
pub async fn negotiate<S:AsyncRead+AsyncWrite+Unpin>(stream:&mut BufReader<S>,plan:&Plan,cancel:&CancellationToken)->Result<Negotiated,Failure>{
 tokio::select!{biased;_=cancel.cancelled()=>Err(Failure::Cancelled),value=tokio::time::timeout(Duration::from_secs(5),negotiate_inner(stream,plan))=>value.map_err(|_|Failure::Timeout)?}
}
async fn negotiate_inner<S:AsyncRead+AsyncWrite+Unpin>(stream:&mut BufReader<S>,plan:&Plan)->Result<Negotiated,Failure>{
 let first=*stream.fill_buf().await.map_err(io)?.first().ok_or(Failure::Protocol)?;
 match first{
  5 if plan.kind!=Kind::Http=>socks5(stream,plan).await,
  4 if plan.kind!=Kind::Http=>socks4(stream,plan).await,
  _ if plan.kind!=Kind::Socks&&token(&[first])=>http(stream,plan).await,
  _=>Err(Failure::Protocol),
 }
}
async fn socks5<S:AsyncRead+AsyncWrite+Unpin>(stream:&mut S,plan:&Plan)->Result<Negotiated,Failure>{
 if stream.read_u8().await.map_err(io)?!=5{return Err(Failure::Protocol)}
 let count=stream.read_u8().await.map_err(io)?;if count==0{return Err(Failure::Protocol)}let mut methods=vec![0;usize::from(count)];stream.read_exact(&mut methods).await.map_err(io)?;
 let selected=if plan.authenticated(){2}else{0};
 if !methods.contains(&selected){stream.write_all(&[5,255]).await.map_err(io)?;return Err(Failure::Auth)}
 stream.write_all(&[5,selected]).await.map_err(io)?;
 if selected==2{
  if stream.read_u8().await.map_err(io)?!=1{return Err(Failure::Protocol)}
  let size=stream.read_u8().await.map_err(io)?;let mut username=vec![0;usize::from(size)];stream.read_exact(&mut username).await.map_err(io)?;
  let size=stream.read_u8().await.map_err(io)?;let mut password=vec![0;usize::from(size)];stream.read_exact(&mut password).await.map_err(io)?;
  let accepted=plan.authorizes(&username,&password);password.fill(0);stream.write_all(&[1,if accepted{0}else{1}]).await.map_err(io)?;if !accepted{return Err(Failure::Auth)}
 }
 if stream.read_u8().await.map_err(io)?!=5{return Err(Failure::Protocol)}let command=stream.read_u8().await.map_err(io)?;if stream.read_u8().await.map_err(io)?!=0{return Err(Failure::Protocol)}
 let destination=address(stream).await?;
 let command=match command{1 if port(&destination)!=0=>Command::Connect(destination),3=>Command::Associate(destination),_=>{stream.write_all(&[5,7,0,1,0,0,0,0,0,0]).await.map_err(io)?;return Err(Failure::Unsupported)}};
 Ok(Negotiated{protocol:Protocol::Socks5,command,prefix:Vec::new()})
}
async fn terminated<S:AsyncRead+Unpin>(stream:&mut S)->Result<Vec<u8>,Failure>{let mut value=Vec::new();loop{let byte=stream.read_u8().await.map_err(io)?;if byte==0{return Ok(value)}if value.len()==255{return Err(Failure::Limit)}value.push(byte)}}
async fn socks4<S:AsyncRead+AsyncWrite+Unpin>(stream:&mut S,plan:&Plan)->Result<Negotiated,Failure>{
 if stream.read_u8().await.map_err(io)?!=4{return Err(Failure::Protocol)}let command=stream.read_u8().await.map_err(io)?;let port=stream.read_u16().await.map_err(io)?;let mut bytes=[0;4];stream.read_exact(&mut bytes).await.map_err(io)?;let _userid=terminated(stream).await?;
 if plan.authenticated()||command!=1||port==0{stream.write_all(&[0,91,0,0,0,0,0,0]).await.map_err(io)?;return Err(Failure::Auth)}
 let destination=if bytes[..3]==[0,0,0]&&bytes[3]!=0{let name=terminated(stream).await?;domain(std::str::from_utf8(&name).map_err(|_|Failure::Protocol)?,port)?}else{Destination::Address(SocketAddr::new(Ipv4Addr::from(bytes).into(),port))};
 Ok(Negotiated{protocol:Protocol::Socks4,command:Command::Connect(destination),prefix:Vec::new()})
}
fn token(value:&[u8])->bool{!value.is_empty()&&value.iter().all(|b|b.is_ascii_alphanumeric()||b"!#$%&'*+-.^_`|~".contains(b))}
fn trim(mut value:&[u8])->&[u8]{while matches!(value.first(),Some(b' '|b'\t')){value=&value[1..]}while matches!(value.last(),Some(b' '|b'\t')){value=&value[..value.len()-1]}value}
fn authority(value:&str,default:Option<u16>)->Result<Destination,Failure>{
 if value.is_empty()||value.bytes().any(|b|b<=32||b==127)||value.contains(['@','/','?','#','\\']){return Err(Failure::Protocol)}
 let(host,explicit)=if value.starts_with('['){let end=value.find(']').ok_or(Failure::Protocol)?;let rest=&value[end+1..];let port=if rest.is_empty(){None}else{Some(rest.strip_prefix(':').ok_or(Failure::Protocol)?)};let host=&value[1..end];if host.parse::<Ipv6Addr>().is_err(){return Err(Failure::Protocol)}(host,port)}else if let Some((host,port))=value.rsplit_once(':'){if host.contains(':'){return Err(Failure::Protocol)}(host,Some(port))}else{(value,None)};
 let port=match explicit{Some(v)if !v.is_empty()&&v.bytes().all(|b|b.is_ascii_digit())=>v.parse::<u16>().map_err(|_|Failure::Protocol)?,Some(_)=>return Err(Failure::Protocol),None=>default.ok_or(Failure::Protocol)?};if port==0{return Err(Failure::Protocol)}
 domain(host,port)
}
async fn http<S:AsyncRead+AsyncWrite+Unpin>(stream:&mut BufReader<S>,plan:&Plan)->Result<Negotiated,Failure>{
 let mut lines=Vec::<Vec<u8>>::new();let mut total=0;
 loop{let mut line=Vec::new();let count=(&mut *stream).take((65537-total)as u64).read_until(b'\n',&mut line).await.map_err(io)?;total+=count;if count==0{return Err(Failure::Protocol)}if total>65536||lines.len()>256{return Err(Failure::Limit)}if !line.ends_with(b"\r\n"){return Err(Failure::Protocol)}line.truncate(line.len()-2);if line.is_empty(){break}if line.iter().any(|b|(*b<32&&*b!=b'\t')||*b==127){return Err(Failure::Protocol)}lines.push(line)}
 let first=lines.first().ok_or(Failure::Protocol)?;let first=std::str::from_utf8(first).map_err(|_|Failure::Protocol)?;let fields=first.split(' ').collect::<Vec<_>>();if fields.len()!=3||!token(fields[0].as_bytes())||!matches!(fields[2],"HTTP/1.0"|"HTTP/1.1"){return Err(Failure::Protocol)}
 let mut headers=Vec::<(Vec<u8>,Vec<u8>)>::new();let mut auth=None;let mut host=None;let mut length=None;let mut transfer=false;let mut connection=Vec::<String>::new();let mut upgrade=false;
 for line in &lines[1..]{let split=line.iter().position(|v|*v==b':').ok_or(Failure::Protocol)?;let key=&line[..split];if !token(key){return Err(Failure::Protocol)}let key=key.to_ascii_lowercase();let value=trim(&line[split+1..]);
  match key.as_slice(){
   b"proxy-authorization"=>{if auth.replace(value).is_some(){return Err(Failure::Protocol)}},
   b"host"=>{if host.replace(value).is_some(){return Err(Failure::Protocol)}},
   b"content-length"=>{let parsed=std::str::from_utf8(value).ok().filter(|v|!v.is_empty()&&v.bytes().all(|b|b.is_ascii_digit())).and_then(|v|v.parse::<u64>().ok()).ok_or(Failure::Protocol)?;if length.replace(parsed).is_some(){return Err(Failure::Protocol)}},
   b"transfer-encoding"=>{if transfer||!value.eq_ignore_ascii_case(b"chunked")||fields[2]!="HTTP/1.1"{return Err(Failure::Protocol)}transfer=true},
   b"connection"=>{let text=std::str::from_utf8(value).map_err(|_|Failure::Protocol)?;for part in text.split(','){let value=part.trim().to_ascii_lowercase();if !token(value.as_bytes()){return Err(Failure::Protocol)}connection.push(value)}},
   b"upgrade"=>upgrade=true,_=>{},
  }headers.push((key,value.to_vec()));
 }
 if transfer&&length.is_some(){return Err(Failure::Protocol)}
 if plan.authenticated(){
  let accepted=auth.and_then(|value|{let space=value.iter().position(|v|*v==b' ')?;if !value[..space].eq_ignore_ascii_case(b"basic"){return None}let mut bytes=base64::engine::general_purpose::STANDARD.decode(trim(&value[space+1..])).ok()?;let colon=bytes.iter().position(|v|*v==b':')?;let result=plan.authorizes(&bytes[..colon],&bytes[colon+1..]);bytes.fill(0);Some(result)}).unwrap_or(false);
  if !accepted{stream.write_all(b"HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"MCRF\"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await.map_err(io)?;return Err(Failure::Auth)}
 }
 if fields[0]=="CONNECT"{return Ok(Negotiated{protocol:Protocol::HttpConnect,command:Command::Connect(authority(fields[1],None)?),prefix:Vec::new()})}
 let(target,path,derived_host)=if fields[1].get(..7).is_some_and(|v|v.eq_ignore_ascii_case("http://")){let value=&fields[1][7..];
  let end=value.find(['/', '?','#']).unwrap_or(value.len());let target=authority(&value[..end],Some(80))?;let path=&value[end..];(target,if path.is_empty(){"/".to_owned()}else if path.starts_with('?'){format!("/{path}")}else{path.to_owned()},Some(value[..end].to_owned()))
 }else if fields[1].starts_with('/') {(authority(std::str::from_utf8(host.ok_or(Failure::Protocol)?).map_err(|_|Failure::Protocol)?,Some(80))?,fields[1].to_owned(),None)}else{return Err(Failure::Unsupported)};
 if path.contains('#')||!path.bytes().all(|b|b>32&&b<127)||path.as_bytes().iter().enumerate().any(|(i,b)|*b==b'%'&&!(path.as_bytes().get(i+1).is_some_and(u8::is_ascii_hexdigit)&&path.as_bytes().get(i+2).is_some_and(u8::is_ascii_hexdigit))){return Err(Failure::Protocol)}
 if upgrade&&!connection.iter().any(|v|v=="upgrade"){return Err(Failure::Protocol)}
 if connection.iter().any(|v|matches!(v.as_str(),"host"|"content-length"|"transfer-encoding")){return Err(Failure::Protocol)}
 let mut prefix=format!("{} {path} {}\r\n",fields[0],fields[2]).into_bytes();
 for(key,value)in headers{if matches!(key.as_slice(),b"proxy-authorization"|b"proxy-connection"|b"connection"|b"keep-alive")||connection.iter().any(|v|v.as_bytes()==key.as_slice()&&v!="upgrade"){continue}prefix.extend_from_slice(&key);prefix.extend_from_slice(b": ");prefix.extend_from_slice(&value);prefix.extend_from_slice(b"\r\n")}
 if host.is_none(){let host=derived_host.ok_or(Failure::Protocol)?;prefix.extend_from_slice(b"host: ");prefix.extend_from_slice(host.as_bytes());prefix.extend_from_slice(b"\r\n")}
 prefix.extend_from_slice(if upgrade{b"connection: upgrade\r\n\r\n"}else{b"connection: close\r\n\r\n"});
 Ok(Negotiated{protocol:Protocol::HttpForward,command:Command::Connect(target),prefix})
}
pub fn success(protocol:Protocol,bound:Option<SocketAddr>)->Vec<u8>{match protocol{
 Protocol::Socks5=>{let bound=bound.unwrap_or_else(||SocketAddr::new(Ipv4Addr::UNSPECIFIED.into(),0));let mut bytes=vec![5,0,0];encode(&Destination::Address(bound),&mut bytes);bytes},
 Protocol::Socks4=>vec![0,90,0,0,0,0,0,0],Protocol::HttpConnect=>b"HTTP/1.1 200 Connection Established\r\n\r\n".to_vec(),Protocol::HttpForward=>Vec::new(),
}}
fn encode(destination:&Destination,bytes:&mut Vec<u8>){match destination{Destination::Address(value)=>{match value.ip(){IpAddr::V4(ip)=>{bytes.push(1);bytes.extend_from_slice(&ip.octets())},IpAddr::V6(ip)=>{bytes.push(4);bytes.extend_from_slice(&ip.octets())}}bytes.extend_from_slice(&value.port().to_be_bytes())},Destination::Domain(name,port)=>{bytes.extend_from_slice(&[3,name.len()as u8]);bytes.extend_from_slice(name.as_bytes());bytes.extend_from_slice(&port.to_be_bytes())}}}
pub async fn datagram(bytes:&[u8])->Result<(Destination,&[u8]),Failure>{
 if bytes.len()>65507||bytes.len()<4||bytes[..2]!=[0,0]{return Err(Failure::Protocol)}if bytes[2]!=0{return Err(Failure::Unsupported)}let mut cursor=std::io::Cursor::new(&bytes[3..]);let destination=address(&mut cursor).await?;if port(&destination)==0{return Err(Failure::Protocol)}let used=usize::try_from(cursor.position()).map_err(|_|Failure::Limit)?+3;Ok((destination,&bytes[used..]))
}
pub fn datagram_reply(destination:&Destination,payload:&[u8])->Result<Vec<u8>,Failure>{if port(destination)==0{return Err(Failure::Protocol)}if let Destination::Domain(name,_)=destination{if name.is_empty()||name.len()>255||name.chars().any(char::is_control){return Err(Failure::Protocol)}}let mut bytes=vec![0,0,0];encode(destination,&mut bytes);if bytes.len()+payload.len()>65507{return Err(Failure::Limit)}bytes.extend_from_slice(payload);Ok(bytes)}
#[cfg(test)]#[path="mcrf_bs_proxy_protocol_tests.rs"]mod tests;
