//! Source-only BS routing policy. Not yet attached to a TUN or advertised by BS.
//! Matching never opens sockets or creates another TCP/IP stack.
use std::{net::IpAddr,collections::HashSet};
use regex_lite::Regex;
use serde_json::{Map, Value};
use serde::de::{self, Visitor, MapAccess, SeqAccess};
mod adguard;

pub mod session;
pub mod listeners;
pub mod packet;
pub mod observations;
pub mod associations;
pub mod service_flows;
pub mod service_nat;
pub mod raw_tcp;
pub mod tcp_lifetime;
pub mod packet_nat;
pub mod dns_reflector;
pub mod dns_rules;
pub mod dns_selector;
pub mod dns_wire;
pub mod fake_ip;
pub mod inspect;
pub mod dns_cache;
pub mod dns_options;
pub mod dns_limits;
pub mod bundle;
pub mod pipeline;
pub mod server_routing;
pub mod tun;
mod rule_sets;

// serde_json::Value normally keeps the last duplicate key. A private policy
// must reject an ambiguous document instead of changing a route implicitly.
struct Unique(Value);
/// Bounded duplicate-rejecting JSON shared by the private session envelope.
pub fn decode_document(bytes:&[u8],limit:usize)->Result<Value,Error>{
    if limit==0||limit>24*1024*1024||bytes.len()>limit{return Err(Error::Limit)}
    let Unique(value)=serde_json::from_slice(bytes).map_err(|_|Error::Document)?;Ok(value)
}
impl<'de> serde::Deserialize<'de> for Unique {
    fn deserialize<D:serde::Deserializer<'de>>(d:D)->Result<Self,D::Error> {
        struct Checked;
        impl<'de> Visitor<'de> for Checked {
            type Value=Unique;
            fn expecting(&self,f:&mut std::fmt::Formatter)->std::fmt::Result {f.write_str("an unambiguous JSON document")}
            fn visit_bool<E:de::Error>(self,v:bool)->Result<Unique,E>{Ok(Unique(Value::Bool(v)))}
            fn visit_i64<E:de::Error>(self,v:i64)->Result<Unique,E>{Ok(Unique(Value::from(v)))}
            fn visit_u64<E:de::Error>(self,v:u64)->Result<Unique,E>{Ok(Unique(Value::from(v)))}
            fn visit_f64<E:de::Error>(self,v:f64)->Result<Unique,E>{serde_json::Number::from_f64(v).map(|n|Unique(Value::Number(n))).ok_or_else(||E::custom("non-finite number"))}
            fn visit_str<E:de::Error>(self,v:&str)->Result<Unique,E>{Ok(Unique(Value::String(v.to_owned())))}
            fn visit_string<E:de::Error>(self,v:String)->Result<Unique,E>{Ok(Unique(Value::String(v)))}
            fn visit_unit<E:de::Error>(self)->Result<Unique,E>{Ok(Unique(Value::Null))}
            fn visit_seq<A:SeqAccess<'de>>(self,mut a:A)->Result<Unique,A::Error>{
                let mut values=Vec::new();while let Some(Unique(v))=a.next_element()? {if values.len()>=100_000{return Err(de::Error::custom("array limit"))}values.push(v)}
                Ok(Unique(Value::Array(values)))
            }
            fn visit_map<A:MapAccess<'de>>(self,mut a:A)->Result<Unique,A::Error>{
                let mut values=Map::new();while let Some((key,Unique(v)))=a.next_entry::<String,Unique>()? {
                    if values.len()>=1024||values.insert(key,v).is_some(){return Err(de::Error::custom("duplicate field or object limit"))}
                }
                Ok(Unique(Value::Object(values)))
            }
        }
        d.deserialize_any(Checked)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Target { Tunnel, Direct, Block, Egress(u8) }
