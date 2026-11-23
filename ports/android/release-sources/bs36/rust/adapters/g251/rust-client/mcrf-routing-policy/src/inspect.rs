//! Bounded inspection of a reassembled TCP prefix, never individual IP packets.
//! Pending is distinct from an inspected stream with no usable domain. ECH
//! outer names are not evidence for the inner destination.
#[derive(Clone,Debug,PartialEq,Eq)]pub enum Inspection{Pending,Unknown,Known{protocol:&'static str,domain:Option<String>}}
fn domain(value:&str)->Option<String>{
 let value=value.strip_suffix('.').unwrap_or(value);
 if value.is_empty()||value.len()>253||!value.is_ascii()||value.split('.').any(|s|s.is_empty()||s.len()>63||s.bytes().any(|b|!b.is_ascii_alphanumeric()&&!matches!(b,b'-'|b'_'))){None}else{Some(value.to_ascii_lowercase())}
}
fn word(bytes:&[u8],at:usize)->Option<usize>{let p=bytes.get(at..at+2)?;Some(u16::from_be_bytes([p[0],p[1]])as usize)}
fn hello(bytes:&[u8])->Option<Option<String>>{
 if bytes.len()<38||bytes[0]!=1||bytes[4]!=3||bytes[5]>4{return None}let size=((bytes[1]as usize)<<16)|((bytes[2]as usize)<<8)|bytes[3]as usize;
 if size+4!=bytes.len(){return None}let mut at=38;let session=*bytes.get(at)?as usize;at+=1+session;
 let cipher=word(bytes,at)?;if cipher==0||cipher%2!=0{return None}at+=2+cipher;
 let compression=*bytes.get(at)?as usize;if compression==0{return None}at+=1+compression;
 if at==bytes.len(){return Some(None)}let count=word(bytes,at)?;at+=2;if at+count!=bytes.len(){return None}
 let mut seen=std::collections::HashSet::new();let mut name=None;let mut encrypted=false;
 while at<bytes.len(){let kind=word(bytes,at)?;let size=word(bytes,at+2)?;at+=4;let end=at.checked_add(size)?;if end>bytes.len()||!seen.insert(kind){return None}
  if matches!(kind,0xfe0d|0xffce){encrypted=true}
  if kind==0{let size=word(bytes,at)?;let mut p=at+2;if p+size!=end{return None}let mut host=false;
   while p<end{let kind=*bytes.get(p)?;let length=word(bytes,p+1)?;p+=3;let finish=p.checked_add(length)?;if finish>end{return None}
    if kind==0{if host{return None}host=true;name=Some(domain(std::str::from_utf8(&bytes[p..finish]).ok()?)?)}p=finish;
   }
  }at=end;
 }Some(if encrypted{None}else{name})
}
pub fn tls_handshake(bytes:&[u8],eof:bool)->Inspection{
 if bytes.len()<4{return if eof{Inspection::Unknown}else{Inspection::Pending}}
 let size=4+((bytes[1]as usize)<<16)+((bytes[2]as usize)<<8)+bytes[3]as usize;
 if bytes[0]!=1||size>65536{return Inspection::Unknown}if bytes.len()<size{return if eof{Inspection::Unknown}else{Inspection::Pending}}
 match hello(&bytes[..size]){Some(domain)=>Inspection::Known{protocol:"tls",domain},None=>Inspection::Unknown}
}
fn tls(bytes:&[u8],eof:bool)->Inspection{
 let mut at=0;let mut handshake=Vec::new();let mut records=0;
 loop{
  if bytes.len()-at<5{return if eof{Inspection::Unknown}else{Inspection::Pending}}
  if bytes[at]!=22||bytes[at+1]!=3||bytes[at+2]>4{return Inspection::Unknown}
  let size=word(bytes,at+3).unwrap_or(0);if size==0||size>18432{return Inspection::Unknown}let end=at+5+size;
  if end>bytes.len(){return if eof{Inspection::Unknown}else{Inspection::Pending}}
  records+=1;if records>16||handshake.len()+size>65536{return Inspection::Unknown}handshake.extend_from_slice(&bytes[at+5..end]);at=end;
  if handshake.len()>=4{
   if handshake[0]!=1{return Inspection::Unknown}let length=((handshake[1]as usize)<<16)|((handshake[2]as usize)<<8)|handshake[3]as usize;
   if length+4>65536{return Inspection::Unknown}
   if handshake.len()>=length+4{return match hello(&handshake[..length+4]){Some(domain)=>Inspection::Known{protocol:"tls",domain},None=>Inspection::Unknown}}
  }
  if at==bytes.len(){return if eof{Inspection::Unknown}else{Inspection::Pending}}
 }
}
fn authority(value:&str)->Option<String>{
 if value.starts_with('['){let end=value.find(']')?;value[1..end].parse::<std::net::Ipv6Addr>().ok()?;let rest=&value[end+1..];if !rest.is_empty(){rest.strip_prefix(':')?.parse::<u16>().ok().filter(|v|*v!=0)?;}return None}
 let host=if let Some((host,port))=value.rsplit_once(':'){port.parse::<u16>().ok().filter(|v|*v!=0)?;host}else{value};domain(host)
}
// Shapes follow the pinned primary's default TCP sniffers. They classify
// protocol metadata only; recognition never opens a socket or grants a route.
fn fixed_prefix(bytes:&[u8],prefix:&[u8],protocol:&'static str,eof:bool)->Option<Inspection>{
 if bytes.starts_with(prefix){Some(Inspection::Known{protocol,domain:None})}
 else if prefix.starts_with(bytes){Some(if eof{Inspection::Unknown}else{Inspection::Pending})}else{None}
}
fn sip(bytes:&[u8],eof:bool)->Option<Inspection>{
 let methods:&[&[u8]]=&[b"INVITE ",b"ACK ",b"BYE ",b"CANCEL ",b"OPTIONS ",b"REGISTER ",b"PRACK ",b"SUBSCRIBE ",b"NOTIFY ",b"PUBLISH ",b"INFO ",b"REFER ",b"MESSAGE ",b"UPDATE "];
 if !methods.iter().any(|m|bytes.starts_with(m)||m.starts_with(bytes)){return None}
 let Some(end)=bytes.windows(2).position(|v|v==b"\r\n")else{return if eof||bytes.len()>=1024{None}else{Some(Inspection::Pending)}};
 if end>1024{return None}let text=std::str::from_utf8(&bytes[..end]).ok()?;let parts:Vec<_>=text.split(' ').collect();
 if parts.len()!=3||parts[2]!="SIP/2.0"||parts[1].is_empty()||parts[1].contains('\t'){return None}
 let uri=parts[1];let lower=uri.to_ascii_lowercase();
 let host=if lower.starts_with("sips:"){&uri[5..]}else if lower.starts_with("sip:"){&uri[4..]}else if lower.starts_with("tel:"){return Some(Inspection::Known{protocol:"sip",domain:None})}else{return None};
 let host=host.rsplit('@').next()?.split([';','?']).next()?;
 let name=if host.starts_with('['){None}else{let host=host.split(':').next()?;if host.is_empty(){return None}if host.parse::<std::net::IpAddr>().is_ok(){None}else{Some(domain(host)?)}};
 Some(Inspection::Known{protocol:"sip",domain:name})
}
fn openvpn(body:&[u8])->bool{
 if body.len()<14||!matches!(body[0],0x38|0x50){return false}
 if body.len()==14{return body[9..14].iter().all(|v|*v==0)}
 let integer=|at:usize|body.get(at..at+4).map(|v|u32::from_be_bytes(v.try_into().expect("four bytes")));
 let plausible=|at:usize|integer(at).is_some_and(|v|v>=1262304000&&u64::from(v)<=std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0,|d|d.as_secs()).saturating_add(86400));
 for h in [16,20,28,32,48,64]{if body.len()==22+h&&integer(9+h)==Some(1)&&plausible(13+h)&&body[17+h..22+h].iter().all(|v|*v==0){return true}}
 body.len()>=49&&integer(9)==Some(1)&&plausible(13)
}
pub fn tcp(bytes:&[u8],eof:bool)->Inspection{
 if bytes.is_empty(){return if eof{Inspection::Unknown}else{Inspection::Pending}}if bytes.len()>131072{return Inspection::Unknown}
 if bytes[0]==22{return tls(bytes,eof)}
 if let Some(value)=fixed_prefix(bytes,b"\x13BitTorrent protocol","bittorrent",eof){return value}
 if bytes.starts_with(b"SSH-2.0-")||b"SSH-2.0-".starts_with(bytes){
  if bytes.len()<8{return if eof{Inspection::Unknown}else{Inspection::Pending}}
  if bytes.contains(&b'\n')||eof{return Inspection::Known{protocol:"ssh",domain:None}}return Inspection::Pending
 }
 if bytes[0]==3{
  let expected=[3,0,0,19,14,0xe0];let available=bytes.len().min(expected.len());
  if bytes[..available]==expected[..available]{
   if bytes.len()<14{return if eof{Inspection::Unknown}else{Inspection::Pending}}
   if bytes[11]==1&&bytes[13]==8{return Inspection::Known{protocol:"rdp",domain:None}}
  }
 }
 // DNS over TCP has a two-byte message length. Inspect only the first
 // complete query; a pipelined next query is not part of this frame. The
 // question name is NOT the connection's destination-domain evidence.
 if bytes.len()==1&&bytes[0]<=16{return if eof{Inspection::Unknown}else{Inspection::Pending}}
 if let Some(size)=word(bytes,0).filter(|size|(12..=4096).contains(size)){
  if bytes.len()<14{return if eof{Inspection::Unknown}else{Inspection::Pending}}
  let header=&bytes[2..14];
  if header[2]&0xf8==0&&header[4..10]==[0,1,0,0,0,0]{
   if bytes.len()<size+2{return if eof{Inspection::Unknown}else{Inspection::Pending}}
   return if crate::dns_wire::question(&bytes[2..size+2]).is_ok(){Inspection::Known{protocol:"dns",domain:None}}else{Inspection::Unknown}
  }
 }
 if let Some(size)=word(bytes,0).filter(|size|(14..=4096).contains(size)){
  if bytes.get(2).is_some_and(|v|matches!(*v,0x38|0x50)){
   if bytes.len()<size+2{return if eof{Inspection::Unknown}else{Inspection::Pending}}
   if openvpn(&bytes[2..size+2]){return Inspection::Known{protocol:"openvpn",domain:None}}
  }
 }
 if let Some(value)=sip(bytes,eof){return value}
 let methods:&[&[u8]]=&[b"GET ",b"POST ",b"HEAD ",b"PUT ",b"DELETE ",b"OPTIONS ",b"PATCH ",b"CONNECT ",b"TRACE "];
 if !methods.iter().any(|m|bytes.starts_with(m)||m.starts_with(bytes)){return Inspection::Unknown}
 let Some(end)=bytes.windows(4).position(|v|v==b"\r\n\r\n")else{return if eof||bytes.len()>=16384{Inspection::Unknown}else{Inspection::Pending}};
 if end+4>16384{return Inspection::Unknown}let Ok(text)=std::str::from_utf8(&bytes[..end])else{return Inspection::Unknown};
 let mut lines=text.split("\r\n");let Some(request)=lines.next()else{return Inspection::Unknown};let fields:Vec<_>=request.split(' ').collect();
 if fields.len()!=3||!matches!(fields[2],"HTTP/1.0"|"HTTP/1.1")||fields[1].is_empty(){return Inspection::Unknown}
 let mut host=None;
 for line in lines{let Some((key,value))=line.split_once(':')else{return Inspection::Unknown};if key.is_empty()||!key.bytes().all(|v|v.is_ascii_alphanumeric()||v==b'-'){return Inspection::Unknown}
  if key.eq_ignore_ascii_case("host"){if host.is_some(){return Inspection::Unknown}host=Some(value.trim());}
 }
 let target=if fields[0]=="CONNECT"{Some(fields[1])}else{fields[1].strip_prefix("http://").map(|v|v.split('/').next().unwrap_or(v))};
 if let(Some(host),Some(target))=(host,target){if authority(host)!=authority(target){return Inspection::Unknown}}
 Inspection::Known{protocol:"http",domain:host.or(target).and_then(authority)}
}

