//! One immutable Main-captured session document. Parsing preserves every TUN,
//! DNS and conditional control field; it is NOT runtime feature admission.
use serde_json::Value;use crate::{Error,Policy,only,decode_document};
pub struct Bundle{pub policy:Policy,document:Value}
impl Bundle{
 pub fn parse(bytes:&[u8])->Result<Self,Error>{
  let document=decode_document(bytes,24*1024*1024)?;let root=document.as_object().ok_or(Error::Document)?;
  only(root,&["schema","policy","dns","controls","routeOptions","tunInbounds","dnsDialers","listeners","certificates","egressBindings"])?;
  if root.get("schema").and_then(Value::as_u64)!=Some(1){return Err(Error::Document)}
  let policy_value=root.get("policy").ok_or(Error::Document)?;
  let policy=Policy::parse(&serde_json::to_vec(policy_value).map_err(|_|Error::Document)?)?;
  match (policy.egress_tags(),root.get("egressBindings")){
   ([],None)=>{},
   ([],Some(_))|(_,None)=>return Err(Error::Document),
   (tags,Some(value))=>{
    let bindings=value.as_array().ok_or(Error::Document)?;
    if bindings.len()!=tags.len(){return Err(Error::Value)}
    let mut ports=std::collections::HashSet::new();
    for (row,tag) in bindings.iter().zip(tags){
     let row=row.as_object().ok_or(Error::Document)?;
     only(row,&["tag","port","username","password"])?;
     if row.get("tag").and_then(Value::as_str)!=Some(tag.as_str()){return Err(Error::Value)}
     let port=row.get("port").and_then(Value::as_u64).filter(|value|(1024..=65535).contains(value)).ok_or(Error::Value)?;
     if !ports.insert(port){return Err(Error::Value)}
     for key in ["username","password"]{
      let secret=row.get(key).and_then(Value::as_str).ok_or(Error::Value)?;
      if !(16..=255).contains(&secret.len())||!secret.bytes().all(|v|v.is_ascii_graphic()){return Err(Error::Value)}
     }
    }
   }
  }
  let rules=policy_value.get("rules").and_then(Value::as_array).ok_or(Error::Document)?.len();
  let controls=root.get("controls").and_then(Value::as_array).ok_or(Error::Document)?;if controls.len()>4096{return Err(Error::Limit)}let mut before=0;
  for row in controls{
   let row=row.as_object().ok_or(Error::Document)?;only(row,&["beforeRule","operation"])?;
   let position=row.get("beforeRule").and_then(Value::as_u64).and_then(|v|usize::try_from(v).ok()).filter(|v|*v<=rules&&*v>=before).ok_or(Error::Value)?;before=position;
   let operation=row.get("operation").and_then(Value::as_object).ok_or(Error::Document)?;
   if !matches!(operation.get("action").and_then(Value::as_str),Some("sniff"|"hijack-dns"|"resolve"|"route-options")){return Err(Error::Unsupported)}
  }
  let dns=root.get("dns").ok_or(Error::Document)?;
  if !dns.is_null()&&(!dns.is_object()||serde_json::to_vec(dns).map_err(|_|Error::Document)?.len()>1024*1024){return Err(Error::Document)}
  let tuns=root.get("tunInbounds").and_then(Value::as_array).ok_or(Error::Document)?;
  if tuns.len()>1{return Err(Error::Unsupported)}for tun in tuns{if tun.get("type").and_then(Value::as_str)!=Some("tun"){return Err(Error::Value)}}
  if serde_json::to_vec(tuns).map_err(|_|Error::Document)?.len()>1024*1024{return Err(Error::Limit)}
  let listeners=crate::listeners::parse(root.get("listeners"))?;
  if let Some(bindings)=root.get("egressBindings").and_then(Value::as_array){
   if bindings.iter().any(|row|listeners.iter().any(|listener|row["port"].as_u64()==Some(u64::from(listener.address.port())))){
    return Err(Error::Value)
   }
  }
  if let Some(value)=root.get("certificates"){
   let row=value.as_object().ok_or(Error::Document)?;only(row,&["store","roots"])?;if !matches!(row.get("store").and_then(Value::as_str),Some("system"|"mozilla")){return Err(Error::Unsupported)}
   let roots=row.get("roots").and_then(Value::as_array).ok_or(Error::Document)?;if roots.is_empty()||roots.len()>1024{return Err(Error::Limit)}let mut total=0usize;
   for value in roots{let text=value.as_str().ok_or(Error::Value)?;if text.is_empty()||text.len()>43692{return Err(Error::Limit)}total=total.checked_add(text.len()).filter(|v|*v<=3*1024*1024).ok_or(Error::Limit)?;}
  }
  let options=root.get("routeOptions").and_then(Value::as_object).ok_or(Error::Document)?;only(options,&["auto_detect_interface","find_process","default_domain_resolver"])?;
  let dialers=root.get("dnsDialers").and_then(Value::as_object).ok_or(Error::Document)?;if dialers.len()>256{return Err(Error::Limit)}
  if dialers.iter().any(|(tag,value)|tag.is_empty()||tag.len()>255||crate::target(value,policy.egress_tags()).is_err()){return Err(Error::Value)}
  Ok(Self{policy,document})
 }
 pub fn document(&self)->&Value{&self.document}
 pub fn sets(&self)->Option<&Value>{self.document["policy"].get("ruleSets")}
 pub fn runtime_dialers(&self)->Value{
  let mut result=self.document["dnsDialers"].clone();
  if let Some(dialers)=result.as_object_mut(){
   for value in dialers.values_mut(){
    if let Ok(crate::Target::Egress(slot))=crate::target(value,self.policy.egress_tags()){*value=Value::from(slot)}
   }
  }
  result
 }
 pub fn into_parts(self)->(Policy,Value){(self.policy,self.document)}
}
