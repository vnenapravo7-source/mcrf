//! Strict Linux/Android SCM_RIGHTS ingress. Own delivered FDs even on MSG_CTRUNC.
use std::{fs::File,io,os::fd::{AsFd,AsRawFd,FromRawFd,OwnedFd,RawFd},time::Duration};
use anyhow::{Result,bail};
use tokio::io::unix::AsyncFd;
use tokio_util::sync::CancellationToken;

pub(super) struct Packet { pub fds:Vec<OwnedFd>,pub truncated:bool }

fn recv_rights(fd:RawFd) -> io::Result<Packet> {
    let mut byte=[0u8;1];
    let mut iov=libc::iovec{iov_base:byte.as_mut_ptr().cast(),iov_len:1};
    let mut control=[0usize;64]; // Aligned; enough for124 FDs, deliberately bounded.
    let mut msg:libc::msghdr=unsafe{std::mem::zeroed()};
    msg.msg_iov=&mut iov;msg.msg_iovlen=1;
    msg.msg_control=control.as_mut_ptr().cast();msg.msg_controllen=std::mem::size_of_val(&control) as _;
    let count=unsafe{libc::recvmsg(fd,&mut msg,libc::MSG_DONTWAIT|libc::MSG_CMSG_CLOEXEC)};
    if count<0 {return Err(io::Error::last_os_error())}
    if count==0 {return Err(io::Error::new(io::ErrorKind::UnexpectedEof,"direct TUN peer closed"))}
    let mut fds=Vec::new();
    let base=msg.msg_control as usize;
    let end=base.checked_add(msg.msg_controllen as usize).ok_or_else(||io::Error::other("invalid ancillary length"))?;
    let mut header=unsafe{libc::CMSG_FIRSTHDR(&msg)};
    let mut malformed=false;
    while !header.is_null() {
        let start=header as usize;
        let minimum=unsafe{libc::CMSG_LEN(0)} as usize;
        if start<base||start.checked_add(minimum).is_none_or(|n|n>end) {malformed=true;break}
        let length=unsafe{(*header).cmsg_len} as usize;
        if length<minimum||start.checked_add(length).is_none_or(|n|n>end) {malformed=true;break}
        if unsafe{(*header).cmsg_level==libc::SOL_SOCKET&&(*header).cmsg_type==libc::SCM_RIGHTS} {
            let bytes=length-minimum;
            if bytes%std::mem::size_of::<RawFd>()!=0 {malformed=true}
            // Kernel constructs valid delivered SCM_RIGHTS headers, including
            // truncation. Own every integral FD before returning any error.
            for index in 0..bytes/std::mem::size_of::<RawFd>() {
                let raw=unsafe{std::ptr::read_unaligned(libc::CMSG_DATA(header).cast::<RawFd>().add(index))};
                if raw<0 {malformed=true} else {fds.push(unsafe{OwnedFd::from_raw_fd(raw)})}
            }
        } else {malformed=true}
        header=unsafe{libc::CMSG_NXTHDR(&msg,header)};
    }
    if malformed {return Err(io::Error::new(io::ErrorKind::InvalidData,"malformed direct TUN ancillary data"))}
    Ok(Packet{fds,truncated:msg.msg_flags&libc::MSG_CTRUNC!=0})
}

async fn accept(listener:&AsyncFd<OwnedFd>,cancel:&CancellationToken,deadline:tokio::time::Instant) -> Result<OwnedFd> {
    loop {
        let mut ready=tokio::select! {
            biased;
            _=cancel.cancelled()=>bail!("direct TUN accept cancelled"),
            _=tokio::time::sleep_until(deadline)=>bail!("direct TUN initial accept deadline"),
            ready=listener.readable()=>ready?,
        };
        if cancel.is_cancelled()||tokio::time::Instant::now()>=deadline {bail!("direct TUN initial accept deadline")}
        match ready.try_io(|socket| {
            let raw=unsafe{libc::accept4(socket.get_ref().as_raw_fd(),std::ptr::null_mut(),std::ptr::null_mut(),libc::SOCK_CLOEXEC|libc::SOCK_NONBLOCK)};
            if raw<0 {Err(io::Error::last_os_error())} else {Ok(unsafe{OwnedFd::from_raw_fd(raw)})}
        }) {
            Ok(result)=>return Ok(result?),Err(_)=>continue,
        }
    }
}

