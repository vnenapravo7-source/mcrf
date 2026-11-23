//! Strict BS direct-TUN boundary. No provider, socket creation, Go or RAW relay.
use anyhow::{Result, bail};
use std::sync::{OnceLock, atomic::{AtomicBool, Ordering}};

struct Session {
    token: u64,
    parent_pid: u32,
    started: std::time::Instant,
    received: AtomicBool,
    adopted: AtomicBool,
    lost: AtomicBool,
    #[cfg(unix)]
    ack: std::sync::Mutex<Option<(std::os::fd::OwnedFd, std::time::Instant)>>,
}
static SESSION: OnceLock<Session> = OnceLock::new();

pub fn parse_token(value: &str) -> Result<u64> {
    if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {bail!("invalid direct TUN generation")}
    let token=value.parse::<u64>().map_err(|_| anyhow::anyhow!("invalid direct TUN generation"))?;
    if token==0 || token>i64::MAX as u64 || token.to_string()!=value {bail!("invalid direct TUN generation")}
    Ok(token)
}

pub fn configure(tun_uds: &str) -> Result<()> {
    let supplied=std::env::var_os("MCRF_DIRECT_TUN_GENERATION");
    if tun_uds.is_empty() {
        if supplied.is_some() {bail!("direct TUN generation without TUN ingress")}
        return Ok(())
    }
    if !cfg!(any(target_os="android",target_os="linux")) {bail!("direct TUN is unsupported on this target")}
    if tun_uds.len()<16 || tun_uds.len()>100 || !tun_uds.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'||b==b'_') {
        bail!("invalid private direct TUN socket")
    }
    let raw=supplied.and_then(|v|v.into_string().ok()).ok_or_else(||anyhow::anyhow!("direct TUN generation required"))?;
    let token=parse_token(&raw)?;
    let parent=std::env::var("MCRF_DIRECT_TUN_PARENT_PID").map_err(|_|anyhow::anyhow!("direct TUN parent identity required"))?;
    let parent=parse_token(&parent)?;
    if parent>i32::MAX as u64 {bail!("invalid direct TUN parent identity")}
    SESSION.set(Session{token,parent_pid:parent as u32,started:std::time::Instant::now(),received:AtomicBool::new(false),adopted:AtomicBool::new(false),lost:AtomicBool::new(false),
        #[cfg(unix)] ack:std::sync::Mutex::new(None),
    }).map_err(|_|anyhow::anyhow!("direct TUN session already configured"))?;
    use std::io::Write;
    let mut output=std::io::stdout().lock();
    writeln!(output,"MCRF_DIRECT_TUN_IDENTITY {token} {}",std::process::id())?;
    output.flush()?;
    Ok(())
}

pub fn active() -> bool {SESSION.get().is_some()}

#[cfg(any(target_os="android",target_os="linux"))]
#[path="mcrf_direct_tun_receive.rs"]
mod receiver;

#[cfg(any(target_os="android",target_os="linux"))]
pub async fn receive(listener:&tokio::io::unix::AsyncFd<std::os::fd::OwnedFd>,cancel:&tokio_util::sync::CancellationToken) -> Result<std::fs::File> {
    receiver::receive(listener,cancel).await
}
#[cfg(all(unix,not(any(target_os="android",target_os="linux"))))]
pub async fn receive(_: &tokio::io::unix::AsyncFd<std::os::fd::OwnedFd>,_: &tokio_util::sync::CancellationToken) -> Result<std::fs::File> {
    bail!("unsupported direct TUN target")
}

fn require_one_descriptor(count:usize,truncated:bool) -> Result<()> {
    if truncated||count!=1 {bail!("direct TUN requires exactly one complete SCM_RIGHTS descriptor")}
    Ok(())
}

#[cfg(unix)]
pub fn validate_peer(fd: std::os::fd::BorrowedFd<'_>) -> Result<()> {
    use std::os::fd::AsRawFd;
    if !active() {return Ok(())}
    validate_same_uid(fd)?;
    let flags=unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_GETFL)};
    let fd_flags=unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_GETFD)};
    if flags<0||fd_flags<0||unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_SETFL,flags|libc::O_NONBLOCK)}<0||
        unsafe{libc::fcntl(fd.as_raw_fd(),libc::F_SETFD,fd_flags|libc::FD_CLOEXEC)}<0 {bail!("direct TUN peer descriptor setup failed")}
    Ok(())
}

