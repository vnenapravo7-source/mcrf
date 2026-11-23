use anyhow::Result;
use crate::rules::{Rule,RuleSet};
#[derive(Debug,Clone)]
pub struct ConfigBundle {rules:RuleSet,pub(crate) turn_tcp:bool,pub(crate) preset:crate::Preset}
impl ConfigBundle {
    pub fn with_preset(mut self,preset:crate::Preset)->Self {self.preset=preset;self}
    pub fn builtin_base()->Result<Self>{Ok(Self{rules:RuleSet::new(vec![Rule::new(vec!["*".to_owned()],"base",None)?]),turn_tcp:false,preset:crate::Preset::Current})}
    pub fn builtin_turn()->Result<Self>{let mut result=Self::builtin_base()?;result.turn_tcp=true;Ok(result)}
    pub fn rules(&self)->&RuleSet{&self.rules}
}
