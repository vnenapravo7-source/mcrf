//! Exact modern DNS endpoint admission for the native executor. This parser
//! never downloads/reads a file or replaces a profile resolver with a default.
use std::{collections::HashMap,net::IpAddr};
use serde_json::{Value,Map};
use crate::mcrf_bs_dns_transport::{Transport,Direction};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Failure{Invalid,Unsupported,Reference,Cycle}
pub struct Remote{pub host:String,pub port:u16,pub direction:Direction,pub transport:Transport,pub resolver:Option<String>,pub strategy:String}
pub enum Server{Remote(Remote),Local,Hosts{predefined:HashMap<String,Vec<IpAddr>>,system:bool},Fake{inet4:Option<String>,inet6:Option<String>}}
pub struct Plan{servers:HashMap<String,Server>}
fn only(object:&Map<String,Value>,keys:&[&str])->Result<(),Failure>{if object.keys().any(|key|!keys.contains(&key.as_str())){Err(Failure::Unsupported)}else{Ok(())}}
fn text<'a>(object:&'a Map<String,Value>,key:&str)->Result<&'a str,Failure>{object.get(key).and_then(Value::as_str).ok_or(Failure::Invalid)}
fn strategy(value:Option<&Value>)->Result<String,Failure>{
    let value=match value{None=>"",Some(value)=>value.as_str().ok_or(Failure::Invalid)?};
    if !matches!(value,""|"prefer_ipv4"|"prefer_ipv6"|"ipv4_only"|"ipv6_only"){return Err(Failure::Unsupported)}Ok(value.to_owned())
}
fn host(value:&str)->Result<String,Failure>{
    if value.is_empty()||value.len()>253||value.contains('%'){return Err(Failure::Invalid)}
    if let Ok(ip)=value.parse::<IpAddr>(){return Ok(ip.to_string())}
    match url::Host::parse(value).map_err(|_|Failure::Invalid)?{
        url::Host::Domain(domain)=>{
            let labels=domain.strip_suffix('.').unwrap_or(&domain);
            if labels.is_empty()||labels.len()>253||labels.split('.').any(|v|v.is_empty()||v.len()>63){return Err(Failure::Invalid)}Ok(domain)
        },url::Host::Ipv4(ip)=>Ok(ip.to_string()),url::Host::Ipv6(ip)=>Ok(ip.to_string()),
    }
}
impl Plan{
    pub fn parse(document:&Value,dialers:&Value)->Result<Self,Failure>{
        let rows=document.get("servers").and_then(Value::as_array).ok_or(Failure::Invalid)?;
        if rows.is_empty()||rows.len()>256{return Err(Failure::Invalid)}let dialers=dialers.as_object().ok_or(Failure::Invalid)?;
        let mut servers=HashMap::new();
        for row in rows{
            let row=row.as_object().ok_or(Failure::Invalid)?;let tag=text(row,"tag")?;
            if tag.is_empty()||tag.len()>255||servers.contains_key(tag){return Err(Failure::Invalid)}
            let kind=text(row,"type")?;
            let server=match kind{
                "local"=>{only(row,&["type","tag"])?;Server::Local},
                "hosts"=>{
                    // Only the fixed OS hosts path can be opened by the later
                    // executor. Private profile files require explicit capture.
                    only(row,&["type","tag","predefined","path"])?;
                    if let Some(paths)=row.get("path"){
                        let paths=match paths{Value::Array(paths)=>paths.iter().collect::<Vec<_>>(),value=>vec![value]};
                        if paths.len()>4||paths.iter().any(|v|v.as_str()!=Some("/system/etc/hosts")){return Err(Failure::Unsupported)}
                    }
                    // In the selected primary, even predefined-only Hosts
                    // also reads the default OS hosts file. Retain that work.
                    let empty=Map::new();let records=row.get("predefined").map_or(Ok(&empty),|v|v.as_object().ok_or(Failure::Invalid))?;
                    if records.len()>65536{return Err(Failure::Invalid)}let mut hosts=HashMap::new();
                    for (name,values)in records{
                        let name=host(name.strip_suffix('.').unwrap_or(name))?;
                        let values=match values{Value::Array(rows)=>rows.iter().collect::<Vec<_>>(),value=>vec![value]};if values.is_empty()||values.len()>64{return Err(Failure::Invalid)}
                        let ips=values.into_iter().map(|v|v.as_str().ok_or(Failure::Invalid)?.parse::<IpAddr>().map_err(|_|Failure::Invalid)).collect::<Result<Vec<_>,_>>()?;
                        if hosts.insert(name,ips).is_some(){return Err(Failure::Invalid)}
                    }Server::Hosts{predefined:hosts,system:true}
                },
                "fakeip"=>{
                    only(row,&["type","tag","inet4_range","inet6_range"])?;let inet4=row.get("inet4_range").map(|v|v.as_str().map(str::to_owned).ok_or(Failure::Invalid)).transpose()?;
                    let inet6=row.get("inet6_range").map(|v|v.as_str().map(str::to_owned).ok_or(Failure::Invalid)).transpose()?;
                    if inet4.is_none()&&inet6.is_none(){return Err(Failure::Invalid)}
                    // Prefix arithmetic/collision admission belongs to the
                    // session's fake-address allocator before any TUN is opened.
                    Server::Fake{inet4,inet6}
                },
                "udp"|"tcp"|"tls"|"https"=>{
                    only(row,&["type","tag","server","server_port","path","tls","detour","domain_resolver"])?;
                    let original=text(row,"server")?;let address=host(original)?;
                    let port=row.get("server_port").map_or(Ok(match kind{"tls"=>853,"https"=>443,_=>53}),|v|v.as_u64().and_then(|n|u16::try_from(n).ok()).filter(|n|*n!=0).ok_or(Failure::Invalid))?;
                    let direction=match row.get("detour"){
                        None=>Direction::Direct,
                        Some(Value::String(tag))=>match dialers.get(tag){
                            Some(value) if value.as_str()==Some("proxy")=>Direction::Vpn,
                            Some(value) if value.as_str()==Some("direct")=>Direction::Direct,
                            Some(value)=>Direction::Egress(value.as_u64().and_then(|v|u8::try_from(v).ok()).filter(|v|*v<32).ok_or(Failure::Reference)?),
                            None=>return Err(Failure::Reference),
                        },
                        _=>return Err(Failure::Invalid),
                    };
                    let (resolver,resolution_strategy)=match row.get("domain_resolver"){
                        None=>(None,String::new()),Some(Value::String(tag))=>(Some(tag.clone()),String::new()),
                        Some(Value::Object(value))=>{only(value,&["server","strategy"])?;(Some(text(value,"server")?.to_owned()),strategy(value.get("strategy"))?)},_=>return Err(Failure::Invalid),
                    };
                    if address.parse::<IpAddr>().is_err()&&resolver.is_none(){return Err(Failure::Reference)}
                    let mut name=address.clone();
                    if let Some(tls)=row.get("tls"){
                        if !matches!(kind,"tls"|"https"){return Err(Failure::Unsupported)}let tls=tls.as_object().ok_or(Failure::Invalid)?;
                        only(tls,&["enabled","server_name","insecure"])?;
                        if tls.get("enabled").is_some_and(|v|v.as_bool()!=Some(true))||tls.get("insecure").is_some_and(|v|v.as_bool()!=Some(false)){return Err(Failure::Unsupported)}
                        if let Some(value)=tls.get("server_name"){name=host(value.as_str().ok_or(Failure::Invalid)?)?;}
                    }
                    let transport=match kind{
                        "udp"=>Transport::Udp,"tcp"=>Transport::Tcp,"tls"=>Transport::Tls{name},
                        "https"=>{let path=row.get("path").map_or(Ok("/dns-query"),|v|v.as_str().ok_or(Failure::Invalid))?;if !path.starts_with('/')||!path.bytes().all(|v|v>32&&v<127){return Err(Failure::Invalid)}Transport::Https{name,path:path.to_owned()}},_=>unreachable!(),
                    };
                    if kind!="https"&&row.contains_key("path"){return Err(Failure::Unsupported)}
                    Server::Remote(Remote{host:address,port,direction,transport,resolver,strategy:resolution_strategy})
                },_=>return Err(Failure::Unsupported),
            };servers.insert(tag.to_owned(),server);
        }
        for (tag,server)in &servers{
            let mut seen=std::collections::HashSet::from([tag.as_str()]);let mut current=server;
            while let Server::Remote(remote)=current{
                let Some(tag)=remote.resolver.as_deref()else{break};if !seen.insert(tag){return Err(Failure::Cycle)}current=servers.get(tag).ok_or(Failure::Reference)?;
                if matches!(current,Server::Fake{..}){return Err(Failure::Unsupported)}
            }
        }Ok(Self{servers})
    }
    pub fn server(&self,tag:&str)->Option<&Server>{self.servers.get(tag)}
    pub fn servers(&self)->impl Iterator<Item=(&str,&Server)>{self.servers.iter().map(|(tag,server)|(tag.as_str(),server))}
}
#[cfg(test)]mod tests{
 use super::*;use serde_json::json;
 fn parse(rows:Value)->Result<Plan,Failure>{Plan::parse(&json!({"servers":rows}),&json!({"p":"proxy","d":"direct","b":"block"}))}
 #[test]fn remote_endpoints_keep_transport_identity_and_explicit_dialer(){
  let p=parse(json!([{"type":"https","tag":"secure","server":"2001:db8::1","detour":"p","tls":{"server_name":"Dns.OWNED.invalid"}},{"type":"udp","tag":"plain","server":"192.0.2.1"}])).unwrap();
  let Some(Server::Remote(r))=p.server("secure")else{panic!()};assert_eq!(r.host,"2001:db8::1");assert_eq!(r.port,443);assert_eq!(r.direction,Direction::Vpn);
  assert!(matches!(&r.transport,Transport::Https{name,path}if name=="dns.owned.invalid"&&path=="/dns-query"));
  let Some(Server::Remote(r))=p.server("plain")else{panic!()};assert_eq!(r.direction,Direction::Direct);assert_eq!(r.port,53);
 }
 #[test]fn bootstrap_references_missing_cycles_and_fake_resolvers_are_rejected(){
  for rows in [json!([{"type":"udp","tag":"a","server":"dns.invalid"}]),json!([{"type":"udp","tag":"a","server":"dns.invalid","domain_resolver":"missing"}]),json!([{"type":"udp","tag":"a","server":"dns.invalid","domain_resolver":"b"},{"type":"udp","tag":"b","server":"other.invalid","domain_resolver":"a"}]),json!([{"type":"udp","tag":"a","server":"dns.invalid","domain_resolver":"f"},{"type":"fakeip","tag":"f","inet4_range":"198.18.0.0/15"}])]{assert!(parse(rows).is_err())}
  assert!(parse(json!([{"type":"udp","tag":"a","server":"dns.invalid","domain_resolver":{"server":"h","strategy":"ipv4_only"}},{"type":"hosts","tag":"h","predefined":{"dns.invalid":"192.0.2.1"}}])).is_ok());
 }
 #[test]fn unsupported_security_and_file_options_never_fall_back(){
  for extra in [json!({"tls":{"insecure":true}}),json!({"tls":{"enabled":false}}),json!({"tls":{"certificate_path":"/private/key"}}),json!({"detour":"missing"}),json!({"detour":"b"}),json!({"server_port":0}),json!({"path":"/bad\r\nheader"})]{
   let mut row=json!({"type":"https","tag":"a","server":"192.0.2.1"});for(k,v)in extra.as_object().unwrap(){row[k]=v.clone()}assert!(parse(json!([row])).is_err());
  }
  assert!(parse(json!([{"type":"hosts","tag":"h","path":"/data/private/hosts"}])).is_err());
  assert!(parse(json!([{"type":"quic","tag":"q","server":"192.0.2.1"}])).is_err());
 }
 #[test]fn hosts_normalize_names_retain_system_hosts_and_reject_collisions(){
  let p=parse(json!([{"type":"hosts","tag":"h","predefined":{"Owned.INVALID.":["192.0.2.1","2001:db8::1"]}}])).unwrap();
  let Some(Server::Hosts{predefined,system})=p.server("h")else{panic!()};assert!(*system);assert_eq!(predefined["owned.invalid"].len(),2);
  assert!(parse(json!([{"type":"hosts","tag":"h","predefined":{"Owned.invalid":"192.0.2.1","owned.invalid":"192.0.2.2"}}])).is_err());
 }
}
