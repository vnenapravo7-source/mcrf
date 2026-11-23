//! Kernel UDP ingress evidence for a DNS listener. The original caller is still
//! verified separately; ancillary ifindex prevents physical-interface packets
//! from impersonating packets injected through the owned VPN TUN.
use std::{net::{IpAddr,Ipv4Addr,Ipv6Addr,SocketAddr,UdpSocket},os::fd::{AsRawFd,BorrowedFd},io};
pub struct Datagram{pub peer:SocketAddr,pub destination:IpAddr,pub interface:u32,pub bytes:Vec<u8>}
impl Datagram{
 pub fn from_owned_tun(&self,index:u32,local:IpAddr)->bool{
  let alias:IpAddr=if local.is_ipv4(){"198.19.255.53"}else{"fdfe:dcba:9876:dd54::53"}.parse().expect("fixed DNS alias");
  index!=0&&self.interface==index&&self.destination==local&&self.peer.ip()==alias
 }
}
#[repr(C,align(16))]struct Control([u8;128]);
fn io_error()->io::Error{io::Error::last_os_error()}
pub fn enable(socket:&UdpSocket,ipv6:bool)->io::Result<()>{
 let value:libc::c_int=1;let(level,kind)=if ipv6{(libc::IPPROTO_IPV6,libc::IPV6_RECVPKTINFO)}else{(libc::IPPROTO_IP,libc::IP_PKTINFO)};
 if unsafe{libc::setsockopt(socket.as_raw_fd(),level,kind,(&value as *const libc::c_int).cast(),std::mem::size_of_val(&value)as _)}!=0{return Err(io_error())}Ok(())
}
pub fn receive(socket:&UdpSocket)->io::Result<Option<Datagram>>{
 let mut bytes=[0u8;4097];let mut peer:libc::sockaddr_storage=unsafe{std::mem::zeroed()};let mut control=Control([0;128]);
 let mut iovec=libc::iovec{iov_base:bytes.as_mut_ptr().cast(),iov_len:bytes.len()};let mut message:libc::msghdr=unsafe{std::mem::zeroed()};
 message.msg_name=(&mut peer as *mut libc::sockaddr_storage).cast();message.msg_namelen=std::mem::size_of_val(&peer)as _;message.msg_iov=&mut iovec;message.msg_iovlen=1;message.msg_control=control.0.as_mut_ptr().cast();message.msg_controllen=control.0.len()as _;
 let count=unsafe{libc::recvmsg(socket.as_raw_fd(),&mut message,libc::MSG_DONTWAIT)};if count<0{return Err(io_error())}
 if !(12..=4096).contains(&count)||message.msg_flags&(libc::MSG_TRUNC|libc::MSG_CTRUNC)!=0||message.msg_controllen as usize>control.0.len(){return Ok(None)}
 let peer=match peer.ss_family as i32{
  libc::AF_INET if message.msg_namelen as usize>=std::mem::size_of::<libc::sockaddr_in>()=>{let value=unsafe{std::ptr::read_unaligned((&peer as *const libc::sockaddr_storage).cast::<libc::sockaddr_in>())};SocketAddr::new(IpAddr::V4(Ipv4Addr::from(value.sin_addr.s_addr.to_ne_bytes())),u16::from_be(value.sin_port))},
  libc::AF_INET6 if message.msg_namelen as usize>=std::mem::size_of::<libc::sockaddr_in6>()=>{let value=unsafe{std::ptr::read_unaligned((&peer as *const libc::sockaddr_storage).cast::<libc::sockaddr_in6>())};if value.sin6_scope_id!=0{return Ok(None)}SocketAddr::new(IpAddr::V6(Ipv6Addr::from(value.sin6_addr.s6_addr)),u16::from_be(value.sin6_port))},_=>return Ok(None),
 };
 let start=control.0.as_ptr()as usize;let end=start+message.msg_controllen as usize;let mut current=unsafe{libc::CMSG_FIRSTHDR(&message)};let mut evidence=None;
 while !current.is_null(){
  let pointer=current as usize;if pointer<start||pointer.checked_add(std::mem::size_of::<libc::cmsghdr>()).is_none_or(|v|v>end){return Ok(None)}
  let header=unsafe{std::ptr::read_unaligned(current)};if (header.cmsg_len as usize)<std::mem::size_of::<libc::cmsghdr>()||pointer.checked_add(header.cmsg_len as usize).is_none_or(|v|v>end){return Ok(None)}
  let data=unsafe{libc::CMSG_DATA(current)};
  let value=match(header.cmsg_level,header.cmsg_type){
   (libc::IPPROTO_IP,libc::IP_PKTINFO) if header.cmsg_len as usize>=unsafe{libc::CMSG_LEN(std::mem::size_of::<libc::in_pktinfo>()as _)}as usize=>{
    let value=unsafe{std::ptr::read_unaligned(data.cast::<libc::in_pktinfo>())};if value.ipi_ifindex<=0{return Ok(None)}Some((value.ipi_ifindex as u32,IpAddr::V4(Ipv4Addr::from(value.ipi_addr.s_addr.to_ne_bytes()))))
   },
   (libc::IPPROTO_IPV6,libc::IPV6_PKTINFO) if header.cmsg_len as usize>=unsafe{libc::CMSG_LEN(std::mem::size_of::<libc::in6_pktinfo>()as _)}as usize=>{
    let value=unsafe{std::ptr::read_unaligned(data.cast::<libc::in6_pktinfo>())};let Some(index)=u32::try_from(value.ipi6_ifindex).ok().filter(|v|*v>0)else{return Ok(None)};Some((index,IpAddr::V6(Ipv6Addr::from(value.ipi6_addr.s6_addr))))
   },_=>None,
  };if let Some(value)=value{if evidence.is_some(){return Ok(None)}evidence=Some(value)}current=unsafe{libc::CMSG_NXTHDR(&message,current)};
 }
 let Some((interface,destination))=evidence else{return Ok(None)};if interface==0||peer.port()==0||peer.is_ipv4()!=destination.is_ipv4(){return Ok(None)}
 Ok(Some(Datagram{peer,destination,interface,bytes:bytes[..count as usize].to_vec()}))
}
/// Called only on the already SCM_RIGHTS-validated owned TUN descriptor.
pub fn interface_index(tun:BorrowedFd<'_>)->io::Result<u32>{
 let mut info=[0u8;40];if unsafe{libc::ioctl(tun.as_raw_fd(),libc::TUNGETIFF,info.as_mut_ptr())}<0{return Err(io_error())}
 let name=std::ffi::CStr::from_bytes_until_nul(&info[..16]).map_err(|_|io::ErrorKind::InvalidData)?;if name.to_bytes().is_empty(){return Err(io::ErrorKind::InvalidData.into())}
 let index=unsafe{libc::if_nametoindex(name.as_ptr())};if index==0{return Err(io_error())}Ok(index)
}
#[cfg(test)]mod tests{
 use super::*;use std::os::fd::AsFd;
 async fn receive_ready(socket:&UdpSocket)->Option<Datagram>{tokio::time::timeout(std::time::Duration::from_secs(1),async{loop{match receive(socket){Ok(value)=>return value,Err(error)if error.kind()==io::ErrorKind::WouldBlock=>tokio::task::yield_now().await,Err(error)=>panic!("owned UDP ingress failed: {:?}",error.kind())}}}).await.unwrap()}
 #[tokio::test]async fn kernel_reports_actual_interface_and_local_address_for_both_families(){
  let index=unsafe{libc::if_nametoindex(c"lo".as_ptr())};assert!(index>0);
  for local in ["127.0.0.1:0","[::1]:0"]{
   let server=UdpSocket::bind(local).unwrap();server.set_nonblocking(true).unwrap();let destination=server.local_addr().unwrap();enable(&server,destination.is_ipv6()).unwrap();let client=UdpSocket::bind(local).unwrap();let query=mcrf_bs_routing_policy::dns_wire::query(1,"owned.invalid",1).unwrap();client.send_to(&query,destination).unwrap();
   let received=receive_ready(&server).await.unwrap();assert_eq!(received.interface,index);assert_eq!(received.destination,destination.ip());assert_eq!(received.peer,client.local_addr().unwrap());assert_eq!(received.bytes,query);
   assert!(!received.from_owned_tun(index,destination.ip())); // Loopback is NOT a synthetic TUN peer.
   let synthetic=Datagram{peer:SocketAddr::new(if destination.is_ipv4(){"198.19.255.53"}else{"fdfe:dcba:9876:dd54::53"}.parse().unwrap(),40000),destination:received.destination,interface:index,bytes:query};
   assert!(synthetic.from_owned_tun(index,destination.ip()));assert!(!synthetic.from_owned_tun(index+1,destination.ip()));
  }
 }
 #[tokio::test]async fn missing_ancillary_evidence_and_truncated_datagrams_are_not_trusted(){
  let server=UdpSocket::bind("127.0.0.1:0").unwrap();server.set_nonblocking(true).unwrap();let client=UdpSocket::bind("127.0.0.1:0").unwrap();let query=mcrf_bs_routing_policy::dns_wire::query(1,"owned.invalid",1).unwrap();client.send_to(&query,server.local_addr().unwrap()).unwrap();assert!(receive_ready(&server).await.is_none());
  enable(&server,false).unwrap();client.send_to(&[0;8000],server.local_addr().unwrap()).unwrap();assert!(receive_ready(&server).await.is_none());
 }
 #[test]fn an_ordinary_socket_cannot_assert_an_owned_tun_interface(){let socket=UdpSocket::bind("127.0.0.1:0").unwrap();assert!(interface_index(socket.as_fd()).is_err());}
}
