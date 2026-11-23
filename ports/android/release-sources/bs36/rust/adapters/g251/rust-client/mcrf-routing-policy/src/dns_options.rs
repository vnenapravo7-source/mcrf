//! Typed DNS query options. Admission is separate from implementation: retaining
//! an ECS/optimistic option here does not authorize a runtime to ignore it.
use std::time::Duration;use serde_json::Value;
#[derive(Clone,Debug,PartialEq,Eq)]pub enum Error{Invalid,Unsupported}
#[derive(Clone,Debug,PartialEq,Eq)]pub struct QueryOptions{pub strategy:String,pub disable_cache:bool,pub rewrite_ttl:Option<u32>,pub subnet:Option<String>,pub remove_subnet:bool,pub timeout:Duration}
pub struct RootOptions{pub query:QueryOptions,pub capacity:usize,pub disable_expire:bool,pub reverse_mapping:bool,pub optimistic:Option<Duration>}
fn boolean(value:Option<&Value>,default:bool)->Result<bool,Error>{value.map_or(Ok(default),|v|v.as_bool().ok_or(Error::Invalid))}
fn duration(value:&Value)->Result<Duration,Error>{
 let text=value.as_str().ok_or(Error::Invalid)?;let (number,scale)=if let Some(n)=text.strip_suffix("ms"){(n,1u64)}else if let Some(n)=text.strip_suffix('s'){(n,1000)}else if let Some(n)=text.strip_suffix('m'){(n,60000)}else{return Err(Error::Unsupported)};
 if number.is_empty()||!number.bytes().all(|b|b.is_ascii_digit()){return Err(Error::Invalid)}let ms=number.parse::<u64>().map_err(|_|Error::Invalid)?.checked_mul(scale).filter(|v|*v>0&&*v<=86400000).ok_or(Error::Invalid)?;Ok(Duration::from_millis(ms))
}
fn strategy(value:&Value)->Result<&str,Error>{let text=value.as_str().ok_or(Error::Invalid)?;if matches!(text,""|"prefer_ipv4"|"prefer_ipv6"|"ipv4_only"|"ipv6_only"){Ok(text)}else{Err(Error::Unsupported)}}
fn subnet(value:&Value)->Result<String,Error>{let text=value.as_str().ok_or(Error::Invalid)?;crate::Cidr::parse(text).map_err(|_|Error::Invalid)?;Ok(text.to_owned())}
impl QueryOptions{
 /// The selected primary only enables disable_cache on a matching operation;
 /// a later false does not undo an earlier true. Empty strategy also inherits.
 pub fn apply(&mut self,value:&Value)->Result<(),Error>{
  let row=value.as_object().ok_or(Error::Invalid)?;
  if row.keys().any(|k|!matches!(k.as_str(),"strategy"|"disable_cache"|"rewrite_ttl"|"client_subnet"|"remove_client_subnet"|"timeout")){return Err(Error::Unsupported)}
  if let Some(value)=row.get("strategy"){let text=strategy(value)?;if !text.is_empty(){self.strategy=text.to_owned()}}
  self.disable_cache|=boolean(row.get("disable_cache"),false)?;
  if let Some(value)=row.get("rewrite_ttl"){self.rewrite_ttl=Some(value.as_u64().and_then(|v|u32::try_from(v).ok()).ok_or(Error::Invalid)?)}
  if let Some(value)=row.get("timeout"){self.timeout=duration(value)?}
  if let Some(value)=row.get("client_subnet"){self.subnet=Some(subnet(value)?);self.remove_subnet=false;}
  if boolean(row.get("remove_client_subnet"),false)?{self.subnet=None;self.remove_subnet=true;}
  Ok(())
 }
}
impl RootOptions{
 pub fn parse(value:&Value)->Result<Self,Error>{
  let row=value.as_object().ok_or(Error::Invalid)?;
  let query=QueryOptions{strategy:row.get("strategy").map_or(Ok(""),strategy)?.to_owned(),disable_cache:boolean(row.get("disable_cache"),false)?,rewrite_ttl:None,subnet:row.get("client_subnet").map(subnet).transpose()?,remove_subnet:false,timeout:row.get("timeout").map_or(Ok(Duration::from_secs(10)),duration)?};
  let capacity=row.get("cache_capacity").map_or(Ok(1024),|v|v.as_u64().and_then(|v|usize::try_from(v).ok()).filter(|v|*v<=65536).map(|v|v.max(1024)).ok_or(Error::Invalid))?;
  // The pinned primary keys caches by transport unconditionally. The legacy
  // independent_cache flag remains accepted there; still validate its type.
  boolean(row.get("independent_cache"),true)?;
  let optimistic=match row.get("optimistic"){
   None=>None,Some(Value::Bool(false))=>None,Some(Value::Bool(true))=>Some(Duration::from_secs(30)),
   Some(Value::Object(v))=>{if v.keys().any(|k|!matches!(k.as_str(),"enabled"|"timeout")){return Err(Error::Unsupported)}let timeout=v.get("timeout").map_or(Ok(Duration::from_secs(30)),duration)?;if boolean(v.get("enabled"),false)?{Some(timeout)}else{None}},_=>return Err(Error::Invalid),
  };
  Ok(Self{query,capacity,disable_expire:boolean(row.get("disable_expire"),false)?,reverse_mapping:boolean(row.get("reverse_mapping"),false)?,optimistic})
 }
}
