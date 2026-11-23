//! Source-only ordered DNS selection from the SAME compiled document. Retains
//! transport/options/actions for the executor's separate capability admission;
//! a parsed selector alone does not authorize starting a native DNS runtime.
use std::collections::HashMap;
use serde_json::{Value,json};
use crate::{Error,only,dns_rules::{DnsMatcher,Question}};
pub enum Action {Route{server:String,options:Value},Predefined(Value),Reject(Value),Options(Value)}
pub struct Selection<'a>{pub action:&'a Action,pub preceding_options:Vec<&'a Value>}
pub struct Selector{servers:HashMap<String,Value>,rules:Vec<(DnsMatcher,Action)>,fallback:Action,document:Value}
impl Selector{
    pub fn parse(document:&Value,sets:&Value)->Result<Self,Error>{
        let root=document.as_object().ok_or(Error::Document)?;
        only(root,&["servers","rules","final","strategy","disable_cache","disable_expire","independent_cache","cache_capacity","reverse_mapping","client_subnet","timeout","optimistic"])?;
        if serde_json::to_vec(document).map_err(|_|Error::Document)?.len()>1024*1024{return Err(Error::Limit)}
        let server_rows=root.get("servers").and_then(Value::as_array).ok_or(Error::Document)?;
        if server_rows.is_empty()||server_rows.len()>256{return Err(Error::Limit)}
        let mut servers=HashMap::new();
        for row in server_rows{
            let object=row.as_object().ok_or(Error::Document)?;
            let tag=object.get("tag").and_then(Value::as_str).filter(|v|!v.is_empty()&&v.len()<=255).ok_or(Error::Value)?;
            // Keep the COMPLETE transport, TLS and dialer document. The future
            // executor must validate every option rather than strip unknowns.
            if servers.insert(tag.to_owned(),row.clone()).is_some(){return Err(Error::Value)}
        }
        let final_tag=root.get("final").and_then(Value::as_str).ok_or(Error::Document)?;
        if !servers.contains_key(final_tag){return Err(Error::Value)}
        let rows=root.get("rules").map_or(Ok(&[][..]),|v|v.as_array().map(Vec::as_slice).ok_or(Error::Value))?;
        if rows.len()>1024{return Err(Error::Limit)}
        let mut actions=Vec::with_capacity(rows.len());let mut matches=Vec::with_capacity(rows.len());
        for row in rows{
            let mut matched=row.as_object().ok_or(Error::Document)?.clone();
            let kind=matched.remove("action").unwrap_or(json!("route"));let kind=kind.as_str().ok_or(Error::Value)?;
            let mut options=serde_json::Map::new();
            let keys:&[&str]=match kind{
                "route"=>&["server","strategy","disable_cache","rewrite_ttl","client_subnet","remove_client_subnet","timeout"],
                "route-options"=>&["strategy","disable_cache","rewrite_ttl","client_subnet","remove_client_subnet","timeout"],
                "predefined"=>&["rcode","answer","ns","extra"],
                "reject"=>&["method","no_drop"],_=>return Err(Error::Unsupported),
            };
            for key in keys{if let Some(value)=matched.remove(*key){options.insert((*key).to_owned(),value);}}
            let action=match kind{
                "route"=>{let server=options.remove("server").and_then(|v|v.as_str().map(str::to_owned)).ok_or(Error::Value)?;if !servers.contains_key(&server){return Err(Error::Value)}Action::Route{server,options:Value::Object(options)}},
                "route-options"=>Action::Options(Value::Object(options)),
                "predefined"=>Action::Predefined(Value::Object(options)),
                "reject"=>Action::Reject(Value::Object(options)),_=>unreachable!(),
            };
            actions.push(action);matches.push(json!({"match":matched}));
        }
        let mut envelope=json!({"rules":matches});
        if !sets.as_object().ok_or(Error::Value)?.is_empty(){envelope["ruleSets"]=sets.clone()}
        let expanded=crate::rule_sets::expand(envelope)?;
        let rows=expanded["rules"].as_array().ok_or(Error::Document)?;
        let rules=rows.iter().zip(actions).map(|(row,action)|Ok((DnsMatcher::parse(&row["match"])?,action))).collect::<Result<_,Error>>()?;
        Ok(Self{servers,rules,fallback:Action::Route{server:final_tag.to_owned(),options:json!({})},document:document.clone()})
    }
    pub fn select(&self,question:&Question<'_>)->Result<Selection<'_>,Error>{
        let mut preceding_options=Vec::new();
        for (matcher,action) in &self.rules{if matcher.matches(question)?{
            if let Action::Options(options)=action{preceding_options.push(options)}else{return Ok(Selection{action,preceding_options})}
        }}Ok(Selection{action:&self.fallback,preceding_options})
    }
    pub fn server(&self,tag:&str)->Option<&Value>{self.servers.get(tag)}
    pub fn document(&self)->&Value{&self.document}
    pub fn actions(&self)->impl Iterator<Item=&Action>{self.rules.iter().map(|(_,action)|action).chain(std::iter::once(&self.fallback))}
}
