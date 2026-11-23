//! Domain-only AdGuard matcher representation recovered from an SRS trie.
//! `||` anchors at a label boundary, `|` at the beginning, `^` at the end;
//! `*` spans any characters. HTTP/filter-list syntax is not interpreted here.
use crate::Error;
use regex_lite::Regex;
use std::collections::HashSet;
pub(crate) struct Patterns{exact:HashSet<String>,suffix:HashSet<String>,other:Vec<Regex>}
impl Patterns{
 pub(crate) fn new(rows:&[String])->Result<Self,Error>{
  let mut result=Self{exact:HashSet::new(),suffix:HashSet::new(),other:Vec::new()};
  for row in rows{
   if let Some(body)=row.strip_suffix('^').filter(|v|!v.contains('*')){
    if let Some(body)=body.strip_prefix("||"){result.suffix.insert(body.to_owned());continue}
    if let Some(body)=body.strip_prefix('|'){result.exact.insert(body.to_owned());continue}
   }
   result.other.push(compile(row)?);
  }Ok(result)
 }
 pub(crate) fn matches(&self,name:&str)->bool{
  if self.exact.contains(name)||self.suffix.contains(name){return true}
  let mut suffix=name;
  while let Some(dot)=suffix.find('.') {suffix=&suffix[dot+1..];if self.suffix.contains(suffix){return true}}
  self.other.iter().any(|pattern|pattern.is_match(name))
 }
}
pub(crate) fn compile(value:&str)->Result<Regex,Error>{
 if value.len()>8192||value.bytes().filter(|b|*b==b'*').count()>64{return Err(Error::Limit)}
 let(mut body,left)=if let Some(body)=value.strip_prefix("||"){(body,"(?:^|\\.)")}else if let Some(body)=value.strip_prefix('|'){(body,"^")}else{(value,"")};
 let right=if let Some(trimmed)=body.strip_suffix('^'){body=trimmed;"$"}else{""};
 // `value` is the recovered wire pattern: its compiler already removed
 // one terminal dot. Applying that normalization twice changes two-dot rules.
 let mut pattern=String::with_capacity(value.len()*2+32);
 // The primary's terminal-key traversal accepts an empty name immediately
 // when the wire trie begins with its non-end-anchored suffix marker.
 if right.is_empty(){pattern.push_str("(?:^$|")}
 pattern.push_str(left);
 for ch in body.chars(){match ch{'*'=>pattern.push_str("(?s:.*)"),'.'|'+'|'?'|'('|')'|'|'|'['|']'|'{'|'}'|'^'|'$'|'\\'=>{pattern.push('\\');pattern.push(ch)},_=>pattern.push(ch)}}
 pattern.push_str(right);if right.is_empty(){pattern.push(')')}Regex::new(&pattern).map_err(|_|Error::Value)
}
#[cfg(test)]mod tests{
 use super::*;
 #[test]fn recovered_patterns_match_the_pinned_primary_domain_matcher(){
  let rows:serde_json::Value=serde_json::from_str(include_str!("../tests/fixtures/adguard-reference.json")).unwrap();
  assert_eq!(rows.as_array().unwrap().len(),374);
  for row in rows.as_array().unwrap(){let matcher=compile(row["pattern"].as_str().unwrap()).unwrap();let optimized=Patterns::new(&[row["pattern"].as_str().unwrap().to_owned()]).unwrap();let expected=row["matches"].as_bool().unwrap();let name=row["domain"].as_str().unwrap();assert_eq!(matcher.is_match(name),expected,"original={} recovered={} domain={}",row["original"],row["pattern"],row["domain"]);assert_eq!(optimized.matches(name),expected,"optimized original={} domain={}",row["original"],row["domain"])}
 }
 #[test]fn large_literal_lists_use_shared_hash_tables_not_per_rule_regexes(){let rows=(0..5000).map(|n|format!("||{n}.owned.invalid^")).collect::<Vec<_>>();let patterns=Patterns::new(&rows).unwrap();assert!(patterns.other.is_empty());assert_eq!(patterns.suffix.len(),5000);assert!(patterns.matches("sub.4999.owned.invalid"));assert!(!patterns.matches("4999.owned.invalid.evil"));assert!(!patterns.matches("sub.4999.OWNED.invalid"))}
 #[test]fn excessive_pattern_complexity_is_not_admitted(){assert!(matches!(compile(&"*".repeat(65)),Err(Error::Limit)));assert!(matches!(compile(&"x".repeat(8193)),Err(Error::Limit)))}
}
