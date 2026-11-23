//! Source-only selected DNS transport in the SAME native carrier process.
//! Every socket is explicitly bound before connect/send. VPN DNS uses an
//! in-process tuple lease; it never starts a SOCKS listener or another engine.
use std::{net::{SocketAddr,IpAddr,Ipv4Addr,Ipv6Addr},sync::Arc,pin::Pin,task::{Context,Poll}};
use tokio::{io::{AsyncRead,AsyncWrite,AsyncReadExt,AsyncWriteExt,ReadBuf},net::{TcpSocket,TcpStream,UdpSocket},time::Instant};
use tokio_util::sync::CancellationToken;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Invalid,Cancelled,Deadline,Bind,Lease,Io,Tls,Http,Wire}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Direction{Direct,Vpn,Egress(u8)}
pub struct EgressEndpoint{port:u16,username:String,password:String}
pub trait SocketPolicy:Send+Sync{
    fn tls_roots(&self)->Option<Arc<turn_rustls::RootCertStore>>{None}
    fn egress(&self,_slot:u8)->Option<&EgressEndpoint>{None}
    fn prepare(&self,protocol:u8,fd:i32,port:u16,peer:SocketAddr,direction:Direction)->Result<Box<dyn Send>,Failure>;
}
struct EgressPolicy{inner:Arc<dyn SocketPolicy>,endpoints:Vec<EgressEndpoint>}
impl SocketPolicy for EgressPolicy{
    fn tls_roots(&self)->Option<Arc<turn_rustls::RootCertStore>>{self.inner.tls_roots()}
    fn egress(&self,slot:u8)->Option<&EgressEndpoint>{self.endpoints.get(usize::from(slot))}
    fn prepare(&self,protocol:u8,fd:i32,port:u16,peer:SocketAddr,direction:Direction)->Result<Box<dyn Send>,Failure>{
        if matches!(direction,Direction::Egress(_)){return Err(Failure::Bind)}
        self.inner.prepare(protocol,fd,port,peer,direction)
    }
}
pub fn with_egresses(bindings:Option<&serde_json::Value>,inner:Arc<dyn SocketPolicy>)->Result<Arc<dyn SocketPolicy>,Failure>{
    let Some(bindings)=bindings else{return Ok(inner)};
    // Bundle::parse has already checked canonical order, bounds and equality
    // between policy tags and these loopback endpoints.
    let endpoints=bindings.as_array().ok_or(Failure::Invalid)?.iter().map(|entry|Ok(EgressEndpoint{
        port:entry["port"].as_u64().and_then(|port|u16::try_from(port).ok()).filter(|port|*port>=1024).ok_or(Failure::Invalid)?,
        username:entry["username"].as_str().ok_or(Failure::Invalid)?.to_owned(),
        password:entry["password"].as_str().ok_or(Failure::Invalid)?.to_owned(),
    })).collect::<Result<_,Failure>>()?;
    Ok(Arc::new(EgressPolicy{inner,endpoints}))
}
pub struct NativePolicy{physical:u64,vpn:u64,vpn_ipv4:bool,vpn_ipv6:bool}
impl NativePolicy{
    pub fn new(physical:u64,vpn:u64,vpn_ipv4:bool,vpn_ipv6:bool)->Result<Self,Failure>{
        if physical==0||vpn==0||physical==vpn||!vpn_ipv4&&!vpn_ipv6||crate::mcrf_bs_network::physical_network()!=Some(physical){return Err(Failure::Invalid)}
        Ok(Self{physical,vpn,vpn_ipv4,vpn_ipv6})
    }
    pub fn proxy_only(physical:u64)->Result<Self,Failure>{
        if physical==0{return Err(Failure::Invalid)}
        Ok(Self{physical,vpn:0,vpn_ipv4:false,vpn_ipv6:false})
    }
}
impl SocketPolicy for NativePolicy{
    fn prepare(&self,protocol:u8,fd:i32,port:u16,peer:SocketAddr,direction:Direction)->Result<Box<dyn Send>,Failure>{
        if matches!(direction,Direction::Egress(_)){return Err(Failure::Bind)}
        // Binding to a VPN Network alone is insufficient when that address
        // family is absent from its routes. Reject before opening a route.
        if direction==Direction::Vpn&&!(if peer.is_ipv4(){self.vpn_ipv4}else{self.vpn_ipv6}){return Err(Failure::Bind)}
        if direction==Direction::Direct&&peer.ip().is_loopback(){
            // Captured loopback routes require an explicit local source, not
            // a physical Network route. Verify the real bound socket first.
            if fd<0{return Err(Failure::Bind)}use std::os::fd::BorrowedFd;let borrowed=unsafe{BorrowedFd::borrow_raw(fd)};let socket=socket2::SockRef::from(&borrowed);
            let local=socket.local_addr().ok().and_then(|v|v.as_socket()).ok_or(Failure::Bind)?;
            if !local.ip().is_loopback()||local.is_ipv4()!=peer.is_ipv4()||local.port()!=port{return Err(Failure::Bind)}return Ok(Box::new(()))
        }
        crate::mcrf_bs_network::bind_socket(if direction==Direction::Direct{self.physical}else{self.vpn},fd).map_err(|_|Failure::Bind)?;
        if direction==Direction::Direct{Ok(Box::new(()))}else{
            let key=mcrf_bs_routing_policy::service_flows::Key{protocol,local_port:port,peer};
            Ok(Box::new(crate::mcrf_bs_service::register_internal(key).map_err(|_|Failure::Lease)?))
        }
    }
}
// Revoke the scoped tuple before its socket/FD can be reused.
pub struct Leased<T>{_lease:Box<dyn Send>,io:T}
impl<T:AsyncRead+Unpin> AsyncRead for Leased<T>{
    fn poll_read(self:Pin<&mut Self>,cx:&mut Context<'_>,buffer:&mut ReadBuf<'_>)->Poll<std::io::Result<()>>{Pin::new(&mut self.get_mut().io).poll_read(cx,buffer)}
}
impl<T:AsyncWrite+Unpin> AsyncWrite for Leased<T>{
    fn poll_write(self:Pin<&mut Self>,cx:&mut Context<'_>,bytes:&[u8])->Poll<std::io::Result<usize>>{Pin::new(&mut self.get_mut().io).poll_write(cx,bytes)}
    fn poll_flush(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<std::io::Result<()>>{Pin::new(&mut self.get_mut().io).poll_flush(cx)}
    fn poll_shutdown(self:Pin<&mut Self>,cx:&mut Context<'_>)->Poll<std::io::Result<()>>{Pin::new(&mut self.get_mut().io).poll_shutdown(cx)}
}
fn local(peer:SocketAddr,direction:Direction)->SocketAddr{let loopback=direction==Direction::Direct&&peer.ip().is_loopback();SocketAddr::new(if peer.is_ipv4(){IpAddr::V4(if loopback{Ipv4Addr::LOCALHOST}else{Ipv4Addr::UNSPECIFIED})}else{IpAddr::V6(if loopback{Ipv6Addr::LOCALHOST}else{Ipv6Addr::UNSPECIFIED})},0)}
#[cfg(unix)]fn raw<T:std::os::fd::AsRawFd>(socket:&T)->Result<i32,Failure>{Ok(socket.as_raw_fd())}
#[cfg(not(unix))]fn raw<T>(_:&T)->Result<i32,Failure>{Err(Failure::Bind)}
pub async fn tcp(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction)->Result<Leased<TcpStream>,Failure>{
    if let Direction::Egress(slot)=direction{
        return upstream(policy,slot,1,peer).await.map(|(stream,_)|stream)
    }
    tcp_bound(policy,peer,direction).await
}
pub fn encode_socks_address(address:SocketAddr,bytes:&mut Vec<u8>){
    match address.ip(){
        IpAddr::V4(ip)=>{bytes.push(1);bytes.extend_from_slice(&ip.octets())},
        IpAddr::V6(ip)=>{bytes.push(4);bytes.extend_from_slice(&ip.octets())},
    }
    bytes.extend_from_slice(&address.port().to_be_bytes());
}
pub async fn read_socks_address<S:AsyncRead+Unpin>(stream:&mut S)->Result<Option<SocketAddr>,Failure>{
    let ip=match stream.read_u8().await.map_err(|_|Failure::Io)?{
        1=>{let mut bytes=[0;4];stream.read_exact(&mut bytes).await.map_err(|_|Failure::Io)?;Some(Ipv4Addr::from(bytes).into())},
        4=>{let mut bytes=[0;16];stream.read_exact(&mut bytes).await.map_err(|_|Failure::Io)?;Some(Ipv6Addr::from(bytes).into())},
        3=>{let len=usize::from(stream.read_u8().await.map_err(|_|Failure::Io)?);if len==0{return Err(Failure::Wire)}let mut bytes=[0;255];stream.read_exact(&mut bytes[..len]).await.map_err(|_|Failure::Io)?;None},
        _=>return Err(Failure::Wire),
    };
    let port=stream.read_u16().await.map_err(|_|Failure::Io)?;
    Ok(ip.map(|ip|SocketAddr::new(ip,port)))
}
/// The only connection made directly is to the selected local worker. A
/// rejected handshake cannot turn into an unauthenticated or direct dial.
pub async fn upstream(policy:&dyn SocketPolicy,slot:u8,command:u8,peer:SocketAddr)->Result<(Leased<TcpStream>,Option<SocketAddr>),Failure>{
    if !matches!(command,1|3)||peer.port()==0{return Err(Failure::Invalid)}
    let endpoint=policy.egress(slot).ok_or(Failure::Lease)?;
    let local=SocketAddr::new(Ipv4Addr::LOCALHOST.into(),endpoint.port);
    let mut stream=tcp_bound(policy,local,Direction::Direct).await?;
    stream.write_all(&[5,1,2]).await.map_err(|_|Failure::Io)?;
    let mut reply=[0;2];stream.read_exact(&mut reply).await.map_err(|_|Failure::Io)?;
    if reply!=[5,2]{return Err(Failure::Wire)}
    let username=endpoint.username.as_bytes();let password=endpoint.password.as_bytes();
    let mut auth=Vec::with_capacity(3+username.len()+password.len());
    auth.extend_from_slice(&[1,username.len() as u8]);auth.extend_from_slice(username);
    auth.push(password.len() as u8);auth.extend_from_slice(password);
    let written=stream.write_all(&auth).await;auth.fill(0);written.map_err(|_|Failure::Io)?;
    stream.read_exact(&mut reply).await.map_err(|_|Failure::Io)?;
    if reply!=[1,0]{return Err(Failure::Wire)}
    let mut request=vec![5,command,0];encode_socks_address(peer,&mut request);
    stream.write_all(&request).await.map_err(|_|Failure::Io)?;
    let mut result=[0;3];stream.read_exact(&mut result).await.map_err(|_|Failure::Io)?;
    if result!=[5,0,0]{return Err(Failure::Wire)}
    let bound=read_socks_address(&mut stream).await?;
    Ok((stream,bound))
}
pub struct SocksUdp{state:tokio::sync::Mutex<(Leased<TcpStream>,Vec<u8>)>,io:UdpSocket,peer:SocketAddr}
impl SocksUdp{
    pub async fn open(policy:&dyn SocketPolicy,slot:u8,peer:SocketAddr)->Result<Self,Failure>{
        if peer.port()==0||peer.ip().is_unspecified()||peer.ip().is_multicast(){return Err(Failure::Invalid)}
        let io=UdpSocket::bind((Ipv4Addr::LOCALHOST,0)).await.map_err(|_|Failure::Io)?;
        let local=io.local_addr().map_err(|_|Failure::Io)?;
        let (control,bound)=upstream(policy,slot,3,local).await?;
        // A wildcard IPv4 reply denotes the same local address as the
        // authenticated TCP peer. Any nonlocal reply is rejected.
        let SocketAddr::V4(bound)=bound.ok_or(Failure::Wire)? else{return Err(Failure::Wire)};
        if bound.port()==0||!matches!(*bound.ip(),Ipv4Addr::LOCALHOST|Ipv4Addr::UNSPECIFIED){return Err(Failure::Wire)}
        io.connect((Ipv4Addr::LOCALHOST,bound.port())).await.map_err(|_|Failure::Io)?;
        Ok(Self{state:tokio::sync::Mutex::new((control,vec![0;65507])),io,peer})
    }
    pub async fn send(&self,bytes:&[u8])->Result<(),Failure>{
        let header=if self.peer.is_ipv4(){10}else{22};
        if bytes.len()>65507-header{return Err(Failure::Invalid)}
        let mut datagram=Vec::with_capacity(header+bytes.len());
        datagram.extend_from_slice(&[0,0,0]);encode_socks_address(self.peer,&mut datagram);datagram.extend_from_slice(bytes);
        if self.io.send(&datagram).await.map_err(|_|Failure::Io)?!=datagram.len(){return Err(Failure::Io)}
        Ok(())
    }
    pub async fn receive(&self,bytes:&mut [u8])->Result<usize,Failure>{
        let mut state=self.state.lock().await;
        let (control,buffer)=&mut *state;
        loop{
            let size=tokio::select!{biased;_=control.read_u8()=>return Err(Failure::Io),
                result=self.io.recv(buffer.as_mut_slice())=>result.map_err(|_|Failure::Io)?};
            if size<4||buffer[..3]!=[0,0,0]{continue}
            let mut cursor=std::io::Cursor::new(&buffer[3..size]);
            let Ok(Some(peer))=read_socks_address(&mut cursor).await else{continue};
            if peer!=self.peer{continue}
            let payload=&buffer[3+cursor.position() as usize..size];
            if payload.len()>bytes.len(){return Err(Failure::Invalid)}
            bytes[..payload.len()].copy_from_slice(payload);
            return Ok(payload.len())
        }
    }
}
async fn tcp_bound(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction)->Result<Leased<TcpStream>,Failure>{
    let socket=if peer.is_ipv4(){TcpSocket::new_v4()}else{TcpSocket::new_v6()}.map_err(|_|Failure::Io)?;
    socket.bind(local(peer,direction)).map_err(|_|Failure::Io)?;
    let lease=policy.prepare(6,raw(&socket)?,socket.local_addr().map_err(|_|Failure::Io)?.port(),peer,direction)?;
    let io=socket.connect(peer).await.map_err(|_|Failure::Io)?;io.set_nodelay(true).map_err(|_|Failure::Io)?;
    Ok(Leased{io,_lease:lease})
}
fn validate(query:&[u8],reply:&[u8])->Result<(),Failure>{
    if !(12..=4096).contains(&query.len())||!(12..=65535).contains(&reply.len())||query[..2]!=reply[..2]||reply[2]&0x80==0||query[2]&0x78!=reply[2]&0x78{return Err(Failure::Wire)}Ok(())
}
async fn framed<T:AsyncRead+AsyncWrite+Unpin>(socket:&mut T,query:&[u8])->Result<Vec<u8>,Failure>{
    socket.write_u16(query.len()as u16).await.map_err(|_|Failure::Io)?;socket.write_all(query).await.map_err(|_|Failure::Io)?;socket.flush().await.map_err(|_|Failure::Io)?;
    let size=socket.read_u16().await.map_err(|_|Failure::Io)? as usize;if size<12{return Err(Failure::Wire)}let mut reply=vec![0;size];socket.read_exact(&mut reply).await.map_err(|_|Failure::Io)?;validate(query,&reply)?;
    if reply[2]&2!=0{return Err(Failure::Wire)}Ok(reply)
}
async fn udp(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction,query:&[u8])->Result<Vec<u8>,Failure>{
    if let Direction::Egress(slot)=direction{
        let socket=SocksUdp::open(policy,slot,peer).await?;
        socket.send(query).await?;
        let mut reply=vec![0;65535];let size=socket.receive(&mut reply).await?;reply.truncate(size);validate(query,&reply)?;
        return if reply[2]&2!=0{let mut stream=tcp(policy,peer,direction).await?;framed(&mut stream,query).await}else{Ok(reply)}
    }
    let io=UdpSocket::bind(local(peer,direction)).await.map_err(|_|Failure::Io)?;
    let lease=policy.prepare(17,raw(&io)?,io.local_addr().map_err(|_|Failure::Io)?.port(),peer,direction)?;
    let socket=Leased{io,_lease:lease};socket.io.connect(peer).await.map_err(|_|Failure::Io)?;
    let sent=socket.io.send(query).await.map_err(|_|Failure::Io)?;if sent!=query.len(){return Err(Failure::Io)}
    let mut reply=vec![0;65535];let size=socket.io.recv(&mut reply).await.map_err(|_|Failure::Io)?;reply.truncate(size);validate(query,&reply)?;
    if reply[2]&2!=0{let mut stream=tcp(policy,peer,direction).await?;framed(&mut stream,query).await}else{Ok(reply)}
}
pub enum Transport{Udp,Tcp,Tls{name:String},Https{name:String,path:String}}
pub async fn exchange(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction,transport:&Transport,query:&[u8],deadline:Instant,cancel:&CancellationToken)->Result<Vec<u8>,Failure>{
    if mcrf_bs_routing_policy::dns_wire::question(query).is_err()||peer.port()==0||peer.ip().is_unspecified()||peer.ip().is_multicast(){return Err(Failure::Invalid)}
    if cancel.is_cancelled(){return Err(Failure::Cancelled)}
    let now=Instant::now();if deadline<=now{return Err(Failure::Deadline)}
    if deadline.duration_since(now)>std::time::Duration::from_secs(30){return Err(Failure::Invalid)}
    let reply=tokio::select!{biased;
        _=cancel.cancelled()=>Err(Failure::Cancelled),
        result=tokio::time::timeout_at(deadline,async{
            match transport{
                Transport::Udp=>udp(policy,peer,direction,query).await,
                Transport::Tcp=>{let mut stream=tcp(policy,peer,direction).await?;framed(&mut stream,query).await},
                Transport::Tls{name}=>{let mut stream=tls(policy,peer,direction,name,false).await?;framed(&mut stream,query).await},
                Transport::Https{name,path}=>{let mut stream=tls(policy,peer,direction,name,true).await?;http(&mut stream,peer,name,path,query).await},
            }
        })=>result.map_err(|_|Failure::Deadline)?,
    }?;
    mcrf_bs_routing_policy::dns_wire::addresses(query,&reply).map_err(|_|Failure::Wire)?;
    Ok(reply)
}
async fn tls(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction,name:&str,http:bool)->Result<turn_tokio_rustls::client::TlsStream<Leased<TcpStream>>,Failure>{
    tls_with_config(policy,peer,direction,name,tls_config(policy,http)).await
}
pub(crate) fn tls_config(policy:&dyn SocketPolicy,http:bool)->Arc<turn_rustls::ClientConfig>{
 let roots=policy.tls_roots().unwrap_or_else(||{let mut roots=turn_rustls::RootCertStore::empty();roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());Arc::new(roots)});
 let mut config=turn_rustls::ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();if http{config.alpn_protocols=vec![b"http/1.1".to_vec()]}Arc::new(config)
}
async fn tls_with_config(policy:&dyn SocketPolicy,peer:SocketAddr,direction:Direction,name:&str,config:Arc<turn_rustls::ClientConfig>)->Result<turn_tokio_rustls::client::TlsStream<Leased<TcpStream>>,Failure>{
    let server=turn_rustls::pki_types::ServerName::try_from(name.to_owned()).map_err(|_|Failure::Invalid)?;
    let stream=tcp(policy,peer,direction).await?;
    turn_tokio_rustls::TlsConnector::from(config).connect(server,stream).await.map_err(|_|Failure::Tls)
}
async fn line<T:AsyncRead+Unpin>(stream:&mut T,remaining:&mut usize)->Result<String,Failure>{
    let mut bytes=Vec::new();loop{
        if *remaining==0||bytes.len()>2048{return Err(Failure::Http)}*remaining-=1;
        let byte=stream.read_u8().await.map_err(|_|Failure::Io)?;bytes.push(byte);
        if bytes.ends_with(b"\r\n"){bytes.truncate(bytes.len()-2);break}
        if byte==b'\n'||byte==0{return Err(Failure::Http)}
    }String::from_utf8(bytes).map_err(|_|Failure::Http)
}
async fn http<T:AsyncRead+AsyncWrite+Unpin>(stream:&mut T,peer:SocketAddr,name:&str,path:&str,query:&[u8])->Result<Vec<u8>,Failure>{
    if name.is_empty()||!name.bytes().all(|v|v>32&&v<127&&!matches!(v,b'/'|b'\\'|b'@'))||!path.starts_with('/')||!path.bytes().all(|v|v>32&&v<127){return Err(Failure::Invalid)}
    let host=if name.parse::<Ipv6Addr>().is_ok(){format!("[{name}]")}else{name.to_owned()};let host=if peer.port()==443{host}else{format!("{host}:{}",peer.port())};
    let request=format!("POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nAccept-Encoding: identity\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",query.len());
    stream.write_all(request.as_bytes()).await.map_err(|_|Failure::Io)?;stream.write_all(query).await.map_err(|_|Failure::Io)?;stream.flush().await.map_err(|_|Failure::Io)?;
    let mut remaining=16384;let status=line(stream,&mut remaining).await?;
    if !(status.starts_with("HTTP/1.1 200 ")||status=="HTTP/1.1 200"||status.starts_with("HTTP/1.0 200 ")){return Err(Failure::Http)}
    let mut length=None;let mut chunked=false;let mut media=false;let mut seen=std::collections::HashSet::new();
    loop{let value=line(stream,&mut remaining).await?;if value.is_empty(){break}let (key,value)=value.split_once(':').ok_or(Failure::Http)?;
        if key.is_empty()||!key.bytes().all(|v|v.is_ascii_alphanumeric()||v==b'-'){return Err(Failure::Http)}let key=key.to_ascii_lowercase();let value=value.trim();
        if matches!(key.as_str(),"content-length"|"content-type"|"transfer-encoding"|"content-encoding")&&!seen.insert(key.clone()){return Err(Failure::Http)}
        match key.as_str(){
            "content-length"=>{if value.is_empty()||!value.bytes().all(|v|v.is_ascii_digit()){return Err(Failure::Http)}length=Some(value.parse::<usize>().map_err(|_|Failure::Http)?);},
            "transfer-encoding"=>{if !value.eq_ignore_ascii_case("chunked"){return Err(Failure::Http)}chunked=true},
            "content-type"=>media=value.split(';').next().unwrap_or("").trim().eq_ignore_ascii_case("application/dns-message"),
            "content-encoding"=>if !value.eq_ignore_ascii_case("identity"){return Err(Failure::Http)},_=>{},
        }
    }
    if !media||chunked&&length.is_some()||length.is_some_and(|v|!(12..=65535).contains(&v)){return Err(Failure::Http)}
    let mut reply=Vec::new();
    if chunked{
        loop{let value=line(stream,&mut remaining).await?;let size=value.split(';').next().ok_or(Failure::Http)?;if size.is_empty()||!size.bytes().all(|v|v.is_ascii_hexdigit()){return Err(Failure::Http)}let size=usize::from_str_radix(size,16).map_err(|_|Failure::Http)?;
            if size==0{while !line(stream,&mut remaining).await?.is_empty(){}break}
            let end=reply.len().checked_add(size).filter(|v|*v<=65535).ok_or(Failure::Http)?;let offset=reply.len();reply.resize(end,0);stream.read_exact(&mut reply[offset..]).await.map_err(|_|Failure::Io)?;
            let mut suffix=[0;2];stream.read_exact(&mut suffix).await.map_err(|_|Failure::Io)?;if suffix!=*b"\r\n"{return Err(Failure::Http)}
        }
    }else if let Some(size)=length{reply.resize(size,0);stream.read_exact(&mut reply).await.map_err(|_|Failure::Io)?;}
    else{stream.take(65536).read_to_end(&mut reply).await.map_err(|_|Failure::Io)?;}
    validate(query,&reply)?;if reply[2]&2!=0{return Err(Failure::Wire)}Ok(reply)
}
#[cfg(all(test,unix))]
#[path="mcrf_bs_dns_transport_tests.rs"]mod tests;
