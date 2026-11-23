//! Session-local fake addresses. An address is never reassigned to another
//! domain during the session, including after expiry/eviction. Active flows pin
//! their immutable real destination; pressure never evicts a live flow.
use std::{collections::{HashMap,HashSet},net::{IpAddr,Ipv4Addr,Ipv6Addr}};
use crate::Cidr;
#[path="fake_ip_names.rs"]pub mod names;
#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid,Family,Stale,Clock,Closed,Capacity,Unknown}
#[derive(Clone)]struct Pool{base:u128,bits:u8,width:u8,next:u128,limit:u128}
impl Pool{
    fn new(text:&str,family:u8)->Result<Self,Error>{
        let cidr=Cidr::parse(text).map_err(|_|Error::Invalid)?;let width=if cidr.address.is_ipv4(){32}else{128};
        if width!=family||cidr.bits<if width==32{8}else{16}||cidr.bits>if width==32{30}else{120}{return Err(Error::Invalid)}
        let host_bits=width-cidr.bits;let size=1u128<<host_bits;
        let value=match cidr.address{IpAddr::V4(v)=>u128::from(u32::from(v)),IpAddr::V6(v)=>u128::from(v)};
        let base=value&!(size-1);let ip=if width==32{IpAddr::V4(Ipv4Addr::from(base as u32))}else{IpAddr::V6(Ipv6Addr::from(base))};
        if ip.is_unspecified()||ip.is_loopback()||ip.is_multicast()||match ip{IpAddr::V4(v)=>v.octets()[0]>=224||v.is_link_local(),IpAddr::V6(v)=>v.is_unicast_link_local()}{return Err(Error::Invalid)}
        Ok(Self{base,bits:cidr.bits,width,next:1,limit:if width==32{size-1}else{size}})
    }
    fn contains(&self,ip:IpAddr)->bool{
        let value=match ip{IpAddr::V4(v)if self.width==32=>u128::from(u32::from(v)),IpAddr::V6(v)if self.width==128=>u128::from(v),_=>return false};
        value>>(self.width-self.bits)==self.base>>(self.width-self.bits)
    }
    fn allocate(&mut self,excluded:&HashSet<IpAddr>)->Result<IpAddr,Error>{
        while self.next<self.limit{
            let value=self.base|self.next;self.next+=1;
            let ip=if self.width==32{IpAddr::V4(Ipv4Addr::from(value as u32))}else{IpAddr::V6(Ipv6Addr::from(value))};
            if !excluded.contains(&ip){return Ok(ip)}
        }Err(Error::Capacity)
    }
}
struct Entry{domain:String,real:IpAddr,expires:u64,pins:usize}
pub struct Resolved<'a>{pub domain:&'a str,pub address:IpAddr}
pub struct Table{
    generation:u64,last:u64,last_sweep:u64,closed:bool,capacity:usize,
    v4:Option<Pool>,v6:Option<Pool>,excluded:HashSet<IpAddr>,
    entries:HashMap<IpAddr,Entry>,reverse:HashMap<(String,IpAddr),IpAddr>,flows:HashMap<u64,IpAddr>,
}
impl Table{
    pub fn new(generation:u64,capacity:usize,inet4:Option<&str>,inet6:Option<&str>,excluded:&[IpAddr])->Result<Self,Error>{
        if generation==0||generation>i64::MAX as u64||!(1..=8192).contains(&capacity)||excluded.len()>1024{return Err(Error::Invalid)}
        let v4=inet4.map(|v|Pool::new(v,32)).transpose()?;let v6=inet6.map(|v|Pool::new(v,128)).transpose()?;if v4.is_none()&&v6.is_none(){return Err(Error::Invalid)}
        let mut excluded:HashSet<_>=excluded.iter().copied().collect();excluded.insert("198.19.255.53".parse().unwrap());excluded.insert("fdfe:dcba:9876:dd54::53".parse().unwrap());
        Ok(Self{generation,last:0,last_sweep:0,closed:false,capacity,v4,v6,excluded,entries:HashMap::new(),reverse:HashMap::new(),flows:HashMap::new()})
    }
    fn check(&mut self,generation:u64,now:u64)->Result<(),Error>{
        if self.closed{return Err(Error::Closed)}if generation!=self.generation{return Err(Error::Stale)}if now<self.last{return Err(Error::Clock)}self.last=now;Ok(())
    }
    fn reap(&mut self,now:u64){
        self.entries.retain(|_,entry|entry.pins>0||entry.expires>now);
        self.reverse.retain(|_,fake|self.entries.contains_key(fake));self.last_sweep=now;
    }
    pub fn contains(&self,ip:IpAddr)->bool{self.v4.as_ref().is_some_and(|v|v.contains(ip))||self.v6.as_ref().is_some_and(|v|v.contains(ip))}
    /// All-or-nothing allocation. Zero-TTL answers still get an explicit short
    /// mapping, never overwrite an older real-IP/domain observation implicitly.
    pub fn allocate(&mut self,generation:u64,now:u64,domain:&str,addresses:&[IpAddr],ttl_ms:u64)->Result<Vec<IpAddr>,Error>{
        self.check(generation,now)?;let domain=domain.strip_suffix('.').unwrap_or(domain);
        if domain.is_empty()||domain.len()>253||!domain.is_ascii()||domain.split('.').any(|v|v.is_empty()||v.len()>63||!v.bytes().all(|b|b.is_ascii_alphanumeric()||matches!(b,b'-'|b'_')))||addresses.is_empty()||addresses.len()>64||ttl_ms>86400000{return Err(Error::Invalid)}
        if now-self.last_sweep>=1000||self.entries.len()+addresses.len()>self.capacity{self.reap(now)}
        let domain=domain.to_ascii_lowercase();let mut unique=Vec::new();for ip in addresses{if !unique.contains(ip){unique.push(*ip)}}
        let missing=unique.iter().filter(|ip|!self.reverse.contains_key(&(domain.clone(),**ip))).count();
        if self.entries.len()+missing>self.capacity{return Err(Error::Capacity)}
        let expires=now.checked_add(ttl_ms.clamp(30000,86400000)).ok_or(Error::Invalid)?;
        let mut v4=self.v4.clone();let mut v6=self.v6.clone();let mut output=Vec::with_capacity(unique.len());let mut added=Vec::new();
        for real in unique{
            if let Some(fake)=self.reverse.get(&(domain.clone(),real)){output.push(*fake);continue}
            let pool=if real.is_ipv4(){v4.as_mut()}else{v6.as_mut()}.ok_or(Error::Family)?;
            let fake=pool.allocate(&self.excluded)?;output.push(fake);added.push((fake,real));
        }
        self.v4=v4;self.v6=v6;
        for(fake,real)in added{self.entries.insert(fake,Entry{domain:domain.clone(),real,expires,pins:0});self.reverse.insert((domain.clone(),real),fake);}
        for fake in &output{self.entries.get_mut(fake).ok_or(Error::Unknown)?.expires=expires}
        Ok(output)
    }
    pub fn resolve(&mut self,generation:u64,now:u64,fake:IpAddr)->Result<Option<Resolved<'_>>,Error>{
        self.check(generation,now)?;
        Ok(self.entries.get(&fake).filter(|v|v.pins>0||v.expires>now).map(|v|Resolved{domain:&v.domain,address:v.real}))
    }
    pub fn pin(&mut self,generation:u64,now:u64,id:u64,fake:IpAddr)->Result<Resolved<'_>,Error>{
        self.check(generation,now)?;if id==0||id>i64::MAX as u64{return Err(Error::Invalid)}
        if let Some(current)=self.flows.get(&id){if *current!=fake{return Err(Error::Invalid)}}else{
            if self.flows.len()>=8192{return Err(Error::Capacity)}
            let entry=self.entries.get_mut(&fake).filter(|v|v.pins>0||v.expires>now).ok_or(Error::Unknown)?;
            entry.pins=entry.pins.checked_add(1).ok_or(Error::Capacity)?;self.flows.insert(id,fake);
        }
        let entry=self.entries.get(&fake).ok_or(Error::Unknown)?;Ok(Resolved{domain:&entry.domain,address:entry.real})
    }
    pub fn release(&mut self,generation:u64,now:u64,id:u64)->Result<(),Error>{
        self.check(generation,now)?;
        if let Some(fake)=self.flows.remove(&id){let entry=self.entries.get_mut(&fake).ok_or(Error::Unknown)?;entry.pins=entry.pins.checked_sub(1).ok_or(Error::Invalid)?;}Ok(())
    }
    pub fn close(&mut self){self.closed=true;self.entries.clear();self.reverse.clear();self.flows.clear()}
}
