/// Only setup bytes are split. Established tunnel traffic is never paced.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Preset { #[default] Current, Gentle, Extended }

impl Preset {
    pub fn name(self) -> &'static str { match self {
        Self::Current => "current", Self::Gentle => "gentle", Self::Extended => "extended",
    }}
    pub fn turn_parts(self) -> &'static [usize] { match self {
        Self::Current => &[1, 6, 8, 20],
        Self::Gentle => &[6, 20],
        Self::Extended => &[1, 2, 5, 7, 8, 20],
    }}
    pub fn turn_delay_ms(self) -> u64 { match self {
        Self::Current => 5, Self::Gentle => 1, Self::Extended => 10,
    }}
    pub fn tls_chunk(self) -> usize { match self {
        Self::Current => 2, Self::Gentle => 8, Self::Extended => 1,
    }}
    pub fn tls_delay_ms(self) -> u64 { match self {
        Self::Current => 10, Self::Gentle => 1, Self::Extended => 15,
    }}
    pub fn low_ttl(self) -> bool { self != Self::Gentle }
}

impl std::str::FromStr for Preset {
    type Err = String;
    fn from_str(value: &str) -> Result<Self, Self::Err> { match value {
        "current" => Ok(Self::Current), "gentle" => Ok(Self::Gentle),
        "extended" => Ok(Self::Extended), _ => Err(format!("Unknown Gooby strategy: {value}")),
    }}
}
