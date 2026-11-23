//! Session-local reflection to a worker-owned kernel listener. Routing and fresh
//! kernel owner checks remain mandatory in the host, including before replies.
//! Reserved alias traffic is always consumed, even when malformed or full.
use std::{collections::{HashMap,HashSet},net::IpAddr};
use crate::{packet::PacketInfo,packet_nat};
pub const ALIAS4:&str="198.19.255.53";
pub const ALIAS6:&str="fdfe:dcba:9876:dd54::53";
#[derive(Clone,Copy,Hash,PartialEq,Eq,Debug)]
pub struct Client {pub source:IpAddr,pub port:u16,pub protocol:u8,pub destination:IpAddr}
#[derive(Clone,Copy)]struct Mapping {client:Client,translated:u16,expires:u64,syn:u32}
#[derive(Clone,Copy,Hash,PartialEq,Eq)]struct Fragment {source:IpAddr,destination:IpAddr,id:u32,protocol:u8}
struct Rewrite {source:IpAddr,destination:IpAddr,expires:u64}
struct Pending {expires:u64,packets:Vec<Vec<u8>>}
#[derive(Debug,PartialEq,Eq)]pub enum Error {Config,Clock,Closed}
pub struct Reflector {
    sources:HashSet<IpAddr>,local4:Option<IpAddr>,local6:Option<IpAddr>,port:u16,next:u16,last:u64,closed:bool,
    by_client:HashMap<Client,u16>,by_port:HashMap<(u8,u16),Mapping>,
    fragments:HashMap<Fragment,Rewrite>,pending:HashMap<Fragment,Pending>,pending_bytes:usize,
}
impl Reflector {
    pub fn new(sources:&[IpAddr],local4:Option<IpAddr>,local6:Option<IpAddr>,port:u16)->Result<Self,Error>{
        if sources.is_empty()||sources.len()>16{return Err(Error::Config)}
        let sources:HashSet<_>=sources.iter().copied().collect();
        if sources.is_empty()||sources.len()>16||port<1024||local4.is_none()&&local6.is_none()||
            local4.is_some_and(|v|!v.is_ipv4())||local6.is_some_and(|v|v.is_ipv4())||
            sources.iter().any(|v|v.is_unspecified()||v.is_multicast()||v.is_loopback()||*v==ALIAS4.parse::<IpAddr>().unwrap()||*v==ALIAS6.parse::<IpAddr>().unwrap())||
            local4.into_iter().chain(local6).any(|v|!sources.contains(&v)){return Err(Error::Config)}
        Ok(Self{sources,local4,local6,port,next:32768,last:0,closed:false,by_client:HashMap::new(),by_port:HashMap::new(),fragments:HashMap::new(),pending:HashMap::new(),pending_bytes:0})
    }
    fn tick(&mut self,now:u64)->Result<(),Error>{
        if self.closed{return Err(Error::Closed)}if now<self.last{return Err(Error::Clock)}self.last=now;
        self.by_port.retain(|_,v|v.expires>now);self.by_client.retain(|client,port|self.by_port.get(&(client.protocol,*port)).is_some_and(|v|v.client==*client));
        self.fragments.retain(|_,v|v.expires>now);
        self.pending.retain(|_,v|{if v.expires>now{true}else{self.pending_bytes-=v.packets.iter().map(Vec::len).sum::<usize>();false}});Ok(())
    }
    pub fn client(&mut self,now:u64,protocol:u8,port:u16)->Result<Option<Client>,Error>{
        self.tick(now)?;
        Ok(self.by_port.get(&(protocol,port)).filter(|v|self.by_client.get(&v.client)==Some(&port)).map(|v|v.client))
    }
    /// None is exact pass-through. Some contains only packets for local TUN
    /// injection; an empty vector is a terminal drop, never a provider fallback.
    pub fn reflect(&mut self,now:u64,packet:&[u8])->Result<Option<Vec<Vec<u8>>>,Error>{
        self.reflect_inner(now,packet,false)
    }
    /// Only call AFTER an exact conditional hijack-dns policy decision. This
    /// preserves an app-selected resolver address in owner lookup and replies;
    /// ordinary traffic to port53 is never captured merely because it is DNS.
    pub fn reflect_hijacked(&mut self,now:u64,packet:&[u8])->Result<Option<Vec<Vec<u8>>>,Error>{
        self.reflect_inner(now,packet,true)
    }
    fn reflect_inner(&mut self,now:u64,packet:&[u8],hijacked:bool)->Result<Option<Vec<Vec<u8>>>,Error>{
        if self.closed{return Err(Error::Closed)}if now<self.last{return Err(Error::Clock)}self.last=now;
        let reserved=match packet.first().map(|v|v>>4){
            Some(4) if packet.len()>=20=>packet[16..20]==[198,19,255,53],
            Some(6) if packet.len()>=40=>packet[24..40]==ALIAS6.parse::<std::net::Ipv6Addr>().unwrap().octets(),_=>false,
        };
        if !reserved&&!hijacked{return Ok(None)}
        let Ok(info)=PacketInfo::read(packet) else{return Ok(if reserved||hijacked{Some(vec![])}else{None})};
        let fragment=info.fragment.map(|v|Fragment{source:info.source,destination:info.destination,id:v.id,protocol:v.protocol});
        if info.fragment.is_some_and(|f|f.offset_bytes==0){if let Some(key)=fragment{self.fragments.remove(&key);}}
        self.tick(now)?;
        if !matches!(info.protocol,6|17)||!self.sources.contains(&info.source){return Ok(Some(vec![]))}
        let local=if info.source.is_ipv4(){self.local4}else{self.local6};let Some(local)=local else{return Ok(Some(vec![]))};
        if info.fragment.is_some_and(|v|v.offset_bytes!=0){
            let key=fragment.unwrap();
            if let Some(rule)=self.fragments.get(&key){return Ok(Some(packet_nat::rewrite(packet,rule.source,rule.destination,None).ok().into_iter().collect()))}
            if self.pending.len()>=128||self.pending_bytes+packet.len()>524288{return Ok(Some(vec![]))}
            let pending=self.pending.entry(key).or_insert_with(||Pending{expires:now.saturating_add(2000),packets:Vec::new()});
            if pending.packets.len()<32{pending.packets.push(packet.to_vec());self.pending_bytes+=packet.len()}
            return Ok(Some(vec![]))
        }
        let Some((source_port,destination_port))=info.ports else{return Ok(Some(vec![]))};
        let alias:IpAddr=if info.source.is_ipv4(){ALIAS4}else{ALIAS6}.parse().map_err(|_|Error::Config)?;
        let (mapping,source,destination,ports)=if info.source==local&&source_port==self.port {
            let Some(mapping)=self.by_port.get(&(info.protocol,destination_port)).copied() else{return Ok(Some(vec![]))};
            if self.by_client.get(&mapping.client)!=Some(&mapping.translated)||info.destination!=alias{return Ok(Some(vec![]))}
            (mapping,mapping.client.destination,mapping.client.source,(53,mapping.client.port))
        }else{
            if destination_port!=53||source_port==0{return Ok(Some(vec![]))}
            let client=Client{source:info.source,port:source_port,protocol:info.protocol,destination:info.destination};
            let syn=info.tcp.filter(|v|v.flags&0x12==2);let old=self.by_client.get(&client).and_then(|port|self.by_port.get(&(info.protocol,*port))).copied();
            let replace=old.is_none()||syn.is_some_and(|v|old.is_some_and(|old|old.syn!=v.sequence));
            let mapping=if replace {
                if info.protocol==6&&syn.is_none()||self.by_port.len()>=1024{return Ok(Some(vec![]))}
                let mut available=None;
                for _ in 0..65536{self.next=self.next.wrapping_add(1);if self.next<1024{self.next=1024}if self.next!=self.port&&!self.by_port.contains_key(&(info.protocol,self.next)){available=Some(self.next);break}}
                let Some(translated)=available else{return Ok(Some(vec![]))};
                let mapping=Mapping{client,translated,expires:now,syn:syn.map_or(0,|v|v.sequence)};
                // Retain old translated-port tombstone until expiry, but make
                // it unable to reach a new client after tuple reuse/new SYN.
                self.by_client.insert(client,translated);self.by_port.insert((info.protocol,translated),mapping);mapping
            }else{old.unwrap()};
            // Always use the reserved synthetic peer at the kernel listener;
            // keep the ORIGINAL resolver only in the private mapping. A local
            // app cannot impersonate this peer by binding its own address.
            (mapping,alias,local,(mapping.translated,self.port))
        };
        let Some(live)=self.by_port.get_mut(&(info.protocol,mapping.translated)) else{return Ok(Some(vec![]))};
        live.expires=now.saturating_add(if info.protocol==6{120000}else{crate::dns_limits::UDP_MAPPING_MS});
        let Ok(out)=packet_nat::rewrite(packet,source,destination,Some(ports)) else{return Ok(Some(vec![]))};
        let mut replies=vec![out];
        if let Some(key)=fragment {
            if self.fragments.len()>=256{return Ok(Some(vec![]))}
            self.fragments.insert(key,Rewrite{source,destination,expires:now.saturating_add(10000)});
            if let Some(pending)=self.pending.remove(&key){for part in pending.packets{self.pending_bytes-=part.len();if let Ok(out)=packet_nat::rewrite(&part,source,destination,None){replies.push(out)}}}
        }
        Ok(Some(replies))
    }
    pub fn close(&mut self){self.closed=true;self.by_client.clear();self.by_port.clear();self.fragments.clear();self.pending.clear();self.pending_bytes=0}
}
