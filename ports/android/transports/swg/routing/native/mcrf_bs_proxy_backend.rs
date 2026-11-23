//! Shared ordered TCP routing for authenticated local proxy connections.
//! Not enabled until UDP association and runtime ownership are integrated.
use std::{sync::Arc,net::SocketAddr,collections::HashSet};
use tokio::time::Instant;
use tokio_util::sync::CancellationToken;
use mcrf_bs_routing_policy::{Flow,Observed};
use crate::{mcrf_bs_proxy_listener::{Accepted,Identity,Handler,Failure},mcrf_bs_proxy_protocol::{Command,Destination,success},mcrf_bs_tcp_dispatch::{Stream,owned::{Session,Original,Decision}},mcrf_bs_tcp_inspect::Prefix,mcrf_bs_owner::Owner};
fn same(a:&Owner,b:&Owner)->bool{a.uid==b.uid&&a.packages.iter().collect::<HashSet<_>>()==b.packages.iter().collect::<HashSet<_>>()}
fn dns(session:Arc<Session>,identity:Identity,expected:Arc<Owner>,destination:SocketAddr,cancel:CancellationToken)->crate::mcrf_bs_dns_rpc::Resolver{
 Arc::new(move|request|{let(session,identity,expected,cancel)=(session.clone(),identity.clone(),expected.clone(),cancel.clone());Box::pin(async move{
  if request.protocol!=6||cancel.is_cancelled(){return Err(3)}
  let deadline=Instant::now()+mcrf_bs_routing_policy::dns_limits::RESPONSE;
  let first=tokio::time::timeout_at(deadline,identity.owner(&session.owner,&cancel)).await.map_err(|_|7u8)?.map_err(|_|4u8)?;if !same(&first,&expected){return Err(8)}
  let source=identity.peer();let flow=Flow{source:source.ip(),destination:destination.ip(),source_port:source.port(),port:destination.port(),network:"tcp",domain:Observed::Pending,protocol:Observed::Known(Some("dns")),packages:Some(&first.packages),underlay:Observed::Known(Some(session.underlay.borrowed()))};
  let answer=session.engine.resolve(session.sockets.as_ref(),&request.query,&flow,deadline,&cancel).await.map_err(|_|5u8)?;
  let fresh=tokio::time::timeout_at(deadline,identity.owner(&session.owner,&cancel)).await.map_err(|_|7u8)?.map_err(|_|8u8)?;if cancel.is_cancelled()||!same(&fresh,&expected){return Err(8)}
  session.engine.admit(&request.query,&answer,fresh.uid).map_err(|_|9u8)?;Ok(answer)
 })})
}
pub fn handler(session:Arc<Session>)->Handler{Arc::new(move|accepted,cancel|{let session=session.clone();Box::pin(async move{serve(session,None,accepted,&cancel).await})})}
pub fn mixed_handler(tcp:Arc<Session>,udp:Arc<crate::mcrf_bs_udp_dispatch::Session>)->Handler{mixed_handler_with_limits(tcp,udp,Arc::new(crate::mcrf_bs_proxy_udp::Limits::new()))}
pub(crate) fn mixed_handler_with_limits(tcp:Arc<Session>,udp:Arc<crate::mcrf_bs_udp_dispatch::Session>,limits:Arc<crate::mcrf_bs_proxy_udp::Limits>)->Handler{Arc::new(move|accepted,cancel|{let(tcp,udp,limits)=(tcp.clone(),udp.clone(),limits.clone());Box::pin(async move{serve(tcp,Some((udp,limits)),accepted,&cancel).await})})}
async fn serve(session:Arc<Session>,udp:Option<(Arc<crate::mcrf_bs_udp_dispatch::Session>,Arc<crate::mcrf_bs_proxy_udp::Limits>)>,accepted:Accepted,cancel:&CancellationToken)->Result<(),Failure>{
 let(identity,request,stream)=accepted.into_parts()?;
 let expected=Arc::new(identity.owner(&session.owner,cancel).await.map_err(|_|Failure::Backend)?);
 let destination=match request.command{Command::Connect(v)=>v,Command::Associate(v)=>{let Some((udp,limits))=udp else{return Err(Failure::Backend)};if request.protocol!=crate::mcrf_bs_proxy_protocol::Protocol::Socks5||!request.prefix.is_empty(){return Err(Failure::Backend)}return crate::mcrf_bs_proxy_udp::associate(udp,identity,expected,stream,v,limits,cancel).await}};
 let(destination,name)=match destination{
  Destination::Address(value)=>{let name=session.engine.proxy_destination_name(value.ip()).map_err(|_|Failure::Backend)?;(value,name)},
  Destination::Domain(name,port)=>(SocketAddr::from(([0,0,0,0],port)),Some(name)),
 };
 if destination.port()==0{return Err(Failure::Backend)}
 let observed=if name.is_some(){None}else{session.engine.domain(expected.uid,destination.ip()).map_err(|_|Failure::Backend)?};
 let mut stream:Stream=Box::new(crate::mcrf_bs_proxy_stream::Stream::new(stream,success(request.protocol,None)));
 let mut captured=Prefix{bytes:request.prefix,protocol:None,domain:None};
 let decision=tokio::select!{biased;_=cancel.cancelled()=>return Err(Failure::Cancelled),value=session.router.decide(Original{source:identity.peer(),destination,destination_domain:name.as_deref(),observed_domain:observed.as_deref(),uid:expected.uid,packages:&expected.packages},&mut stream,&mut captured,cancel)=>value.map_err(|_|Failure::Backend)?};
 let fresh=identity.owner(&session.owner,cancel).await.map_err(|_|Failure::Backend)?;if cancel.is_cancelled()||!same(&fresh,&expected){return Err(Failure::Backend)}
 match decision{
  Decision::Block=>Ok(()),
  Decision::Forward{peer,target}=>{if session.engine.virtual_address(peer.ip()).map_err(|_|Failure::Backend)?{return Err(Failure::Backend)}crate::mcrf_bs_tcp_inspect::forward_candidates(stream,&[peer],session.sockets.as_ref(),captured,target,cancel).await.map_err(|_|Failure::Backend)},
  Decision::ForwardMany{peers,target}=>{for peer in &peers{if session.engine.virtual_address(peer.ip()).map_err(|_|Failure::Backend)?{return Err(Failure::Backend)}}crate::mcrf_bs_tcp_inspect::forward_candidates(stream,&peers,session.sockets.as_ref(),captured,target,cancel).await.map_err(|_|Failure::Backend)},
  Decision::Dns=>{let source=identity.peer();let resolve=dns(session,identity,expected,destination,cancel.clone());crate::mcrf_bs_dns_frontend::serve_stream(&mut stream,captured.bytes,source.port(),resolve,cancel).await.map_err(|_|Failure::Backend)},
 }
}

#[cfg(test)]#[path="mcrf_bs_proxy_backend_tests.rs"]mod tests;