#[derive(Clone, PartialEq, Eq, Debug)]
pub enum Error { Document, Limit, Unsupported, Value, MissingOwner, MissingDomain, MissingProtocol, MissingNetwork, MissingResolution }
/// Pending is different from a completed inspection which found no domain or
/// application protocol. The caller must defer/drop unresolved flows, not let
/// a missing observation fall through an inverted blocking rule into direct.
#[derive(Clone, Copy)]
pub enum Observed<T> { Pending, Known(T) }
/// One captured underlay observation. A network change invalidates the owning
/// session's cached decisions before this snapshot can be replaced.
#[derive(Clone, Copy)]
pub struct NetworkContext<'a> {
    pub kind: &'a str,
    pub expensive: bool,
    pub constrained: bool,
    pub wifi_ssid: &'a str,
    pub wifi_bssid: &'a str,
}
#[derive(Clone, Copy)]
pub struct Flow<'a> {
    pub source: IpAddr,
    pub destination: IpAddr,
    pub source_port: u16,
    pub port: u16,
    pub network: &'a str,
    pub domain: Observed<Option<&'a str>>,
    pub protocol: Observed<Option<&'a str>>,
    /// None means lookup failed; Some(empty) means a successful owner lookup.
    pub packages: Option<&'a [String]>,
    pub underlay: Observed<Option<NetworkContext<'a>>>,
}
/// Explicit destination identity after FakeIP/override resolution. A sniffed
/// name alone does NOT replace an ordinary numeric destination. Empty known
/// results are not a fallback to the virtual IP; inversion follows aggregation.
#[derive(Clone,Copy)]
pub enum DestinationView<'a>{Address,Domain{addresses:&'a [IpAddr],ip_version:Option<u8>},UnresolvedDomain{ip_version:Option<u8>}}
impl DestinationView<'_>{fn validate(self)->Result<(),Error>{if let Self::Domain{addresses,ip_version}=self{if addresses.len()>64{return Err(Error::Limit)}if ip_version.is_some_and(|v|!matches!(v,4|6)){return Err(Error::Value)}}if let Self::UnresolvedDomain{ip_version}=self{if ip_version.is_some_and(|v|!matches!(v,4|6)){return Err(Error::Value)}}Ok(())}}
enum Condition {
    Group { any: bool, children: Vec<Matcher> },
    Alternatives(Vec<Condition>),
    Domain(DomainPatterns),
    Regex(Vec<Regex>, bool),
    Adguard(adguard::Patterns),
    Address(Vec<Cidr>, bool),
    Ports(Vec<(u16,u16)>, bool),
    Names(Vec<String>, NameKind),
    PrivateAddress(bool),
    IpVersion(bool),
    UnderlayNames(Vec<String>, UnderlayName),
    UnderlayFlag(bool),
}
enum UnderlayName { Kind, Ssid, Bssid }
enum DomainKind { Exact, Suffix, Keyword }
/// Immutable lookup tables: a large GeoSite list must not lowercase/copy every
/// stored domain on every new flow. Suffix probes are bounded by host labels.
struct DomainPatterns {exact:HashSet<String>,suffix:HashSet<String>,keywords:Vec<String>}
impl DomainPatterns {
    fn new(rows:Vec<String>,kind:DomainKind)->Self {
        let mut result=Self{exact:HashSet::new(),suffix:HashSet::new(),keywords:Vec::new()};
        match kind {
            DomainKind::Exact=>result.exact.extend(rows),
            DomainKind::Suffix=>for row in rows{
                if !row.starts_with('.') {result.exact.insert(row.clone());}
                result.suffix.insert(row.strip_prefix('.').unwrap_or(&row).to_owned());
            },
            DomainKind::Keyword=>result.keywords=rows,
        };result
    }
    fn matches(&self,host:&str)->bool {
        if host.is_empty(){return false}
        // Preserve the original matcher contract: lowercase the observed host,
        // not the supplied patterns; leading/trailing dots carry meaning.
        let host=host.to_ascii_lowercase();
        self.exact.contains(&host)||host.match_indices('.').any(|(i,_)|self.suffix.contains(&host[i+1..]))||self.keywords.iter().any(|p|host.contains(p))
    }
}

enum NameKind { Network, Protocol, Package }
struct Matcher { inverted: bool, conditions: Vec<Condition> }
struct Cidr { address: IpAddr, bits: u8 }
pub struct Policy { rules: Vec<(Matcher,Target)>, fallback: Target, egress_tags: Vec<String> }
#[derive(Clone,Copy,Default)]
pub struct Requirements { pub domain:bool,pub protocol:bool,pub owner:bool,pub underlay:bool }

