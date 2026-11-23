//! Trusted process selector. The proof producer supplies the `ported` adapters;
//! a standalone boundary build remains fail-closed without protocol dependencies.
use std::ffi::OsString;

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Generation { V20, V215, V219, V251, V252 }

impl Generation {
    pub fn parse(value: &str) -> Result<Self, &'static str> {
        match value {
            "2.0" => Ok(Self::V20), "2.1.5" => Ok(Self::V215),
            "2.1.9" => Ok(Self::V219), "2.5.1" => Ok(Self::V251),
            "2.5.2" | "2.5.3" => Ok(Self::V252),
            _ => Err("unknown CSQTT host generation"),
        }
    }
}

#[derive(Debug)]
pub struct Launch { pub generation: Generation, pub args: Vec<OsString> }

/// A legacy adapter's provider sockets and system resolver belong to its
/// physical Android network. The Go parent still serves SOCKS on loopback.
pub const LEGACY_UNDERLAY: &str = "MCRF_CSQTT_LEGACY_UNDERLAY";
pub const MODERN_UNDERLAY: &str = "MCRF_CSQTT_MODERN_UNDERLAY";

fn legacy_underlay(generation: Generation, value: Option<&str>) -> Result<Option<u64>, &'static str> {
    let Some(value) = value else { return Ok(None) };
    if !matches!(generation, Generation::V20 | Generation::V215 | Generation::V219) {
        return Err("legacy physical network is restricted to legacy CSQTT");
    }
    parse_physical_handle(value).map(Some)
}

fn parse_physical_handle(value: &str) -> Result<u64, &'static str> {
    let handle = value.parse::<u64>().map_err(|_| "invalid physical network")?;
    if handle == 0 || handle > i64::MAX as u64 || handle.to_string() != value {
        return Err("invalid physical network");
    }
    Ok(handle)
}

pub fn configure_legacy_underlay(launch: &Launch, value: Option<&str>) -> Result<(), &'static str> {
    let Some(handle) = legacy_underlay(launch.generation, value)? else { return Ok(()) };
    bind_provider_underlay(handle)
}

pub fn configure_modern_underlay(launch: &Launch, direct: bool, value: Option<&str>) -> Result<(), &'static str> {
    let Some(value) = value else { return Ok(()) };
    if direct || !matches!(launch.generation, Generation::V251 | Generation::V252) {
        return Err("modern physical network is restricted to the proxy transport");
    }
    bind_provider_underlay(parse_physical_handle(value)?)
}

#[cfg(target_os = "android")]
fn bind_provider_underlay(handle: u64) -> Result<(), &'static str> {
    #[link(name = "android")]
    unsafe extern "C" {
        fn android_setprocnetwork(handle: u64) -> std::ffi::c_int;
    }
    // Called before entering the adapter or creating its Tokio runtime.
    if unsafe { android_setprocnetwork(handle) } == 0 {
        Ok(())
    } else {
        Err("CSQTT could not bind to the physical network")
    }
}

#[cfg(not(target_os = "android"))]
fn bind_provider_underlay(_: u64) -> Result<(), &'static str> {
    Err("CSQTT physical network is only available on Android")
}

/// Source-bound native protocol marker. This is capability, never readiness.
pub const DIRECT_TUN_MARKER: &str = "MCRF_DIRECT_TUN_FD_V1";
pub fn direct_tun_capability() -> &'static str {
    if cfg!(feature="ported") {
        if cfg!(feature="native-routing") {
            return "{\"marker\":\"MCRF_DIRECT_TUN_FD_V1\",\"version\":2,\"generations\":[\"2.5.1\",\"2.5.2\",\"2.5.3\"],\"fdAdoptionRequired\":true}"
        }
        "{\"marker\":\"MCRF_DIRECT_TUN_FD_V1\",\"version\":1,\"generations\":[\"2.5.2\",\"2.5.3\"],\"fdAdoptionRequired\":true}"
    } else { "{\"version\":0,\"generations\":[],\"fdAdoptionRequired\":true}" }
}

