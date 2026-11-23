//! Strict control receive only. Returning an Endpoint is NOT I/O adoption/READY.
//! Caller owns bounded accept/receive deadlines and invokes adoption ACK only
//! after original run_tun has registered AsyncFd and attached both I/O tasks.
use std::{io,mem,os::fd::{AsFd,AsRawFd,BorrowedFd,FromRawFd,OwnedFd}};
use crate::{contract::{Contract,INPUT_KIND},unix::{self,Endpoint}};

pub const CONTROL_BYTE:u8=0x50;
pub const ADOPTION_ACK:u8=0x50;

fn invalid(text:&'static str)->io::Error {io::Error::new(io::ErrorKind::InvalidData,text)}

fn validate_control(fd:BorrowedFd<'_>,contract:&Contract)->io::Result<()> {
    if unix::option::<libc::c_int>(fd,libc::SO_DOMAIN)?!=libc::AF_UNIX ||
       unix::option::<libc::c_int>(fd,libc::SO_TYPE)?!=libc::SOCK_STREAM ||
       unix::option::<libc::c_int>(fd,libc::SO_ACCEPTCONN)?!=0 {
        return Err(invalid("packet control requires connected Unix stream"))
    }
    let peer:libc::ucred=unix::option(fd,libc::SO_PEERCRED)?;
    if peer.uid!=contract.uid() || peer.pid!=contract.parent_pid() {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied,"packet control owner mismatch"))
    }
    Ok(())
}

