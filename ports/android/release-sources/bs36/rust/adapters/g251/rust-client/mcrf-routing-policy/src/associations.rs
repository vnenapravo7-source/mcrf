//! Explicit native flow lifetime; a five-tuple alone is not an owner identity.
//! No sockets, payload copies, TCP termination or allocation on cached packets.
use std::{collections::HashMap,net::IpAddr};
use crate::packet::PacketInfo;

#[derive(Debug,PartialEq,Eq)]
pub enum AssociationError { Invalid, Closed, Stale, Clock, Capacity, Fragment }
#[derive(Hash,PartialEq,Eq)]
struct Key {source:IpAddr,destination:IpAddr,source_port:u16,destination_port:u16,protocol:u8}
struct Owner {uid:u32,packages:Vec<String>}
struct Entry {id:u64,last:u64,initial_sequence:Option<u32>,established:bool,pinned:bool,udp_owned:bool,raw_owned:bool,owner:Option<Owner>}
pub struct Association {
    pub id:u64,pub created:bool,pub retire_after_packet:bool,
    /// UDP has no SYN/socket-close signal in TUN. Application-sensitive policy
    /// must freshly confirm its owner, not assume that tuple reuse is safe.
    pub refresh_owner:bool,
    /// Caller must retire these IDs from decision/metadata caches too.
    pub retired:Vec<u64>,
}
pub struct Tracker {
    generation:u64,next:u64,last:u64,last_sweep:u64,capacity:usize,closed:bool,
    entries:HashMap<Key,Entry>,
}
impl Tracker {
    pub fn is_full(&self)->bool{self.entries.len()>=self.capacity}
    pub fn new(generation:u64,capacity:usize)->Result<Self,AssociationError>{
        if generation==0||generation>i64::MAX as u64||!(1..=8192).contains(&capacity){return Err(AssociationError::Invalid)}
        Ok(Self{generation,next:0,last:0,last_sweep:0,capacity,closed:false,entries:HashMap::with_capacity(capacity)})
    }
    fn check(&mut self,generation:u64,now:u64)->Result<(),AssociationError>{
        if self.closed{return Err(AssociationError::Closed)}
        if generation!=self.generation{return Err(AssociationError::Stale)}
        if now<self.last{return Err(AssociationError::Clock)}
        self.last=now;Ok(())
    }
    /// Nonmutating fast-path probe. A previously failed query must remain
    /// retryable; absence of an owner is never a cached negative observation.
    pub fn needs_owner(&self,generation:u64,now:u64,packet:&PacketInfo)->bool {
        if self.closed||generation!=self.generation||now<self.last{return true}
        let Some((source_port,destination_port))=packet.ports else{return false};
        if packet.fragment.is_some()||!matches!(packet.protocol,6|17){return false}
        if packet.protocol==17{return true}
        let key=Key{source:packet.source,destination:packet.destination,source_port,destination_port,protocol:packet.protocol};
        let Some(entry)=self.entries.get(&key) else{return true};
        entry.owner.is_none()||!entry.pinned&&!entry.raw_owned&&now-entry.last>=120_000||packet.tcp.is_some_and(|tcp|tcp.flags&2!=0&&
            (entry.established||entry.initial_sequence!=Some(tcp.sequence)))
    }
    pub fn observe(&mut self,generation:u64,now:u64,packet:&PacketInfo)->Result<Association,AssociationError>{
        self.check(generation,now)?;
        if packet.fragment.is_some(){return Err(AssociationError::Fragment)}
        let (source_port,destination_port)=packet.ports.ok_or(AssociationError::Invalid)?;
        if !matches!(packet.protocol,6|17)||source_port==0||destination_port==0||
            (packet.protocol==6)!=packet.tcp.is_some(){return Err(AssociationError::Invalid)}
        let key=Key{source:packet.source,destination:packet.destination,source_port,destination_port,protocol:packet.protocol};
        let syn=packet.tcp.filter(|tcp|tcp.flags&2!=0);
        let rst=packet.tcp.is_some_and(|tcp|tcp.flags&4!=0);
        let mut retired=Vec::new();
        if let Some(entry)=self.entries.get_mut(&key){
            let idle=if packet.protocol==17{30_000}else{120_000};
            let new_syn=syn.is_some_and(|tcp|entry.established||entry.initial_sequence!=Some(tcp.sequence));
            if (entry.pinned||entry.udp_owned||entry.raw_owned||now-entry.last<idle)&&!new_syn {
                entry.last=now;if packet.protocol==6&&syn.is_none(){entry.established=true}
                return Ok(Association{id:entry.id,created:false,retire_after_packet:rst,refresh_owner:packet.protocol==17,retired})
            }
            retired.push(entry.id);self.entries.remove(&key);
        }
        if self.entries.len()>=self.capacity {
            if now-self.last_sweep>=1000 {
                self.entries.retain(|key,entry|{
                    if !entry.pinned&&!entry.udp_owned&&!entry.raw_owned&&now-entry.last>=if key.protocol==17{30_000}else{120_000}{retired.push(entry.id);false}else{true}
                });self.last_sweep=now;
            }
            if self.entries.len()>=self.capacity{return Err(AssociationError::Capacity)}
        }
        let id=self.next.checked_add(1).filter(|id|*id<=i64::MAX as u64).ok_or(AssociationError::Closed)?;
        self.next=id;self.entries.insert(key,Entry{id,last:now,initial_sequence:syn.map(|tcp|tcp.sequence),established:packet.protocol==6&&syn.is_none(),pinned:false,udp_owned:false,raw_owned:false,owner:None});
        Ok(Association{id,created:true,retire_after_packet:rst,refresh_owner:true,retired})
    }
    /// Confirm only the still-current tuple/ticket. A changed UDP socket owner
    /// gets a new decision identity even before the tuple's idle deadline.
    pub fn confirm_owner(&mut self,generation:u64,now:u64,packet:&PacketInfo,id:u64,uid:u32,mut packages:Vec<String>)->Result<Association,AssociationError>{
        self.check(generation,now)?;
        if uid>i32::MAX as u32||packages.len()>64||packages.iter().map(|p|p.len()+3).sum::<usize>()+2>11000||
            packages.iter().any(|p|p.is_empty()||p.len()>255||!p.split('.').all(|part|{
                let mut bytes=part.bytes();bytes.next().is_some_and(|v|v.is_ascii_alphabetic()||v==b'_')&&bytes.all(|v|v.is_ascii_alphanumeric()||v==b'_')
            })){return Err(AssociationError::Invalid)}
        packages.sort_unstable();if packages.windows(2).any(|pair|pair[0]==pair[1]){return Err(AssociationError::Invalid)}
        let (source_port,destination_port)=packet.ports.ok_or(AssociationError::Invalid)?;
        if packet.fragment.is_some(){return Err(AssociationError::Fragment)}
        let key=Key{source:packet.source,destination:packet.destination,source_port,destination_port,protocol:packet.protocol};
        let entry=self.entries.get_mut(&key).ok_or(AssociationError::Stale)?;
        if entry.id!=id{return Err(AssociationError::Stale)}
        let changed=entry.owner.as_ref().is_some_and(|owner|owner.uid!=uid||owner.packages!=packages);
        let mut retired=Vec::new();
        if changed {
            let next=self.next.checked_add(1).filter(|id|*id<=i64::MAX as u64).ok_or(AssociationError::Closed)?;
            retired.push(entry.id);entry.id=next;entry.pinned=false;entry.udp_owned=false;entry.raw_owned=false;self.next=next;
        }
        entry.owner=Some(Owner{uid,packages});
        Ok(Association{id:entry.id,created:changed,retire_after_packet:packet.tcp.is_some_and(|tcp|tcp.flags&4!=0),refresh_owner:false,retired})
    }
    pub fn owner(&self,packet:&PacketInfo,id:u64)->Option<(u32,&[String])>{
        if self.closed||packet.fragment.is_some(){return None}
        let (source_port,destination_port)=packet.ports?;
        let key=Key{source:packet.source,destination:packet.destination,source_port,destination_port,protocol:packet.protocol};
        let entry=self.entries.get(&key)?;if entry.id!=id{return None}
        entry.owner.as_ref().map(|owner|(owner.uid,owner.packages.as_slice()))
    }
    /// An accepted TCP stream may remain idle beyond the raw-packet cache TTL.
    /// Pin only after its handshake AND a successful owner confirmation. A new
    /// SYN or changed owner still retires it; pinning never reserves a tuple.
    pub fn pin_tcp(&mut self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr,id:u64)->Result<(),AssociationError>{
        self.check(generation,now)?;
        if id==0||source.port()==0||destination.port()==0||source.is_ipv4()!=destination.is_ipv4(){return Err(AssociationError::Invalid)}
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        let entry=self.entries.get_mut(&key).ok_or(AssociationError::Stale)?;
        if entry.id!=id||now-entry.last>=120_000{return Err(AssociationError::Stale)}
        if !entry.established||entry.owner.is_none()||entry.pinned||entry.raw_owned{return Err(AssociationError::Invalid)}
        entry.pinned=true;Ok(())
    }
    /// A service SYN must be authenticated before it is forwarded. Unlike an
    /// accepted stream, this includes the pre-handshake and pinned phases.
    pub fn tcp_epoch(&self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr)->Result<Option<u64>,AssociationError>{
        if self.closed{return Err(AssociationError::Closed)}if generation!=self.generation{return Err(AssociationError::Stale)}if now<self.last{return Err(AssociationError::Clock)}
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        Ok(self.entries.get(&key).filter(|entry|entry.pinned||entry.raw_owned||now-entry.last<120_000).map(|entry|entry.id))
    }
    pub fn tcp_candidate(&self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr)->Result<Option<u64>,AssociationError>{
        if self.closed{return Err(AssociationError::Closed)}if generation!=self.generation{return Err(AssociationError::Stale)}if now<self.last{return Err(AssociationError::Clock)}
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        Ok(self.entries.get(&key).filter(|entry|entry.established&&!entry.pinned&&!entry.raw_owned&&now-entry.last<120_000).map(|entry|entry.id))
    }
    /// A raw flow owns its original SYN epoch without accepting a second TCP
    /// stream. Its RAII owner must retire it on close; a new SYN/UID still wins.
    pub fn own_raw_tcp(&mut self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr,id:u64)->Result<(),AssociationError>{
        self.check(generation,now)?;
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        let entry=self.entries.get_mut(&key).ok_or(AssociationError::Stale)?;
        if entry.id!=id||entry.owner.is_none()||entry.established||entry.initial_sequence.is_none()||entry.pinned||entry.raw_owned||now-entry.last>=120_000{return Err(AssociationError::Stale)}
        entry.raw_owned=true;Ok(())
    }
    pub fn tcp_stream_identity(&self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr)->Result<Option<u64>,AssociationError>{
        if self.closed{return Err(AssociationError::Closed)}if generation!=self.generation{return Err(AssociationError::Stale)}if now<self.last{return Err(AssociationError::Clock)}
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        Ok(self.entries.get(&key).filter(|entry|entry.pinned&&entry.owner.is_some()).map(|entry|entry.id))
    }
    /// A live UDP socket lease has its own runtime idle deadline. It must not
    /// inherit a TCP pin or expire midway through authenticated DNS/owner IO.
    pub fn own_udp(&mut self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr,id:u64)->Result<(),AssociationError>{
        self.check(generation,now)?;
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:17};
        let entry=self.entries.get_mut(&key).ok_or(AssociationError::Stale)?;
        if id==0||entry.id!=id||entry.owner.is_none()||entry.pinned||entry.udp_owned||entry.raw_owned||now-entry.last>=30_000{return Err(AssociationError::Stale)}
        entry.udp_owned=true;Ok(())
    }
    /// UDP never inherits a TCP pin. Unowned metadata retains its bounded 30s
    /// cache lifetime; an owned epoch is retired with the last socket callback.
    pub fn udp_identity(&self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr)->Result<Option<u64>,AssociationError>{
        if self.closed{return Err(AssociationError::Closed)}if generation!=self.generation{return Err(AssociationError::Stale)}if now<self.last{return Err(AssociationError::Clock)}
        if source.port()==0||destination.port()==0||source.is_ipv4()!=destination.is_ipv4(){return Err(AssociationError::Invalid)}
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:17};
        Ok(self.entries.get(&key).filter(|entry|entry.owner.is_some()&&(entry.udp_owned||now-entry.last<30_000)).map(|entry|entry.id))
    }
    pub fn unpin_tcp(&mut self,generation:u64,now:u64,source:std::net::SocketAddr,destination:std::net::SocketAddr,id:u64)->Result<(),AssociationError>{
        self.check(generation,now)?;
        let key=Key{source:source.ip(),destination:destination.ip(),source_port:source.port(),destination_port:destination.port(),protocol:6};
        let entry=self.entries.get_mut(&key).ok_or(AssociationError::Stale)?;
        if id==0||entry.id!=id||!entry.pinned{return Err(AssociationError::Stale)}
        entry.pinned=false;entry.last=now;Ok(())
    }
    pub fn retire(&mut self,generation:u64,now:u64,id:u64)->Result<(),AssociationError>{
        self.check(generation,now)?;if id==0{return Err(AssociationError::Invalid)}
        self.entries.retain(|_,entry|entry.id!=id);Ok(())
    }
    pub fn close(&mut self){self.closed=true;self.entries.clear()}
}
