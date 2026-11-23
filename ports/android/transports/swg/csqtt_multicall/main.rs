use mcrf_csqtt_multicall_boundary::{
    configure_legacy_underlay, configure_modern_underlay, direct_tun_capability, run, select,
    validate_direct_tun_entry, LEGACY_UNDERLAY, MODERN_UNDERLAY,
};
fn main() {
    let arguments:Vec<_>=std::env::args_os().skip(1).collect();
    if arguments.len()==1&&arguments[0]=="--mcrf-direct-tun-capability" {
        println!("{}",direct_tun_capability());
        return;
    }
    let mode = std::env::var("MCRF_BS_RUST_MODE").ok();
    let token=std::env::var("MCRF_DIRECT_TUN_GENERATION").ok();
    let result = select(mode.as_deref(),arguments).and_then(|launch| {
        validate_direct_tun_entry(&launch,token.as_deref())?;
        fn network(key: &str) -> Result<Option<String>, &'static str> {
            match std::env::var(key) {
                Ok(value) => Ok(Some(value)),
                Err(std::env::VarError::NotPresent) => Ok(None),
                Err(std::env::VarError::NotUnicode(_)) => Err("invalid physical network"),
            }
        }
        let underlay = network(LEGACY_UNDERLAY)?;
        let modern = network(MODERN_UNDERLAY)?;
        if underlay.is_some() && modern.is_some() { return Err("conflicting physical network selectors"); }
        configure_legacy_underlay(&launch, underlay.as_deref())?;
        configure_modern_underlay(&launch,token.is_some(),modern.as_deref())?;
        run(launch)
    });
    if let Err(message) = result { eprintln!("{message}"); std::process::exit(78); }
}