pub(super) async fn receive(listener:&AsyncFd<OwnedFd>,cancel:&CancellationToken) -> Result<File> {
    let session=super::SESSION.get().ok_or_else(||anyhow::anyhow!("direct TUN session missing"))?;
    let remaining=Duration::from_secs(60).checked_sub(session.started.elapsed()).ok_or_else(||anyhow::anyhow!("direct TUN startup deadline"))?;
    let initial_deadline=tokio::time::Instant::now()+remaining;
    let connection=accept(listener,cancel,initial_deadline).await?;
    super::validate_peer(connection.as_fd())?;
    let connection=AsyncFd::new(connection)?;
    let deadline=std::cmp::min(initial_deadline,tokio::time::Instant::now()+Duration::from_secs(8));
    loop {
        let mut ready=tokio::select! {
            biased;
            _=cancel.cancelled()=>bail!("direct TUN receive cancelled"),
            _=tokio::time::sleep_until(deadline)=>bail!("direct TUN receive deadline"),
            ready=connection.readable()=>ready?,
        };
        if cancel.is_cancelled()||tokio::time::Instant::now()>=deadline {bail!("direct TUN receive deadline")}
        let packet=match ready.try_io(|socket|recv_rights(socket.get_ref().as_raw_fd())) {
            Ok(result)=>result?,Err(_)=>continue,
        };
        // packet.fds drops on every count/truncation/setup error; no retry path.
        super::received(connection.get_ref().as_fd(),&packet.fds,packet.truncated)?;
        return Ok(File::from(packet.fds.into_iter().next().ok_or_else(||anyhow::anyhow!("direct TUN descriptor absent"))?));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn count_fds()->usize {std::fs::read_dir("/proc/self/fd").unwrap().count()}
    fn send_fds(fd:RawFd,fds:&[RawFd]) {
        let mut byte=[1u8];let mut iov=libc::iovec{iov_base:byte.as_mut_ptr().cast(),iov_len:1};
        let space=unsafe{libc::CMSG_SPACE(std::mem::size_of_val(fds) as u32)} as usize;
        let mut control=vec![0usize;space.div_ceil(std::mem::size_of::<usize>())];
        let mut msg:libc::msghdr=unsafe{std::mem::zeroed()};
        msg.msg_iov=&mut iov;msg.msg_iovlen=1;msg.msg_control=control.as_mut_ptr().cast();msg.msg_controllen=space as _;
        unsafe {
            let hdr=libc::CMSG_FIRSTHDR(&msg);
            (*hdr).cmsg_level=libc::SOL_SOCKET;(*hdr).cmsg_type=libc::SCM_RIGHTS;
            (*hdr).cmsg_len=libc::CMSG_LEN(std::mem::size_of_val(fds) as u32) as _;
            std::ptr::copy_nonoverlapping(fds.as_ptr(),libc::CMSG_DATA(hdr).cast::<RawFd>(),fds.len());
            assert_eq!(libc::sendmsg(fd,&msg,libc::MSG_NOSIGNAL),1);
        }
    }
    #[test] fn two_descriptors_are_rejected_without_fd_leak() {
        let (send,receive)=std::os::unix::net::UnixStream::pair().unwrap();
        let file=File::open("/dev/null").unwrap();let before=count_fds();
        send_fds(send.as_raw_fd(),&[file.as_raw_fd();2]);
        let packet=recv_rights(receive.as_raw_fd()).unwrap();
        assert_eq!(packet.fds.len(),2);assert!(!packet.truncated);
        assert!(super::super::require_one_descriptor(packet.fds.len(),packet.truncated).is_err());
        drop(packet);assert_eq!(count_fds(),before);
    }
    #[test] fn truncated_rights_are_owned_and_closed_without_fd_leak() {
        let (send,receive)=std::os::unix::net::UnixStream::pair().unwrap();
        let file=File::open("/dev/null").unwrap();let before=count_fds();
        send_fds(send.as_raw_fd(),&[file.as_raw_fd();128]);
        let packet=recv_rights(receive.as_raw_fd()).unwrap();
        assert!(packet.truncated);assert!(!packet.fds.is_empty());
        assert!(super::super::require_one_descriptor(packet.fds.len(),packet.truncated).is_err());
        drop(packet);assert_eq!(count_fds(),before);
    }
    #[tokio::test] async fn initial_accept_deadline_and_cancellation_are_bounded() {
        let name=format!("mcrf-direct-test-{}",std::process::id());
        let raw=unsafe{libc::socket(libc::AF_UNIX,libc::SOCK_STREAM|libc::SOCK_NONBLOCK|libc::SOCK_CLOEXEC,0)};
        assert!(raw>=0);let owned=unsafe{OwnedFd::from_raw_fd(raw)};
        let mut address:libc::sockaddr_un=unsafe{std::mem::zeroed()};address.sun_family=libc::AF_UNIX as _;
        for(i,b)in name.bytes().enumerate(){address.sun_path[i+1]=b as _;}
        let length=(std::mem::size_of_val(&address.sun_family)+1+name.len()) as libc::socklen_t;
        assert_eq!(unsafe{libc::bind(raw,(&address as *const libc::sockaddr_un).cast(),length)},0);
        assert_eq!(unsafe{libc::listen(raw,1)},0);
        let listener=AsyncFd::new(owned).unwrap();let cancel=CancellationToken::new();
        assert!(accept(&listener,&cancel,tokio::time::Instant::now()+Duration::from_millis(20)).await.is_err());
        cancel.cancel();
        assert!(accept(&listener,&cancel,tokio::time::Instant::now()+Duration::from_secs(60)).await.is_err());
    }
}
