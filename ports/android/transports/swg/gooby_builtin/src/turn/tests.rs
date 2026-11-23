use super::*;

#[tokio::test]
async fn splits_type_and_cookie_without_reordering_or_inserting_bytes() {
    use std::{pin::Pin, task::{Context, Poll}};
    #[derive(Default)]
    struct Recorder(Vec<Vec<u8>>);
    impl AsyncWrite for Recorder {
        fn poll_write(mut self: Pin<&mut Self>, _: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
            self.0.push(bytes.to_vec());
            Poll::Ready(Ok(bytes.len()))
        }
        fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> { Poll::Ready(Ok(())) }
        fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> { Poll::Ready(Ok(())) }
    }
    let header = allocate(0);
    let mut writer = Recorder::default();
    split_header(&mut writer, &header, crate::Preset::Current).await.unwrap();
    assert_eq!(writer.0.iter().map(Vec::len).collect::<Vec<_>>(), [1, 5, 2, 12]);
    assert_eq!(writer.0.concat(), header);
}

fn allocate(body_len: usize) -> Vec<u8> {
    let mut bytes = vec![0; HEADER_LEN + body_len];
    bytes[..2].copy_from_slice(&[0, 3]);
    bytes[2..4].copy_from_slice(&(body_len as u16).to_be_bytes());
    bytes[4..8].copy_from_slice(&[0x21, 0x12, 0xa4, 0x42]);
    for (i, b) in bytes[8..].iter_mut().enumerate() {
        *b = i as u8;
    }
    bytes
}

async fn check(bytes: Vec<u8>, expected_splits: u64, fragmented: bool) {
    for preset in [crate::Preset::Current, crate::Preset::Gentle, crate::Preset::Extended] {
    timeout(Duration::from_secs(3), async {
        let (mut source, mut input) = tokio::io::duplex(1024);
        let (mut output, mut sink) = tokio::io::duplex(1024);
        let diagnostics = Diagnostics::default();
        let original = bytes.clone();
        let producer = async {
            if fragmented {
                for b in bytes.chunks(1) {
                    source.write_all(b).await.unwrap();
                }
            } else {
                source.write_all(&bytes).await.unwrap();
            }
            source.shutdown().await.unwrap();
        };
        let processor = async {
            let mut first = Vec::new();
            extend_prefix(&mut input, &mut first, 5).await.unwrap();
            outbound(&mut input, &mut output, first, &diagnostics, preset)
                .await
                .unwrap();
        };
        let consumer = async {
            let mut result = Vec::new();
            sink.read_to_end(&mut result).await.unwrap();
            assert_eq!(result, original);
        };
        tokio::join!(producer, processor, consumer);
        assert_eq!(
            diagnostics.snapshot().turn_allocate_requests,
            expected_splits
        );
        assert_eq!(
            diagnostics.snapshot().passthrough_connections,
            u64::from(expected_splits == 0)
        );
    })
    .await
    .unwrap();
    }
}

#[tokio::test]
async fn preserves_two_allocates_and_coalesced_large_payload_without_more_splits() {
    let mut bytes = allocate(32);
    bytes.extend(allocate(128));
    // Even an Allocate-shaped third message must no longer be processed.
    bytes.extend(allocate(65532));
    bytes.extend(vec![0x42; 128 * 1024]);
    check(bytes, 2, false).await;
}

#[tokio::test]
async fn accepts_fragmented_input_and_stops_on_channel_data() {
    let mut bytes = allocate(32);
    bytes.extend([0x40, 0, 0, 3, 1, 2, 3, 0]);
    bytes.extend(allocate(16));
    check(bytes, 1, true).await;
}

#[tokio::test]
async fn unknown_and_malformed_headers_pass_unchanged() {
    check(b"plain application data".to_vec(), 0, true).await;
    let mut bad_cookie = allocate(4);
    bad_cookie[6] ^= 1;
    check(bad_cookie, 0, true).await;
    let mut bad_length = allocate(4);
    bad_length[3] = 3;
    check(bad_length, 0, false).await;
    for len in 0..HEADER_LEN {
        check(allocate(4)[..len].to_vec(), 0, true).await;
    }
}

#[tokio::test]
async fn truncated_body_errors_without_claiming_success() {
    let mut source = &allocate(4)[..22];
    let mut first = Vec::new();
    extend_prefix(&mut source, &mut first, 5).await.unwrap();
    let diagnostics = Diagnostics::default();
    let mut output = Vec::new();
    assert!(outbound(&mut source, &mut output, first, &diagnostics, crate::Preset::Current)
        .await
        .is_err());
    assert_eq!(diagnostics.snapshot().turn_allocate_requests, 0);
}

#[tokio::test]
async fn observer_is_bounded_to_first_application_of_each_kind() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    let count = Arc::new(AtomicUsize::new(0));
    let observed = count.clone();
    let diagnostics = Diagnostics {
        observer: Some(Arc::new(move |_| {
            observed.fetch_add(1, Ordering::Relaxed);
        })),
        ..Diagnostics::default()
    };
    for _ in 0..140 {
        diagnostics.record(StrategyEvent::TurnAllocateSplit);
    }
    assert_eq!(diagnostics.snapshot().turn_allocate_requests, 140);
    assert_eq!(count.load(Ordering::Relaxed), 1);
}
