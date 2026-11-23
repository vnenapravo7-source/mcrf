//! Shared native DNS lifetime contract. One reply includes two authenticated
//! owner round trips plus the configured resolver timeout. The outer watchdog
//! and UDP reflection entry must outlive that work; STOP remains immediate.
use std::time::Duration;
pub const QUERY_MAX:Duration=Duration::from_secs(30);
pub const OWNER_QUERY:Duration=Duration::from_secs(2);
pub const RESPONSE:Duration=Duration::from_secs(35);
pub const UDP_MAPPING_MS:u64=40_000;
