// Local event adapter only. The historical server/client wire codecs stay intact.
// 2.0 and 2.1.5 reply TUNCONF:IPv4:DNS:clientPort, without a revision field.
// The retained local IP bridge needs that fifth field but never sends it to peers.
pub fn for_ip_bridge(raw: &str) -> String {
    if raw.starts_with("TUNCONF:") && raw.split(':').count() == 4 {
        format!("{raw}:historical-ipc")
    } else {
        raw.to_owned()
    }
}

#[cfg(test)]
mod tests {
    use super::for_ip_bridge;

    #[test]
    fn old_four_field_reply_keeps_address_dns_and_client_port() {
        assert_eq!(for_ip_bridge("TUNCONF:10.66.67.2:1.1.1.1:19000"),
                   "TUNCONF:10.66.67.2:1.1.1.1:19000:historical-ipc");
    }

    #[test]
    fn multiple_dns_and_zero_client_port_are_not_reinterpreted() {
        assert_eq!(for_ip_bridge("TUNCONF:10.66.67.2:1.1.1.1,8.8.8.8:0"),
                   "TUNCONF:10.66.67.2:1.1.1.1,8.8.8.8:0:historical-ipc");
    }

    #[test]
    fn modern_revision_or_policy_is_never_overwritten() {
        for frame in ["TUNCONF:10.66.67.2:1.1.1.1:19000:stream-v2",
                      "TUNCONF:10.66.67.2:1.1.1.1:19000:stream-v2:workers=20"] {
            assert_eq!(for_ip_bridge(frame), frame);
        }
    }

    #[test]
    fn other_events_and_malformed_short_frames_stay_for_normal_validation() {
        for frame in ["DENIED:device", "NOCONF", "TUNCONF:10.66.67.2:1.1.1.1", ""] {
            assert_eq!(for_ip_bridge(frame), frame);
        }
    }
}