/// Receives the SCM_RIGHTS duplicate, owning every delivered FD before checking
/// truncation/count/type. The old real-TUN receive/guard is never called here.
pub fn receive(control:BorrowedFd<'_>,contract:Contract)->io::Result<Endpoint> {
    validate_control(control,&contract)?;
    let mut byte=[0u8;1];
    let mut iov=libc::iovec{iov_base:byte.as_mut_ptr().cast(),iov_len:1};
    let mut ancillary=[0usize;64];
    let mut message:libc::msghdr=unsafe{mem::zeroed()};
    message.msg_iov=&mut iov;message.msg_iovlen=1;
    message.msg_control=ancillary.as_mut_ptr().cast();message.msg_controllen=mem::size_of_val(&ancillary) as _;
    let count=unsafe{libc::recvmsg(control.as_raw_fd(),&mut message,libc::MSG_DONTWAIT|libc::MSG_CMSG_CLOEXEC)};
    if count<0 {return Err(io::Error::last_os_error())}
    if count==0 {return Err(io::Error::new(io::ErrorKind::UnexpectedEof,"packet control closed"))}
    let mut owned=Vec::<OwnedFd>::new();
    let base=message.msg_control as usize;
    let end=base.checked_add(message.msg_controllen as usize).ok_or_else(||invalid("packet control ancillary length"))?;
    let mut header=unsafe{libc::CMSG_FIRSTHDR(&message)};
    let mut malformed=false;
    while !header.is_null() {
        let start=header as usize;let minimum=unsafe{libc::CMSG_LEN(0)} as usize;
        if start<base || start.checked_add(minimum).is_none_or(|n|n>end) {malformed=true;break}
        let length=unsafe{(*header).cmsg_len} as usize;
        if length<minimum || start.checked_add(length).is_none_or(|n|n>end) {malformed=true;break}
        if unsafe{(*header).cmsg_level==libc::SOL_SOCKET && (*header).cmsg_type==libc::SCM_RIGHTS} {
            let bytes=length-minimum;
            if bytes%mem::size_of::<libc::c_int>()!=0 {malformed=true}
            for index in 0..bytes/mem::size_of::<libc::c_int>() {
                let raw=unsafe{std::ptr::read_unaligned(libc::CMSG_DATA(header).cast::<libc::c_int>().add(index))};
                if raw<0 {malformed=true} else {owned.push(unsafe{OwnedFd::from_raw_fd(raw)})}
            }
        }else{malformed=true}
        header=unsafe{libc::CMSG_NXTHDR(&message,header)};
    }
    if malformed || byte[0]!=CONTROL_BYTE || message.msg_flags&(libc::MSG_CTRUNC|libc::MSG_TRUNC)!=0 || owned.len()!=1 {
        return Err(invalid("packet control requires one complete owned FD"))
    }
    unix::adopt(INPUT_KIND,owned.pop().expect("validated one FD"),contract)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::File,os::unix::net::UnixStream};
    const ID:&str="0123456789abcdef0123456789abcdef";
    fn contract()->Contract {Contract::new(INPUT_KIND,unsafe{libc::getuid()},std::process::id() as _,1500,1,ID).ok().unwrap()}
    fn seqpair()->(OwnedFd,OwnedFd) {
        let mut fds=[-1;2];
        assert_eq!(unsafe{libc::socketpair(libc::AF_UNIX,libc::SOCK_SEQPACKET|libc::SOCK_CLOEXEC,0,fds.as_mut_ptr())},0);
        unsafe{(OwnedFd::from_raw_fd(fds[0]),OwnedFd::from_raw_fd(fds[1]))}
    }
    fn count()->usize {std::fs::read_dir("/proc/self/fd").unwrap().count()}
    fn send(control:&UnixStream,descriptors:&[libc::c_int],value:u8) {
        let mut value=[value];let mut iov=libc::iovec{iov_base:value.as_mut_ptr().cast(),iov_len:1};
        let size=unsafe{libc::CMSG_SPACE(mem::size_of_val(descriptors) as u32)} as usize;
        let mut ancillary=vec![0usize;size.div_ceil(mem::size_of::<usize>())];
        let mut message:libc::msghdr=unsafe{mem::zeroed()};message.msg_iov=&mut iov;message.msg_iovlen=1;
        message.msg_control=ancillary.as_mut_ptr().cast();message.msg_controllen=size as _;
        unsafe {
            let header=libc::CMSG_FIRSTHDR(&message);(*header).cmsg_level=libc::SOL_SOCKET;(*header).cmsg_type=libc::SCM_RIGHTS;
            (*header).cmsg_len=libc::CMSG_LEN(mem::size_of_val(descriptors) as u32) as _;
            std::ptr::copy_nonoverlapping(descriptors.as_ptr(),libc::CMSG_DATA(header).cast(),descriptors.len());
            assert_eq!(libc::sendmsg(control.as_raw_fd(),&message,libc::MSG_NOSIGNAL),1);
        }
    }
    #[test] fn actual_owned_scm_handoff_does_not_emit_early_adoption_ack() {
        use std::io::Read;
        let (mut parent,child)=UnixStream::pair().unwrap();parent.set_nonblocking(true).unwrap();
        let (native,_router)=seqpair();let baseline=count();
        send(&parent,&[native.as_raw_fd()],CONTROL_BYTE);
        let endpoint=receive(child.as_fd(),contract()).unwrap();
        assert_eq!(endpoint.contract().endpoint_id(),ID);assert_eq!(count(),baseline+1);
        let mut byte=[0u8;1];assert_eq!(parent.read(&mut byte).unwrap_err().kind(),io::ErrorKind::WouldBlock);
        drop(endpoint);assert_eq!(count(),baseline);
    }
    #[test] fn extra_fds_truncation_wrong_kind_and_marker_close_every_delivered_duplicate() {
        for (number,marker) in [(2,CONTROL_BYTE),(128,CONTROL_BYTE),(1,1)] {
            let (parent,child)=UnixStream::pair().unwrap();let file=File::open("/dev/null").unwrap();
            let baseline=count();send(&parent,&vec![file.as_raw_fd();number],marker);
            assert!(receive(child.as_fd(),contract()).is_err());assert_eq!(count(),baseline);
        }
        let (parent,child)=UnixStream::pair().unwrap();let file=File::open("/dev/null").unwrap();
        let baseline=count();send(&parent,&[file.as_raw_fd()],CONTROL_BYTE);
        assert!(receive(child.as_fd(),contract()).is_err());assert_eq!(count(),baseline);
    }
    #[test] fn would_block_and_control_owner_failure_do_not_claim_adoption() {
        let (_parent,child)=UnixStream::pair().unwrap();
        assert_eq!(receive(child.as_fd(),contract()).err().unwrap().kind(),io::ErrorKind::WouldBlock);
        let wrong=Contract::new(INPUT_KIND,unsafe{libc::getuid()},std::process::id() as i32+1,1500,1,ID).ok().unwrap();
        assert_eq!(receive(child.as_fd(),wrong).err().unwrap().kind(),io::ErrorKind::PermissionDenied);
    }
}
