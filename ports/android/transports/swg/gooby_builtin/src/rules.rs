use anyhow::{bail, Context, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Strategy {
    Base,
    /// Internal CSQTTR preset, not a replacement for the original ZIP strategies.
    Turn,
    Directly,
    Custom { script: String },
}

#[derive(Debug, Clone)]
enum DomainPattern {
    Any,
    Exact(String),
    WildcardSuffix(String),
}

impl DomainPattern {
    fn parse(raw: &str) -> Result<Self> {
        let value = normalize_domain(raw);
        if value.is_empty() {
            bail!("empty domain pattern");
        }
        if value == "*" {
            return Ok(Self::Any);
        }
        if let Some(suffix) = value.strip_prefix("*.") {
            if suffix.is_empty() || suffix.contains('*') {
                bail!("invalid wildcard domain pattern: {raw}");
            }
            return Ok(Self::WildcardSuffix(suffix.to_owned()));
        }
        if value.contains('*') {
            bail!("wildcard is supported only as '*.' prefix: {raw}");
        }
        Ok(Self::Exact(value))
    }

    fn score(&self, host: &str) -> Option<usize> {
        match self {
            Self::Any => Some(1),
            Self::Exact(domain) if host == domain => Some(2_000_000 + domain.len()),
            Self::WildcardSuffix(suffix)
                if host.len() > suffix.len()
                    && host.ends_with(suffix)
                    && host.as_bytes()[host.len() - suffix.len() - 1] == b'.' =>
            {
                Some(1_000_000 + suffix.len())
            }
            _ => None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Rule {
    patterns: Vec<DomainPattern>,
    pub strategy: Strategy,
}

impl Rule {
    pub fn new(domains: Vec<String>, strategy: &str, script: Option<String>) -> Result<Self> {
        if domains.is_empty() {
            bail!("rules.domains must contain at least one domain pattern");
        }

        let patterns = domains
            .iter()
            .map(|domain| DomainPattern::parse(domain).with_context(|| format!("domain {domain:?}")))
            .collect::<Result<Vec<_>>>()?;

        let strategy = match strategy.trim().to_ascii_lowercase().as_str() {
            "base" => {
                if script.is_some() {
                    bail!("script must be NULL unless strategy=custom");
                }
                Strategy::Base
            }
            "directly" => {
                if script.is_some() {
                    bail!("script must be NULL unless strategy=custom");
                }
                Strategy::Directly
            }
            "custom" => {
                let script = script.context("strategy=custom requires a script name")?;
                validate_script_name(&script)?;
                Strategy::Custom { script }
            }
            other => bail!("unsupported strategy {other:?}; expected base, directly, or custom"),
        };

        Ok(Self { patterns, strategy })
    }

    fn score(&self, host: &str) -> Option<usize> {
        self.patterns.iter().filter_map(|p| p.score(host)).max()
    }
}

#[derive(Debug, Clone, Default)]
pub struct RuleSet {
    rules: Vec<Rule>,
}

impl RuleSet {
    pub fn new(rules: Vec<Rule>) -> Self {
        Self { rules }
    }

    pub fn len(&self) -> usize {
        self.rules.len()
    }

    pub fn resolve(&self, host: &str) -> Strategy {
        let host = normalize_domain(host);
        let mut selected: Option<(usize, usize, &Rule)> = None;

        for (index, rule) in self.rules.iter().enumerate() {
            let Some(score) = rule.score(&host) else {
                continue;
            };
            match selected {
                None => selected = Some((score, index, rule)),
                Some((best_score, best_index, _))
                    if score > best_score || (score == best_score && index < best_index) =>
                {
                    selected = Some((score, index, rule));
                }
                _ => {}
            }
        }

        selected
            .map(|(_, _, rule)| rule.strategy.clone())
            .unwrap_or(Strategy::Directly)
    }

    pub fn iter(&self) -> impl Iterator<Item = &Rule> {
        self.rules.iter()
    }
}

fn normalize_domain(value: &str) -> String {
    value.trim().trim_end_matches('.').to_ascii_lowercase()
}

fn validate_script_name(name: &str) -> Result<()> {
    if name.is_empty()
        || !name.ends_with(".lua")
        || name.contains('/')
        || name.contains('\\')
        || name == "."
        || name == ".."
    {
        bail!("script must be a plain .lua file name, got {name:?}");
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exact_beats_wildcard() {
        let rules = RuleSet::new(vec![
            Rule::new(vec!["*.youtube.com".into()], "base", None).unwrap(),
            Rule::new(vec!["music.youtube.com".into()], "directly", None).unwrap(),
        ]);
        assert_eq!(rules.resolve("music.youtube.com"), Strategy::Directly);
        assert_eq!(rules.resolve("www.youtube.com"), Strategy::Base);
        assert_eq!(rules.resolve("youtube.com"), Strategy::Directly);
    }
}
