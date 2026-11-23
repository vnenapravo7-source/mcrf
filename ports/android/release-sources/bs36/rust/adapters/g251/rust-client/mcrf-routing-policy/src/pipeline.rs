//! Ordered routing effects. The owner executes each yielded effect and resumes
//! with new metadata at the opaque cursor, never restarting/skipping rules.
//! Options are retained for separate runtime admission before opening a TUN.
use serde_json::{Value,json};use crate::{bundle::Bundle,Matcher,Policy,Flow,DestinationView,Error,Observed,Requirements,Target};
#[derive(Clone,Copy,Debug,PartialEq,Eq)]pub enum Kind{Sniff,HijackDns,Resolve,RouteOptions}
pub struct Operation{pub kind:Kind,pub options:Value}
#[derive(Clone,Copy)]pub struct Cursor{next:usize,server_revision:u64}
pub enum Instruction<'a>{Route(Target),Effect{operation:&'a Operation,next:Cursor},ResolveNeeded{retry:Cursor}}
enum Step{Route(Target),Effect(Operation)}
pub struct Pipeline{steps:Vec<(Option<Matcher>,Step)>,fallback:Target,server:Option<std::sync::Arc<crate::server_routing::State>>}
impl Pipeline{
 pub fn new(bundle:Bundle)->Result<Self,Error>{
  let(Policy{rules,fallback,..},document)=bundle.into_parts();let controls=document["controls"].as_array().ok_or(Error::Document)?;
  let mut matches=Vec::new();let mut operations=Vec::new();
  for control in controls{
   let mut operation=control["operation"].as_object().ok_or(Error::Document)?.clone();let action=operation.remove("action").ok_or(Error::Document)?;
   let(kind,keys)=match action.as_str(){
    Some("sniff")=>(Kind::Sniff,&["sniffer","timeout","override_destination"][..]),
    Some("hijack-dns")=>(Kind::HijackDns,&[][..]),
    Some("resolve")=>(Kind::Resolve,&["server","strategy","disable_cache","disable_optimistic_cache","rewrite_ttl","timeout","client_subnet","remove_client_subnet"][..]),
    Some("route-options")=>(Kind::RouteOptions,&["override_address","override_port","network_strategy","fallback_delay","udp_disable_domain_unmapping","udp_connect","udp_timeout","tls_fragment","tls_fragment_fallback_delay","tls_record_fragment","tls_record_fragment_fallback_delay"][..]),_=>return Err(Error::Unsupported),
   };
   let mut options=serde_json::Map::new();for key in keys{if let Some(value)=operation.remove(*key){options.insert((*key).to_owned(),value);}}
   matches.push(json!({"match":operation}));operations.push(Operation{kind,options:Value::Object(options)});
  }
  let mut envelope=json!({"rules":matches});if let Some(sets)=document["policy"].get("ruleSets"){envelope["ruleSets"]=sets.clone()}
  let expanded=crate::rule_sets::expand(envelope)?;let mut budget=100000;
  let mut matched=expanded["rules"].as_array().ok_or(Error::Document)?.iter().map(|row|{
   let value=&row["match"];if value.as_object().is_some_and(|v|v.is_empty()){Ok(None)}else{Matcher::parse(value,0,&mut budget).map(Some)}
  }).collect::<Result<Vec<_>,_>>()?.into_iter().zip(operations).peekable();
  let count=rules.len();let mut rules=rules.into_iter();let mut position=0;let mut steps=Vec::with_capacity(count+controls.len());
  for index in 0..=count{
   while controls.get(position).is_some_and(|row|row["beforeRule"].as_u64()==Some(index as u64)){
    let(matcher,operation)=matched.next().ok_or(Error::Document)?;steps.push((matcher,Step::Effect(operation)));position+=1;
   }
   if let Some((matcher,target))=rules.next(){steps.push((Some(matcher),Step::Route(target)));}
  }
  if position!=controls.len()||matched.next().is_some(){return Err(Error::Document)}Ok(Self{steps,fallback,server:None})
 }
 pub fn with_server(mut self,server:std::sync::Arc<crate::server_routing::State>)->Self{
  if server.enabled()&&!matches!(self.steps.first(),Some((None,Step::Effect(Operation{kind:Kind::Sniff,options}))) if options.as_object().is_some_and(|v|v.is_empty())){
   self.steps.insert(0,(None,Step::Effect(Operation{kind:Kind::Sniff,options:json!({})})));
  }
  self.server=Some(server);self
 }
 pub fn start(&self)->Cursor{Cursor{next:0,server_revision:self.server.as_ref().map_or(0,|s|s.revision().unwrap_or(u64::MAX))}}
 pub fn requirements(&self)->Requirements{let mut needed=Requirements::default();for(matcher,_)in &self.steps{if let Some(matcher)=matcher{matcher.requirements(&mut needed)}}needed}
 /// Metadata still observable after an effect. Used only to omit sniffing
 /// whose result cannot affect any subsequent matcher or destination.
 pub fn requirements_from(&self,cursor:Cursor)->Result<Requirements,Error>{
  if cursor.next>self.steps.len(){return Err(Error::Value)}
  let mut needed=Requirements::default();
  if let Some(server)=&self.server{if let Some(policy)=server.snapshot(cursor.server_revision)?{let requirements=policy.requirements();needed.domain|=requirements.domain;needed.owner|=requirements.owner;}}
  for(matcher,_)in self.steps.iter().skip(cursor.next){if let Some(matcher)=matcher{matcher.requirements(&mut needed)}}
  Ok(needed)
 }
 /// Determine if a sniff can affect this particular flow. Unknown domain and
 /// protocol are distinct from known absence. A definitely false application
 /// or port condition cannot require inspection, even with unknown metadata.
 pub fn sniff_needed(&self,flow:&Flow<'_>,destination:DestinationView<'_>,cursor:Cursor)->Result<bool,Error>{
  destination.validate()?;if cursor.next>self.steps.len(){return Err(Error::Value)}
  let pending=Flow{domain:Observed::Pending,protocol:Observed::Pending,..*flow};
  if let Some(server)=&self.server{if let Some(policy)=server.snapshot(cursor.server_revision)?{
   match policy.decide_with_destination(&pending,destination){Err(Error::MissingDomain|Error::MissingProtocol)=>return Ok(true),Err(Error::MissingResolution) if policy.requirements().domain=>return Ok(true),Ok(_)|Err(Error::MissingResolution)=>{},Err(error)=>return Err(error)}
  }}
  for(matcher,step)in self.steps.iter().skip(cursor.next){
   match matcher.as_ref().map_or(Ok(true),|m|m.matches_in(&pending,destination)){
    Ok(false)=>continue,
    Ok(true) if matches!(step,Step::Route(_)|Step::Effect(Operation{kind:Kind::HijackDns,..}))=>return Ok(false),
    Err(Error::MissingDomain|Error::MissingProtocol)=>return Ok(true),
    // Effects may change the destination before another matcher runs.
    Ok(true)|Err(Error::MissingResolution|Error::MissingOwner|Error::MissingNetwork)=>{
     let needed=self.requirements_from(cursor)?;return Ok(needed.domain||needed.protocol)
    },
    Err(error)=>return Err(error),
   }
  }
  Ok(false)
 }
 pub fn operations(&self)->impl Iterator<Item=&Operation>{self.steps.iter().filter_map(|(_,step)|match step{Step::Effect(operation)=>Some(operation),_=>None})}
 pub fn next(&self,flow:&Flow<'_>,destination:DestinationView<'_>,cursor:Cursor)->Result<Instruction<'_>,Error>{
  destination.validate()?;if cursor.next>self.steps.len(){return Err(Error::Value)}
  let server=match &self.server{Some(server)=>server.snapshot(cursor.server_revision)?,None=>None};
  let mode=self.server.as_ref().map(|s|s.mode());
  for(index,(matcher,step))in self.steps.iter().enumerate().skip(cursor.next){
   if mode==Some(crate::server_routing::Mode::ServerOnly)&&matches!(step,Step::Effect(Operation{kind:Kind::Resolve|Kind::RouteOptions,..})){continue}
   if matches!(step,Step::Route(_)){
    let decision=match mode{
     Some(crate::server_routing::Mode::ServerOnly)=>Some(match &server{Some(policy)=>policy.decide_with_destination(flow,destination).map(Some),None=>Ok(Some(Target::Tunnel))}),
     Some(crate::server_routing::Mode::ServerFirst)=>server.as_ref().map(|policy|policy.match_with_destination(flow,destination)),_=>None,
    };
    if let Some(decision)=decision{match decision{Ok(Some(target))=>return Ok(Instruction::Route(target)),Ok(None)=>{},Err(Error::MissingResolution)=>return Ok(Instruction::ResolveNeeded{retry:Cursor{next:index,..cursor}}),Err(error)=>return Err(error)}}
   }
   let matched=match matcher.as_ref().map_or(Ok(true),|m|m.matches_in(flow,destination)){
    Ok(value)=>value,Err(Error::MissingResolution)=>return Ok(Instruction::ResolveNeeded{retry:Cursor{next:index,..cursor}}),Err(error)=>return Err(error),
   };if matched{
   return Ok(match step{Step::Route(target)=>Instruction::Route(target.clone()),Step::Effect(operation)=>Instruction::Effect{operation,next:Cursor{next:index+1,..cursor}}})
  }}
  let decision=match mode{
   Some(crate::server_routing::Mode::ServerOnly)=>Some(match &server{Some(policy)=>policy.decide_with_destination(flow,destination),None=>Ok(Target::Tunnel)}),
   Some(crate::server_routing::Mode::ServerFirst)=>server.as_ref().map(|policy|policy.decide_with_destination(flow,destination)),
   Some(crate::server_routing::Mode::LocalFirst)=>server.as_ref().map(|policy|policy.match_with_destination(flow,destination).map(|target|target.unwrap_or(self.fallback))),_=>None,
  };
  if let Some(decision)=decision{return match decision{Ok(target)=>Ok(Instruction::Route(target)),Err(Error::MissingResolution)=>Ok(Instruction::ResolveNeeded{retry:cursor}),Err(error)=>Err(error)}}
  Ok(Instruction::Route(self.fallback.clone()))
 }
}
