//! One bounded owner of all UDP flow tasks. Completion removes the matching
//! generation before a tuple is reused; stop joins every task and drops queues.
use std::{collections::HashMap,net::SocketAddr,sync::Arc};
use tokio::{sync::mpsc,task::JoinSet};
use tokio_util::sync::CancellationToken;
use crate::{packet::PacketBuf,mcrf_bs_datagram::Datagram,mcrf_bs_udp_dispatch::{Session,Failure}};
type Key=(SocketAddr,SocketAddr);
#[derive(Debug)]pub enum Event{InvalidPacket,QueueFull,Capacity,Flow(Failure),TaskFailed}
#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid,TaskFailed,Exhausted}
pub type Report=Arc<dyn Fn(Event)+Send+Sync>;
struct Flow{id:u64,sender:mpsc::Sender<Datagram>}
type Completed=(Key,u64,Result<(),Failure>);
fn complete(result:Result<Completed,tokio::task::JoinError>,flows:&mut HashMap<Key,Flow>,report:&Report)->Result<(),Error>{
 match result{
  Ok((key,id,result))=>{if flows.get(&key).is_some_and(|flow|flow.id==id){flows.remove(&key);}if let Err(error)=result{report(Event::Flow(error))}Ok(())},
  Err(_)=>{report(Event::TaskFailed);Err(Error::TaskFailed)},
 }
}
/// Input belongs exclusively to this session's TUN reader. No shared/global
/// sender table may retain a closed association after this function returns.
pub async fn run(session:Arc<Session>,mut input:mpsc::Receiver<PacketBuf>,capacity:usize,queue:usize,report:Report,cancel:&CancellationToken)->Result<(),Error>{
 if !(1..=8192).contains(&capacity)||!(1..=64).contains(&queue){return Err(Error::Invalid)}
 let stopped=cancel.child_token();let mut tasks=JoinSet::<Completed>::new();let mut flows=HashMap::<Key,Flow>::with_capacity(capacity);let mut next=0u64;
 let outcome=async{
  loop{
   tokio::select!{biased;
    _=stopped.cancelled()=>return Ok(()),
    result=tasks.join_next(),if !tasks.is_empty()=>{if let Some(result)=result{complete(result,&mut flows,&report)?}},
    packet=input.recv()=>{
     let Some(packet)=packet else{return Ok(())};
     // Reap first so a packet following an idle expiry gets a new admission,
     // not a send into a stale receiver or an inherited route/owner.
     while let Some(result)=tasks.try_join_next(){complete(result,&mut flows,&report)?}
     let(source,destination,packet)=match Datagram::from_ip(packet){Ok(value)=>value,Err(_)=>{report(Event::InvalidPacket);continue}};
     let key=(source,destination);
     if let Some(flow)=flows.get(&key){match flow.sender.try_send(packet){Ok(())=>{},Err(mpsc::error::TrySendError::Full(_))=>report(Event::QueueFull),Err(mpsc::error::TrySendError::Closed(_))=>{report(Event::Capacity)}}continue}
     if tasks.len()>=capacity{report(Event::Capacity);continue}
     next=next.checked_add(1).ok_or(Error::Exhausted)?;let id=next;let(sender,receiver)=mpsc::channel(queue);
     // New bounded queue is empty. Keep ownership in the pool until sent.
     if sender.try_send(packet).is_err(){return Err(Error::TaskFailed)}
     flows.insert(key,Flow{id,sender});let worker=session.clone();let token=stopped.child_token();
     tasks.spawn(async move{let result=worker.serve(source,destination,receiver,&token).await;(key,id,result)});
    }
   }
  }
 }.await;
 stopped.cancel();flows.clear();tasks.abort_all();while tasks.join_next().await.is_some(){}
 // Drop queued input as well: the caller cannot keep pool slots alive through
 // an obsolete sender after the session has stopped.
 input.close();while input.try_recv().is_ok(){}
 outcome
}

#[cfg(test)]mod tests{
 use super::*;
 #[test]fn old_task_completion_cannot_remove_replacement_tuple(){
  let key=("10.89.0.2:45000".parse().unwrap(),"192.0.2.1:443".parse().unwrap());let(tx,_rx)=mpsc::channel(1);let mut flows=HashMap::new();flows.insert(key,Flow{id:2,sender:tx});let report:Report=Arc::new(|_|panic!("successful completion must not report failure"));
  complete(Ok((key,1,Ok(()))),&mut flows,&report).unwrap();assert_eq!(flows.get(&key).unwrap().id,2);complete(Ok((key,2,Ok(()))),&mut flows,&report).unwrap();assert!(flows.is_empty());
 }
}