fn target(value:&Value,egress_tags:&[String])->Result<Target,Error> {
    match value.as_str().ok_or(Error::Value)? {
        "proxy"=>Ok(Target::Tunnel),
        "direct"=>Ok(Target::Direct),
        "block"=>Ok(Target::Block),
        tag=>egress_tags.iter().position(|entry|entry==tag)
            .map(|slot|Target::Egress(slot as u8)).ok_or(Error::Value),
    }
}
fn egress_tags(object:&Map<String,Value>)->Result<Vec<String>,Error> {
    match object.get("schema").and_then(Value::as_u64) {
        Some(1) if !object.contains_key("egressTags")=>Ok(Vec::new()),
        Some(2)=>{
            let rows=object.get("egressTags").and_then(Value::as_array).ok_or(Error::Document)?;
            if rows.is_empty()||rows.len()>32{return Err(Error::Limit)}
            let mut seen=HashSet::new();
            rows.iter().map(|row|{
                let tag=row.as_str().ok_or(Error::Value)?;
                let id=tag.strip_prefix("mcrf-route-").ok_or(Error::Value)?;
                if id.len()!=64||!id.bytes().all(|v|v.is_ascii_digit()||matches!(v,b'a'..=b'f'))||
                    !seen.insert(tag){return Err(Error::Value)}
                Ok(tag.to_owned())
            }).collect()
        }
        _=>Err(Error::Document),
    }
}
fn values(value:&Value)->Result<Vec<&Value>,Error> {
    let result=if let Some(items)=value.as_array(){items.iter().collect()}else{vec![value]};
    if result.is_empty()||result.len()>100_000 {return Err(Error::Limit)}
    Ok(result)
}
fn strings(value:&Value)->Result<Vec<String>,Error> {
    values(value)?.into_iter().map(|v| {
        let s=v.as_str().ok_or(Error::Value)?;
        if s.is_empty()||s.len()>8192||s.chars().any(char::is_control){return Err(Error::Value)}
        Ok(s.to_owned())
    }).collect()
}
fn domains(value:&Value)->Result<Vec<String>,Error> {
    let rows=strings(value)?;
    for row in &rows {
        if row.len()>253{return Err(Error::Value)}
    }
    Ok(rows)
}
fn only(object:&Map<String,Value>,keys:&[&str])->Result<(),Error> {
    if object.keys().any(|key|!keys.contains(&key.as_str())){Err(Error::Unsupported)}else{Ok(())}
}
impl Cidr {
    fn parse(text:&str)->Result<Self,Error> {
        let (ip,bits)=text.split_once('/').ok_or(Error::Value)?;
        let address=ip.parse::<IpAddr>().map_err(|_|Error::Value)?;
        let bits=bits.parse::<u8>().map_err(|_|Error::Value)?;
        if bits>if address.is_ipv4(){32}else{128}{return Err(Error::Value)}
        Ok(Self{address,bits})
    }
    fn matches(&self,ip:IpAddr)->bool {
        match (self.address,ip) {
            (IpAddr::V4(a),IpAddr::V4(b))=>self.bits==0||(u32::from(a)^u32::from(b))>>(32-self.bits)==0,
            (IpAddr::V6(a),IpAddr::V6(b))=>self.bits==0||(u128::from(a)^u128::from(b))>>(128-self.bits)==0,
            _=>false,
        }
    }
}
impl Policy {
    pub fn egress_tags(&self)->&[String]{&self.egress_tags}
    pub fn default_target(&self)->Target{self.fallback}
    pub fn uses_tunnel(&self)->bool{
        self.fallback==Target::Tunnel||self.rules.iter().any(|(_,target)|*target==Target::Tunnel)
    }
    pub fn requirements(&self)->Requirements {
        let mut value=Requirements::default();
        for (matcher,_) in &self.rules {matcher.requirements(&mut value)}value
    }
    pub fn parse(bytes:&[u8])->Result<Self,Error> {
        if bytes.len()>16*1024*1024{return Err(Error::Limit)}
        let value=decode_document(bytes,16*1024*1024)?;
        let value=rule_sets::expand(value)?;
        let object=value.as_object().ok_or(Error::Document)?;
        only(object,&["schema","defaultTarget","rules","egressTags"])?;
        let egress_tags=egress_tags(object)?;
        let fallback=target(object.get("defaultTarget").ok_or(Error::Document)?,&egress_tags)?;
        let rows=object.get("rules").and_then(Value::as_array).ok_or(Error::Document)?;
        if rows.len()>1024{return Err(Error::Limit)}
        let mut budget=100_000usize;
        let mut rules=Vec::with_capacity(rows.len());
        for row in rows {
            let row=row.as_object().ok_or(Error::Document)?;only(row,&["match","target"])?;
            rules.push((Matcher::parse(row.get("match").ok_or(Error::Document)?,0,&mut budget)?,target(row.get("target").ok_or(Error::Document)?,&egress_tags)?));
        }
        Ok(Self{rules,fallback,egress_tags})
    }
    /// Failed package ownership never becomes a direct-route fallback, even
    /// under inversion. Caller must retain/drop the flow when this returns Err.
    pub fn decide(&self,flow:&Flow<'_>)->Result<Target,Error> {
        self.decide_with_destination(flow,DestinationView::Address)
    }
    pub fn decide_with_destination(&self,flow:&Flow<'_>,destination:DestinationView<'_>)->Result<Target,Error>{
        Ok(self.match_with_destination(flow,destination)?.unwrap_or(self.fallback))
    }
    pub fn match_with_destination(&self,flow:&Flow<'_>,destination:DestinationView<'_>)->Result<Option<Target>,Error>{
        destination.validate()?;
        for (matcher,target) in &self.rules {if matcher.matches_in(flow,destination)? {return Ok(Some(target.clone()))}}
        Ok(None)
    }
}
impl Matcher {
    fn requirements(&self,value:&mut Requirements) {for condition in &self.conditions{condition.requirements(value)}}
    fn parse(value:&Value,depth:usize,budget:&mut usize)->Result<Self,Error> {
        if depth>32||*budget==0{return Err(Error::Limit)}
        *budget-=1;
        let object=value.as_object().ok_or(Error::Document)?;
        let inverted=match object.get("invert"){None=>false,Some(v)=>v.as_bool().ok_or(Error::Value)?};
        let mut conditions=Vec::new();
        if object.get("type").and_then(Value::as_str)==Some("logical") {
            only(object,&["type","mode","rules","invert"])?;
            let any=match object.get("mode").and_then(Value::as_str){Some("or")=>true,Some("and")=>false,_=>return Err(Error::Value)};
            let items=object.get("rules").and_then(Value::as_array).ok_or(Error::Value)?;
            if items.is_empty()||items.len()>1024{return Err(Error::Limit)}
            conditions.push(Condition::Group{any,children:items.iter().map(|v|Self::parse(v,depth+1,budget)).collect::<Result<_,_>>()?});
        }else {
            if object.get("type").is_some_and(|v|v.as_str()!=Some("default")){return Err(Error::Unsupported)}
            // Preserve the currently selected routing engine's default-rule
            // groups: alternatives inside each address/port group, conjunction
            // BETWEEN groups and ordinary conditions. In particular a domain
            // and a destination CIDR in one rule are not an AND condition.
            let mut groups: [Vec<Condition>;4]=std::array::from_fn(|_|Vec::new());
            for (key,value) in object {
                let condition=match key.as_str() {
                    "type"|"invert"=>continue,
                    "domain"=>Condition::Domain(DomainPatterns::new(domains(value)?,DomainKind::Exact)),
                    "domain_suffix"=>Condition::Domain(DomainPatterns::new(domains(value)?,DomainKind::Suffix)),
                    "domain_keyword"=>Condition::Domain(DomainPatterns::new(strings(value)?,DomainKind::Keyword)),
                    "domain_regex"|"package_name_regex"=>Condition::Regex(strings(value)?.iter().map(|v|Regex::new(v).map_err(|_|Error::Value)).collect::<Result<_,_>>()?,key=="package_name_regex"),
                    "adguard_domain"=>Condition::Adguard(adguard::Patterns::new(&strings(value)?)?),
                    "ip_cidr"|"source_ip_cidr"=>Condition::Address(strings(value)?.iter().map(|v|Cidr::parse(v)).collect::<Result<_,_>>()?,key=="source_ip_cidr"),
                    "ip_is_private"|"source_ip_is_private"=>{
                        if !value.as_bool().ok_or(Error::Value)? {continue}
                        Condition::PrivateAddress(key=="source_ip_is_private")
                    },
                    "ip_version"=>Condition::IpVersion(match value.as_u64(){Some(4)=>false,Some(6)=>true,_=>return Err(Error::Value)}),
                    "network_type"|"wifi_ssid"|"wifi_bssid"=>{
                        let rows=strings(value)?;
                        if key=="network_type"&&rows.iter().any(|v|!matches!(v.as_str(),"wifi"|"cellular"|"ethernet"|"other")){return Err(Error::Unsupported)}
                        Condition::UnderlayNames(rows,match key.as_str(){"network_type"=>UnderlayName::Kind,"wifi_ssid"=>UnderlayName::Ssid,_=>UnderlayName::Bssid})
                    },
                    "network_is_expensive"|"network_is_constrained"=>{
                        if !value.as_bool().ok_or(Error::Value)? {continue}
                        Condition::UnderlayFlag(key=="network_is_constrained")
                    },
                    "network"|"protocol"|"package_name"=>{
                        let names=strings(value)?;
                        if key=="network"&&names.iter().any(|v|v!="tcp"&&v!="udp"){return Err(Error::Unsupported)}
                        Condition::Names(names,match key.as_str(){"network"=>NameKind::Network,"protocol"=>NameKind::Protocol,_=>NameKind::Package})
                    },
                    "port"|"source_port"=>Condition::Ports(values(value)?.into_iter().map(|v|v.as_u64().and_then(|n|u16::try_from(n).ok()).map(|n|(n,n)).ok_or(Error::Value)).collect::<Result<_,_>>()?,key=="source_port"),
                    "port_range"|"source_port_range"=>Condition::Ports(strings(value)?.iter().map(|v| {
                        let (a,b)=v.split_once(':').ok_or(Error::Value)?;
                        let a=if a.is_empty(){0}else{a.parse::<u16>().map_err(|_|Error::Value)?};
                        let b=if b.is_empty(){u16::MAX}else{b.parse::<u16>().map_err(|_|Error::Value)?};
                        if a>b{return Err(Error::Value)}Ok((a,b))
                    }).collect::<Result<_,_>>()?,key=="source_port_range"),
                    // Geo selectors must first be expanded from the selected
                    // origin-bound DAT snapshot. Never ignore an unknown filter.
                    _=>return Err(Error::Unsupported),
                };
                match key.as_str() {
                    "domain"|"domain_suffix"|"domain_keyword"|"domain_regex"|"adguard_domain"|"ip_cidr"|"ip_is_private"=>groups[0].push(condition),
                    "source_ip_cidr"|"source_ip_is_private"=>groups[1].push(condition),
                    "port"|"port_range"=>groups[2].push(condition),
                    "source_port"|"source_port_range"=>groups[3].push(condition),
                    _=>conditions.push(condition),
                }
            }
            for mut group in groups {
                if group.len()==1 {conditions.push(group.pop().unwrap())}
                else if !group.is_empty(){conditions.push(Condition::Alternatives(group))}
            }
        }
        if conditions.is_empty(){return Err(Error::Value)}
        Ok(Self{inverted,conditions})
    }
    fn matches(&self,f:&Flow<'_>)->Result<bool,Error> {
        self.matches_in(f,DestinationView::Address)
    }
    fn matches_in(&self,f:&Flow<'_>,destination:DestinationView<'_>)->Result<bool,Error>{
        let mut missing=None;
        for c in &self.conditions {
            match c.matches(f,destination){Ok(false)=>return Ok(self.inverted),Ok(true)=>{},Err(e @ (Error::MissingOwner|Error::MissingDomain|Error::MissingProtocol|Error::MissingNetwork|Error::MissingResolution))=>missing=Some(e),Err(e)=>return Err(e)}
        }
        if let Some(error)=missing{return Err(error)}
        Ok(!self.inverted)
    }
}
impl Condition {
    fn requirements(&self,value:&mut Requirements) {
        match self {
            Self::Group{children,..}=>for child in children{child.requirements(value)},
            Self::Alternatives(children)=>for child in children{child.requirements(value)},
            Self::Domain(..)=>value.domain=true,
            Self::Regex(_,package)=>if *package{value.owner=true}else{value.domain=true},
            Self::Adguard(_)=>value.domain=true,
            Self::Names(_,NameKind::Package)=>value.owner=true,
            Self::Names(_,NameKind::Protocol)=>value.protocol=true,
            Self::UnderlayNames(..)|Self::UnderlayFlag(_)=>value.underlay=true,
            _=>{},
        }
    }
    fn matches(&self,f:&Flow<'_>,destination:DestinationView<'_>)->Result<bool,Error> {
        Ok(match self {
            Self::Alternatives(rows)=> {
                let mut missing=None;
                for row in rows {match row.matches(f,destination) {
                    Ok(true)=>return Ok(true),Ok(false)=>{},
                    Err(e @ (Error::MissingOwner|Error::MissingDomain|Error::MissingProtocol|Error::MissingNetwork|Error::MissingResolution))=>missing=Some(e),Err(e)=>return Err(e)
                }}
                if let Some(error)=missing{return Err(error)}false
            },
            Self::Group{any,children}=> {
                let mut missing=None;
                for child in children {match child.matches_in(f,destination){Ok(v) if v==*any=>return Ok(*any),Ok(_)=>{},Err(e @ (Error::MissingOwner|Error::MissingDomain|Error::MissingProtocol|Error::MissingNetwork|Error::MissingResolution))=>missing=Some(e),Err(e)=>return Err(e)}}
                if let Some(error)=missing{return Err(error)}!*any
            },
            Self::Domain(patterns)=>observed_domain(f)?.is_some_and(|domain|patterns.matches(domain)),
            Self::Regex(patterns,package)=>if *package {f.packages.ok_or(Error::MissingOwner)?.iter().any(|name|patterns.iter().any(|r|r.is_match(name)))}else{observed_domain(f)?.is_some_and(|name|patterns.iter().any(|r|r.is_match(name)))},
            Self::Adguard(patterns)=>observed_domain(f)?.is_some_and(|name|patterns.matches(name)),
            Self::Address(rows,source)=>if *source{rows.iter().any(|r|r.matches(f.source))}else{match destination{DestinationView::Address=>rows.iter().any(|r|r.matches(f.destination)),DestinationView::Domain{addresses,..}=>addresses.iter().any(|ip|rows.iter().any(|r|r.matches(*ip))),DestinationView::UnresolvedDomain{..}=>return Err(Error::MissingResolution) }},
            Self::PrivateAddress(source)=>if *source{non_public(f.source)}else{match destination{DestinationView::Address=>non_public(f.destination),DestinationView::Domain{addresses,..}=>addresses.iter().any(|ip|non_public(*ip)),DestinationView::UnresolvedDomain{..}=>return Err(Error::MissingResolution)}},
            Self::IpVersion(ipv6)=>match destination{DestinationView::Address=>f.destination.is_ipv6()==*ipv6,DestinationView::Domain{ip_version,..}|DestinationView::UnresolvedDomain{ip_version}=>ip_version.is_some_and(|v|(v==6)==*ipv6)},
            Self::UnderlayNames(rows,kind)=>underlay(f)?.is_some_and(|n|rows.iter().any(|v|v==match kind {UnderlayName::Kind=>n.kind,UnderlayName::Ssid=>n.wifi_ssid,UnderlayName::Bssid=>n.wifi_bssid})),
            Self::UnderlayFlag(constrained)=>underlay(f)?.is_some_and(|n|if *constrained{n.constrained}else{n.expensive}),
            Self::Ports(rows,source)=>rows.iter().any(|(a,b)|(*a..=*b).contains(&if *source{f.source_port}else{f.port})),
            Self::Names(rows,kind)=>match kind {
                NameKind::Network=>rows.iter().any(|x|x==f.network),
                NameKind::Protocol=>match f.protocol {Observed::Known(protocol)=>protocol.is_some_and(|p|rows.iter().any(|x|x==p)),Observed::Pending=>return Err(Error::MissingProtocol)},
                NameKind::Package=>f.packages.ok_or(Error::MissingOwner)?.iter().any(|p|rows.contains(p)),
            },
        })
    }
}

fn observed_domain<'a>(f:&Flow<'a>)->Result<Option<&'a str>,Error> {
    match f.domain {Observed::Known(domain)=>Ok(domain),Observed::Pending=>Err(Error::MissingDomain)}
}

fn underlay<'a>(f:&Flow<'a>)->Result<Option<NetworkContext<'a>>,Error> {
    match f.underlay {Observed::Known(value)=>Ok(value),Observed::Pending=>Err(Error::MissingNetwork)}
}

// Match the selected primary's sing/common/network.IsPublicAddr definition,
// rather than Rust's broader/different is_global classification. Documentation
// and CGNAT ranges are deliberately NOT silently added to the private set.
fn non_public(ip:IpAddr)->bool {
    match ip {
        IpAddr::V4(v)=>v.is_private()||v.is_loopback()||v.is_multicast()||v.is_link_local()||v.is_unspecified(),
        IpAddr::V6(v)=>if let Some(v4)=v.to_ipv4_mapped(){non_public(IpAddr::V4(v4))}else{
            v.is_loopback()||v.is_multicast()||v.is_unspecified()||(v.segments()[0]&0xfe00)==0xfc00||(v.segments()[0]&0xffc0)==0xfe80
        },
    }
}