pub fn validate_direct_tun_entry(launch: &Launch,token:Option<&str>) -> Result<(), &'static str> {
    let mut uds=None;
    let mut args=launch.args.iter();
    while let Some(arg)=args.next() {
        let text=arg.to_str().ok_or("invalid native CLI encoding")?;
        let value=if text=="--tun-uds"||text=="-tun-uds" {
            Some(args.next().and_then(|v|v.to_str()).ok_or("missing TUN socket")?)
        } else {text.strip_prefix("--tun-uds=").or_else(||text.strip_prefix("-tun-uds="))};
        if let Some(value)=value {
            if uds.is_some()||value.len()<16||value.len()>100||!value.bytes().all(|b|b.is_ascii_alphanumeric()||b==b'-'||b==b'_') {return Err("invalid private TUN socket")}
            uds=Some(value);
        }
    }
    if uds.is_none()&&token.is_none() {return Ok(())}
    if !(launch.generation==Generation::V252||cfg!(feature="native-routing")&&launch.generation==Generation::V251)||uds.is_none() {return Err("unsupported direct TUN generation or ingress")}
    let token=token.ok_or("direct TUN generation required")?;
    if token.is_empty()||!token.bytes().all(|b|b.is_ascii_digit()) {return Err("invalid direct TUN generation")}
    let number=token.parse::<u64>().map_err(|_|"invalid direct TUN generation")?;
    if number==0||number>i64::MAX as u64||number.to_string()!=token {return Err("invalid direct TUN generation")}
    Ok(())
}

/// Host env is mandatory. Optional host CLI must agree. JSON is never inspected.
pub fn select(env: Option<&str>, args: Vec<OsString>) -> Result<Launch, &'static str> {
    let generation = Generation::parse(env.ok_or("missing CSQTT host generation")?)?;
    let mut forwarded = Vec::new();
    let mut seen = false;
    for arg in args {
        if let Some(value) = arg.to_str().and_then(|a| a.strip_prefix("--mcrf-generation=")) {
            if seen || Generation::parse(value)? != generation { return Err("conflicting CSQTT host CLI"); }
            seen = true;
        } else if arg.to_string_lossy().starts_with("--mcrf-generation") {
            return Err("invalid CSQTT host CLI");
        } else { forwarded.push(arg); }
    }
    Ok(Launch { generation, args: forwarded })
}

/// Each adapter will own its runtime, signals, stdin/stdout and shutdown state.
/// It must consume the existing generation CLI without changing the wire format.
pub trait Adapter { fn run(&self, launch: Launch) -> Result<(), &'static str>; }

#[cfg(not(feature = "ported"))]
pub fn run(_launch: Launch) -> Result<(), &'static str> {
    // Deliberately no fallback to another generation, child executable, or JSON mode.
    Err("CSQTT multi-call ports are incomplete; use the verified split transport layout")
}

#[cfg(feature = "ported")]
pub fn run(launch: Launch) -> Result<(), &'static str> {
    match launch.generation {
        Generation::V20 => mcrf_adapter_g20::mcrf_run(),
        Generation::V215 => mcrf_adapter_g215::mcrf_run(),
        Generation::V219 => mcrf_adapter_g219::mcrf_run(),
        Generation::V251 => mcrf_adapter_g251::mcrf_run(),
        Generation::V252 => mcrf_adapter_g253::mcrf_run(),
    }
    Ok(())
}

