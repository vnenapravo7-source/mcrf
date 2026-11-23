//! Expand only captured source rule-set documents; never opens URLs or files.
//! Mirrors pinned sing-box rule_item_rule_set.go: exactly one noninverted
//! default headless rule merges address/port groups with the outer rule.
//! Multiple/logical/inverted rules instead require the outer groups separately.
use serde_json::{Map,Value};
use crate::Error;
const GROUPS:[&[&str];4]=[
    &["domain","domain_suffix","domain_keyword","domain_regex","adguard_domain","ip_cidr","ip_is_private"],
    &["source_ip_cidr","source_ip_is_private"],&["port","port_range"],&["source_port","source_port_range"],
];
struct Budget {nodes:usize,bytes:usize}
impl Budget {
    fn charge(&mut self,bytes:usize)->Result<(),Error>{
        self.nodes=self.nodes.checked_sub(1).ok_or(Error::Limit)?;
        self.bytes=self.bytes.checked_sub(bytes).ok_or(Error::Limit)?;Ok(())
    }
    fn copy(&mut self,v:&Value,depth:usize)->Result<Value,Error>{
        if depth>32{return Err(Error::Limit)}
        self.charge(16)?;
        Ok(match v {
            Value::String(s)=>{self.bytes=self.bytes.checked_sub(s.len().checked_mul(6).ok_or(Error::Limit)?).ok_or(Error::Limit)?;Value::String(s.clone())},
            Value::Array(a)=>Value::Array(a.iter().map(|v|self.copy(v,depth+1)).collect::<Result<_,_>>()?),
            Value::Object(o)=>{let mut out=Map::new();for(k,v)in o{self.bytes=self.bytes.checked_sub(k.len()*6+4).ok_or(Error::Limit)?;out.insert(k.clone(),self.copy(v,depth+1)?);}Value::Object(out)},
            _=>v.clone(),
        })
    }
}
fn logical(any:bool,mut children:Vec<Value>,invert:bool,b:&mut Budget)->Result<Value,Error>{
    if children.is_empty(){return Err(Error::Value)}
    if children.len()==1&&!invert{return Ok(children.pop().unwrap())}
    b.charge(80)?;
    Ok(serde_json::json!({"type":"logical","mode":if any{"or"}else{"and"},"rules":children,"invert":invert}))
}
fn subset(row:&Map<String,Value>,keys:impl Fn(&str)->bool,b:&mut Budget)->Result<Option<Value>,Error>{
    let mut out=Map::new();
    for(k,v)in row{if keys(k)&&!(matches!(k.as_str(),"ip_is_private"|"source_ip_is_private"|"network_is_expensive"|"network_is_constrained")&&v.as_bool()==Some(false)){out.insert(k.clone(),b.copy(v,0)?);}}
    if out.is_empty(){Ok(None)}else{b.charge(32)?;Ok(Some(Value::Object(out)))}
}
fn ordinary(k:&str)->bool{k!="type"&&k!="invert"&&k!="rule_set"&&!GROUPS.iter().any(|g|g.contains(&k))}
fn merge(outer:&Map<String,Value>,inner:&Map<String,Value>,b:&mut Budget)->Result<Value,Error>{
    let mut children=Vec::new();
    for row in [outer,inner]{if let Some(v)=subset(row,ordinary,b)?{children.push(v)}}
    for group in GROUPS{
        let mut alternatives=Vec::new();
        for row in [outer,inner]{if let Some(v)=subset(row,|k|group.contains(&k),b)?{alternatives.push(v)}}
        if !alternatives.is_empty(){children.push(logical(true,alternatives,false,b)?)}
    }
    logical(false,children,false,b)
}
fn expand_match(value:&Value,sets:&Map<String,Value>,depth:usize,b:&mut Budget)->Result<Value,Error>{
    if depth>24{return Err(Error::Limit)}
    let row=value.as_object().ok_or(Error::Document)?;
    if row.get("type").and_then(Value::as_str)==Some("logical"){
        // Preserve unknown fields so the matcher can reject them, never erase one.
        let mut out=Map::new();
        for(k,v)in row{
            let v=if k=="rules"{Value::Array(v.as_array().ok_or(Error::Value)?.iter().map(|r|expand_match(r,sets,depth+1,b)).collect::<Result<_,_>>()?)}else{b.copy(v,depth)?};
            out.insert(k.clone(),v);
        }
        b.charge(32)?;return Ok(Value::Object(out))
    }
    let Some(references)=row.get("rule_set")else{return b.copy(value,depth)};
    if row.get("type").is_some_and(|v|v.as_str()!=Some("default")){return Err(Error::Unsupported)}
    let invert=match row.get("invert"){None=>false,Some(v)=>v.as_bool().ok_or(Error::Value)?};
    let tags=match references{Value::String(s)=>vec![s.as_str()],Value::Array(a)=>a.iter().map(|v|v.as_str().ok_or(Error::Value)).collect::<Result<_,_>>()?,_=>return Err(Error::Value)};
    if tags.is_empty()||tags.len()>128{return Err(Error::Limit)}
    let mut branches=Vec::new();
    for tag in tags{
        if tag.is_empty()||tag.len()>128{return Err(Error::Value)}
        let document=sets.get(tag).and_then(Value::as_object).ok_or(Error::Unsupported)?;
        if document.keys().any(|k|k!="version"&&k!="rules")||!matches!(document.get("version").and_then(Value::as_u64),Some(1..=5)){return Err(Error::Unsupported)}
        let rules=document.get("rules").and_then(Value::as_array).ok_or(Error::Document)?;
        if rules.is_empty()||rules.len()>1024{return Err(Error::Limit)}
        let single=if rules.len()==1{rules[0].as_object()}else{None};
        // A source set cannot acquire remote/cyclic dependencies from its content.
        fn no_references(v:&Value,depth:usize)->bool{
            if depth>24{return false}
            match v{Value::Object(o)=>!o.contains_key("rule_set")&&o.values().all(|v|no_references(v,depth+1)),Value::Array(a)=>a.iter().all(|v|no_references(v,depth+1)),_=>true}
        }
        if !rules.iter().all(|v|no_references(v,0)){return Err(Error::Unsupported)}
        if let Some(inner)=single.filter(|r|r.get("type").is_none_or(|v|v.as_str()==Some("default"))&&r.get("invert").is_none_or(|v|v.as_bool()==Some(false))){
            branches.push(merge(row,inner,b)?);
        }else{
            let mut children=Vec::new();
            if let Some(outer)=subset(row,|k|k!="rule_set"&&k!="invert"&&k!="type",b)?{children.push(outer)}
            let contents=rules.iter().map(|v|b.copy(v,depth+1)).collect::<Result<_,_>>()?;
            children.push(logical(true,contents,false,b)?);
            branches.push(logical(false,children,false,b)?);
        }
    }
    logical(true,branches,invert,b)
}
pub(super) fn expand(mut value:Value)->Result<Value,Error>{
    let object=value.as_object_mut().ok_or(Error::Document)?;
    let Some(sets)=object.remove("ruleSets")else{return Ok(value)};
    let sets=sets.as_object().ok_or(Error::Document)?;
    if sets.is_empty()||sets.len()>128{return Err(Error::Limit)}
    let rows=object.get_mut("rules").and_then(Value::as_array_mut).ok_or(Error::Document)?;
    let mut b=Budget{nodes:100_000,bytes:16*1024*1024};
    for row in rows{
        let row=row.as_object_mut().ok_or(Error::Document)?;
        let expanded=expand_match(row.get("match").ok_or(Error::Document)?,sets,0,&mut b)?;
        row.insert("match".to_owned(),expanded);
    }
    Ok(value)
}