/// Packet metadata follows the pinned primary's sniffer order. QUIC Initial
/// decryption lives in the native rustls inspector, not this pure parser.
pub const UDP_SNIFFERS:&[&str]=&["dns","quic","stun","wireguard","openvpn","ike","tailscale","sip","bittorrent","dtls","ntp"];
pub fn stream_sniffer(name:&str)->bool{matches!(name,"tls"|"http"|"dns"|"bittorrent"|"ssh"|"rdp"|"sip"|"openvpn")}
pub fn datagram_sniffer(name:&str)->bool{UDP_SNIFFERS.contains(&name)}
pub fn supports(name:&str)->bool{stream_sniffer(name)||datagram_sniffer(name)}
fn utp(bytes:&[u8])->bool{
 if bytes.len()<20||bytes[0]&15!=1||bytes[0]>>4>4{return false}
 let mut extension=bytes[1];let mut at=20usize;
 for _ in 0..128{
  if extension==0{return true}
  let Some(header)=bytes.get(at..at+2)else{return false};extension=header[0];if extension>4{return false}
  let Some(next)=at.checked_add(2+usize::from(header[1])).filter(|next|*next<=bytes.len())else{return false};at=next;
 }false
}
fn ike(bytes:&[u8])->bool{
 let body=if bytes.len()>=32&&bytes[..4]==[0;4]{&bytes[4..]}else{bytes};
 if body.len()<28||body[..8].iter().all(|v|*v==0)||body[8..16].iter().any(|v|*v!=0)||body[16]==0||body[20..24]!=[0;4]{return false}
 if !matches!((body[17],body[18],body[19]),(0x20,34,8)|(0x10,2|4,0)){return false}
 u32::from_be_bytes(body[24..28].try_into().expect("fixed header"))as usize==body.len()
}
/// Selected packet sniffer. DNS question names are never destination-domain
/// evidence; unlike SIP/QUIC authority, they name data being requested.
pub fn datagram(bytes:&[u8],name:&str)->Inspection{
 if bytes.is_empty()||bytes.len()>65535{return Inspection::Unknown}
 if name=="sip"{return sip(bytes,true).unwrap_or(Inspection::Unknown)}
 let known=match name{
  "dns"=>crate::dns_wire::question(bytes).is_ok(),
  "stun"=>bytes.len()>=20&&bytes[4..8]==[0x21,0x12,0xa4,0x42]&&word(bytes,2).is_some_and(|size|bytes.len()>=20+size),
  "wireguard"=>match bytes[0]{1=>bytes.len()==148,2=>bytes.len()==92,3=>bytes.len()==64,4=>bytes.len()>=32&&(bytes.len()-32)%16==0,_=>false},
  "openvpn"=>openvpn(bytes),
  "ike"=>ike(bytes),
  "tailscale"=>bytes.len()>=78&&bytes.starts_with(b"TS\xf0\x9f\x92\xac"),
  "bittorrent"=>utp(bytes)||bytes.len()>=16&&bytes[..8]==0x41727101980u64.to_be_bytes()&&bytes[8..12]==[0;4],
  "dtls"=>bytes.len()>=13&&matches!(bytes[0],20|21|22|23|25)&&bytes[1]==0xfe&&matches!(bytes[2],0xff|0xfd),
  "ntp"=>bytes.len()>=48&&matches!((bytes[0]>>3)&7,3|4)&&bytes[0]&7==3&&u32::from_be_bytes(bytes[4..8].try_into().expect("header"))<=16*65536&&u32::from_be_bytes(bytes[8..12].try_into().expect("header"))<=16*65536,
  _=>false,
 };
 if known{let protocol=match name{"dns"=>"dns","stun"=>"stun","wireguard"=>"wireguard","openvpn"=>"openvpn","ike"=>"ike","tailscale"=>"tailscale","bittorrent"=>"bittorrent","dtls"=>"dtls","ntp"=>"ntp",_=>return Inspection::Unknown};Inspection::Known{protocol,domain:None}}else{Inspection::Unknown}
}
