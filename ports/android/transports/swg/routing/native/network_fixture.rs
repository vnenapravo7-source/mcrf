//! Owned executable fixture only. No provider, DNS lookup or real profile.
#[path="mcrf_bs_network.rs"]mod binding;
fn main(){
    use std::{io::{Read,Write},time::Duration,net::{SocketAddr,TcpStream}};
    let args:Vec<_>=std::env::args().collect();
    assert_eq!(args.len(),4);assert!(matches!(args[1].as_str(),"bound"|"default"));
    let peer:SocketAddr=args[3].parse().expect("numeric owned peer");
    assert!(peer.ip()=="10.0.2.2".parse::<std::net::IpAddr>().unwrap(),"only owned emulator host loopback");
    if args[1]=="bound"{binding::bind_network(binding::handle(&args[2]).expect("network handle")).expect("physical network binding")}
    else{assert_eq!(args[2],"0")}
    let token=b"MCRF-owned-bind";
    match TcpStream::connect_timeout(&peer,Duration::from_millis(900)){
        Ok(mut stream)=>{
            stream.set_read_timeout(Some(Duration::from_secs(2))).unwrap();stream.set_write_timeout(Some(Duration::from_secs(2))).unwrap();
            stream.write_all(token).unwrap();let mut reply=[0u8;15];stream.read_exact(&mut reply).unwrap();assert_eq!(&reply,token);
            println!("OWNED_NETWORK_ECHO_OK");
        },
        Err(_) if args[1]=="default"=>println!("OWNED_DEFAULT_CONNECT_FAILED"),
        Err(error)=>panic!("owned bound connection failed: {:?}",error.kind()),
    }
}
