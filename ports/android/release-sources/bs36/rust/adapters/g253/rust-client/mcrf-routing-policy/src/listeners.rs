//! Side-effect-free admission of explicitly captured local proxy listeners.
//! This is configuration, not evidence that a listener runtime is attached.
use std::net::{IpAddr,SocketAddr};
use serde_json::Value;
use crate::{Error,only};
#[derive(Clone,Copy,PartialEq,Eq,Debug)]pub enum Kind{Mixed,Socks,Http}
pub struct Credentials{username:Vec<u8>,password:Vec<u8>}
pub struct Plan{pub kind:Kind,pub address:SocketAddr,users:Vec<Credentials>}
impl Plan{
 pub fn authenticated(&self)->bool{!self.users.is_empty()}
 pub fn authorizes(&self,username:&[u8],password:&[u8])->bool{
  fn same(a:&[u8],b:&[u8])->bool{let mut difference=a.len()^b.len();for n in 0..255{difference|=usize::from(a.get(n).copied().unwrap_or(0)^b.get(n).copied().unwrap_or(0))}difference==0}
  let mut matched=false;for user in &self.users{matched|=same(&user.username,username)&same(&user.password,password)}matched
 }
}
pub fn parse(value:Option<&Value>)->Result<Vec<Plan>,Error>{
 let Some(value)=value else{return Ok(Vec::new())};let rows=value.as_array().ok_or(Error::Document)?;if rows.len()>16{return Err(Error::Limit)}
 let mut plans=Vec::<Plan>::new();let mut tags=std::collections::HashSet::new();
 for row in rows{
  let row=row.as_object().ok_or(Error::Document)?;only(row,&["type","tag","listen","listen_port","users"])?;
  let kind=match row.get("type").and_then(Value::as_str){Some("mixed")=>Kind::Mixed,Some("socks")=>Kind::Socks,Some("http")=>Kind::Http,_=>return Err(Error::Unsupported)};
  let tag=row.get("tag").and_then(Value::as_str).ok_or(Error::Value)?;if tag.is_empty()||tag.len()>255||tag.chars().any(char::is_control)||!tags.insert(tag){return Err(Error::Value)}
  let ip=row.get("listen").and_then(Value::as_str).ok_or(Error::Value)?.parse::<IpAddr>().map_err(|_|Error::Value)?;if !ip.is_loopback(){return Err(Error::Unsupported)}
  let port=row.get("listen_port").and_then(Value::as_u64).and_then(|v|u16::try_from(v).ok()).filter(|v|*v>=1024).ok_or(Error::Value)?;let address=SocketAddr::new(ip,port);
  if plans.iter().any(|p|p.address==address){return Err(Error::Value)}
  let mut users=Vec::new();if let Some(rows)=row.get("users"){
   let rows=rows.as_array().ok_or(Error::Value)?;if rows.len()>32{return Err(Error::Limit)}
   for row in rows{let row=row.as_object().ok_or(Error::Value)?;only(row,&["username","password"])?;
    let read=|key:&str|->Result<Vec<u8>,Error>{let value=row.get(key).and_then(Value::as_str).ok_or(Error::Value)?;if value.is_empty()||value.len()>255||value.chars().any(char::is_control){return Err(Error::Value)}Ok(value.as_bytes().to_vec())};
    let user=Credentials{username:read("username")?,password:read("password")?};if users.iter().any(|old:&Credentials|old.username==user.username){return Err(Error::Value)}users.push(user);
   }
  }plans.push(Plan{kind,address,users});
 }Ok(plans)
}
#[cfg(test)]mod tests{
 use super::*;use serde_json::json;
 fn document()->Value{json!([{"type":"mixed","tag":"local-proxy","listen":"127.0.0.1","listen_port":10808,"users":[{"username":"owned","password":"fixture"}]}])}
 #[test]fn credentials_and_listener_metadata_are_captured_without_binding(){let plans=parse(Some(&document())).unwrap();assert_eq!(plans.len(),1);let p=&plans[0];assert_eq!(p.kind,Kind::Mixed);assert_eq!(p.address,"127.0.0.1:10808".parse().unwrap());assert!(p.authenticated());assert!(p.authorizes(b"owned",b"fixture"));assert!(!p.authorizes(b"owned",b"fixture\0"));assert!(!p.authorizes(b"other",b"fixture"));assert!(!p.authorizes(b"owned",b""));}
 #[test]fn unknown_options_ambiguous_ports_and_public_bindings_are_rejected(){
  for(key,value)in [("listen",json!("0.0.0.0")),("listen",json!("localhost")),("type",json!("unknown")),("listen_port",json!(0)),("listen_port",json!(65536)),("sniff",json!(true))]{let mut d=document();d[0][key]=value;assert!(parse(Some(&d)).is_err())}
  let mut d=document();let duplicate=d[0].clone();d.as_array_mut().unwrap().push(duplicate);assert!(parse(Some(&d)).is_err());
  let mut d=document();d[0]["users"][0]["password"]=json!("private\nvalue");assert!(parse(Some(&d)).is_err());
 }
}
