//! Standalone Unix packet FD core. Not integrated into CSQTT or capability-advertised.
//! Separate packet endpoint guard. Never substitutes for the real TUN guard.
#![cfg(any(target_os = "android", target_os = "linux"))]

use std::{fs::File,io,mem,os::fd::{AsFd,AsRawFd,BorrowedFd,OwnedFd}};

use crate::contract::{Contract,INPUT_KIND,MAX_PACKET};

#[derive(Clone,Copy,Debug,PartialEq,Eq,Hash)]
pub struct KernelIdentity { pub device:u64, pub inode:u64 }
pub struct Endpoint { fd:OwnedFd, contract:Contract, identity:KernelIdentity }
impl Endpoint {
    pub fn as_fd(&self)->BorrowedFd<'_> {self.fd.as_fd()}
    pub fn max_packet(&self)->usize {self.contract.max_packet()}
    pub fn contract(&self)->&Contract {&self.contract}
    pub fn kernel_identity(&self)->KernelIdentity {self.identity}
    pub fn into_file(self)->File {File::from(self.fd)}
}

fn invalid(message:&'static str)->io::Error {io::Error::new(io::ErrorKind::InvalidData,message)}
fn max_packet(value:usize)->io::Result<()> {
    if !(20..=MAX_PACKET).contains(&value) {return Err(invalid("packet endpoint MTU outside contract"))}
    Ok(())
}
pub(crate) fn option<T:Copy>(fd:BorrowedFd<'_>,name:libc::c_int)->io::Result<T> {
    let mut value=mem::MaybeUninit::<T>::uninit();
    let mut length=mem::size_of::<T>() as libc::socklen_t;
    let result=unsafe{libc::getsockopt(fd.as_raw_fd(),libc::SOL_SOCKET,name,value.as_mut_ptr().cast(),&mut length)};
    if result<0 {return Err(io::Error::last_os_error())}
    if length as usize!=mem::size_of::<T>() {return Err(invalid("packet endpoint socket option size"))}
    // Private callers use only integer socket options and the kernel ucred POD.
    Ok(unsafe{value.assume_init()})
}

/// Consumes the SCM_RIGHTS-owned FD; any validation error closes it via RAII.
/// Control-channel authentication/one-FD count/truncation remain separate gates.
pub fn adopt(kind:&str,fd:OwnedFd,contract:Contract)->io::Result<Endpoint> {
    if kind!=INPUT_KIND {return Err(invalid("unsupported packet endpoint input kind"))}
    max_packet(contract.max_packet())?;
    if contract.parent_pid()<=0 {return Err(invalid("packet endpoint parent identity required"))}
    let borrowed=fd.as_fd();
    let mut stat:libc::stat=unsafe{mem::zeroed()};
    if unsafe{libc::fstat(fd.as_raw_fd(),&mut stat)}<0 {return Err(io::Error::last_os_error())}
    if stat.st_mode&libc::S_IFMT!=libc::S_IFSOCK {return Err(invalid("packet endpoint is not a socket"))}
    let identity=KernelIdentity{device:stat.st_dev as u64,inode:stat.st_ino as u64};
    if option::<libc::c_int>(borrowed,libc::SO_DOMAIN)?!=libc::AF_UNIX ||
       option::<libc::c_int>(borrowed,libc::SO_TYPE)?!=libc::SOCK_SEQPACKET ||
       option::<libc::c_int>(borrowed,libc::SO_ACCEPTCONN)?!=0 {
        return Err(invalid("connected Unix seqpacket endpoint required"))
    }
    let peer:libc::ucred=option(borrowed,libc::SO_PEERCRED)?;
    if peer.uid!=contract.uid() || peer.pid!=contract.parent_pid() {
        return Err(invalid("packet endpoint peer identity mismatch"))
    }
    let mut address:libc::sockaddr_un=unsafe{mem::zeroed()};
    let mut length=mem::size_of_val(&address) as libc::socklen_t;
    if unsafe{libc::getpeername(fd.as_raw_fd(),(&mut address as *mut libc::sockaddr_un).cast(),&mut length)}<0 {
        return Err(io::Error::last_os_error())
    }
    if (length as usize)<mem::size_of_val(&address.sun_family) || (length as usize)>mem::size_of_val(&address) ||
       address.sun_family as libc::c_int!=libc::AF_UNIX {return Err(invalid("packet endpoint is not connected"))}
    let flags=unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_GETFL)};
    let fd_flags=unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_GETFD)};
    if flags<0 || fd_flags<0 {return Err(io::Error::last_os_error())}
    if flags&libc::O_ACCMODE!=libc::O_RDWR {return Err(invalid("read/write packet endpoint required"))}
    if unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_SETFL,flags|libc::O_NONBLOCK)}<0 ||
       unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_SETFD,fd_flags|libc::FD_CLOEXEC)}<0 {
        return Err(io::Error::last_os_error())
    }
    Ok(Endpoint{fd,contract,identity})
}

