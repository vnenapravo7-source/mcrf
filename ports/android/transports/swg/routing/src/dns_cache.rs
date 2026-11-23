//! Bounded session-local response cache. No filesystem persistence, cross-
//! profile reuse, expiry guessing or default resolver. Caller must first select
//! the server and options, and attribute every delivered answer to its caller.
use std::collections::HashMap;use crate::dns_wire;
#[derive(Debug,PartialEq,Eq)]pub enum Error{Invalid,Stale,Closed,Clock,Wire}
#[derive(Hash,PartialEq,Eq)]struct Key{server:String,query:Vec<u8>}
struct Entry{bytes:Vec<u8>,expires:u64,used:u64,charge:usize}
pub struct Cache{generation:u64,capacity:usize,last:u64,sequence:u64,closed:bool,disable_expire:bool,bytes:usize,entries:HashMap<Key,Entry>}
const BUDGET:usize=32*1024*1024;
impl Cache{
 pub fn new(generation:u64,capacity:usize,disable_expire:bool)->Result<Self,Error>{
  if generation==0||generation>i64::MAX as u64||!(1..=65536).contains(&capacity){return Err(Error::Invalid)}
  Ok(Self{generation,capacity,last:0,sequence:0,closed:false,disable_expire,bytes:0,entries:HashMap::new()})
 }
 fn check(&mut self,generation:u64,now:u64)->Result<(),Error>{
  if self.closed{return Err(Error::Closed)}if generation!=self.generation{return Err(Error::Stale)}if now<self.last{return Err(Error::Clock)}self.last=now;Ok(())
 }
 fn key(server:&str,query:&[u8])->Result<Key,Error>{
  if server.is_empty()||server.len()>255{return Err(Error::Invalid)}dns_wire::question(query).map_err(|_|Error::Wire)?;
  let mut query=query.to_vec();query[..2].fill(0);Ok(Key{server:server.to_owned(),query})
 }
 pub fn store(&mut self,generation:u64,now:u64,server:&str,query:&[u8],reply:&[u8])->Result<(),Error>{
  self.check(generation,now)?;let key=Self::key(server,query)?;let ttl=dns_wire::cache_ttl(query,reply).map_err(|_|Error::Wire)?;
  if let Some(old)=self.entries.remove(&key){self.bytes-=old.charge}
  if ttl==0{return Ok(())}let expires=now.checked_add(u64::from(ttl)*1000).ok_or(Error::Invalid)?;
  let charge=key.server.len()+key.query.len()+reply.len()+256;if charge>BUDGET{return Err(Error::Invalid)}
  while self.entries.len()>=self.capacity||self.bytes+charge>BUDGET{
   let oldest=self.entries.iter().min_by_key(|(_,v)|v.used).map(|(k,_)|Key{server:k.server.clone(),query:k.query.clone()}).ok_or(Error::Invalid)?;
   let old=self.entries.remove(&oldest).ok_or(Error::Invalid)?;self.bytes-=old.charge;
  }
  self.sequence=self.sequence.checked_add(1).ok_or(Error::Invalid)?;
  let mut bytes=dns_wire::rewrite_ttl(query,reply,ttl).map_err(|_|Error::Wire)?;bytes[..2].fill(0);
  self.entries.insert(key,Entry{bytes,expires,used:self.sequence,charge});self.bytes+=charge;Ok(())
 }
 pub fn get(&mut self,generation:u64,now:u64,server:&str,query:&[u8])->Result<Option<Vec<u8>>,Error>{
  self.check(generation,now)?;let key=Self::key(server,query)?;
  if self.entries.get(&key).is_some_and(|e|!self.disable_expire&&now>=e.expires){let old=self.entries.remove(&key).ok_or(Error::Invalid)?;self.bytes-=old.charge;return Ok(None)}
  self.sequence=self.sequence.checked_add(1).ok_or(Error::Invalid)?;
  let Some(entry)=self.entries.get_mut(&key)else{return Ok(None)};entry.used=self.sequence;
  let mut reply=entry.bytes.clone();reply[..2].copy_from_slice(&query[..2]);
  if !self.disable_expire{let ttl=((entry.expires-now)/1000).min(u64::from(u32::MAX))as u32;reply=dns_wire::rewrite_ttl(query,&reply,ttl).map_err(|_|Error::Wire)?;}
  Ok(Some(reply))
 }
 pub fn close(&mut self){self.closed=true;self.entries.clear();self.bytes=0}
}
