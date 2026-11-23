//! Domain-only FakeIP identity. Resolving the real destination belongs to the
//! selected FLOW route, not the DNS query that allocated this address. Entries
//! remain immutable until session close so cached answers cannot alias another
//! domain. Capacity pressure fails explicitly instead of reassigning addresses.
use super::{Pool,Error};use std::{collections::{HashMap,HashSet},net::IpAddr};
pub struct Table{generation:u64,closed:bool,capacity:usize,v4:Option<Pool>,v6:Option<Pool>,excluded:HashSet<IpAddr>,names:HashMap<IpAddr,String>,reverse:HashMap<(String,bool),IpAddr>,flows:HashMap<u64,IpAddr>}
impl Table{
 pub fn new(generation:u64,capacity:usize,inet4:Option<&str>,inet6:Option<&str>,excluded:&[IpAddr])->Result<Self,Error>{
  if generation==0||generation>i64::MAX as u64||!(1..=8192).contains(&capacity)||excluded.len()>1024{return Err(Error::Invalid)}
  let v4=inet4.map(|s|Pool::new(s,32)).transpose()?;let v6=inet6.map(|s|Pool::new(s,128)).transpose()?;if v4.is_none()&&v6.is_none(){return Err(Error::Invalid)}
  let mut excluded:HashSet<_>=excluded.iter().copied().collect();excluded.insert("198.19.255.53".parse().unwrap());excluded.insert("fdfe:dcba:9876:dd54::53".parse().unwrap());
  Ok(Self{generation,closed:false,capacity,v4,v6,excluded,names:HashMap::new(),reverse:HashMap::new(),flows:HashMap::new()})
 }
 fn check(&self,generation:u64)->Result<(),Error>{if self.closed{Err(Error::Closed)}else if self.generation!=generation{Err(Error::Stale)}else{Ok(())}}
 pub fn enabled(&self,ipv6:bool)->bool{if ipv6{self.v6.is_some()}else{self.v4.is_some()}}
 pub fn contains(&self,ip:IpAddr)->bool{self.v4.as_ref().is_some_and(|p|p.contains(ip))||self.v6.as_ref().is_some_and(|p|p.contains(ip))}
 pub fn overlaps(&self,other:&Self)->bool{
  for(left,right)in [(&self.v4,&other.v4),(&self.v6,&other.v6)]{if let(Some(left),Some(right))=(left,right){
   let address=|p:&Pool|if p.width==32{IpAddr::V4(std::net::Ipv4Addr::from(p.base as u32))}else{IpAddr::V6(std::net::Ipv6Addr::from(p.base))};
   if left.contains(address(right))||right.contains(address(left)){return true}
  }}false
 }
 pub fn allocate(&mut self,generation:u64,name:&str,ipv6:bool)->Result<IpAddr,Error>{
  self.check(generation)?;let name=name.strip_suffix('.').unwrap_or(name);
  if name.is_empty()||name.len()>253||!name.is_ascii()||name.split('.').any(|s|s.is_empty()||s.len()>63||s.bytes().any(|b|!b.is_ascii_alphanumeric()&&!matches!(b,b'-'|b'_'))){return Err(Error::Invalid)}
  let name=name.to_ascii_lowercase();if let Some(ip)=self.reverse.get(&(name.clone(),ipv6)){return Ok(*ip)}
  if self.names.len()>=self.capacity{return Err(Error::Capacity)}
  let selected=if ipv6{&mut self.v6}else{&mut self.v4};let mut pool=selected.clone().ok_or(Error::Family)?;
  let ip=pool.allocate(&self.excluded)?;*selected=Some(pool);self.names.insert(ip,name.clone());self.reverse.insert((name,ipv6),ip);Ok(ip)
 }
 pub fn lookup(&self,generation:u64,ip:IpAddr)->Result<Option<&str>,Error>{self.check(generation)?;Ok(self.names.get(&ip).map(String::as_str))}
 pub fn pin(&mut self,generation:u64,id:u64,ip:IpAddr)->Result<&str,Error>{
  self.check(generation)?;if id==0||id>i64::MAX as u64{return Err(Error::Invalid)}
  let name=self.names.get(&ip).ok_or(Error::Unknown)?;
  if let Some(prior)=self.flows.get(&id){if *prior!=ip{return Err(Error::Invalid)}}else{if self.flows.len()>=8192{return Err(Error::Capacity)}self.flows.insert(id,ip);}
  Ok(name)
 }
 pub fn release(&mut self,generation:u64,id:u64)->Result<(),Error>{self.check(generation)?;self.flows.remove(&id);Ok(())}
 pub fn close(&mut self){self.closed=true;self.names.clear();self.reverse.clear();self.flows.clear()}
}