#[cfg(any(target_os="android",target_os="linux"))]
fn validate_same_uid(fd: std::os::fd::BorrowedFd<'_>) -> Result<()> {
    use std::os::fd::AsRawFd;
    let mut peer:libc::ucred=unsafe{std::mem::zeroed()};
    let mut size=std::mem::size_of::<libc::ucred>() as libc::socklen_t;
    let result=unsafe{libc::getsockopt(fd.as_raw_fd(),libc::SOL_SOCKET,libc::SO_PEERCRED,
        (&mut peer as *mut libc::ucred).cast(),&mut size)};
    if result!=0 || size as usize!=std::mem::size_of::<libc::ucred>() || peer.uid!=unsafe{libc::getuid()} ||
        SESSION.get().is_some_and(|session|peer.pid!=session.parent_pid as i32) {
        bail!("direct TUN peer identity rejected")
    }
    Ok(())
}

#[cfg(all(unix,not(any(target_os="android",target_os="linux"))))]
fn validate_same_uid(_: std::os::fd::BorrowedFd<'_>) -> Result<()> {bail!("unsupported direct TUN target")}

#[cfg(any(target_os="android",target_os="linux"))]
fn validate_tun(fd: std::os::fd::BorrowedFd<'_>) -> Result<()> {
    use std::os::fd::AsRawFd;
    let raw=fd.as_raw_fd();
    let flags=unsafe{libc::fcntl(raw,libc::F_GETFL)};
    if flags<0 || flags&libc::O_ACCMODE!=libc::O_RDWR {bail!("direct TUN requires a read/write descriptor")}
    // TUNGETIFF writes ifreq (<=40 bytes on supported Android ABIs).
    let mut info=[0u8;40];
    if unsafe{libc::ioctl(raw,libc::TUNGETIFF,info.as_mut_ptr())}<0 {bail!("descriptor is not a TUN device")}
    let interface_flags=i16::from_ne_bytes([info[16],info[17]]) as i32;
    if interface_flags&libc::IFF_TUN==0 || interface_flags&libc::IFF_TAP!=0 || interface_flags&libc::IFF_NO_PI==0 {
        bail!("direct TUN requires IFF_TUN and IFF_NO_PI")
    }
    let descriptor_flags=unsafe{libc::fcntl(raw,libc::F_GETFD)};
    if descriptor_flags<0 || unsafe{libc::fcntl(raw,libc::F_SETFD,descriptor_flags|libc::FD_CLOEXEC)}<0 ||
        unsafe{libc::fcntl(raw,libc::F_SETFL,flags|libc::O_NONBLOCK)}<0 {
        bail!("direct TUN descriptor setup failed")
    }
    Ok(())
}

#[cfg(all(unix,not(any(target_os="android",target_os="linux"))))]
fn validate_tun(_: std::os::fd::BorrowedFd<'_>) -> Result<()> {bail!("unsupported direct TUN target")}

#[cfg(unix)]
pub fn received(connection: std::os::fd::BorrowedFd<'_>, descriptors: &[std::os::fd::OwnedFd], truncated: bool) -> Result<()> {
    use std::os::fd::{AsFd,AsRawFd,FromRawFd,OwnedFd};
    let Some(session)=SESSION.get() else {return Ok(())};
    validate_same_uid(connection)?;
    require_one_descriptor(descriptors.len(),truncated)?;
    validate_tun(descriptors[0].as_fd())?;
    if session.received.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err() {
        bail!("direct TUN permits one descriptor per child")
    }
    let duplicate=unsafe{libc::fcntl(connection.as_raw_fd(),libc::F_DUPFD_CLOEXEC,0)};
    if duplicate<0 {bail!("direct TUN acknowledgement channel unavailable")}
    let owned=unsafe{OwnedFd::from_raw_fd(duplicate)};
    let mut pending=session.ack.lock().map_err(|_|anyhow::anyhow!("direct TUN acknowledgement state unavailable"))?;
    *pending=Some((owned,std::time::Instant::now()));
    Ok(())
}

/// One validated FD only. Retire the listener, but keep its critical task owned
/// until shutdown; never re-enter the startup accept deadline after handoff.
pub async fn handoff_once<T>(receiver:T,file:std::fs::File,sender:&tokio::sync::mpsc::Sender<std::fs::File>,cancel:&tokio_util::sync::CancellationToken) -> Result<()> {
    sender.send(file).await.map_err(|_|anyhow::anyhow!("direct TUN handoff channel closed"))?;
    drop(receiver);
    cancel.cancelled().await;
    Ok(())
}

fn event(prefix: &str,token:u64) -> Result<()> {
    use std::io::Write;
    let mut output=std::io::stdout().lock();
    writeln!(output,"{prefix} {token}")?;
    output.flush()?;
    Ok(())
}

/// Called ONLY after successful AsyncFd registration and both owned I/O tasks attach.
pub fn adopted() -> Result<()> {
    let Some(session)=SESSION.get() else {return Ok(())};
    adopt_session(session)
}

fn adopt_session(session:&Session) -> Result<()> {
    if session.started.elapsed()>=std::time::Duration::from_secs(60) {bail!("direct TUN startup deadline")}
    if !session.received.load(Ordering::Acquire) || session.lost.load(Ordering::Acquire) ||
        session.adopted.load(Ordering::Acquire) {bail!("direct TUN cannot adopt this session")}
    #[cfg(unix)] {
        use std::os::fd::AsRawFd;
        let (connection,at)=session.ack.lock().map_err(|_|anyhow::anyhow!("direct TUN acknowledgement state unavailable"))?
            .take().ok_or_else(||anyhow::anyhow!("direct TUN acknowledgement channel absent"))?;
        if at.elapsed()>=std::time::Duration::from_secs(8) {bail!("direct TUN adoption deadline")}
        send_adoption_ack(connection.as_raw_fd())?;
    }
    #[cfg(not(unix))] {bail!("unsupported direct TUN target")}
    session.adopted.store(true,Ordering::Release);
    event("MCRF_DIRECT_TUN_ADOPTED",session.token)
}

#[cfg(unix)]
fn send_adoption_ack(fd:std::os::fd::RawFd) -> Result<()> {
    let ack=[1u8];
    if unsafe{libc::send(fd,ack.as_ptr().cast(),1,libc::MSG_NOSIGNAL|libc::MSG_DONTWAIT)}!=1 {
        bail!("direct TUN adoption acknowledgement failed")
    }
    Ok(())
}

pub struct Guard;
pub fn guard() -> Guard {Guard}
impl Drop for Guard {
    fn drop(&mut self) {
        if let Some(session)=SESSION.get() {
            if !session.lost.swap(true,Ordering::AcqRel) {
                #[cfg(unix)] if let Ok(mut ack)=session.ack.lock(){ack.take();}
                let _=event("MCRF_DIRECT_TUN_LOST",session.token);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn token_requires_canonical_positive_signed_63bit() {
        for valid in ["1","9223372036854775807"] {assert!(parse_token(valid).is_ok());}
        for invalid in ["","0","01","+1","-1"," 1","1\n","9223372036854775808","18446744073709551616"] {
            assert!(parse_token(invalid).is_err(),"{invalid:?}");
        }
    }
    #[cfg(any(target_os="android",target_os="linux"))]
    #[test] fn ordinary_file_is_not_a_tun_descriptor() {
        use std::os::fd::AsFd;
        let file=std::fs::OpenOptions::new().read(true).write(true).open("/dev/null").unwrap();
        assert!(validate_tun(file.as_fd()).is_err());
    }
    #[cfg(any(target_os="android",target_os="linux"))]
    #[test] fn unix_peer_same_uid_is_observed_not_assumed() {
        use std::os::fd::AsFd;
        let (a,_b)=std::os::unix::net::UnixStream::pair().unwrap();
        assert!(validate_same_uid(a.as_fd()).is_ok());
    }
    #[cfg(any(target_os="android",target_os="linux"))]
    #[tokio::test(start_paused = true)]
    async fn one_fd_handoff_outlives_startup_deadline_and_cancels_without_leak() {
        use std::os::fd::AsRawFd;
        fn count()->usize {std::fs::read_dir("/proc/self/fd").unwrap().count()}
        let baseline=count();
        let (listener,peer)=std::os::unix::net::UnixStream::pair().unwrap();
        let listener=tokio::io::unix::AsyncFd::new(listener).unwrap();
        let file=std::fs::File::open("/dev/null").unwrap();
        let sent_fd=file.as_raw_fd();
        let (tx,mut rx)=tokio::sync::mpsc::channel(1);
        let cancel=tokio_util::sync::CancellationToken::new();
        let owned_cancel=cancel.clone();
        let task=tokio::spawn(async move {handoff_once(listener,file,&tx,&owned_cancel).await});
        let handed=rx.recv().await.unwrap();
        assert_eq!(handed.as_raw_fd(),sent_fd); // Move, not another FD duplicate.
        tokio::task::yield_now().await;
        assert_eq!(count(),baseline+2); // Peer and handed FD; listener released.
        tokio::time::advance(std::time::Duration::from_secs(61)).await;
        tokio::task::yield_now().await;
        assert!(!task.is_finished(),"successful handoff must stay parked beyond startup deadline");
        assert!(matches!(rx.try_recv(),Err(tokio::sync::mpsc::error::TryRecvError::Empty)));
        cancel.cancel();
        tokio::time::timeout(std::time::Duration::from_secs(1),task).await.unwrap().unwrap().unwrap();
        drop(handed);drop(peer);
        assert_eq!(count(),baseline);
    }
    #[cfg(any(target_os="android",target_os="linux"))]
    #[tokio::test(start_paused = true)]
    async fn failed_fd_handoff_errors_without_parking_or_leaking() {
        fn count()->usize {std::fs::read_dir("/proc/self/fd").unwrap().count()}
        let baseline=count();
        let (listener,peer)=std::os::unix::net::UnixStream::pair().unwrap();
        let listener=tokio::io::unix::AsyncFd::new(listener).unwrap();
        let file=std::fs::File::open("/dev/null").unwrap();
        let (tx,rx)=tokio::sync::mpsc::channel(1);drop(rx);
        let cancel=tokio_util::sync::CancellationToken::new();
        let result=tokio::time::timeout(std::time::Duration::from_secs(1),handoff_once(listener,file,&tx,&cancel)).await.unwrap();
        assert!(result.is_err());assert!(!cancel.is_cancelled());
        assert_eq!(count(),baseline+1); // Only peer remains; failed send drops FD.
        drop(peer);assert_eq!(count(),baseline);
    }
    #[cfg(any(target_os="android",target_os="linux"))]
    #[test] fn acknowledgement_requires_received_fd_and_only_adopts_once() {
        use std::{io::Read,os::fd::{AsRawFd,FromRawFd,OwnedFd}};
        let (send,mut peer)=std::os::unix::net::UnixStream::pair().unwrap();
        peer.set_nonblocking(true).unwrap();
        let duplicate=unsafe{libc::fcntl(send.as_raw_fd(),libc::F_DUPFD_CLOEXEC,0)};
        assert!(duplicate>=0);
        let session=Session{token:1,parent_pid:std::process::id(),started:std::time::Instant::now(),
            received:AtomicBool::new(false),adopted:AtomicBool::new(false),lost:AtomicBool::new(false),
            ack:std::sync::Mutex::new(Some((unsafe{OwnedFd::from_raw_fd(duplicate)},std::time::Instant::now())))};
        assert!(adopt_session(&session).is_err());
        let mut byte=[0u8;1];
        assert_eq!(peer.read(&mut byte).unwrap_err().kind(),std::io::ErrorKind::WouldBlock);
        session.received.store(true,Ordering::Release);
        adopt_session(&session).unwrap();
        assert_eq!(peer.read(&mut byte).unwrap(),1);assert_eq!(byte,[1]);
        assert!(adopt_session(&session).is_err());
        assert_eq!(peer.read(&mut byte).unwrap_err().kind(),std::io::ErrorKind::WouldBlock);
    }
}
