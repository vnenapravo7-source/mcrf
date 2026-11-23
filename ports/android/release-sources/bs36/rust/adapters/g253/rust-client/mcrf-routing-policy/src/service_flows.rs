//! Explicit leases for resolver sockets owned by Main. Registration must be
//! acknowledged over the private carrier pipe BEFORE connect/send. A matching
//! tuple alone grants nothing: the caller supplies a fresh kernel owner UID.
use std::{collections::HashMap,net::SocketAddr};
#[derive(Clone,Copy,Debug,PartialEq,Eq,Hash)]
pub struct Key {pub protocol:u8,pub local_port:u16,pub peer:SocketAddr}
/// Created only after a fresh kernel UID was accepted by this registry. Keeping
/// a copy is not permission: the exact lease must still be current on use.
#[derive(Clone,Copy,Debug,PartialEq,Eq,Hash)]
pub struct Authorization{generation:u64,uid:u32,id:u64,key:Key,scoped:bool}
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub struct Candidate{generation:u64,id:u64,key:Key,scoped:bool}
impl Candidate{pub fn key(&self)->Key{self.key}}
impl Authorization{pub fn generation(&self)->u64{self.generation}pub fn key(&self)->Key{self.key}pub fn candidate(&self)->Candidate{Candidate{generation:self.generation,id:self.id,key:self.key,scoped:self.scoped}}}
#[derive(Debug,PartialEq,Eq)]
pub enum Error {Invalid,Stale,Closed,Clock,Capacity,Duplicate,Unknown}
struct Lease {key:Key,expires:Option<u64>}
pub struct Registry {generation:u64,uid:u32,last:u64,last_id:u64,closed:bool,scoped:bool,capacity:usize,next_expiry:Option<u64>,leases:HashMap<u64,Lease>,keys:HashMap<Key,u64>}
impl Registry {
    pub fn new(generation:u64,uid:u32)->Result<Self,Error>{
        if generation==0||generation>i64::MAX as u64||uid>i32::MAX as u32{return Err(Error::Invalid)}
        Ok(Self{generation,uid,last:0,last_id:0,closed:false,scoped:false,capacity:64,next_expiry:None,leases:HashMap::new(),keys:HashMap::new()})
    }
    /// Same-process sockets have an owning RAII guard and do not depend on a
    /// timed private-pipe registration. Only this explicitly separate registry
    /// can admit scoped entries; Main's untrusted control API remains 64/30s.
    pub fn scoped(generation:u64,uid:u32)->Result<Self,Error>{let mut value=Self::new(generation,uid)?;value.scoped=true;value.capacity=8192;Ok(value)}
    pub fn register_scoped(&mut self,generation:u64,now:u64,id:u64,key:Key)->Result<(),Error>{
        self.check(generation,now)?;if !self.scoped{return Err(Error::Invalid)}self.insert(id,key,None)
    }
    fn check(&mut self,generation:u64,now:u64)->Result<(),Error>{
        if self.closed{return Err(Error::Closed)}
        if generation!=self.generation{return Err(Error::Stale)}
        if now<self.last{return Err(Error::Clock)}self.last=now;
        // Most packets only need one exact-key lookup. Never scan every
        // native socket on every data packet; owned scopes have no deadline.
        if self.next_expiry.is_some_and(|expires|now>=expires){
            self.leases.retain(|_,lease|lease.expires.is_none_or(|expires|expires>now));
            self.keys.retain(|_,id|self.leases.contains_key(id));
            self.next_expiry=self.leases.values().filter_map(|lease|lease.expires).min();
        }Ok(())
    }
    pub fn register(&mut self,generation:u64,now:u64,id:u64,key:Key,lifetime:u64)->Result<(),Error>{
        self.check(generation,now)?;
        if !(1..=30_000).contains(&lifetime){return Err(Error::Invalid)}
        let expires=now.checked_add(lifetime).ok_or(Error::Invalid)?;self.insert(id,key,Some(expires))
    }
    fn insert(&mut self,id:u64,key:Key,expires:Option<u64>)->Result<(),Error>{
        if id==0||id>i64::MAX as u64||id<=self.last_id||!matches!(key.protocol,6|17)||key.local_port==0||key.peer.port()==0||
            key.peer.ip().is_unspecified()||key.peer.ip().is_multicast(){return Err(Error::Invalid)}
        if self.keys.contains_key(&key){return Err(Error::Duplicate)}
        if self.leases.len()>=self.capacity{return Err(Error::Capacity)}
        if let Some(expires)=expires{self.next_expiry=Some(self.next_expiry.map_or(expires,|old|old.min(expires)))}
        self.last_id=id;self.keys.insert(key,id);self.leases.insert(id,Lease{key,expires});Ok(())
    }
    pub fn unregister(&mut self,generation:u64,now:u64,id:u64)->Result<(),Error>{
        self.check(generation,now)?;
        let lease=self.leases.remove(&id).ok_or(Error::Unknown)?;self.keys.remove(&lease.key);Ok(())
    }
    pub fn admitted(&mut self,generation:u64,now:u64,uid:Option<u32>,key:Key)->Result<bool,Error>{
        self.check(generation,now)?;Ok(uid==Some(self.uid)&&self.keys.contains_key(&key))
    }
    pub fn authorize(&mut self,generation:u64,now:u64,uid:Option<u32>,key:Key)->Result<Option<Authorization>,Error>{
        self.check(generation,now)?;if uid!=Some(self.uid){return Ok(None)}
        Ok(self.keys.get(&key).copied().map(|id|Authorization{generation,uid:self.uid,id,key,scoped:self.scoped}))
    }
    pub fn current(&mut self,generation:u64,now:u64,proof:&Authorization)->Result<bool,Error>{
        self.check(generation,now)?;
        Ok(proof.generation==generation&&proof.uid==self.uid&&proof.scoped==self.scoped&&self.keys.get(&proof.key)==Some(&proof.id))
    }
    pub fn ticket(&mut self,generation:u64,now:u64,key:Key)->Result<Option<Candidate>,Error>{
        self.check(generation,now)?;Ok(self.keys.get(&key).copied().map(|id|Candidate{generation,id,key,scoped:self.scoped}))
    }
    /// A candidate only requests a real owner lookup. It grants no permission.
    pub fn candidate(&mut self,generation:u64,now:u64,key:Key)->Result<bool,Error>{
        self.check(generation,now)?;Ok(self.keys.contains_key(&key))
    }
    pub fn close(&mut self){self.closed=true;self.leases.clear();self.keys.clear();self.next_expiry=None}
}
#[cfg(test)]mod tests {
    use super::*;
    fn key()->Key{Key{protocol:6,local_port:41234,peer:"192.0.2.42:853".parse().unwrap()}}
    #[test]fn resolver_tuple_requires_fresh_main_uid_and_exact_protocol_port_peer(){
        let mut r=Registry::new(42,10001).unwrap();r.register(42,0,1,key(),30000).unwrap();
        assert!(r.admitted(42,0,Some(10001),key()).unwrap());
        for uid in [None,Some(10002)]{assert!(!r.admitted(42,0,uid,key()).unwrap())}
        for altered in [Key{protocol:17,..key()},Key{local_port:41235,..key()},Key{peer:"192.0.2.43:853".parse().unwrap(),..key()}]{assert!(!r.admitted(42,0,Some(10001),altered).unwrap())}
        r.unregister(42,1,1).unwrap();assert!(!r.admitted(42,1,Some(10001),key()).unwrap());
    }
    #[test]fn expiry_replay_stale_session_and_close_never_grant_a_route(){
        let mut r=Registry::new(42,10001).unwrap();r.register(42,0,1,key(),5).unwrap();
        assert!(!r.admitted(42,5,Some(10001),key()).unwrap());
        assert_eq!(r.register(42,5,1,key(),5),Err(Error::Invalid));
        r.register(42,5,2,key(),5).unwrap();assert_eq!(r.unregister(41,5,2),Err(Error::Stale));
        assert!(r.admitted(42,5,Some(10001),key()).unwrap());assert_eq!(r.admitted(42,4,Some(10001),key()),Err(Error::Clock));
        r.close();assert_eq!(r.admitted(42,5,Some(10001),key()),Err(Error::Closed));
    }
    #[test]fn bounded_registry_never_evicts_live_resolver_leases(){
        let mut r=Registry::new(42,10001).unwrap();
        for id in 1..=64{r.register(42,0,id,Key{local_port:41000+id as u16,..key()},30000).unwrap()}
        assert_eq!(r.register(42,0,65,key(),30000),Err(Error::Capacity));
        assert!(r.admitted(42,0,Some(10001),Key{local_port:41001,..key()}).unwrap());
        r.unregister(42,0,1).unwrap();r.register(42,0,65,key(),30000).unwrap();
        assert_eq!(r.register(42,0,66,key(),30000),Err(Error::Duplicate));
    }
    #[test]fn scoped_native_socket_survives_idle_time_until_guard_unregisters_but_never_authorizes_other_uid(){
        let mut r=Registry::scoped(42,10001).unwrap();r.register_scoped(42,0,1,key()).unwrap();
        for now in [30_000,120_000,3_600_000]{assert!(r.candidate(42,now,key()).unwrap());assert!(r.admitted(42,now,Some(10001),key()).unwrap());assert!(!r.admitted(42,now,Some(10002),key()).unwrap())}
        r.unregister(42,3_600_001,1).unwrap();assert!(!r.admitted(42,3_600_001,Some(10001),key()).unwrap());assert_eq!(r.register_scoped(42,3_600_001,1,key()),Err(Error::Invalid));
        let mut external=Registry::new(42,10001).unwrap();assert_eq!(external.register_scoped(42,0,1,key()),Err(Error::Invalid));assert_eq!(external.register(42,0,1,key(),30_001),Err(Error::Invalid));
    }
    #[test]fn scoped_capacity_and_namespace_are_bounded_without_expiring_live_owned_fds(){
        let mut r=Registry::scoped(42,10001).unwrap();assert_eq!(r.capacity,8192);r.capacity=2;
        r.register_scoped(42,0,1,key()).unwrap();r.register_scoped(42,0,2,Key{local_port:41235,..key()}).unwrap();assert_eq!(r.register_scoped(42,600_000,3,Key{local_port:41236,..key()}),Err(Error::Capacity));assert!(r.admitted(42,600_000,Some(10001),key()).unwrap());r.unregister(42,600_000,1).unwrap();r.register_scoped(42,600_000,3,Key{local_port:41236,..key()}).unwrap();
    }
    #[test]fn opaque_candidate_changes_on_lease_replacement_and_is_never_owner_permission(){
        let mut r=Registry::scoped(42,10001).unwrap();r.register_scoped(42,0,1,key()).unwrap();let ticket=r.ticket(42,0,key()).unwrap().unwrap();assert!(r.authorize(42,0,Some(10002),key()).unwrap().is_none());let proof=r.authorize(42,0,Some(10001),key()).unwrap().unwrap();assert_eq!(proof.candidate(),ticket);
        r.unregister(42,1,1).unwrap();r.register_scoped(42,1,2,key()).unwrap();assert_ne!(r.ticket(42,1,key()).unwrap(),Some(ticket));assert!(!r.current(42,1,&proof).unwrap());assert_ne!(r.authorize(42,1,Some(10001),key()).unwrap().unwrap().candidate(),ticket);
    }

}
