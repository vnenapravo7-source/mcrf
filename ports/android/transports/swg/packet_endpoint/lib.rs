//! Standalone packet-endpoint boundary. Not wired into CSQTT or advertised by BS.
//! Real TUN validation and original CSQTT packet processing remain elsewhere.
pub mod contract;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod unix;
#[cfg(any(target_os = "android", target_os = "linux"))]
pub mod control;
