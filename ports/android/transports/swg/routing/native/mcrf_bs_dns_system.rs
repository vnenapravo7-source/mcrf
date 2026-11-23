//! Explicit Android physical-network DNS, never a fallback for a selected
//! tunnel resolver. API29 symbols are resolved dynamically so the carrier's
//! API26 ELF floor is preserved. Cancel/result each consume the FD exactly once.
//! Ownership contract: Android CTS NativeDnsAsyncTest / NativeMultinetworkJni.
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Unsupported,Invalid,Query,Io,Wire,Deadline,Cancelled}
#[cfg(target_os="android")]
mod android{
    use super::*;
    use std::{sync::OnceLock,os::fd::{AsRawFd,RawFd}};
    type Send=unsafe extern "C" fn(u64,*const u8,usize,u32)->i32;
    type Result=unsafe extern "C" fn(i32,*mut i32,*mut u8,usize)->i32;
    type Cancel=unsafe extern "C" fn(i32);
    struct Api{send:Send,result:Result,cancel:Cancel}
    static API:OnceLock<Option<Api>>=OnceLock::new();
    fn api()->Option<&'static Api>{API.get_or_init(||unsafe{
        // One fixed system-library reference for the process, not a caller
        // path or an unbounded dlopen per query. It must outlive function ptrs.
        let library=libc::dlopen(c"libandroid.so".as_ptr(),libc::RTLD_NOW|libc::RTLD_LOCAL);
        if library.is_null(){return None}
        let send=libc::dlsym(library,c"android_res_nsend".as_ptr());let result=libc::dlsym(library,c"android_res_nresult".as_ptr());let cancel=libc::dlsym(library,c"android_res_cancel".as_ptr());
        if send.is_null()||result.is_null()||cancel.is_null(){libc::dlclose(library);return None}
        Some(Api{send:std::mem::transmute::<*mut libc::c_void,Send>(send),result:std::mem::transmute::<*mut libc::c_void,Result>(result),cancel:std::mem::transmute::<*mut libc::c_void,Cancel>(cancel)})
    }).as_ref()}
    struct Query{fd:RawFd,api:&'static Api}
    impl AsRawFd for Query{fn as_raw_fd(&self)->RawFd{self.fd}}
    impl Drop for Query{fn drop(&mut self){if self.fd>=0{unsafe{(self.api.cancel)(self.fd)};self.fd=-1}}}
    pub fn available()->bool{api().is_some()}
    pub async fn query(network:u64,message:&[u8],no_cache:bool)->std::result::Result<Vec<u8>,Failure>{
        let api=api().ok_or(Failure::Unsupported)?;
        query_api(api,network,message,no_cache).await
    }
    async fn query_api(api:&'static Api,network:u64,message:&[u8],no_cache:bool)->std::result::Result<Vec<u8>,Failure>{
        let fd=unsafe{(api.send)(network,message.as_ptr(),message.len(),if no_cache{2}else{0})};
        if fd<0{return Err(Failure::Query)}let owned=Query{fd,api};
        let flags=unsafe{libc::fcntl(fd,libc::F_GETFL)};
        if flags<0||unsafe{libc::fcntl(fd,libc::F_SETFL,flags|libc::O_NONBLOCK)}<0||unsafe{libc::fcntl(fd,libc::F_SETFD,libc::FD_CLOEXEC)}<0{return Err(Failure::Io)}
        let fd=tokio::io::unix::AsyncFd::new(owned).map_err(|_|Failure::Io)?;
        let ready=fd.readable().await.map_err(|_|Failure::Io)?;drop(ready);
        // Remove epoll ownership before NDK closes the descriptor. Disarm
        // Query::drop BEFORE res_nresult, including every error return path.
        let mut owned=fd.into_inner();let raw=std::mem::replace(&mut owned.fd,-1);
        let mut bytes=vec![0;65535];let mut rcode=-1;
        let size=unsafe{(api.result)(raw,&mut rcode,bytes.as_mut_ptr(),bytes.len())};
        if !(12..=65535).contains(&size){return Err(Failure::Query)}bytes.truncate(size as usize);
        mcrf_bs_routing_policy::dns_wire::addresses(message,&bytes).map_err(|_|Failure::Wire)?;Ok(bytes)
    }
    #[cfg(test)]mod tests{
        use super::*;use std::sync::atomic::{AtomicUsize,Ordering};
        static RESULTS:AtomicUsize=AtomicUsize::new(0);static CANCELS:AtomicUsize=AtomicUsize::new(0);
        unsafe extern "C" fn send_ready(_:u64,_:*const u8,_:usize,_:u32)->i32{
            let mut pair=[-1;2];if unsafe{libc::pipe(pair.as_mut_ptr())}!=0{return -1}
            unsafe{libc::write(pair[1],b"x".as_ptr().cast(),1);libc::close(pair[1]);}pair[0]
        }
        unsafe extern "C" fn result_error(fd:i32,_:*mut i32,_:*mut u8,_:usize)->i32{RESULTS.fetch_add(1,Ordering::SeqCst);unsafe{libc::close(fd)};-1}
        unsafe extern "C" fn cancel(fd:i32){CANCELS.fetch_add(1,Ordering::SeqCst);unsafe{libc::close(fd)};}
        static MOCK:Api=Api{send:send_ready,result:result_error,cancel};
        #[tokio::test]async fn result_consumes_fd_once_even_on_error_and_cancel_owns_unconsumed_fd(){
            let before=RESULTS.load(Ordering::SeqCst);let cancelled=CANCELS.load(Ordering::SeqCst);
            assert_eq!(query_api(&MOCK,1,&[],false).await,Err(Failure::Query));
            assert_eq!(RESULTS.load(Ordering::SeqCst),before+1);assert_eq!(CANCELS.load(Ordering::SeqCst),cancelled);
            let fd=unsafe{send_ready(1,std::ptr::null(),0,0)};assert!(fd>=0);
            drop(Query{fd,api:&MOCK});assert_eq!(CANCELS.load(Ordering::SeqCst),cancelled+1);
            assert_eq!(unsafe{libc::fcntl(fd,libc::F_GETFD)},-1);
        }
    }
}
pub fn available()->bool{
    #[cfg(target_os="android")]{android::available()}
    #[cfg(not(target_os="android"))]{false}
}
pub async fn exchange(network:u64,query:&[u8],no_cache:bool,deadline:Instant,cancel:&CancellationToken)->Result<Vec<u8>,Failure>{
    if network==0||mcrf_bs_routing_policy::dns_wire::question(query).is_err(){return Err(Failure::Invalid)}
    if cancel.is_cancelled(){return Err(Failure::Cancelled)}let now=Instant::now();if deadline<=now{return Err(Failure::Deadline)}
    if deadline.duration_since(now)>std::time::Duration::from_secs(30){return Err(Failure::Invalid)}
    #[cfg(target_os="android")]{tokio::select!{biased;
        _=cancel.cancelled()=>Err(Failure::Cancelled),
        result=tokio::time::timeout_at(deadline,android::query(network,query,no_cache))=>result.map_err(|_|Failure::Deadline)?,
    }}
    #[cfg(not(target_os="android"))]{let _=no_cache;Err(Failure::Unsupported)}
}
