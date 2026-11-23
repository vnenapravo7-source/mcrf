//! Offline tests for the EXACT production module; no VK client implementation.
#![allow(dead_code)]
use std::time::Duration;
#[derive(Clone, Copy, Debug)]
pub enum VkRateLimitType { FloodControl, RateLimit, CaptchaLimit }
#[derive(Debug)]
pub struct VkRateLimitError {pub limit_type: VkRateLimitType,pub code: i64,pub message: String,pub cooldown: Duration}
impl std::fmt::Display for VkRateLimitError {
    fn fmt(&self,f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {write!(f,"VK limit {:?}",self.limit_type)}
}
impl std::error::Error for VkRateLimitError {}
#[derive(Debug)]
pub struct CallUnavailable {pub code: i64,pub message: String}
impl std::fmt::Display for CallUnavailable {
    fn fmt(&self,f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {write!(f,"unavailable fixture call")}
}
impl std::error::Error for CallUnavailable {}
pub struct TurnCredentials;
pub struct VkAuth;
impl VkAuth {
    async fn mcrf_fetch_credentials(&self,_link: &str,_id: usize) -> anyhow::Result<TurnCredentials> {
        panic!("offline fixture must NEVER invoke provider operations")
    }
}
#[macro_export]
macro_rules! log_error {($($arg:tt)*) => {eprintln!($($arg)*)};}
#[path = "../adapters/g251/rust-client/mcrf_vk_backoff.rs"]
mod g251_admission;
#[path = "../adapters/g253/rust-client/mcrf_vk_backoff.rs"]
mod g253_admission;

#[cfg(test)]
mod direct_tun_harness;