#[cfg(test)]
mod direct_tun_tests {
    use super::*;
    fn launch(mode:&str,args:&[&str])->Launch {select(Some(mode),args.iter().map(OsString::from).collect()).unwrap()}
    #[test] fn direct_tun_requires_explicit_ingress_and_positive_token() {
        let request=launch("2.5.3",&["--tun-uds","mcrf-direct-0123456789abcdef"]);
        assert!(validate_direct_tun_entry(&request,Some("1")).is_ok());
        for token in [None,Some(""),Some("0"),Some("01"),Some("+1"),Some("9223372036854775808")] {
            assert!(validate_direct_tun_entry(&request,token).is_err());
        }
        assert!(validate_direct_tun_entry(&launch("2.5.3",&[]),Some("1")).is_err());
    }
    #[test] fn direct_tun_cannot_enter_legacy_adapter_or_duplicate_socket() {
        for mode in ["2.0","2.1.5","2.1.9"] {
            assert!(validate_direct_tun_entry(&launch(mode,&["--tun-uds","mcrf-direct-0123456789abcdef"]),Some("1")).is_err());
        }
        assert_eq!(validate_direct_tun_entry(&launch("2.5.1",&["--tun-uds","mcrf-direct-0123456789abcdef"]),Some("1")).is_ok(),cfg!(feature="native-routing"));
        assert!(validate_direct_tun_entry(&launch("2.5.2",&["--tun-uds","mcrf-direct-0123456789abcdef"]),Some("9223372036854775807")).is_ok());
        assert!(validate_direct_tun_entry(&launch("2.5.3",&["--tun-uds","mcrf-direct-0123456789abcdef","--tun-uds","mcrf-direct-fedcba9876543210"]),Some("1")).is_err());
    }
    #[test] fn ordinary_raw_entry_is_unchanged_without_direct_request() {
        for mode in ["2.0","2.1.5","2.1.9","2.5.1","2.5.2","2.5.3"] {
            assert!(validate_direct_tun_entry(&launch(mode,&["-listen","127.0.0.1:9000"]),None).is_ok());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn legacy_underlay_requires_a_canonical_handle_and_legacy_generation() {
        for mode in [Generation::V20, Generation::V215, Generation::V219] {
            assert_eq!(legacy_underlay(mode, None), Ok(None));
            assert_eq!(legacy_underlay(mode, Some("4295037282")), Ok(Some(4295037282)));
            for invalid in ["", "0", "+1", "01", "-1", "1\n", "9223372036854775808"] {
                assert!(legacy_underlay(mode, Some(invalid)).is_err());
            }
        }
        for mode in [Generation::V251, Generation::V252] {
            assert_eq!(legacy_underlay(mode, None), Ok(None));
            assert!(legacy_underlay(mode, Some("4295037282")).is_err());
        }
    }
    #[test] fn all_generations_and_alias() {
        for value in ["2.0","2.1.5","2.1.9","2.5.1","2.5.2","2.5.3"] {
            assert!(select(Some(value), vec!["--config".into(), "private.json".into()]).is_ok());
        }
        assert_eq!(Generation::parse("2.5.2"), Generation::parse("2.5.3"));
    }
    #[test] fn rejects_untrusted_or_ambiguous_selection() {
        assert!(select(None, vec!["--mcrf-generation=2.0".into()]).is_err());
        for value in ["", "wdtt", "../../lib.so", "2.5", "2.1.9\n"] { assert!(select(Some(value), vec![]).is_err()); }
        for args in [vec!["--mcrf-generation=2.5.1"], vec!["--mcrf-generation"],
                     vec!["--mcrf-generation=2.0","--mcrf-generation=2.0"]] {
            assert!(select(Some("2.0"), args.into_iter().map(Into::into).collect()).is_err());
        }
    }
    #[cfg(not(feature = "ported"))]
    #[test] fn parallel_selection_has_no_shared_state_and_cannot_run() {
        let jobs: Vec<_> = ["2.0","2.1.5","2.1.9","2.5.1","2.5.3"].into_iter().map(|g|
            std::thread::spawn(move || {
                let launch = select(Some(g), vec![]).unwrap();
                assert_eq!(launch.generation, Generation::parse(g).unwrap());
                assert!(run(launch).is_err());
            })).collect();
        for job in jobs { job.join().unwrap(); }
    }
}
