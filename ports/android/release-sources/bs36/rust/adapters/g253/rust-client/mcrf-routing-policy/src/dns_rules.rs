//! Request-side DNS match semantics. The owning runtime must first expand the
//! exact captured private rule sets and supply fresh original-client metadata.
//! This does not select/open an upstream, invent a caller, or discard actions.
use crate::{Error,Flow,Matcher,only,strings,values};
use serde_json::Value;
enum Kind {Logical{any:bool,children:Vec<DnsMatcher>},Leaf{route:Option<Matcher>,types:Vec<u16>,preferred:Vec<String>}}
pub struct DnsMatcher {inverted:bool,kind:Kind}
pub struct Question<'a>{pub flow:Flow<'a>,pub query_type:u16,pub preferred_by:Option<&'a [String]>}
fn query_type(value:&Value)->Result<u16,Error>{
    if let Some(number)=value.as_u64(){return u16::try_from(number).ok().filter(|v|*v!=0).ok_or(Error::Value)}
    match value.as_str().ok_or(Error::Value)?{
        "A"=>Ok(1),"NS"=>Ok(2),"CNAME"=>Ok(5),"SOA"=>Ok(6),"PTR"=>Ok(12),"MX"=>Ok(15),"TXT"=>Ok(16),
        "AAAA"=>Ok(28),"SRV"=>Ok(33),"NAPTR"=>Ok(35),"DNAME"=>Ok(39),"DS"=>Ok(43),"RRSIG"=>Ok(46),"NSEC"=>Ok(47),"DNSKEY"=>Ok(48),"NSEC3"=>Ok(50),
        "TLSA"=>Ok(52),"SMIMEA"=>Ok(53),"SVCB"=>Ok(64),"HTTPS"=>Ok(65),"IXFR"=>Ok(251),"AXFR"=>Ok(252),"ANY"=>Ok(255),"URI"=>Ok(256),"CAA"=>Ok(257),_=>Err(Error::Unsupported),
    }
}
impl DnsMatcher {
    pub fn parse(value:&Value)->Result<Self,Error>{let mut budget=100000;Self::nested(value,0,&mut budget)}
    fn nested(value:&Value,depth:usize,budget:&mut usize)->Result<Self,Error>{
        if depth>32||*budget==0{return Err(Error::Limit)}*budget-=1;
        let row=value.as_object().ok_or(Error::Document)?;
        let inverted=row.get("invert").map_or(Ok(false),|v|v.as_bool().ok_or(Error::Value))?;
        let kind=if row.get("type").and_then(Value::as_str)==Some("logical"){
            only(row,&["type","mode","rules","invert"])?;
            let any=match row.get("mode").and_then(Value::as_str){Some("or")=>true,Some("and")=>false,_=>return Err(Error::Value)};
            let rows=row.get("rules").and_then(Value::as_array).ok_or(Error::Value)?;
            if rows.is_empty()||rows.len()>1024{return Err(Error::Limit)}
            Kind::Logical{any,children:rows.iter().map(|v|Self::nested(v,depth+1,budget)).collect::<Result<_,_>>()?}
        }else{
            let mut route=row.clone();route.remove("invert");
            let types=route.remove("query_type").map_or(Ok(Vec::new()),|v|values(&v)?.into_iter().map(query_type).collect::<Result<Vec<_>,_>>())?;
            let preferred=route.remove("preferred_by").map_or(Ok(Vec::new()),|v|strings(&v))?;
            // Response-address conditions cannot be evaluated before DNS. Do
            // not replace the unresolved destination with 0.0.0.0/false and
            // accidentally broaden an inverted/direct DNS rule.
            if ["ip_cidr","ip_is_private","ip_version","ip_accept_any"].iter().any(|v|route.contains_key(*v)){return Err(Error::Unsupported)}
            if route.get("type").and_then(Value::as_str)==Some("default"){route.remove("type");}
            let route=if route.is_empty(){None}else{Some(Matcher::parse(&Value::Object(route),depth,budget)?)};
            Kind::Leaf{route,types,preferred}
        };Ok(Self{inverted,kind})
    }
    pub fn matches(&self,question:&Question<'_>)->Result<bool,Error>{
        let matched=match &self.kind{
            Kind::Logical{any,children}=>{
                let mut missing=None;let mut certain=None;
                for child in children{match child.matches(question){Ok(v)if v==*any=>{certain=Some(v);break},Ok(_)=>{},Err(e @ (Error::MissingOwner|Error::MissingDomain|Error::MissingProtocol|Error::MissingNetwork))=>missing=Some(e),Err(e)=>return Err(e)}}
                if let Some(v)=certain{v}else{if let Some(error)=missing{return Err(error)}!*any}
            },
            Kind::Leaf{route,types,preferred}=>{
                if !types.is_empty()&&!types.contains(&question.query_type){false}
                else if !preferred.is_empty(){
                    let actual=question.preferred_by.ok_or(Error::MissingDomain)?;
                    if actual.iter().any(|v|preferred.contains(v)){route.as_ref().map_or(Ok(true),|route|route.matches(&question.flow))?}else{false}
                }else{route.as_ref().map_or(Ok(true),|route|route.matches(&question.flow))?}
            }
        };Ok(matched!=self.inverted)
    }
}
