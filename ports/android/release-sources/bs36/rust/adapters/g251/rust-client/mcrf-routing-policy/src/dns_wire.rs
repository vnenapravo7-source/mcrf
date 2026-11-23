//! Bounded DNS question/answer attribution. Only the answer's CNAME chain from
//! the original question can authorize A/AAAA observations; unrelated answer,
//! authority or additional addresses are not application-domain evidence.
use std::{collections::HashSet,net::{IpAddr,Ipv4Addr,Ipv6Addr}};
#[derive(Debug,PartialEq,Eq)]pub enum Error{Size,Header,Name,Pointer,Question,Record,Chain}
#[derive(Clone,Debug,PartialEq,Eq)]pub struct Question{pub id:u16,pub name:String,pub kind:u16,pub flags:u16,pub end:usize}
#[derive(Clone,Debug,PartialEq,Eq)]pub struct Address{pub domain:String,pub ip:IpAddr,pub ttl_ms:u64}
fn word(bytes:&[u8],at:usize)->Result<u16,Error>{let part=bytes.get(at..at+2).ok_or(Error::Size)?;Ok(u16::from_be_bytes([part[0],part[1]]))}
struct Names<'a>{bytes:&'a [u8]}
impl<'a> Names<'a>{
    fn read(&mut self,start:usize)->Result<(String,usize),Error>{
        let mut at=start;let mut end=None;let mut labels=Vec::new();let mut length=0usize;let mut visited=HashSet::new();
        for _ in 0..128{
            if !visited.insert(at){return Err(Error::Pointer)}
            let size=*self.bytes.get(at).ok_or(Error::Size)?;
            if size&0xc0==0xc0{
                let target=(word(self.bytes,at)?&0x3fff)as usize;
                if target>=at{return Err(Error::Pointer)}
                if end.is_none(){end=Some(at+2)}at=target;continue
            }
            if size&0xc0!=0{return Err(Error::Name)}
            if size==0{return Ok((labels.join("."),end.unwrap_or(at+1)))}
            let size=size as usize;let label=self.bytes.get(at+1..at+1+size).ok_or(Error::Size)?;
            if label.iter().any(|v|!v.is_ascii_alphanumeric()&&!matches!(*v,b'-'|b'_')){return Err(Error::Name)}
            length+=size+usize::from(!labels.is_empty());if length>253{return Err(Error::Name)}
            labels.push(String::from_utf8(label.to_ascii_lowercase()).map_err(|_|Error::Name)?);at+=1+size;
        }Err(Error::Name)
    }
    fn question(&mut self,reply:bool)->Result<Question,Error>{
        if !(12..=if reply{65535}else{4096}).contains(&self.bytes.len()){return Err(Error::Size)}
        let flags=word(self.bytes,2)?;
        if (flags&0x8000!=0)!=reply||flags&0x7800!=0||word(self.bytes,4)?!=1{return Err(Error::Header)}
        if !reply&&(word(self.bytes,6)?!=0||word(self.bytes,8)?!=0){return Err(Error::Header)}
        let(name,at)=self.read(12)?;let kind=word(self.bytes,at)?;
        if kind==0||word(self.bytes,at+2)?!=1{return Err(Error::Question)}
        Ok(Question{id:word(self.bytes,0)?,name,kind,flags,end:at+4})
    }
}
pub fn question(bytes:&[u8])->Result<Question,Error>{
    let mut names=Names{bytes};let q=names.question(false)?;
    let mut at=q.end;let additional=word(bytes,10)?as usize;if additional>16{return Err(Error::Record)}
    for _ in 0..additional{
        let(_,end)=names.read(at)?;let length=word(bytes,end+8)?as usize;
        at=end.checked_add(10+length).filter(|v|*v<=bytes.len()).ok_or(Error::Record)?;
    }
    if at!=bytes.len(){return Err(Error::Record)}Ok(q)
}
pub fn addresses(query:&[u8],reply:&[u8])->Result<Vec<Address>,Error>{
    let expected=question(query)?;let mut names=Names{bytes:reply};let actual=names.question(true)?;
    if (expected.id,&expected.name,expected.kind)!=(actual.id,&actual.name,actual.kind){return Err(Error::Question)}
    if actual.flags&0x0200!=0{return Err(Error::Header)}
    let answers=word(reply,6)?as usize;let authority=word(reply,8)?as usize;let records=answers+authority+word(reply,10)?as usize;if records>4096{return Err(Error::Record)}
    let mut at=actual.end;let mut cnames=Vec::new();let mut values=Vec::new();let mut extended=0;let mut opt=false;
    for index in 0..records{
        let(owner,end)=names.read(at)?;let kind=word(reply,end)?;let class=word(reply,end+2)?;
        let ttl=((word(reply,end+4)?as u32)<<16)|word(reply,end+6)?as u32;
        let length=word(reply,end+8)?as usize;let start=end+10;at=start.checked_add(length).filter(|v|*v<=reply.len()).ok_or(Error::Record)?;
        if kind==41{if opt||index<answers+authority||!owner.is_empty(){return Err(Error::Record)}opt=true;extended=ttl>>24;continue}
        if index>=answers||class!=1{continue}
        match kind{
            5=>{let(target,consumed)=names.read(start)?;if consumed!=at{return Err(Error::Record)}cnames.push((owner,target,ttl));},
            1=>{if length!=4{return Err(Error::Record)}values.push((owner,IpAddr::V4(Ipv4Addr::new(reply[start],reply[start+1],reply[start+2],reply[start+3])),ttl));},
            28=>{if length!=16{return Err(Error::Record)}values.push((owner,IpAddr::V6(Ipv6Addr::from(<[u8;16]>::try_from(&reply[start..at]).map_err(|_|Error::Record)?)),ttl));},_=>{},
        }
    }
    if at!=reply.len(){return Err(Error::Record)}
    if actual.flags&15!=0||extended!=0||!matches!(expected.kind,1|28){return Ok(Vec::new())}
    let mut current=expected.name.clone();let mut chain=HashSet::new();let mut maximum=u32::MAX;
    loop{
        if !chain.insert(current.clone())||chain.len()>16{return Err(Error::Chain)}
        let mut matching=cnames.iter().filter(|(owner,_,_)|owner==&current);
        let Some((_,target,ttl))=matching.next()else{break};
        let mut minimum=*ttl;for(_,other,other_ttl)in matching{if other!=target{return Err(Error::Chain)}minimum=minimum.min(*other_ttl)}
        maximum=maximum.min(minimum);current=target.clone();
    }
    let mut result=Vec::new();
    for(owner,ip,ttl)in values{
        if owner!=current||ip.is_ipv4()!=(expected.kind==1){continue}
        if result.len()>=64{return Err(Error::Record)}
        result.push(Address{domain:expected.name.clone(),ip,ttl_ms:u64::from(ttl.min(maximum)).saturating_mul(1000)});
    }Ok(result)
}
/// Build a query without copying EDNS/client state from an unrelated request.
pub fn query(id:u16,domain:&str,kind:u16)->Result<Vec<u8>,Error>{
    if kind==0{return Err(Error::Question)}let domain=domain.strip_suffix('.').unwrap_or(domain);
    if domain.len()>253||!domain.is_ascii(){return Err(Error::Name)}
    let mut out=vec![0;12];out[..2].copy_from_slice(&id.to_be_bytes());out[2]=1;out[5]=1;
    if !domain.is_empty(){for label in domain.split('.'){
        if label.is_empty()||label.len()>63||label.bytes().any(|b|!b.is_ascii_alphanumeric()&&!matches!(b,b'-'|b'_')){return Err(Error::Name)}
        out.push(label.len()as u8);out.extend_from_slice(label.as_bytes());
    }}out.push(0);out.extend_from_slice(&kind.to_be_bytes());out.extend_from_slice(&1u16.to_be_bytes());question(&out)?;Ok(out)
}
/// Generated Hosts/block replies contain the original question and only the
/// selected family's addresses. Never echo an unvalidated additional section.
pub fn answer(query:&[u8],rcode:u8,ips:&[IpAddr],ttl:u32)->Result<Vec<u8>,Error>{
    let q=question(query)?;if rcode>15||ips.len()>64||rcode!=0&&!ips.is_empty(){return Err(Error::Record)}
    let values:Vec<_>=ips.iter().filter(|ip|matches!(q.kind,1|28)&&ip.is_ipv4()==(q.kind==1)).collect();
    let mut out=query[..q.end].to_vec();let flags=0x8000|0x0080|(q.flags&0x0110)|u16::from(rcode);
    out[2..4].copy_from_slice(&flags.to_be_bytes());out[6..8].copy_from_slice(&(values.len()as u16).to_be_bytes());out[8..12].fill(0);
    for ip in values{
        out.extend([0xc0,0x0c]);out.extend_from_slice(&q.kind.to_be_bytes());out.extend_from_slice(&1u16.to_be_bytes());out.extend_from_slice(&ttl.to_be_bytes());
        match ip{IpAddr::V4(v)=>{out.extend_from_slice(&4u16.to_be_bytes());out.extend(v.octets())},IpAddr::V6(v)=>{out.extend_from_slice(&16u16.to_be_bytes());out.extend(v.octets())}}
    }addresses(query,&out)?;Ok(out)
}
/// Rewrite ordinary record TTLs, keeping EDNS extended flags/rcode untouched.
/// A generated/modified answer cannot keep the DNSSEC authenticated-data bit.
pub fn rewrite_ttl(query:&[u8],reply:&[u8],ttl:u32)->Result<Vec<u8>,Error>{
    addresses(query,reply)?;let mut names=Names{bytes:reply};let q=names.question(true)?;
    let count=word(reply,6)?as usize+word(reply,8)?as usize+word(reply,10)?as usize;
    let mut at=q.end;let mut out=reply.to_vec();out[3]&=!0x20;
    for _ in 0..count{let(_,end)=names.read(at)?;let kind=word(reply,end)?;let length=word(reply,end+8)?as usize;
        if kind!=41{out[end+4..end+8].copy_from_slice(&ttl.to_be_bytes())}at=end+10+length;
    }Ok(out)
}
/// TTL admission matching the selected primary's positive/negative rules.
/// Negative SOA uses min(record TTL, MINIMUM); OPT flags are never a TTL.
pub fn cache_ttl(query:&[u8],reply:&[u8])->Result<u32,Error>{
    addresses(query,reply)?;let mut names=Names{bytes:reply};let q=names.question(true)?;
    let answers=word(reply,6)?as usize;let authority=word(reply,8)?as usize;
    let count=answers+authority+word(reply,10)?as usize;let mut at=q.end;let mut minimum=0;
    for index in 0..count{
        let(_,end)=names.read(at)?;let kind=word(reply,end)?;
        let ttl=((word(reply,end+4)?as u32)<<16)|word(reply,end+6)?as u32;let size=word(reply,end+8)?as usize;at=end+10+size;
        if kind==41{continue}
        if answers==0&&index<authority&&kind==6{
            let(_,p)=names.read(end+10)?;let(_,p)=names.read(p)?;
            if p+20!=at{return Err(Error::Record)}let value=((word(reply,p+16)?as u32)<<16)|word(reply,p+18)?as u32;return Ok(ttl.min(value));
        }
        if minimum==0||ttl>0&&ttl<minimum{minimum=ttl}
    }Ok(minimum)
}
/// Bound UDP to the caller's advertised EDNS size and owned TUN MTU. A large
/// valid answer gets a complete question-only TC reply, never a cut RR/pointer.
pub fn udp_response(query:&[u8],reply:&[u8],mtu:usize,ipv6:bool)->Result<Vec<u8>,Error>{
    addresses(query,reply)?;if !(576..=9000).contains(&mtu)||ipv6&&mtu<1280{return Err(Error::Size)}
    let q=question(query)?;let mut names=Names{bytes:query};let mut at=q.end;let mut advertised=512usize;let mut opt=false;
    for _ in 0..word(query,10)?{
        let(owner,end)=names.read(at)?;let kind=word(query,end)?;let class=word(query,end+2)?;let size=word(query,end+8)?as usize;
        if kind==41{if opt||!owner.is_empty(){return Err(Error::Record)}opt=true;advertised=usize::from(class).clamp(512,4096)}at=end+10+size;
    }
    let limit=advertised.min(mtu-if ipv6{48}else{28});if reply.len()<=limit{return Ok(reply.to_vec())}
    let mut out=query[..q.end].to_vec();out[2]=reply[2]|2;out[3]=reply[3]&!0x20;out[6..12].fill(0);Ok(out)
}