/// v1 framing only; original IP packet bytes/checksums are not rewritten.
fn packet(bytes:&[u8],limit:usize)->io::Result<()> {
    max_packet(limit)?;
    if bytes.len()<20 || bytes.len()>limit || bytes[0]>>4!=4 {return Err(invalid("packet endpoint requires bounded IPv4"))}
    let header=(bytes[0]&15) as usize*4;
    let total=u16::from_be_bytes([bytes[2],bytes[3]]) as usize;
    let fragments=u16::from_be_bytes([bytes[6],bytes[7]]);
    if header<20 || header>bytes.len() || total!=bytes.len() || fragments&0xbfff!=0 {
        return Err(invalid("packet endpoint invalid length or unsupported fragmentation"))
    }
    Ok(())
}

/// An I/O shim for the new kind only. InvalidData/EOF must retire the endpoint,
/// not fall back or dispatch a partially read packet. Real TUN I/O is unchanged.
pub fn read_packet(fd:BorrowedFd<'_>,area:&mut[u8],limit:usize)->io::Result<usize> {
    max_packet(limit)?;
    if area.len()<limit {return Err(invalid("packet endpoint receive buffer too small"))}
    let mut iov=libc::iovec{iov_base:area.as_mut_ptr().cast(),iov_len:limit};
    let mut message:libc::msghdr=unsafe{mem::zeroed()};
    message.msg_iov=&mut iov;message.msg_iovlen=1;
    // No ancillary buffer: undeliverable SCM_RIGHTS are kernel-closed. Reject
    // MSG_CTRUNC rather than silently accepting data-channel FD/credential data.
    let count=unsafe{libc::recvmsg(fd.as_raw_fd(),&mut message,
        libc::MSG_DONTWAIT|libc::MSG_TRUNC|libc::MSG_CMSG_CLOEXEC)};
    if count<0 {return Err(io::Error::last_os_error())}
    if count==0 {return Err(io::Error::new(io::ErrorKind::UnexpectedEof,"packet endpoint closed"))}
    if count as usize>limit || message.msg_flags&(libc::MSG_TRUNC|libc::MSG_CTRUNC)!=0 {
        return Err(invalid("packet endpoint truncated or ancillary message"))
    }
    packet(&area[..count as usize],limit)?;
    Ok(count as usize)
}

