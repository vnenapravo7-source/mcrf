fn main() {
    #[cfg(unix)]
    mcrf_adapter_g253::mcrf_native_router_run();

    #[cfg(not(unix))]
    std::process::exit(78);
}
