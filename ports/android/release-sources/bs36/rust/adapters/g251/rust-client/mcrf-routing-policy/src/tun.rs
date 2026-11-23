//! Side-effect-free admission of the captured Android TUN document. This is a
//! plan, not proof that Android applied it; startup must compare its projection.
use std::net::IpAddr;
use serde_json::Value;
use crate::{Error,only};

#[derive(Clone,Debug,PartialEq,Eq)]pub struct Network{pub address:IpAddr,pub prefix:u8}
impl Network{
 pub fn parse(text:&str)->Result<Self,Error>{
  if text.len()>80{return Err(Error::Value)}let(address,prefix)=text.split_once('/').ok_or(Error::Value)?;
  let address=address.parse::<IpAddr>().map_err(|_|Error::Value)?;if matches!(address,IpAddr::V6(ip)if ip.to_ipv4_mapped().is_some()){return Err(Error::Value)}let prefix=prefix.parse::<u8>().map_err(|_|Error::Value)?;
  if prefix>if address.is_ipv4(){32}else{128}{return Err(Error::Value)}Ok(Self{address,prefix})
 }
 pub fn contains(&self,ip:IpAddr)->bool{match(self.address,ip){
  (IpAddr::V4(a),IpAddr::V4(b))=>self.prefix==0||(u32::from(a)^u32::from(b))>>(32-self.prefix)==0,
  (IpAddr::V6(a),IpAddr::V6(b))=>self.prefix==0||(u128::from(a)^u128::from(b))>>(128-self.prefix)==0,_=>false,
 }}
}
#[derive(Debug)]pub struct Plan{
 pub mtu:u16,pub addresses:Vec<Network>,pub routes:Vec<Network>,pub excluded_routes:Vec<Network>,
 pub included_packages:Vec<String>,pub excluded_packages:Vec<String>,
}
fn strings(value:Option<&Value>,maximum:usize)->Result<Vec<String>,Error>{
 let Some(value)=value else{return Ok(Vec::new())};let array=value.as_array().ok_or(Error::Value)?;if array.len()>maximum{return Err(Error::Limit)}
 let mut rows=Vec::new();for value in array{let text=value.as_str().filter(|v|!v.is_empty()&&v.len()<=255).ok_or(Error::Value)?;if rows.iter().any(|v|v==text){return Err(Error::Value)}rows.push(text.to_owned())}Ok(rows)
}
fn networks(value:Option<&Value>,maximum:usize)->Result<Vec<Network>,Error>{strings(value,maximum)?.iter().map(|s|Network::parse(s)).collect()}
fn boolean(row:&serde_json::Map<String,Value>,key:&str,default:bool)->Result<bool,Error>{row.get(key).map_or(Ok(default),|v|v.as_bool().ok_or(Error::Value))}
fn package(text:&str)->bool{
 let mut parts=text.split('.');let valid=|s:&str|{let mut bytes=s.bytes();bytes.next().is_some_and(|v|v.is_ascii_alphabetic()||v==b'_')&&bytes.all(|v|v.is_ascii_alphanumeric()||v==b'_')};
 parts.next().is_some_and(valid)&&parts.clone().next().is_some()&&parts.all(valid)
}
impl Plan{
 pub fn parse(value:&Value)->Result<Self,Error>{
  let row=value.as_object().ok_or(Error::Document)?;
  only(row,&["type","tag","address","mtu","stack","auto_route","strict_route","dns_mode","route_address","route_exclude_address","include_package","exclude_package","interface_name","platform","gso","endpoint_independent_nat"])?;
  if row.get("type").and_then(Value::as_str)!=Some("tun"){return Err(Error::Value)}
  for key in ["tag","interface_name"]{if let Some(v)=row.get(key){let s=v.as_str().filter(|s|s.len()<=255&&!s.contains('\0')).ok_or(Error::Value)?;if key=="interface_name"&&!s.is_empty(){return Err(Error::Unsupported)}}}
  // Android owns interface creation and capture. Disabled automatic/strict
  // capture, a platform proxy or alternate NAT cannot silently be ignored.
  if !boolean(row,"auto_route",true)?||!boolean(row,"strict_route",true)?||boolean(row,"gso",false)?||boolean(row,"endpoint_independent_nat",false)?{return Err(Error::Unsupported)}
  if row.get("platform").is_some_and(|v|!v.as_object().is_some_and(|v|v.is_empty())){return Err(Error::Unsupported)}
  if row.get("dns_mode").is_some_and(|v|v.as_str()!=Some("hijack")){return Err(Error::Unsupported)}
  // The selected BS backend supplies its original stack. These names belong
  // to the captured primary's implementation preference, not route policy.
  if row.get("stack").is_some_and(|v|!matches!(v.as_str(),Some("system"|"gvisor"|"mixed"))){return Err(Error::Unsupported)}
  let addresses=networks(row.get("address"),16)?;if addresses.is_empty()||addresses.iter().any(|v|v.address.is_unspecified()||v.address.is_loopback()||v.address.is_multicast()){return Err(Error::Value)}
  let ipv6=addresses.iter().any(|v|v.address.is_ipv6());let ipv4=addresses.iter().any(|v|v.address.is_ipv4());
  let mtu=row.get("mtu").map_or(Ok(1500),|v|v.as_u64().and_then(|v|u16::try_from(v).ok()).ok_or(Error::Value))?;
  if !((if ipv6{1280}else{576})..=9000).contains(&mtu){return Err(Error::Value)}
  let mut routes=networks(row.get("route_address"),8192)?;
  if row.contains_key("route_address")&&routes.is_empty(){return Err(Error::Value)}
  if routes.is_empty(){if ipv4{routes.push(Network::parse("0.0.0.0/0")?)}if ipv6{routes.push(Network::parse("::/0")?)}}
  let excluded_routes=networks(row.get("route_exclude_address"),8192)?;
  if routes.iter().chain(&excluded_routes).any(|v|if v.address.is_ipv4(){!ipv4}else{!ipv6}){return Err(Error::Value)}
  let included_packages=strings(row.get("include_package"),512)?;let excluded_packages=strings(row.get("exclude_package"),512)?;
  if !included_packages.is_empty()&&!excluded_packages.is_empty(){return Err(Error::Unsupported)}
  if included_packages.iter().chain(&excluded_packages).any(|v|!package(v)){return Err(Error::Value)}
  Ok(Self{mtu,addresses,routes,excluded_routes,included_packages,excluded_packages})
 }
 pub fn captures(&self,ip:IpAddr)->bool{self.routes.iter().any(|r|r.contains(ip))&&!self.excluded_routes.iter().any(|r|r.contains(ip))}
 /// A control socket must stay inside the VPN for the same-process tunnel leg.
 /// Main can explicitly add its own package to an include plan before startup.
 pub fn captures_host(&self,package:&str)->bool{!self.excluded_packages.iter().any(|p|p==package)&&(self.included_packages.is_empty()||self.included_packages.iter().any(|p|p==package))}
 pub fn projection(&self)->Value{
  let rows=|values:&[Network]|values.iter().map(|n|format!("{}/{}",n.address,n.prefix)).collect::<Vec<_>>();
  serde_json::json!({"schema":1,"mtu":self.mtu,"addresses":rows(&self.addresses),"routes":rows(&self.routes),"excludedRoutes":rows(&self.excluded_routes),"includedApps":self.included_packages,"excludedApps":self.excluded_packages,"hostMustBeInsideVpn":true,"requiresVpnNetworkHandle":true})
 }
}
