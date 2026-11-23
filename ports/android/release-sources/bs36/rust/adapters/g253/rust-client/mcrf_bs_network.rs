//! Android-only physical binding for the owned Rust process, not the Java host.
//! Uses the API23 NDK entry point; never imports the API31 getter on API29/30.
use std::{fmt,sync::OnceLock};
static BOUND:OnceLock<u64>=OnceLock::new();
#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Failure { Invalid, Required, Unexpected, Ownership, Unsupported, Bind(i32), AlreadyBound }
impl fmt::Display for Failure {
    fn fmt(&self,f:&mut fmt::Formatter<'_>)->fmt::Result {
        match self {Self::Bind(errno)=>write!(f,"BS_UNDERLAY_BIND_FAILED errno={errno}"),other=>write!(f,"BS_UNDERLAY_{other:?}")}
    }
}
impl std::error::Error for Failure {}
pub fn handle(value:&str)->Result<u64,Failure>{
    if value.is_empty()||value.starts_with('0')||value.len()>20||!value.bytes().all(|v|v.is_ascii_digit()){return Err(Failure::Invalid)}
    value.parse().map_err(|_|Failure::Invalid)
}
fn choice(value:Option<&str>,policy:bool,owned:bool)->Result<Option<u64>,Failure>{
    match (value,policy,owned){
        (None,false,_)=>Ok(None),(Some(_),false,_)=>Err(Failure::Unexpected),
        (None,true,_)=>Err(Failure::Required),(Some(_),true,false)=>Err(Failure::Ownership),
        (Some(value),true,true)=>handle(value).map(Some),
    }
}
#[cfg(target_os="android")]
#[link(name="android")]
unsafe extern "C" {
    fn android_setprocnetwork(network:u64)->i32;
    fn android_setsocknetwork(network:u64,fd:i32)->i32;
}
/// Override only this owned socket, before connect/send. In particular a DNS
/// socket may use the owned VPN while ordinary carrier sockets stay physical.
/// Caller retains lifetime/ownership of fd; this never closes or duplicates it.
pub fn bind_socket(network:u64,fd:i32)->Result<(),Failure>{
    if network==0||fd<0{return Err(Failure::Invalid)}
    #[cfg(target_os="android")]{
        if unsafe{android_setsocknetwork(network,fd)}!=0{return Err(Failure::Bind(std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)))}
        Ok(())
    }
    #[cfg(not(target_os="android"))]{Err(Failure::Unsupported)}
}
pub fn bind_network(network:u64)->Result<(),Failure>{
    if network==0{return Err(Failure::Invalid)}
    #[cfg(target_os="android")]{
        if unsafe{android_setprocnetwork(network)}!=0{return Err(Failure::Bind(std::io::Error::last_os_error().raw_os_error().unwrap_or(-1)))}
        Ok(())
    }
    #[cfg(not(target_os="android"))]{let _=network;Err(Failure::Unsupported)}
}
pub fn configure(owned:bool)->Result<(),Failure>{
    let value=std::env::var_os("MCRF_BS_UNDERLAY_NETWORK").map(|s|s.into_string().map_err(|_|Failure::Invalid)).transpose()?;
    let legacy=std::env::var_os("MCRF_BS_ROUTING_POLICY_SHA256").is_some();
    let native=std::env::var_os("MCRF_BS_ROUTING_SESSION_SHA256").is_some();
    if legacy&&native{return Err(Failure::Unexpected)}
    let Some(network)=choice(value.as_deref(),legacy||native,owned)? else{return Ok(())};
    if BOUND.get().is_some(){return Err(Failure::AlreadyBound)}
    bind_network(network)?;BOUND.set(network).map_err(|_|Failure::AlreadyBound)
}
pub fn is_bound()->bool{BOUND.get().is_some()}
pub fn physical_network()->Option<u64>{BOUND.get().copied()}

#[cfg(test)]mod tests {
    use super::*;
    #[test]fn an_ordinary_client_never_gets_a_process_binding(){assert_eq!(choice(None,false,false),Ok(None));assert_eq!(choice(Some("123"),false,true),Err(Failure::Unexpected));}
    #[test]fn native_policy_requires_an_owned_tun_and_an_explicit_nonzero_handle(){
        assert_eq!(choice(None,true,true),Err(Failure::Required));assert_eq!(choice(Some("123"),true,false),Err(Failure::Ownership));
        assert_eq!(choice(Some("123"),true,true),Ok(Some(123)));
        for value in ["","0","01","-1","+1","0x123","123 ","18446744073709551616"]{assert_eq!(handle(value),Err(Failure::Invalid))}
        assert_eq!(handle("18446744073709551615"),Ok(u64::MAX));assert_eq!(bind_network(0),Err(Failure::Invalid));
    }
}
