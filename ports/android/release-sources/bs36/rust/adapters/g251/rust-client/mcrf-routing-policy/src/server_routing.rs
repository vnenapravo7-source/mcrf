//! CSQTT server policy compiler. No persistent settings or network access.
use serde_json::{json,Map,Value};
use crate::{decode_document,Error,Policy};
use std::sync::{Arc,RwLock};

pub const MAX_BYTES:usize=4*1024*1024;

#[derive(Clone,Copy,PartialEq,Eq)]pub enum Mode{LocalOnly,ServerOnly,LocalFirst,ServerFirst}
impl Mode{
 pub fn parse(value:&str)->Result<Self,Error>{match value{"mcrf"=>Ok(Self::LocalOnly),"server"=>Ok(Self::ServerOnly),"mcrf_first"=>Ok(Self::LocalFirst),"server_first"=>Ok(Self::ServerFirst),_=>Err(Error::Value)}}
 pub fn token(self)->&'static str{match self{Self::LocalOnly=>"mcrf",Self::ServerOnly=>"server",Self::LocalFirst=>"mcrf_first",Self::ServerFirst=>"server_first"}}
}
struct Versioned{revision:u64,source:Vec<u8>,policy:Option<Arc<Policy>>}
pub struct State{mode:Mode,value:RwLock<Versioned>}
impl State{
 pub fn new(enabled:bool)->Self{Self::with_mode(if enabled{Mode::ServerOnly}else{Mode::LocalOnly})}
 pub fn with_mode(mode:Mode)->Self{Self{mode,value:RwLock::new(Versioned{revision:0,source:Vec::new(),policy:None})}}
 pub fn enabled(&self)->bool{self.mode!=Mode::LocalOnly}
 pub fn mode(&self)->Mode{self.mode}
 pub fn revision(&self)->Result<u64,Error>{Ok(self.value.read().map_err(|_|Error::Value)?.revision)}
 pub fn snapshot(&self,revision:u64)->Result<Option<Arc<Policy>>,Error>{
  let value=self.value.read().map_err(|_|Error::Value)?;
  if revision!=value.revision{return Err(Error::Value)}Ok(value.policy.clone())
 }
 /// Compile before publishing; a malformed update leaves the old policy intact.
 /// Duplicate worker announcements do not invalidate in-flight classifications.
 pub fn update(&self,bytes:&[u8],templates:&str)->Result<u64,Error>{
  if bytes.len()>MAX_BYTES{return Err(Error::Limit)}
  {let value=self.value.read().map_err(|_|Error::Value)?;if value.source==bytes{return Ok(value.revision)}}
  let policy=if bytes.is_empty(){None}else{Some(Arc::new(compile(bytes,templates)?))};
  let mut value=self.value.write().map_err(|_|Error::Value)?;
  if value.source==bytes{return Ok(value.revision)}
  let revision=if self.enabled(){value.revision.checked_add(1).ok_or(Error::Limit)?}else{value.revision};
  value.source=bytes.to_vec();value.policy=if self.enabled(){policy}else{None};value.revision=revision;Ok(revision)
 }
}

