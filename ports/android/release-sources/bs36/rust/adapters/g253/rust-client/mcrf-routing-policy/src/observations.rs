//! Session-local DNS attribution. Only the owning authenticated resolver may
//! admit a completed answer. This does not resolve DNS or infer Android UIDs.
//! A shared address is ambiguous, never an arbitrary first/last domain match.
use std::{collections::HashMap,net::IpAddr};

#[derive(Debug,PartialEq,Eq)]
pub enum ObservationError { Invalid, Stale, Closed, Clock, Capacity, Uncacheable }
#[derive(Debug,PartialEq,Eq)]
pub enum DomainObservation<'a> { Missing, Ambiguous, Unique(&'a str) }
struct Name { value:String,expires:u64 }
#[derive(Default)]
struct Entry { names:Vec<Name>,ambiguous_until:u64 }
impl Entry {
    fn prune(&mut self,now:u64){self.names.retain(|name|name.expires>now);if self.ambiguous_until<=now{self.ambiguous_until=0}}
    fn live(&self)->bool{!self.names.is_empty()||self.ambiguous_until!=0}
}
pub struct DomainCache {
    generation:u64,last:u64,last_sweep:u64,capacity:usize,closed:bool,
    entries:HashMap<(u32,IpAddr),Entry>,
}
impl DomainCache {
    /// A newly delivered zero-TTL answer must retire older attribution for
    /// these addresses before the caller falls back to per-flow inspection.
    pub fn invalidate(&mut self,generation:u64,now:u64,uid:u32,addresses:&[IpAddr])->Result<(),ObservationError>{
        self.check(generation,now)?;if uid>i32::MAX as u32||addresses.len()>64{return Err(ObservationError::Invalid)}
        for address in addresses{self.entries.remove(&(uid,*address));}Ok(())
    }
    pub fn new(generation:u64,capacity:usize)->Result<Self,ObservationError>{
        if generation==0||generation>i64::MAX as u64||!(1..=8192).contains(&capacity){return Err(ObservationError::Invalid)}
        Ok(Self{generation,last:0,last_sweep:0,capacity,closed:false,entries:HashMap::with_capacity(capacity)})
    }
    fn check(&mut self,generation:u64,now:u64)->Result<(),ObservationError>{
        if self.closed{return Err(ObservationError::Closed)}
        if generation!=self.generation{return Err(ObservationError::Stale)}
        if now<self.last{return Err(ObservationError::Clock)}
        self.last=now;Ok(())
    }
    /// An admission error MUST prevent delivery of the corresponding DNS
    /// answer; otherwise a prior observation could incorrectly look unique.
    /// A zero-TTL address answer needs per-flow/fake-IP attribution instead;
    /// delivering it with no mapping could leave an older domain looking unique.
    pub fn admit(&mut self,generation:u64,now:u64,uid:u32,domain:&str,addresses:&[IpAddr],ttl_ms:u64)->Result<(),ObservationError>{
        self.check(generation,now)?;
        let domain=domain.strip_suffix('.').unwrap_or(domain);
        if uid>i32::MAX as u32||domain.is_empty()||domain.len()>253||addresses.len()>64||ttl_ms>86_400_000||
            !domain.is_ascii()||domain.split('.').any(|label|label.is_empty()||label.len()>63||
                !label.bytes().all(|v|v.is_ascii_alphanumeric()||v==b'-'||v==b'_')) {
            return Err(ObservationError::Invalid)
        }
        if addresses.is_empty(){return Ok(())}
        if ttl_ms==0{return Err(ObservationError::Uncacheable)}
        let expires=now.checked_add(ttl_ms.min(600_000)).ok_or(ObservationError::Invalid)?;
        let mut unique=addresses.to_vec();unique.sort_unstable();unique.dedup();
        let additional=unique.iter().filter(|address|!self.entries.contains_key(&(uid,**address))).count();
        if self.entries.len()+additional>self.capacity {
            if now-self.last_sweep>=1000 {
                self.entries.retain(|_,entry|{entry.prune(now);entry.live()});self.last_sweep=now;
            }
            let additional=unique.iter().filter(|address|!self.entries.contains_key(&(uid,**address))).count();
            if self.entries.len()+additional>self.capacity{return Err(ObservationError::Capacity)}
        }
        let domain=domain.to_ascii_lowercase();
        for address in unique {
            let entry=self.entries.entry((uid,address)).or_default();entry.prune(now);
            if let Some(name)=entry.names.iter_mut().find(|name|name.value==domain){name.expires=expires}
            else if entry.names.len()<8 {entry.names.push(Name{value:domain.clone(),expires})}
            else {entry.ambiguous_until=entry.ambiguous_until.max(expires)}
        }
        Ok(())
    }
    pub fn lookup(&mut self,generation:u64,now:u64,uid:u32,address:IpAddr)->Result<DomainObservation<'_>,ObservationError>{
        self.check(generation,now)?;
        if uid>i32::MAX as u32{return Err(ObservationError::Invalid)}
        let Some(entry)=self.entries.get_mut(&(uid,address)) else{return Ok(DomainObservation::Missing)};
        entry.prune(now);
        Ok(if entry.ambiguous_until!=0||entry.names.len()>1{DomainObservation::Ambiguous}
           else if let Some(name)=entry.names.first(){DomainObservation::Unique(&name.value)}
           else{DomainObservation::Missing})
    }
    pub fn close(&mut self){self.closed=true;self.entries.clear()}
}