/// One complete raw IP packet per send. WouldBlock retains the caller's packet;
/// a short send is fatal framing failure, never another seqpacket fragment.
pub fn write_packet(fd:BorrowedFd<'_>,bytes:&[u8],limit:usize)->io::Result<usize> {
    packet(bytes,limit)?;
    let count=unsafe{libc::send(fd.as_raw_fd(),bytes.as_ptr().cast(),bytes.len(),libc::MSG_DONTWAIT|libc::MSG_NOSIGNAL)};
    if count<0 {return Err(io::Error::last_os_error())}
    if count as usize!=bytes.len() {return Err(invalid("packet endpoint non-atomic send"))}
    Ok(count as usize)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::fd::FromRawFd;
    fn pair(kind:libc::c_int)->(OwnedFd,OwnedFd) {
        let mut fds=[-1;2];
        assert_eq!(unsafe{libc::socketpair(libc::AF_UNIX,kind|libc::SOCK_CLOEXEC,0,fds.as_mut_ptr())},0);
        unsafe{(OwnedFd::from_raw_fd(fds[0]),OwnedFd::from_raw_fd(fds[1]))}
    }
    const ID:&str="0123456789abcdef0123456789abcdef";
    fn contract()->Contract {Contract::new(INPUT_KIND,unsafe{libc::getuid()},std::process::id() as _,1500,1,ID).ok().unwrap()}
    fn ipv4(length:usize)->Vec<u8> {
        let mut bytes=vec![0;length];bytes[0]=0x45;bytes[2..4].copy_from_slice(&(length as u16).to_be_bytes());
        bytes[8]=64;bytes[9]=17;bytes[12..16].copy_from_slice(&[10,77,0,2]);bytes[16..20].copy_from_slice(&[192,0,2,1]);bytes
    }
    #[test] fn only_explicit_connected_seqpacket_kind_is_admitted() {
        for kind in [libc::SOCK_STREAM,libc::SOCK_DGRAM] {
            let (fd,_peer)=pair(kind);assert!(adopt(INPUT_KIND,fd,contract()).is_err());
        }
        let (fd,_peer)=pair(libc::SOCK_SEQPACKET);assert!(adopt("tun",fd,contract()).is_err());
        let (fd,_peer)=pair(libc::SOCK_SEQPACKET);let endpoint=adopt(INPUT_KIND,fd,contract()).unwrap();
        assert_ne!(unsafe{libc::fcntl(endpoint.as_fd().as_raw_fd(),libc::F_GETFL)}&libc::O_NONBLOCK,0);
        assert_ne!(unsafe{libc::fcntl(endpoint.as_fd().as_raw_fd(),libc::F_GETFD)}&libc::FD_CLOEXEC,0);
    }
    #[test] fn wrong_peer_or_plain_file_is_rejected_and_owned_fd_closed() {
        fn count()->usize {std::fs::read_dir("/proc/self/fd").unwrap().count()}
        let (fd,peer)=pair(libc::SOCK_SEQPACKET);let before=count();
        let wrong=Contract::new(INPUT_KIND,unsafe{libc::getuid()},std::process::id() as i32+1,1500,1,ID).ok().unwrap();
        assert!(adopt(INPUT_KIND,fd,wrong).is_err());assert_eq!(count(),before-1);drop(peer);
        let file=File::open("/dev/null").unwrap();assert!(adopt(INPUT_KIND,file.into(),contract()).is_err());
    }
    #[test] fn raw_packet_roundtrip_is_message_preserving_and_byte_identical() {
        let (a,b)=pair(libc::SOCK_SEQPACKET);
        let a=adopt(INPUT_KIND,a,contract()).unwrap();let b=adopt(INPUT_KIND,b,contract()).unwrap();
        let packet=ipv4(28);let mut area=[0u8;MAX_PACKET];
        assert_eq!(write_packet(a.as_fd(),&packet,1500).unwrap(),packet.len());
        let count=read_packet(b.as_fd(),&mut area,1500).unwrap();assert_eq!(&area[..count],packet.as_slice());
        assert_eq!(read_packet(b.as_fd(),&mut area,1500).unwrap_err().kind(),io::ErrorKind::WouldBlock);
    }
    #[test] fn oversized_message_invalid_lengths_and_fragments_are_not_dispatched() {
        let (a,b)=pair(libc::SOCK_SEQPACKET);let b=adopt(INPUT_KIND,b,contract()).unwrap();
        let bytes=ipv4(1600);
        assert_eq!(unsafe{libc::send(a.as_raw_fd(),bytes.as_ptr().cast(),bytes.len(),libc::MSG_NOSIGNAL)},1600);
        let mut area=[0u8;MAX_PACKET];assert_eq!(read_packet(b.as_fd(),&mut area,1500).unwrap_err().kind(),io::ErrorKind::InvalidData);
        let mut bytes=ipv4(28);bytes[3]=27;assert!(packet(&bytes,1500).is_err());
        bytes=ipv4(28);bytes[6]=0x20;assert!(packet(&bytes,1500).is_err());
        bytes[6]=0x40;assert!(packet(&bytes,1500).is_ok()); // DF is not fragmentation.
    }
    #[test] fn unconnected_or_listening_descriptor_is_rejected() {
        for listening in [false,true] {
            let raw=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_SEQPACKET|libc::SOCK_CLOEXEC,0)};
            assert!(raw>=0);let owned=unsafe{OwnedFd::from_raw_fd(raw)};
            if listening {
                let name=format!("mcrf-packet-test-{}",std::process::id());
                let mut address:libc::sockaddr_un=unsafe{mem::zeroed()};address.sun_family=libc::AF_UNIX as _;
                for (index,byte) in name.bytes().enumerate(){address.sun_path[index+1]=byte as _;}
                let length=(mem::size_of_val(&address.sun_family)+1+name.len()) as libc::socklen_t;
                assert_eq!(unsafe{libc::bind(raw,(&address as *const libc::sockaddr_un).cast(),length)},0);
                assert_eq!(unsafe{libc::listen(raw,1)},0);
            }
            assert!(adopt(INPUT_KIND,owned,contract()).is_err());
        }
    }
    #[test] fn wrong_uid_is_rejected_and_same_kernel_endpoint_has_same_identity() {
        let (fd,_peer)=pair(libc::SOCK_SEQPACKET);
        let wrong=Contract::new(INPUT_KIND,unsafe{libc::getuid()}.wrapping_add(1),std::process::id() as _,1500,1,ID).ok().unwrap();
        assert!(adopt(INPUT_KIND,fd,wrong).is_err());
        let (fd,_peer)=pair(libc::SOCK_SEQPACKET);
        let duplicate=fd.try_clone().unwrap();
        let a=adopt(INPUT_KIND,fd,contract()).unwrap();let b=adopt(INPUT_KIND,duplicate,contract()).unwrap();
        assert_eq!(a.kernel_identity(),b.kernel_identity());
    }
    #[test] fn closed_peer_fails_without_sigpipe_or_false_success() {
        let (a,b)=pair(libc::SOCK_SEQPACKET);let a=adopt(INPUT_KIND,a,contract()).unwrap();drop(b);
        assert!(write_packet(a.as_fd(),&ipv4(28),1500).is_err());
        let mut area=[0u8;MAX_PACKET];
        assert_eq!(read_packet(a.as_fd(),&mut area,1500).unwrap_err().kind(),io::ErrorKind::UnexpectedEof);
    }
    #[test] fn bounded_backpressure_keeps_whole_packet_without_partial_send() {
        let (a,b)=pair(libc::SOCK_SEQPACKET);
        let a=adopt(INPUT_KIND,a,contract()).unwrap();let b=adopt(INPUT_KIND,b,contract()).unwrap();
        let size:libc::c_int=4096; // TEST ONLY: force WouldBlock; no production buffer tuning.
        assert_eq!(unsafe{libc::setsockopt(a.as_fd().as_raw_fd(),libc::SOL_SOCKET,libc::SO_SNDBUF,
            (&size as *const libc::c_int).cast(),mem::size_of_val(&size) as _)},0);
        let bytes=ipv4(1500);let mut accepted=0;
        for _ in 0..128 {
            match write_packet(a.as_fd(),&bytes,1500) {
                Ok(length)=>{assert_eq!(length,bytes.len());accepted+=1;},
                Err(error) if error.kind()==io::ErrorKind::WouldBlock=>break,
                Err(error)=>panic!("unexpected test send error: {error}"),
            }
        }
        assert!(accepted>0&&accepted<128);
        let mut area=[0u8;MAX_PACKET];
        for _ in 0..accepted {let length=read_packet(b.as_fd(),&mut area,1500).unwrap();assert_eq!(&area[..length],bytes.as_slice());}
        assert_eq!(write_packet(a.as_fd(),&bytes,1500).unwrap(),bytes.len());
    }
    #[test] fn backend_endpoints_do_not_cross_deliver() {
        let (a,parent_a)=pair(libc::SOCK_SEQPACKET);let (b,_parent_b)=pair(libc::SOCK_SEQPACKET);
        let a=adopt(INPUT_KIND,a,contract()).unwrap();
        let c=Contract::new(INPUT_KIND,unsafe{libc::getuid()},std::process::id() as _,1500,2,"fedcba9876543210fedcba9876543210").ok().unwrap();
        let b=adopt(INPUT_KIND,b,c).unwrap();assert_ne!(a.kernel_identity(),b.kernel_identity());
        let bytes=ipv4(28);write_packet(parent_a.as_fd(),&bytes,1500).unwrap();
        let mut area=[0u8;MAX_PACKET];assert_eq!(read_packet(a.as_fd(),&mut area,1500).unwrap(),bytes.len());
        assert_eq!(read_packet(b.as_fd(),&mut area,1500).unwrap_err().kind(),io::ErrorKind::WouldBlock);
        assert_ne!(a.contract().endpoint_id(),b.contract().endpoint_id());
    }
}
