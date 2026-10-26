// MCRF adapter, GPL-3.0-or-later. Added to the pinned Apache-2.0 client.
// This module replaces only local TCP ingress; QUIC/DNS wire code is unchanged.
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use tokio::net::UnixListener;

pub(crate) async fn bind_private_listener() -> Result<(UnixListener, String), std::io::Error> {
    let denied = || std::io::Error::new(std::io::ErrorKind::PermissionDenied, "private ingress required");
    let path = std::env::var_os("MCRF_SLIPSTREAM_SOCKET").ok_or_else(denied)?;
    let path = std::path::Path::new(&path);
    let parent = path.parent().ok_or_else(denied)?;
    if !path.is_absolute() || path.file_name() != Some(std::ffi::OsStr::new("relay.sock")) {
        return Err(denied());
    }
    let info = std::fs::symlink_metadata(parent)?;
    // No abstract sockets, all-interface or raw localhost TCP fallback.
    if !info.is_dir() || info.permissions().mode() & 0o777 != 0o700
        || info.uid() != unsafe { libc::geteuid() } {
        return Err(denied());
    }
    // Never remove somebody else's existing socket to make binding succeed.
    let listener = UnixListener::bind(path)?;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))?;
    Ok((listener, "private-uds".to_string()))
}
