use super::*;
use tokio::io::{AsyncReadExt,AsyncWriteExt};
use std::os::fd::AsRawFd;
fn plan()->Plan{let value=serde_json::json!([{"type":"mixed","tag":"owned","listen":"127.0.0.1","listen_port":10808}]);let mut plan=mcrf_bs_routing_policy::listeners::parse(Some(&value)).unwrap().remove(0);plan.address.set_port(0);plan}
fn request()->Vec<u8>{vec![5,1,0,5,1,0,1,198,51,100,7,1,187]}
#[tokio::test]async fn ownership_uses_the_actual_accepted_tuple_and_ack_waits_for_backend(){
 tokio::time::timeout(Duration::from_secs(5),async{
  let(tx,mut queries)=tokio::sync::mpsc::unbounded_channel();let broker=Arc::new(crate::mcrf_bs_owner::Broker::new(42,Arc::new(move|line|tx.send(line.to_owned()).map_err(|_|crate::mcrf_bs_owner::Failure::Pipe))).unwrap());
  let owner=broker.clone();let handler:Handler=Arc::new(move|mut accepted,cancel|{let owner=owner.clone();Box::pin(async move{
   assert!(matches!(&accepted.request.command,protocol::Command::Connect(protocol::Destination::Address(v))if *v=="198.51.100.7:443".parse().unwrap()));
   let proof=accepted.owner(&owner,&cancel).await.unwrap();assert_eq!(proof.uid,10001);accepted.stream.write_all(&protocol::success(accepted.request.protocol,None)).await.unwrap();let mut data=[0;4];accepted.stream.read_exact(&mut data).await.unwrap();assert_eq!(&data,b"echo");accepted.stream.write_all(&data).await.unwrap();Ok(())
  })});
  let parent=CancellationToken::new();let group=Group::start(42,vec![plan()],4,handler,Arc::new(|event|panic!("unexpected proxy event {event:?}")),&parent).unwrap();let local=group.addresses()[0];let mut client=TcpStream::connect(local).await.unwrap();let peer=client.local_addr().unwrap();let mut bytes=request();bytes.extend_from_slice(b"echo");client.write_all(&bytes).await.unwrap();
  let mut hello=[0;2];client.read_exact(&mut hello).await.unwrap();assert_eq!(hello,[5,0]);let query=queries.recv().await.unwrap();let fields=query.split_whitespace().collect::<Vec<_>>();assert_eq!(fields[4],peer.ip().to_string());assert_eq!(fields[5],peer.port().to_string());assert_eq!(fields[6],local.ip().to_string());assert_eq!(fields[7],local.port().to_string());
  let mut ack=[0;10];assert!(tokio::time::timeout(Duration::from_millis(10),client.read_exact(&mut ack)).await.is_err());broker.respond(&format!("BS_OWNER|42|{}|10001|W10=",fields[2])).unwrap();client.read_exact(&mut ack).await.unwrap();assert_eq!(ack,protocol::success(protocol::Protocol::Socks5,None).as_slice());let mut echo=[0;4];client.read_exact(&mut echo).await.unwrap();assert_eq!(&echo,b"echo");assert_eq!(client.read_u8().await.unwrap_err().kind(),std::io::ErrorKind::UnexpectedEof);
  group.close().await.unwrap();assert!(!parent.is_cancelled());assert!(TcpStream::connect(local).await.is_err());broker.close();
 }).await.unwrap()
}
#[tokio::test]async fn stopping_joins_handlers_closes_real_descriptors_and_keeps_parent_alive(){
 let(tx,mut rx)=tokio::sync::mpsc::unbounded_channel();let handler:Handler=Arc::new(move|accepted,_|{let tx=tx.clone();Box::pin(async move{tx.send(accepted.stream.get_ref().as_raw_fd()).unwrap();std::future::pending::<()>().await;drop(accepted);Ok(())})});
 let parent=CancellationToken::new();let group=Group::start(42,vec![plan()],2,handler,Arc::new(|_|{}),&parent).unwrap();let mut client=TcpStream::connect(group.addresses()[0]).await.unwrap();client.write_all(&request()).await.unwrap();let fd=tokio::time::timeout(Duration::from_secs(2),rx.recv()).await.unwrap().unwrap();assert!(unsafe{libc::fcntl(fd,libc::F_GETFD)}>=0);
 group.close().await.unwrap();assert_eq!(unsafe{libc::fcntl(fd,libc::F_GETFD)},-1);assert_eq!(std::io::Error::last_os_error().raw_os_error(),Some(libc::EBADF));let mut hello=[0;2];client.read_exact(&mut hello).await.unwrap();assert_eq!(hello,[5,0]);assert_eq!(client.read_u8().await.unwrap_err().kind(),std::io::ErrorKind::UnexpectedEof);assert!(!parent.is_cancelled());
}
#[tokio::test]async fn capacity_is_shared_and_excess_clients_cannot_create_backend_tasks(){
 let(tx,mut ready)=tokio::sync::mpsc::unbounded_channel();let count=Arc::new(std::sync::atomic::AtomicUsize::new(0));let calls=count.clone();let handler:Handler=Arc::new(move|accepted,_|{let tx=tx.clone();let calls=calls.clone();Box::pin(async move{calls.fetch_add(1,Ordering::SeqCst);tx.send(()).unwrap();std::future::pending::<()>().await;drop(accepted);Ok(())})});let capacity=Arc::new(AtomicBool::new(false));let reported=capacity.clone();
 let group=Group::start(42,vec![plan()],1,handler,Arc::new(move|event|{if event==Event::Capacity{reported.store(true,Ordering::Release)}}),&CancellationToken::new()).unwrap();let mut first=TcpStream::connect(group.addresses()[0]).await.unwrap();first.write_all(&request()).await.unwrap();tokio::time::timeout(Duration::from_secs(2),ready.recv()).await.unwrap().unwrap();
 let mut second=TcpStream::connect(group.addresses()[0]).await.unwrap();assert_eq!(tokio::time::timeout(Duration::from_secs(2),second.read_u8()).await.unwrap().unwrap_err().kind(),std::io::ErrorKind::UnexpectedEof);assert_eq!(count.load(Ordering::SeqCst),1);assert!(capacity.load(Ordering::Acquire));group.close().await.unwrap();
}
#[tokio::test]async fn all_bindings_are_released_when_one_required_endpoint_is_busy(){
 let reserved=TcpListener::bind("127.0.0.1:0").await.unwrap();let mut busy=plan();busy.address=reserved.local_addr().unwrap();let calls=Arc::new(AtomicBool::new(false));let called=calls.clone();let handler:Handler=Arc::new(move|_,_|{called.store(true,Ordering::Release);Box::pin(async{Ok(())})});
 assert!(matches!(Group::start(42,vec![plan(),busy],2,handler,Arc::new(|_|{}),&CancellationToken::new()),Err(Failure::Bind)));assert!(!calls.load(Ordering::Acquire));
}
