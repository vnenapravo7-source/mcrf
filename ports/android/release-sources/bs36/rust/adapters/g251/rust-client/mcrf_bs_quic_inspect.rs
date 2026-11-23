//! Bounded QUIC v1/v2 Initial metadata using the already pinned rustls QUIC
//! crypto. No connection, application payload decryption or new QUIC stack.
use mcrf_bs_routing_policy::inspect::{Inspection,tls_handshake};
use turn_rustls::{Side,quic::{Keys,Version}};
struct Initial{version:u32,cid:Vec<u8>,keys:Keys,largest:Option<u64>}
pub struct Inspector{initial:Option<Initial>,bytes:Vec<u8>,present:Vec<bool>,packets:usize,done:Option<Inspection>}
fn var(bytes:&[u8],at:&mut usize)->Result<u64,()>{
 let first=*bytes.get(*at).ok_or(())?;let size=1usize<<(first>>6);let part=bytes.get(*at..*at+size).ok_or(())?;let mut value=u64::from(first&63);for byte in &part[1..]{value=(value<<8)|u64::from(*byte)}*at+=size;Ok(value)
}
fn number(value:u64)->Result<usize,()>{usize::try_from(value).map_err(|_|())}
fn keys(cid:&[u8],version:u32)->Result<Keys,()>{
 let version=match version{1=>Version::V1,0x6b3343cf=>Version::V2,_=>return Err(())};
 let suite=turn_rustls::crypto::aws_lc_rs::cipher_suite::TLS13_AES_128_GCM_SHA256.tls13().ok_or(())?.quic_suite().ok_or(())?;
 Ok(suite.keys(cid,Side::Server,version))
}
fn packet_number(truncated:u64,size:usize,largest:Option<u64>)->Result<u64,()>{
 let expected=largest.map_or(0,|p|p+1);let window=1u64<<(size*8);let half=window/2;let mut candidate=(expected&!(window-1))|truncated;
 if candidate+half<=expected&&candidate+window<(1u64<<62){candidate+=window}else if candidate>expected+half&&candidate>=window{candidate-=window}
 if candidate>=(1u64<<62){Err(())}else{Ok(candidate)}
}
impl Inspector{
 pub fn new()->Self{Self{initial:None,bytes:Vec::new(),present:Vec::new(),packets:0,done:None}}
 pub fn finish(&mut self)->Inspection{
  if let Some(done)=&self.done{return done.clone()}let result=if self.initial.is_some(){Inspection::Known{protocol:"quic",domain:None}}else{Inspection::Unknown};self.done=Some(result.clone());self.initial=None;self.bytes.clear();self.present.clear();result
 }
 pub fn feed(&mut self,datagram:&[u8])->Inspection{
  if let Some(done)=&self.done{return done.clone()}
  let result=match self.ingest(datagram){Ok(())=>{
   let contiguous=self.present.iter().position(|p|!*p).unwrap_or(self.present.len());
   match tls_handshake(&self.bytes[..contiguous],false){Inspection::Known{domain,..}=>Inspection::Known{protocol:"quic",domain},other=>other}
  },Err(_)=>Inspection::Unknown};
  if !matches!(result,Inspection::Pending){self.done=Some(result.clone());self.bytes.clear();self.present.clear();self.initial=None;}result
 }
 fn ingest(&mut self,datagram:&[u8])->Result<(),()>{
  if !(1200..=65535).contains(&datagram.len()){return Err(())}let mut cursor=0;let mut count=0;
  while cursor<datagram.len(){
   count+=1;if count>8{return Err(())}let bytes=&datagram[cursor..];let first=*bytes.first().ok_or(())?;
   if first&0xc0!=0xc0{if self.initial.is_some(){break}else{return Err(())}}
   let version=u32::from_be_bytes(bytes.get(1..5).ok_or(())?.try_into().map_err(|_|())?);let initial=match version{1=>(first>>4)&3==0,0x6b3343cf=>(first>>4)&3==1,_=>return Err(())};
   let mut at=5;let dcil=usize::from(*bytes.get(at).ok_or(())?);at+=1;if dcil>20{return Err(())}let cid=bytes.get(at..at+dcil).ok_or(())?;at+=dcil;
   let scil=usize::from(*bytes.get(at).ok_or(())?);at+=1;if scil>20{return Err(())}bytes.get(at..at+scil).ok_or(())?;at+=scil;
   if initial{let token=number(var(bytes,&mut at)?)?;if token>4096{return Err(())}bytes.get(at..at+token).ok_or(())?;at+=token;}
   // Retry has no Length field. It must not be mistaken for Initial frames.
   let retry=if version==1{(first>>4)&3==3}else{(first>>4)&3==0};if retry{return Err(())}
   let size=number(var(bytes,&mut at)?)?;let end=at.checked_add(size).filter(|end|*end<=bytes.len()).ok_or(())?;cursor+=end;
   if !initial{if self.initial.is_none(){return Err(())}continue}
   self.packets+=1;if self.packets>32||size<20{return Err(())}
   if self.initial.is_none(){self.initial=Some(Initial{version,cid:cid.to_vec(),keys:keys(cid,version)?,largest:None});}
   let state=self.initial.as_mut().ok_or(())?;if state.version!=version||state.cid!=cid{return Err(())}
   let sample_len=state.keys.remote.header.sample_len();let sample=bytes.get(at+4..at+4+sample_len).filter(|_|at+4+sample_len<=end).ok_or(())?;
   let mut first=first;let mut pn:[u8;4]=bytes.get(at..at+4).ok_or(())?.try_into().map_err(|_|())?;
   state.keys.remote.header.decrypt_in_place(sample,&mut first,&mut pn).map_err(|_|())?;
   if first&0x0c!=0{return Err(())}let pn_size=usize::from(first&3)+1;let truncated=pn[..pn_size].iter().fold(0u64,|v,b|(v<<8)|u64::from(*b));let pn_value=packet_number(truncated,pn_size,state.largest)?;
   let mut header=bytes[..at+pn_size].to_vec();header[0]=first;header[at..].copy_from_slice(&pn[..pn_size]);
   let mut ciphertext=bytes[at+pn_size..end].to_vec();let payload=state.keys.remote.packet.decrypt_in_place(pn_value,&header,&mut ciphertext).map_err(|_|())?;
   state.largest=Some(state.largest.map_or(pn_value,|n|n.max(pn_value)));self.frames(payload)?;
  }Ok(())
 }
 fn frames(&mut self,bytes:&[u8])->Result<(),()>{
  let mut at=0;let mut count=0;
  while at<bytes.len(){let kind=var(bytes,&mut at)?;count+=1;if count>8192{return Err(())}
   match kind{
    0=>{while bytes.get(at)==Some(&0){at+=1}},1=>{},
    2|3=>{let largest=var(bytes,&mut at)?;var(bytes,&mut at)?;let ranges=var(bytes,&mut at)?;if ranges>256{return Err(())}let mut last=largest.checked_sub(var(bytes,&mut at)?).ok_or(())?;
     for _ in 0..ranges{let gap=var(bytes,&mut at)?.checked_add(2).ok_or(())?;last=last.checked_sub(gap).ok_or(())?.checked_sub(var(bytes,&mut at)?).ok_or(())?;}if kind==3{for _ in 0..3{var(bytes,&mut at)?;}}
    },
    6=>{let offset=number(var(bytes,&mut at)?)?;let size=number(var(bytes,&mut at)?)?;let end=offset.checked_add(size).filter(|v|*v<=65536).ok_or(())?;let data=bytes.get(at..at+size).ok_or(())?;at+=size;
     if self.bytes.len()<end{self.bytes.resize(end,0);self.present.resize(end,false)}
     for(index,byte)in data.iter().enumerate(){let p=offset+index;if self.present[p]&&self.bytes[p]!=*byte{return Err(())}self.bytes[p]=*byte;self.present[p]=true;}
    },
    0x1c=>{var(bytes,&mut at)?;var(bytes,&mut at)?;let length=number(var(bytes,&mut at)?)?;let end=at.checked_add(length).filter(|end|*end<=bytes.len()).ok_or(())?;at=end;},
    _=>return Err(()),
   }
  }Ok(())
 }
}
#[cfg(test)]mod tests{
 use super::*;
 fn encoded(value:u64)->Vec<u8>{if value<64{vec![value as u8]}else if value<16384{((value as u16)|0x4000).to_be_bytes().to_vec()}else{((value as u32)|0x80000000).to_be_bytes().to_vec()}}
 fn hello(ech:bool)->Vec<u8>{
  let name=b"owned.invalid";let mut list=vec![0];list.extend((name.len()as u16).to_be_bytes());list.extend(name);let mut sni=(list.len()as u16).to_be_bytes().to_vec();sni.extend(list);
  let mut ext=vec![0,0];ext.extend((sni.len()as u16).to_be_bytes());ext.extend(sni);if ech{ext.extend([0xfe,0x0d,0,1,0]);}
  let mut body=vec![3,3];body.extend([0;32]);body.extend([0,0,2,0x13,1,1,0]);body.extend((ext.len()as u16).to_be_bytes());body.extend(ext);let mut out=vec![1,0,(body.len()>>8)as u8,body.len()as u8];out.extend(body);out
 }
 fn crypto(offset:u64,data:&[u8])->Vec<u8>{let mut out=vec![6];out.extend(encoded(offset));out.extend(encoded(data.len()as u64));out.extend(data);out}
 fn packet(version:u32,pn:u32,mut payload:Vec<u8>)->Vec<u8>{
  let cid=[0x83,0x94,0xc8,0xf0,0x3e,0x51,0x57,8];let suite=turn_rustls::crypto::aws_lc_rs::cipher_suite::TLS13_AES_128_GCM_SHA256.tls13().unwrap().quic_suite().unwrap();
  let keys=suite.keys(&cid,Side::Client,if version==1{Version::V1}else{Version::V2});let mut header=vec![if version==1{0xc3}else{0xd3}];header.extend(version.to_be_bytes());header.push(8);header.extend(cid);header.extend([0,0]);
  payload.resize(1162,0);header.extend(encoded((4+payload.len()+16)as u64));let pn_at=header.len();header.extend(pn.to_be_bytes());assert_eq!(header.len()+payload.len()+16,1200);
  let tag=keys.local.packet.encrypt_in_place(u64::from(pn),&header,&mut payload).unwrap();let mut first=header[0];keys.local.header.encrypt_in_place(&payload[..16],&mut first,&mut header[pn_at..]).unwrap();header[0]=first;header.extend(payload);header.extend(tag.as_ref());header
 }
 #[test]fn published_rfc9001_client_initial_vector_has_expected_domain(){
  let text=include_str!("mcrf-routing-policy/tests/fixtures/rfc9001-client-initial.hex");let bytes=hex::decode(text.split_whitespace().collect::<String>()).unwrap();assert_eq!(bytes.len(),1200);
  assert_eq!(Inspector::new().feed(&bytes),Inspection::Known{protocol:"quic",domain:Some("example.com".into())});
 }
 #[test]fn v1_and_v2_crypto_fragments_reassemble_out_of_order_with_identical_retransmissions(){
  for version in [1,0x6b3343cf]{let h=hello(false);let first=packet(version,2,crypto(20,&h[20..]));let second=packet(version,1,crypto(0,&h[..20]));let mut inspector=Inspector::new();
   assert_eq!(inspector.feed(&first),Inspection::Pending);assert_eq!(inspector.feed(&first),Inspection::Pending);assert_eq!(inspector.feed(&second),Inspection::Known{protocol:"quic",domain:Some("owned.invalid".into())});
  }
 }
 #[test]fn ech_outer_name_is_withheld_and_timeout_keeps_only_confirmed_protocol(){
  assert_eq!(Inspector::new().feed(&packet(0x6b3343cf,1,crypto(0,&hello(true)))),Inspection::Known{protocol:"quic",domain:None});
  let mut inspector=Inspector::new();assert_eq!(inspector.feed(&packet(1,1,crypto(20,&hello(false)[20..]))),Inspection::Pending);assert_eq!(inspector.finish(),Inspection::Known{protocol:"quic",domain:None});
 }
 #[test]fn invalid_authentication_overlap_and_frames_never_authorize_domain(){
  let mut bad=packet(1,1,crypto(0,&hello(false)));*bad.last_mut().unwrap()^=1;assert_eq!(Inspector::new().feed(&bad),Inspection::Unknown);
  let h=hello(false);let mut inspector=Inspector::new();assert_eq!(inspector.feed(&packet(1,1,crypto(0,&h[..20]))),Inspection::Pending);assert_eq!(inspector.feed(&packet(1,2,crypto(10,&[0xff;20]))),Inspection::Unknown);
  let mut invalid=crypto(0,&h);invalid.push(0x31);assert_eq!(Inspector::new().feed(&packet(1,1,invalid)),Inspection::Unknown);
  assert_eq!(Inspector::new().feed(&packet(1,1,crypto(65536,&[1]))),Inspection::Unknown);
 }
 #[test]fn packet_number_wrap_and_malformed_datagrams_are_bounded_and_never_panic(){
  assert_eq!(packet_number(0,1,Some(255)),Ok(256));assert_eq!(packet_number(255,1,Some(256)),Ok(255));
  let source=packet(1,1,crypto(0,&hello(false)));for at in (0..source.len()).step_by(7){let mut bad=source.clone();bad[at]^=0xff;let _=Inspector::new().feed(&bad);}
  for size in [0,1,1199,65536]{assert_eq!(Inspector::new().feed(&vec![0;size]),Inspection::Unknown)}
 }
}
