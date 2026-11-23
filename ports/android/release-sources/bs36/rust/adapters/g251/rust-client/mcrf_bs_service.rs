//! Private resolver socket registration, never an application-asserted UID.
use std::{sync::{Mutex,OnceLock},time::Instant,net::SocketAddr};
use mcrf_bs_routing_policy::service_flows::{Registry,Key,Error,Authorization,Candidate};
struct Internal {registry:Registry,next:u64}
struct Active {generation:u64,started:Instant,registry:Mutex<Registry>,internal:Mutex<Internal>}
static ACTIVE:OnceLock<Active>=OnceLock::new();
fn number(value:&str)->Result<u64,Error>{
    if value.is_empty()||value.starts_with('0')||!value.bytes().all(|b|b.is_ascii_digit()){return Err(Error::Invalid)}
    value.parse::<u64>().ok().filter(|v|*v<=i64::MAX as u64).ok_or(Error::Invalid)
}
pub fn configure(generation:u64,uid:u32)->Result<(),Error>{
    ACTIVE.set(Active{generation,started:Instant::now(),registry:Mutex::new(Registry::new(generation,uid)?),internal:Mutex::new(Internal{registry:Registry::scoped(generation,uid)?,next:0})}).map_err(|_|Error::Closed)
}
pub fn control(line:&str)->Result<bool,Error>{
    let add=line.starts_with("BS_SERVICE_ADD|");
    if !add&&!line.starts_with("BS_SERVICE_DEL|"){return Ok(false)}
    if line.len()>256||!line.is_ascii(){return Err(Error::Invalid)}
    let active=ACTIVE.get().ok_or(Error::Closed)?;
    let fields:Vec<_>=line.split('|').collect();
    if fields.len()!=if add{8}else{3}{return Err(Error::Invalid)}
    let generation=number(fields[1])?;let id=number(fields[2])?;
    if generation!=active.generation{return Err(Error::Stale)}
    let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;
    let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    if add {
        let protocol=match fields[3]{"6"=>6,"17"=>17,_=>return Err(Error::Invalid)};
        let local_port=u16::try_from(number(fields[4])?).map_err(|_|Error::Invalid)?;
        let ip=fields[5].parse().map_err(|_|Error::Invalid)?;
        let port=u16::try_from(number(fields[6])?).map_err(|_|Error::Invalid)?;
        let result=registry.register(generation,now,id,Key{protocol,local_port,peer:SocketAddr::new(ip,port)},number(fields[7])?);
        let answer=match result{Ok(())=>"OK",Err(Error::Capacity|Error::Duplicate)=>"DENIED",Err(error)=>return Err(error)};
        // Failed acknowledgement must fail the owning carrier. Main may not
        // connect on a timed-out/missing ACK; its ordered DEL retires the lease.
        crate::mcrf_bs_owner::control_output(&format!("MCRF_BS_SERVICE {generation} {id} {answer}")).map_err(|_|Error::Closed)?;
    }else{
        match registry.unregister(generation,now,id){Ok(())|Err(Error::Unknown)=>{},Err(error)=>return Err(error)}
    }
    Ok(true)
}
pub fn admitted(uid:u32,key:Key)->Result<bool,Error>{
    let Some(active)=ACTIVE.get() else{return Ok(false)};
    {let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    if registry.admitted(active.generation,now,Some(uid),key)?{return Ok(true)}}
    let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    internal.registry.admitted(active.generation,now,Some(uid),key)
}
pub fn candidate(key:Key)->Result<bool,Error>{
    let Some(active)=ACTIVE.get()else{return Ok(false)};
    {let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    if registry.candidate(active.generation,now,key)?{return Ok(true)}}
    let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    internal.registry.candidate(active.generation,now,key)
}
/// The UID must come from the authenticated Android owner broker. A tuple or
/// an old returned proof alone is never sufficient to admit an outbound.
pub fn authorize(uid:u32,key:Key)->Result<Option<Authorization>,Error>{
    let Some(active)=ACTIVE.get()else{return Ok(None)};
    {let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    if let Some(proof)=registry.authorize(active.generation,now,Some(uid),key)?{return Ok(Some(proof))}}
    let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    internal.registry.authorize(active.generation,now,Some(uid),key)
}
pub fn current(proof:&Authorization)->Result<bool,Error>{
    let Some(active)=ACTIVE.get()else{return Ok(false)};
    {let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    if registry.current(active.generation,now,proof)?{return Ok(true)}}
    let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    internal.registry.current(active.generation,now,proof)
}
/// Opaque candidate ID is not authorization. Comparing it across the Android
/// owner round trip prevents a reused tuple from inheriting an old request.
pub fn candidate_ticket(key:Key)->Result<Option<Candidate>,Error>{
    let Some(active)=ACTIVE.get()else{return Ok(None)};
    let control={let mut registry=active.registry.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;registry.ticket(active.generation,now,key)?};
    let native={let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;internal.registry.ticket(active.generation,now,key)?};
    match(control,native){(Some(_),Some(_))=>Err(Error::Duplicate),(Some(value),None)|(None,Some(value))=>Ok(Some(value)),(None,None)=>Ok(None)}
}
pub fn authorize_ticket(uid:u32,ticket:Candidate)->Result<Option<Authorization>,Error>{
    if candidate_ticket(ticket.key())?!=Some(ticket){return Ok(None)}
    Ok(authorize(uid,ticket.key())?.filter(|proof|proof.candidate()==ticket))
}
/// Native DNS and private Main-pipe tickets have separate monotonically
/// ordered namespaces. One cannot invalidate or overwrite the other's lease.
pub struct InternalLease {id:u64,generation:u64}
impl Drop for InternalLease{
    fn drop(&mut self){
        if let Some(active)=ACTIVE.get(){if active.generation==self.generation{
            if let Ok(mut internal)=active.internal.lock(){
                if let Ok(now)=u64::try_from(active.started.elapsed().as_millis()){let _=internal.registry.unregister(self.generation,now,self.id);}
            }
        }}
    }
}
pub fn register_internal(key:Key)->Result<InternalLease,Error>{
    let active=ACTIVE.get().ok_or(Error::Closed)?;
    let mut internal=active.internal.lock().map_err(|_|Error::Closed)?;
    let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|Error::Clock)?;
    let id=internal.next.checked_add(1).filter(|v|*v<=i64::MAX as u64).ok_or(Error::Closed)?;
    internal.registry.register_scoped(active.generation,now,id,key)?;internal.next=id;
    Ok(InternalLease{id,generation:active.generation})
}
#[cfg(test)]mod tests {
    use super::*;
    #[test]fn private_service_control_checks_generation_ack_owner_expiry_and_retirement(){
        crate::mcrf_bs_owner::configure(42).unwrap();configure(42,10001).unwrap();
        let key=Key{protocol:6,local_port:41234,peer:"192.0.2.42:853".parse().unwrap()};
        assert_eq!(control("unrelated"),Ok(false));
        assert_eq!(control("BS_SERVICE_ADD|41|1|6|41234|192.0.2.42|853|30000"),Err(Error::Stale));
        assert_eq!(control("BS_SERVICE_ADD|42|01|6|41234|192.0.2.42|853|30000"),Err(Error::Invalid));
        assert_eq!(control("BS_SERVICE_ADD|42|1|6|41234|192.0.2.42|853|30000"),Ok(true));
        assert_eq!(admitted(10001,key),Ok(true));assert_eq!(admitted(10002,key),Ok(false));
        let proof=authorize(10001,key).unwrap().unwrap();assert_eq!(authorize(10002,key),Ok(None));assert_eq!(current(&proof),Ok(true));
        let ticket=candidate_ticket(key).unwrap().unwrap();assert_eq!(authorize_ticket(10001,ticket),Ok(Some(proof)));assert_eq!(authorize_ticket(10002,ticket),Ok(None));
        assert_eq!(control("BS_SERVICE_DEL|42|1"),Ok(true));assert_eq!(admitted(10001,key),Ok(false));
        assert_eq!(current(&proof),Ok(false));
        assert_eq!(authorize_ticket(10001,ticket),Ok(None));
        assert_eq!(control("BS_SERVICE_DEL|42|1"),Ok(true));
        assert_eq!(control("BS_SERVICE_ADD|42|1|6|41234|192.0.2.42|853|30000"),Err(Error::Invalid));
        let internal=register_internal(key).unwrap();assert_eq!(admitted(10001,key),Ok(true));
        let native_proof=authorize(10001,key).unwrap().unwrap();assert_ne!(native_proof,proof);assert_eq!(current(&native_proof),Ok(true));
        assert_ne!(candidate_ticket(key).unwrap(),Some(ticket));assert_eq!(authorize_ticket(10001,ticket),Ok(None));
        // Native ID1 and private Main ID2 are independent namespaces.
        assert_eq!(control("BS_SERVICE_ADD|42|2|6|41235|192.0.2.42|853|30000"),Ok(true));
        drop(internal);assert_eq!(admitted(10001,key),Ok(false));
        assert_eq!(current(&native_proof),Ok(false));assert_eq!(current(&proof),Ok(false));
        // The same process's carrier translation accepts only that exact live
        // scoped authorization, including reverse traffic and zero-copy edits.
        let udp=Key{protocol:17,local_port:41236,peer:"192.0.2.42:9999".parse().unwrap()};let lease=register_internal(udp).unwrap();let proof=authorize(10001,udp).unwrap().unwrap();
        let cancel=tokio_util::sync::CancellationToken::new();let nat=crate::mcrf_bs_carrier_nat::CarrierNat::new(42,std::sync::Arc::new(||Ok(1)),std::sync::Arc::new(std::sync::Mutex::new(mcrf_bs_routing_policy::associations::Tracker::new(42,8).unwrap())),&["10.89.0.2".parse().unwrap()],&["192.0.2.7".parse().unwrap()],8,cancel.clone()).unwrap();
        let original:SocketAddr="10.89.0.2:41236".parse().unwrap();let mut bytes=vec![0u8;33];assert!(crate::direct_relay::craft_udp_packet_into(original,udp.peer,b"owned",&mut bytes));let pointer=bytes.as_ptr();nat.outbound(proof,&mut bytes).unwrap();assert_eq!(bytes.as_ptr(),pointer);
        let info=mcrf_bs_routing_policy::packet::PacketInfo::read(&bytes).unwrap();let translated=SocketAddr::new(info.source,info.ports.unwrap().0);assert_eq!(translated.ip(),"192.0.2.7".parse::<std::net::IpAddr>().unwrap());assert_ne!(translated.port(),original.port());
        let mut reply=vec![0u8;33];assert!(crate::direct_relay::craft_udp_packet_into(udp.peer,translated,b"owned",&mut reply));let pending=reply.clone();assert_eq!(nat.incoming(&mut reply),Ok(Some(crate::mcrf_bs_carrier_nat::Incoming::Service(proof))));let reflected=mcrf_bs_routing_policy::packet::PacketInfo::read(&reply).unwrap();assert_eq!(SocketAddr::new(reflected.destination,reflected.ports.unwrap().1),original);
        drop(lease);let mut retired=pending.clone();assert_eq!(nat.incoming(&mut retired),Ok(None));assert_eq!(retired,pending);nat.stop();assert!(cancel.is_cancelled());
    }
}
