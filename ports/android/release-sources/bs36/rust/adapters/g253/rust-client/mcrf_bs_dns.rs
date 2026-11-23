//! Source-only owned DNS listener reflection and immutable client observations.
//! No resolver choice or production activation occurs in this module.
use std::{net::IpAddr,sync::{Mutex,OnceLock},time::Instant};
use mcrf_bs_routing_policy::dns_reflector::Reflector;
struct State{reflector:Reflector,highest:u64}
struct Active{generation:u64,started:Instant,state:Mutex<State>}
static ACTIVE:OnceLock<Active>=OnceLock::new();
pub fn configure(generation:u64,sources:&[IpAddr],local4:Option<IpAddr>,local6:Option<IpAddr>,port:u16)->Result<(),()>{
    if generation==0||generation>i64::MAX as u64{return Err(())}
    let reflector=Reflector::new(sources,local4,local6,port).map_err(|_|())?;
    ACTIVE.set(Active{generation,started:Instant::now(),state:Mutex::new(State{reflector,highest:0})}).map_err(|_|())
}
/// Keep ordinary data out of the DNS mutex/table maintenance. Policy-approved
/// public-resolver packets enter hijack explicitly; listener replies always
/// target the reserved synthetic peer, including their later IP fragments.
pub fn candidate(packet:&[u8])->bool{
    if ACTIVE.get().is_none(){return false}
    if packet.len()>=20&&packet[0]>>4==4&&packet[16..20]==[198,19,255,53]{return true}
    if packet.len()>=40&&packet[0]>>4==6&&packet[24..40]==[0xfd,0xfe,0xdc,0xba,0x98,0x76,0xdd,0x54,0,0,0,0,0,0,0,0x53]{return true}
    false
}
pub fn reflect(packet:&[u8])->Result<Option<Vec<Vec<u8>>>,()>{
    if !candidate(packet){return Ok(None)}
    let Some(active)=ACTIVE.get()else{return Ok(None)};
    let mut state=active.state.lock().map_err(|_|())?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|())?;
    state.reflector.reflect(now,packet).map_err(|_|())
}
pub fn hijack(packet:&[u8])->Result<Option<Vec<Vec<u8>>>,()>{
    let active=ACTIVE.get().ok_or(())?;let mut state=active.state.lock().map_err(|_|())?;
    let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|())?;
    state.reflector.reflect_hijacked(now,packet).map_err(|_|())
}
pub fn client(generation:u64,protocol:u8,port:u16)->Result<Option<mcrf_bs_routing_policy::dns_reflector::Client>,()>{
    let active=ACTIVE.get().ok_or(())?;if generation!=active.generation||!matches!(protocol,6|17)||port==0{return Err(())}
    let mut state=active.state.lock().map_err(|_|())?;let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|())?;
    state.reflector.client(now,protocol,port).map_err(|_|())
}
fn number(value:&str)->Result<u64,()>{
    if value.is_empty()||value.starts_with('0')||!value.bytes().all(|v|v.is_ascii_digit()){return Err(())}
    value.parse::<u64>().ok().filter(|v|*v<=i64::MAX as u64).ok_or(())
}
pub fn control(line:&str)->Result<bool,()>{
    let Some(body)=line.strip_prefix("BS_DNS_CLIENT|")else{return Ok(false)};
    if line.len()>128||!line.is_ascii(){return Err(())}
    let fields:Vec<_>=body.split('|').collect();if fields.len()!=4{return Err(())}
    let generation=number(fields[0])?;let id=number(fields[1])?;
    let protocol=match fields[2]{"6"=>6,"17"=>17,_=>return Err(())};let port=u16::try_from(number(fields[3])?).map_err(|_|())?;
    let active=ACTIVE.get().ok_or(())?;if active.generation!=generation{return Err(())}
    let mut state=active.state.lock().map_err(|_|())?;if id<=state.highest{return Err(())}state.highest=id;
    let now=u64::try_from(active.started.elapsed().as_millis()).map_err(|_|())?;
    let response=if let Some(client)=state.reflector.client(now,protocol,port).map_err(|_|())?{
        format!("MCRF_BS_DNS_CLIENT {generation} {id} {} {} {} {}",client.protocol,client.source,client.port,client.destination)
    }else{format!("MCRF_BS_DNS_CLIENT {generation} {id} NONE")};
    crate::mcrf_bs_owner::control_output(&response).map_err(|_|())?;Ok(true)
}
#[cfg(test)]mod tests{
    use super::*;
    #[test]fn mapping_control_is_generation_scoped_monotonic_and_not_ownership_authority(){
        crate::mcrf_bs_owner::configure(42).unwrap();configure(42,&["10.89.0.2".parse().unwrap()],Some("10.89.0.2".parse().unwrap()),None,48053).unwrap();
        let mut packet=vec![0;32];packet[0]=0x45;packet[2..4].copy_from_slice(&32u16.to_be_bytes());packet[8]=64;packet[9]=17;
        packet[12..16].copy_from_slice(&[10,89,0,2]);packet[16..20].copy_from_slice(&[198,19,255,53]);packet[20..22].copy_from_slice(&41234u16.to_be_bytes());packet[22..24].copy_from_slice(&53u16.to_be_bytes());packet[24..26].copy_from_slice(&12u16.to_be_bytes());
        let out=reflect(&packet).unwrap().unwrap().remove(0);let port=mcrf_bs_routing_policy::packet::PacketInfo::read(&out).unwrap().ports.unwrap().0;
        assert_eq!(control("unrelated"),Ok(false));assert_eq!(control("BS_DNS_CLIENT|41|1|17|41234"),Err(()));
        assert_eq!(control(&format!("BS_DNS_CLIENT|42|1|17|{port}")),Ok(true));
        assert_eq!(control(&format!("BS_DNS_CLIENT|42|1|17|{port}")),Err(()));
        assert_eq!(control("BS_DNS_CLIENT|42|2|6|41234"),Ok(true));
        assert_eq!(control("BS_DNS_CLIENT|42|03|17|41234"),Err(()));
    }
}
