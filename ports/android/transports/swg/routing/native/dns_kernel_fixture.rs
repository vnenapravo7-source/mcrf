//! Owned Android TUN/kernel reflection test, never production or a provider.
use std::{os::fd::{FromRawFd,AsRawFd},io::{Read,Write},time::{Duration,Instant}};
use mcrf_bs_routing_policy::dns_reflector::Reflector;
#[path="mcrf_bs_network.rs"]mod network;
fn socket_network_probe(args:&[String]){
    use std::net::{SocketAddr,TcpStream};
    assert_eq!(args.len(),5);let physical=network::handle(&args[2]).unwrap();let vpn=network::handle(&args[3]).unwrap();assert_ne!(physical,vpn);
    let peer:SocketAddr=args[4].parse().unwrap();assert_eq!(peer.ip(),"10.0.2.2".parse::<std::net::IpAddr>().unwrap());
    network::bind_network(physical).unwrap();let token=b"MCRF-owned-bind";
    let mut plain=TcpStream::connect_timeout(&peer,Duration::from_millis(900)).unwrap();plain.set_read_timeout(Some(Duration::from_secs(2))).unwrap();plain.set_write_timeout(Some(Duration::from_secs(2))).unwrap();plain.write_all(token).unwrap();let mut echoed=[0u8;15];plain.read_exact(&mut echoed).unwrap();assert_eq!(&echoed,token);drop(plain);
    let fd=unsafe{libc::socket(libc::AF_INET,libc::SOCK_STREAM|libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK,0)};assert!(fd>=0);
    let socket=unsafe{TcpStream::from_raw_fd(fd)};network::bind_socket(vpn,socket.as_raw_fd()).unwrap();
    let mut address:libc::sockaddr_in=unsafe{std::mem::zeroed()};address.sin_family=libc::AF_INET as _;address.sin_port=peer.port().to_be();address.sin_addr.s_addr=u32::from_ne_bytes([10,0,2,2]);
    let connected=unsafe{libc::connect(fd,(&address as *const libc::sockaddr_in).cast(),std::mem::size_of_val(&address)as _)};
    assert_eq!(connected,-1);assert_eq!(std::io::Error::last_os_error().raw_os_error(),Some(libc::EINPROGRESS));
    let mut event=libc::pollfd{fd,events:libc::POLLOUT,revents:0};assert_eq!(unsafe{libc::poll(&mut event,1,900)},0,"VPN socket must await the unforwarded owned TUN");
    println!("OWNED_CHILD_PHYSICAL_AND_SOCKET_VPN_OK");
}
fn main(){
    let args:Vec<_>=std::env::args().collect();
    if args.get(1).map(String::as_str)==Some("network-probe"){socket_network_probe(&args);return}
    assert_eq!(args.len(),4);
    assert!(args[1].starts_with("mcrf-dns-kernel-")&&args[1].len()<90&&args[1].is_ascii());
    let parent=args[2].parse::<i32>().unwrap();assert!(parent>0);
    let port=args[3].parse::<u16>().unwrap();assert!(port>=1024);
    let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;
    for (target,byte) in address.sun_path[1..].iter_mut().zip(args[1].bytes()){*target=byte as _}
    let fd=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_CLOEXEC,0)};assert!(fd>=0);
    let mut stream=unsafe{std::os::unix::net::UnixStream::from_raw_fd(fd)};
    let offset=std::mem::offset_of!(libc::sockaddr_un,sun_path);
    assert_eq!(unsafe{libc::connect(fd,(&address as *const libc::sockaddr_un).cast(),(offset+1+args[1].len())as _)},0);
    stream.set_read_timeout(Some(Duration::from_secs(4))).unwrap();stream.set_write_timeout(Some(Duration::from_secs(4))).unwrap();
    let mut peer:libc::ucred=unsafe{std::mem::zeroed()};let mut size=std::mem::size_of::<libc::ucred>()as libc::socklen_t;
    assert_eq!(unsafe{libc::getsockopt(fd,libc::SOL_SOCKET,libc::SO_PEERCRED,(&mut peer as *mut libc::ucred).cast(),&mut size)},0);
    assert_eq!(peer.uid,unsafe{libc::getuid()});assert_eq!(peer.pid,parent);
    let mut byte=[0u8];let mut io=libc::iovec{iov_base:byte.as_mut_ptr().cast(),iov_len:1};
    // Vec<usize> guarantees alignment for cmsghdr on every supported ABI.
    let mut control=[0usize;16];let mut message:libc::msghdr=unsafe{std::mem::zeroed()};message.msg_iov=&mut io;message.msg_iovlen=1;message.msg_control=control.as_mut_ptr().cast();message.msg_controllen=std::mem::size_of_val(&control);
    assert_eq!(unsafe{libc::recvmsg(fd,&mut message,libc::MSG_CMSG_CLOEXEC)},1);assert_eq!(byte[0],1);assert_eq!(message.msg_flags&libc::MSG_CTRUNC,0);
    let mut received=Vec::new();let mut item=unsafe{libc::CMSG_FIRSTHDR(&message)};
    while !item.is_null(){unsafe{
        assert_eq!((*item).cmsg_level,libc::SOL_SOCKET);assert_eq!((*item).cmsg_type,libc::SCM_RIGHTS);
        let bytes=(*item).cmsg_len as usize-libc::CMSG_LEN(0)as usize;assert_eq!(bytes%std::mem::size_of::<i32>(),0);
        for n in 0..bytes/std::mem::size_of::<i32>(){received.push(std::fs::File::from_raw_fd(*(libc::CMSG_DATA(item)as *const i32).add(n)))}
        item=libc::CMSG_NXTHDR(&message,item);
    }}assert_eq!(received.len(),1);let mut tun=received.remove(0);
    let flags=unsafe{libc::fcntl(tun.as_raw_fd(),libc::F_GETFL)};assert!(flags>=0);assert_eq!(unsafe{libc::fcntl(tun.as_raw_fd(),libc::F_SETFL,flags|libc::O_NONBLOCK)},0);
    let mut reflector=Reflector::new(&["10.89.0.2".parse().unwrap(),"fd00::2".parse().unwrap()],Some("10.89.0.2".parse().unwrap()),Some("fd00::2".parse().unwrap()),port).unwrap();
    stream.write_all(&[1]).unwrap();println!("OWNED_DNS_REFLECTOR_READY");std::io::stdout().flush().unwrap();
    let started=Instant::now();let mut buffer=[0u8;65535];let mut reflected=0;
    while started.elapsed()<Duration::from_secs(15){
        match tun.read(&mut buffer){
            Ok(0)=>break,
            Ok(count)=>if let Some(packets)=reflector.reflect(started.elapsed().as_millis()as u64,&buffer[..count]).unwrap(){
                for packet in packets{let deadline=Instant::now()+Duration::from_secs(1);loop{match tun.write(&packet){Ok(count)=>{assert_eq!(count,packet.len());reflected+=1;break},Err(error)if error.kind()==std::io::ErrorKind::WouldBlock&&Instant::now()<deadline=>std::thread::sleep(Duration::from_millis(1)),other=>panic!("owned TUN write: {other:?}")}}}
            },
            Err(error)if error.kind()==std::io::ErrorKind::WouldBlock=>std::thread::sleep(Duration::from_millis(1)),
            Err(error)=>panic!("owned TUN read: {:?}",error.kind()),
        }
    }
    reflector.close();assert!(reflected>=4);println!("OWNED_DNS_REFLECTOR_DONE packets={reflected}");
}
