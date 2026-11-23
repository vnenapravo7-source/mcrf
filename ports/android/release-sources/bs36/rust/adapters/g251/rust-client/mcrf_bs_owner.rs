//! Staged owned-pipe application metadata. No production configuration yet.
//! An emitter MUST be a nonblocking bounded queue, never a blocking stdout write.
use std::{collections::{HashMap,HashSet},net::SocketAddr,sync::{Arc,Mutex},time::Duration};
use tokio::sync::oneshot;
use tokio_util::sync::CancellationToken;
use base64::{Engine as _,engine::general_purpose::STANDARD};

#[derive(Clone,Copy,Debug,PartialEq,Eq)]
pub enum Failure { Invalid, Closed, Busy, Stale, Denied, Timeout, Cancelled, Pipe }
pub struct Owner {pub uid:u32,pub packages:Vec<String>}
impl std::fmt::Debug for Owner {fn fmt(&self,f:&mut std::fmt::Formatter<'_>)->std::fmt::Result{f.write_str("Owner(redacted)")}}
type Reply=Result<Owner,Failure>;
type Emitter=Arc<dyn Fn(&str)->Result<(),Failure>+Send+Sync>;
struct Pending {closed:bool,next:u64,requests:HashMap<u64,oneshot::Sender<Reply>>}
pub struct Broker {generation:u64,pending:Mutex<Pending>,emit:Emitter,admission:tokio::sync::Semaphore,proxy_uid:Option<u32>}
static ACTIVE:std::sync::OnceLock<Arc<Broker>>=std::sync::OnceLock::new();
struct Ticket<'a> {broker:&'a Broker,id:u64}
impl Drop for Ticket<'_>{fn drop(&mut self){if let Ok(mut state)=self.broker.pending.lock(){state.requests.remove(&self.id);}}}

fn positive(value:&str)->Result<u64,Failure>{
    if value.is_empty()||value.starts_with('0')||!value.bytes().all(|v|v.is_ascii_digit()){return Err(Failure::Invalid)}
    value.parse::<u64>().ok().filter(|v|*v<=i64::MAX as u64).ok_or(Failure::Invalid)
}
fn package(value:&str)->bool {
    !value.is_empty()&&value.len()<=255&&value.split('.').all(|part|{
        let mut chars=part.bytes();chars.next().is_some_and(|v|v.is_ascii_alphabetic()||v==b'_')&&
            chars.all(|v|v.is_ascii_alphanumeric()||v==b'_')
    })
}
impl Broker {
    pub fn generation(&self)->u64{self.generation}
    pub fn new(generation:u64,emit:Emitter)->Result<Self,Failure>{
        if generation==0||generation>i64::MAX as u64{return Err(Failure::Invalid)}
        Ok(Self{generation,emit,pending:Mutex::new(Pending{closed:false,next:0,requests:HashMap::with_capacity(32)}),admission:tokio::sync::Semaphore::new(32),proxy_uid:None})
    }
    pub async fn query(&self,protocol:u8,source:SocketAddr,destination:SocketAddr,cancel:&CancellationToken)->Reply {
        if !matches!(protocol,6|17)||source.port()==0||destination.port()==0||source.is_ipv4()!=destination.is_ipv4(){return Err(Failure::Invalid)}
        if cancel.is_cancelled(){return Err(Failure::Cancelled)}
        if let Some(uid)=self.proxy_uid{
            if !source.ip().is_loopback()||!destination.ip().is_loopback(){return Err(Failure::Denied)}
            if self.pending.lock().map_err(|_|Failure::Closed)?.closed{return Err(Failure::Closed)}
            // Only local proxy sessions that have already rejected all
            // application conditions can use this DNS/flow namespace.
            return Ok(Owner{uid,packages:Vec::new()})
        }
        // A normal burst must wait for bounded admission instead of rejecting
        // connection 33 and forcing a kernel SYN retransmission. Queue wait and
        // platform lookup share ONE deadline; close/cancel wakes all waiters.
        let deadline=tokio::time::Instant::now()+mcrf_bs_routing_policy::dns_limits::OWNER_QUERY;
        let _permit=tokio::select!{biased;
            _=cancel.cancelled()=>return Err(Failure::Cancelled),
            permit=tokio::time::timeout_at(deadline,self.admission.acquire())=>match permit{Ok(Ok(permit))=>permit,Ok(Err(_))=>return Err(Failure::Closed),Err(_)=>return Err(Failure::Timeout)},
        };
        let (sender,receiver)=oneshot::channel();
        let id={
            let mut state=self.pending.lock().map_err(|_|Failure::Closed)?;
            if state.closed{return Err(Failure::Closed)}
            if state.requests.len()>=32{return Err(Failure::Busy)}
            let id=state.next.checked_add(1).filter(|id|*id<=i64::MAX as u64).ok_or(Failure::Closed)?;
            state.next=id;state.requests.insert(id,sender);
            let line=format!("MCRF_BS_OWNER {} {} {} {} {} {} {}",self.generation,id,protocol,source.ip(),source.port(),destination.ip(),destination.port());
            // The same lock orders ID allocation and queue admission. A writer
            // may drain this queue later; it may not call respond synchronously.
            if (self.emit)(&line).is_err(){state.requests.remove(&id);return Err(Failure::Pipe)}
            id
        };
        let _ticket=Ticket{broker:self,id};
        tokio::select! {
            _=cancel.cancelled()=>Err(Failure::Cancelled),
            result=tokio::time::timeout_at(deadline,receiver)=>match result {
                Ok(Ok(reply))=>reply,Ok(Err(_))=>Err(Failure::Closed),Err(_)=>Err(Failure::Timeout),
            }
        }
    }
    /// true consumes a control response, including an already retired ticket.
    /// A bad current response is an explicit protocol error, never a UID default.
    pub fn respond(&self,line:&str)->Result<bool,Failure>{
        let Some(body)=line.strip_prefix("BS_OWNER|") else{return Ok(false)};
        if line.len()>16383||!line.is_ascii(){return Err(Failure::Invalid)}
        let fields:Vec<_>=body.split('|').collect();if fields.len()!=4{return Err(Failure::Invalid)}
        let generation=positive(fields[0])?;let id=positive(fields[1])?;
        if generation!=self.generation{return Err(Failure::Stale)}
        let mut state=self.pending.lock().map_err(|_|Failure::Closed)?;
        if state.closed{return Err(Failure::Closed)}
        if !state.requests.contains_key(&id){return Ok(true)}
        let owner=if fields[2]=="-1" {
            if !fields[3].is_empty(){return Err(Failure::Invalid)}Err(Failure::Denied)
        }else{
            let uid=if fields[2]=="0"{0}else{positive(fields[2])?};if uid>i32::MAX as u64{return Err(Failure::Invalid)}
            if fields[3].len()>15000{return Err(Failure::Invalid)}
            let bytes=STANDARD.decode(fields[3]).map_err(|_|Failure::Invalid)?;
            if bytes.len()>11000||STANDARD.encode(&bytes)!=fields[3]{return Err(Failure::Invalid)}
            let packages:Vec<String>=serde_json::from_slice(&bytes).map_err(|_|Failure::Invalid)?;
            if packages.len()>64||packages.iter().any(|p|!package(p))||packages.iter().collect::<HashSet<_>>().len()!=packages.len(){return Err(Failure::Invalid)}
            Ok(Owner{uid:uid as u32,packages})
        };
        let sender=state.requests.remove(&id).ok_or(Failure::Invalid)?;
        let _=sender.send(owner);Ok(true)
    }
    pub fn close(&self){self.admission.close();if let Ok(mut state)=self.pending.lock(){state.closed=true;for(_,sender) in state.requests.drain(){let _=sender.send(Err(Failure::Closed));}}}
}

pub fn configure(generation:u64)->Result<Arc<Broker>,Failure>{
    configure_output(generation,None)
}
pub fn configure_local_proxy(generation:u64,uid:u32)->Result<Arc<Broker>,Failure>{
    if uid>i32::MAX as u32{return Err(Failure::Invalid)}
    configure_output(generation,Some(uid))
}
fn configure_output(generation:u64,proxy_uid:Option<u32>)->Result<Arc<Broker>,Failure>{
    if ACTIVE.get().is_some(){return Err(Failure::Closed)}
    let(sender,receiver)=std::sync::mpsc::sync_channel::<String>(32);
    let mut broker=Broker::new(generation,Arc::new(move|line|sender.try_send(line.to_owned()).map_err(|_|Failure::Busy)))?;
    broker.proxy_uid=proxy_uid;
    let broker=Arc::new(broker);
    ACTIVE.set(broker.clone()).map_err(|_|Failure::Closed)?;
    let owned=broker.clone();
    std::thread::Builder::new().name("mcrf-bs-owner-output".into()).spawn(move||{
        use std::io::Write;
        for line in receiver {
            let mut output=std::io::stdout().lock();
            if writeln!(output,"{line}").and_then(|_|output.flush()).is_err(){owned.close();return}
        }
    }).map_err(|_|{broker.close();Failure::Pipe})?;
    Ok(broker)
}
pub fn control_response(line:&str)->Result<bool,Failure>{
    if !line.starts_with("BS_OWNER|"){return Ok(false)}
    ACTIVE.get().ok_or(Failure::Closed)?.respond(line)
}
pub fn control_output(line:&str)->Result<(),Failure>{
    (ACTIVE.get().ok_or(Failure::Closed)?.emit)(line)
}
pub fn broker()->Option<Arc<Broker>>{ACTIVE.get().cloned()}

#[cfg(test)]mod tests {
    use super::*;
    fn peer()->SocketAddr{"192.0.2.42:443".parse().unwrap()}
    fn source()->SocketAddr{"198.18.0.1:45678".parse().unwrap()}
    fn broker()->(Arc<Broker>,tokio::sync::mpsc::Receiver<String>){
        let(tx,rx)=tokio::sync::mpsc::channel(32);
        (Arc::new(Broker::new(42,Arc::new(move|line|tx.try_send(line.to_owned()).map_err(|_|Failure::Busy))).unwrap()),rx)
    }
    #[tokio::test]
    async fn actual_control_round_trip_preserves_shared_uid_packages(){
        let(b,mut requests)=broker();let worker=b.clone();let task=tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await});
        assert_eq!(requests.recv().await.unwrap(),"MCRF_BS_OWNER 42 1 6 198.18.0.1 45678 192.0.2.42 443");
        let payload=STANDARD.encode(br#"["org.example.one","org.example.two"]"#);
        assert_eq!(b.respond(&format!("BS_OWNER|42|1|10001|{payload}")),Ok(true));
        let owner=task.await.unwrap().unwrap();assert_eq!(owner.uid,10001);assert_eq!(owner.packages,vec!["org.example.one","org.example.two"]);
        assert_eq!(b.respond("BS_OWNER|42|1|0|W10="),Ok(true));
    }
    #[tokio::test]
    async fn stale_invalid_or_unknown_responses_cannot_poison_a_pending_owner(){
        let(b,mut requests)=broker();let worker=b.clone();let task=tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await});requests.recv().await.unwrap();
        assert_eq!(b.respond("BS_OWNER|43|1|0|W10="),Err(Failure::Stale));
        for line in ["BS_OWNER|42|01|0|W10=","BS_OWNER|42|1|01|W10=","BS_OWNER|42|1|-1|W10=","BS_OWNER|42|1|0|bnVsbA=="] {assert_eq!(b.respond(line),Err(Failure::Invalid))}
        assert_eq!(b.respond("BS_OWNER|42|99|0|W10="),Ok(true));assert!(!task.is_finished());
        assert_eq!(b.respond("BS_OWNER|42|1|-1|"),Ok(true));assert!(matches!(task.await.unwrap(),Err(Failure::Denied)));
    }
    #[tokio::test(start_paused=true)]
    async fn deadline_and_cancellation_retire_only_their_owned_ticket(){
        let(b,mut requests)=broker();let worker=b.clone();let task=tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await});requests.recv().await.unwrap();
        tokio::time::advance(Duration::from_secs(2)).await;assert!(matches!(task.await.unwrap(),Err(Failure::Timeout)));
        assert_eq!(b.respond("BS_OWNER|42|1|0|W10="),Ok(true));
        let cancel=CancellationToken::new();let owned=cancel.clone();let worker=b.clone();let task=tokio::spawn(async move{worker.query(17,source(),peer(),&owned).await});requests.recv().await.unwrap();cancel.cancel();
        assert!(matches!(task.await.unwrap(),Err(Failure::Cancelled)));assert!(b.pending.lock().unwrap().requests.is_empty());
    }
    #[tokio::test]
    async fn queue_bounds_close_and_dropped_queries_do_not_leave_pending_requests(){
        let(b,mut requests)=broker();let mut tasks=Vec::new();
        for _ in 0..32 {let worker=b.clone();tasks.push(tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await}));requests.recv().await.unwrap();}
        let worker=b.clone();let waiting=tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await});tokio::task::yield_now().await;assert!(!waiting.is_finished());assert!(requests.try_recv().is_err());
        tasks.pop().unwrap().abort();let next=requests.recv().await.unwrap();assert!(next.starts_with("MCRF_BS_OWNER 42 33 "));assert_eq!(b.pending.lock().unwrap().requests.len(),32);
        b.close();for task in tasks{assert!(matches!(task.await.unwrap(),Err(Failure::Closed)))}
        assert!(matches!(waiting.await.unwrap(),Err(Failure::Closed)));
        assert!(matches!(b.query(6,source(),peer(),&CancellationToken::new()).await,Err(Failure::Closed)));
    }
    #[tokio::test]async fn a_burst_of_new_connections_queues_with_bounded_pending_instead_of_forcing_syn_retries(){
        let(b,mut requests)=broker();let mut tasks=Vec::new();for _ in 0..100{let worker=b.clone();tasks.push(tokio::spawn(async move{worker.query(6,source(),peer(),&CancellationToken::new()).await}));}
        for _ in 0..100{let line=requests.recv().await.unwrap();assert!(b.pending.lock().unwrap().requests.len()<=32);let id=line.split_whitespace().nth(2).unwrap();b.respond(&format!("BS_OWNER|42|{id}|10001|W10=")).unwrap();}
        for task in tasks{assert_eq!(task.await.unwrap().unwrap().uid,10001)}assert!(b.pending.lock().unwrap().requests.is_empty());assert_eq!(b.admission.available_permits(),32);
    }
}