fn boolean(row:&Map<String,Value>,key:&str,default:bool)->Result<bool,Error>{
 match row.get(key){None=>Ok(default),Some(v)=>v.as_bool().ok_or(Error::Value)}
}
fn white(row:&Map<String,Value>,key:&str,mode:&str,default:bool)->Result<bool,Error>{
 let fallback=match row.get(mode){None=>default,Some(Value::String(v))=>match v.as_str(){"whitelist"=>true,"blacklist"=>false,_=>return Err(Error::Value)},_=>return Err(Error::Value)};
 boolean(row,key,fallback)
}
fn strings(row:&Map<String,Value>,key:&str)->Result<Vec<String>,Error>{
 let Some(value)=row.get(key)else{return Ok(Vec::new())};
 let list=value.as_array().ok_or(Error::Value)?;if list.len()>4096{return Err(Error::Limit)}
 let mut out=std::collections::BTreeSet::new();
 for value in list{let text=value.as_str().ok_or(Error::Value)?.trim();if text.is_empty(){continue}if text.len()>255||text.chars().any(char::is_control){return Err(Error::Value)}out.insert(text.to_owned());}
 Ok(out.into_iter().collect())
}
fn domain(value:&str)->Result<String,Error>{
 let value=value.trim_end_matches('.').to_ascii_lowercase();
 if value.is_empty()||value.len()>253||!value.split('.').all(|label|!label.is_empty()&&label.len()<=63&&label.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'||b==b'_')){return Err(Error::Value)}
 Ok(value)
}

/// Modes match the original client: package scope gates the tunnel, direct
/// domain/IP exceptions precede whitelist matches; remaining whitelist traffic
/// goes direct. Templates are used only in domain blacklist mode.
pub fn compile(bytes:&[u8],templates:&str)->Result<Policy,Error>{
 let value=decode_document(bytes,MAX_BYTES)?;let row=value.as_object().ok_or(Error::Document)?;
 crate::only(row,&["version","name","mode","is_whitelist","packages","domains","domains_mode","domains_is_whitelist","template_domains","ip_mode","ip_is_whitelist","ips"])?;
 if row.get("version").is_some_and(|v|!matches!(v.as_u64(),Some(1|2))){return Err(Error::Unsupported)}
 if row.get("name").is_some_and(|v|v.as_str().is_none_or(|s|s.len()>1024)){return Err(Error::Value)}
 let apps_white=white(row,"is_whitelist","mode",false)?;
 let domains_white=white(row,"domains_is_whitelist","domains_mode",apps_white)?;
 let ips_white=white(row,"ip_is_whitelist","ip_mode",false)?;
 let template=boolean(row,"template_domains",false)?;
 let packages=strings(row,"packages")?;
 if packages.iter().any(|p|!p.split('.').all(|s|!s.is_empty()&&(s.as_bytes()[0].is_ascii_alphabetic()||s.as_bytes()[0]==b'_')&&s.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'_'))){return Err(Error::Value)}
 let mut domains=strings(row,"domains")?.iter().map(|d|domain(d)).collect::<Result<Vec<_>,_>>()?;
 let domain_enabled=template||!domains.is_empty();
 if template&&!domains_white{
  if templates.len()>MAX_BYTES{return Err(Error::Limit)}
  for line in templates.lines(){let line=line.trim();if !line.is_empty()&&!line.starts_with('#'){domains.push(domain(line)?);}}
  domains.sort();domains.dedup();
 }
 let ips=strings(row,"ips")?.into_iter().map(|text|{
  if text.contains('/'){return Ok(text)}
  let ip=text.parse::<std::net::IpAddr>().map_err(|_|Error::Value)?;
  Ok(format!("{ip}/{}",if ip.is_ipv4(){32}else{128}))
 }).collect::<Result<Vec<_>,Error>>()?;
 let mut rules=Vec::new();
 if !packages.is_empty(){rules.push(json!({"match":{"package_name":packages,"invert":apps_white},"target":"direct"}));}
 // An empty package whitelist admits no applications, like VPN include mode.
 if apps_white&&packages.is_empty(){return Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":"direct","rules":[{"match":{"source_ip_cidr":["0.0.0.0/0","::/0"]},"target":"direct"}]})).map_err(|_|Error::Document)?)}
 if !domains_white&&!domains.is_empty(){rules.push(json!({"match":{"domain_suffix":domains},"target":"direct"}));}
 if !ips_white&&!ips.is_empty(){rules.push(json!({"match":{"ip_cidr":ips},"target":"direct"}));}
 if domains_white&&!domains.is_empty(){rules.push(json!({"match":{"domain_suffix":domains},"target":"proxy"}));}
 if ips_white&&!ips.is_empty(){rules.push(json!({"match":{"ip_cidr":ips},"target":"proxy"}));}
 let fallback=if domains_white&&domain_enabled||ips_white&&!ips.is_empty(){"direct"}else{"proxy"};
 Policy::parse(&serde_json::to_vec(&json!({"schema":1,"defaultTarget":fallback,"rules":rules})).map_err(|_|Error::Document)?)
}
