//! Bounded per-flow decisions for a single immutable policy/underlay generation.
//! No sockets, threads, packet copies or TCP termination. Not wired to BS yet.
use std::{collections::HashMap,net::IpAddr};
use crate::{Error,Flow,NetworkContext,Observed,Policy,Target};

const MAX_FLOWS:usize=8192;
const SWEEP_INTERVAL_MS:u64=1000;

#[derive(Clone,PartialEq,Eq,Debug)]
pub enum SessionError { Invalid, Closed, Stale, Clock, Capacity, UnsupportedPacket, Metadata(Error) }

#[derive(Clone,Copy)]
pub struct Decision { pub target:Target,pub cached:bool }

#[derive(Clone,Copy,Hash,PartialEq,Eq)]
struct Key { association:u64,source:IpAddr,destination:IpAddr,source_port:u16,port:u16,network:u8 }
impl Key {
    fn flow(association:u64,f:&Flow<'_>)->Result<Self,SessionError> {
        if association==0{return Err(SessionError::Invalid)}
        let network=match f.network {"tcp"=>6,"udp"=>17,"icmp"=>1,"icmpv6"=>58,_=>return Err(SessionError::UnsupportedPacket)};
        Ok(Self{association,source:f.source,destination:f.destination,source_port:f.source_port,port:f.port,network})
    }
}
struct Entry { target:Target,last:u64 }
enum UnderlaySnapshot {
    Pending,Absent,
    Known{kind:String,expensive:bool,constrained:bool,ssid:String,bssid:String},
}
impl UnderlaySnapshot {
    fn capture(value:Observed<Option<NetworkContext<'_>>>)->Result<Self,SessionError> {
        Ok(match value {
            Observed::Pending=>Self::Pending,Observed::Known(None)=>Self::Absent,
            Observed::Known(Some(n))=>{
                if !matches!(n.kind,"wifi"|"cellular"|"ethernet"|"other")||n.wifi_ssid.len()>256||n.wifi_bssid.len()>64{return Err(SessionError::Invalid)}
                Self::Known{kind:n.kind.to_owned(),expensive:n.expensive,constrained:n.constrained,ssid:n.wifi_ssid.to_owned(),bssid:n.wifi_bssid.to_owned()}
            },
        })
    }
    fn borrowed(&self)->Observed<Option<NetworkContext<'_>>> {
        match self {
            Self::Pending=>Observed::Pending,Self::Absent=>Observed::Known(None),
            Self::Known{kind,expensive,constrained,ssid,bssid}=>Observed::Known(Some(NetworkContext{kind,expensive:*expensive,constrained:*constrained,wifi_ssid:ssid,wifi_bssid:bssid})),
        }
    }
}

/// The owner must create a NEW session on policy or underlay changes, close the
/// old one, and retire flows only after actual close. A packet's caller-supplied
/// underlay is never trusted; decisions use this session's immutable snapshot.
/// Missing metadata is never cached as a negative match or a direct fallback.
/// Association IDs belong to the owner's flow tracker: a new TCP connection or
/// UDP socket owner gets a fresh ID, even if it reuses the identical five-tuple.
/// This cache is not a substitute for that tracker or Android ownership checks.
pub struct Session {
    generation:u64,policy:Policy,underlay:UnderlaySnapshot,
    capacity:usize,idle_ms:u64,last_now:u64,last_sweep:u64,closed:bool,
    entries:HashMap<Key,Entry>,
}
impl Session {
    pub fn new(generation:u64,policy:Policy,underlay:Observed<Option<NetworkContext<'_>>>,capacity:usize,idle_ms:u64)->Result<Self,SessionError> {
        if generation==0||generation>i64::MAX as u64||capacity==0||capacity>MAX_FLOWS||!(1000..=600_000).contains(&idle_ms){return Err(SessionError::Invalid)}
        Ok(Self{generation,policy,underlay:UnderlaySnapshot::capture(underlay)?,capacity,idle_ms,last_now:0,last_sweep:0,closed:false,entries:HashMap::with_capacity(capacity)})
    }
    fn live(&mut self,generation:u64,now:u64)->Result<(),SessionError> {
        if self.closed{return Err(SessionError::Closed)}
        if generation!=self.generation{return Err(SessionError::Stale)}
        if now<self.last_now{return Err(SessionError::Clock)}
        self.last_now=now;Ok(())
    }
    pub fn decide(&mut self,generation:u64,association:u64,now:u64,flow:&Flow<'_>)->Result<Decision,SessionError> {
        self.live(generation,now)?;
        let key=Key::flow(association,flow)?;
        if let Some(entry)=self.entries.get_mut(&key) {
            if now-entry.last<self.idle_ms {
                entry.last=now;return Ok(Decision{target:entry.target,cached:true})
            }
            self.entries.remove(&key);
        }
        if self.entries.len()>=self.capacity {
            // At most one bounded full scan per second even under a new-flow
            // flood. Never evict a live TCP decision and reclassify it midway.
            if now-self.last_sweep>=SWEEP_INTERVAL_MS {
                self.entries.retain(|_,entry|now-entry.last<self.idle_ms);
                self.last_sweep=now;
            }
            if self.entries.len()>=self.capacity{return Err(SessionError::Capacity)}
        }
        let captured=Flow{underlay:self.underlay.borrowed(),..*flow};
        let target=self.policy.decide(&captured).map_err(SessionError::Metadata)?;
        self.entries.insert(key,Entry{target,last:now});
        Ok(Decision{target,cached:false})
    }
    /// Call for a completely retired flow, not the first half-close/FIN.
    pub fn retire(&mut self,generation:u64,association:u64,now:u64,flow:&Flow<'_>)->Result<(),SessionError> {
        self.live(generation,now)?;self.entries.remove(&Key::flow(association,flow)?);Ok(())
    }
    pub fn close(&mut self) {self.closed=true;self.entries.clear()}
    /// Native flow retirement must also clear old owner/routing decisions.
    /// One bounded pass handles a capacity sweep, instead of rescanning for
    /// each expired ID. Cached ordinary packets never allocate this set.
    pub fn retire_associations(&mut self,generation:u64,now:u64,ids:&[u64])->Result<(),SessionError>{
        self.live(generation,now)?;
        if ids.len()>MAX_FLOWS||ids.iter().any(|id|*id==0){return Err(SessionError::Invalid)}
        if ids.is_empty(){return Ok(())}
        let retired:std::collections::HashSet<_>=ids.iter().copied().collect();
        self.entries.retain(|key,_|!retired.contains(&key.association));Ok(())
    }
}
