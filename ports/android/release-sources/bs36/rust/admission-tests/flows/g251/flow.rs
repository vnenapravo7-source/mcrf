#![allow(dead_code)]
mod auth;
mod worker;
mod bootstrap;
#[macro_export]
macro_rules! log_error {($($arg:tt)*) => {eprintln!($($arg)*)};}
use std::sync::{Arc,atomic::{AtomicUsize,Ordering}};

#[tokio::test(start_paused=true)]
async fn worker_auth_peer_flood_stops_next_operation_without_retry_or_fallback(){
    auth::mcrf_vk_backoff::reset_offline_scope();
    let script=Arc::new(auth::Script::default());
    let peer=worker::GroupCredentials::fixture("peer",auth::VkAuth::fixture(script.clone(),false),false);
    let running=peer.clone();let pending=tokio::spawn(async move{running.readiness().await});
    script.before_second.notified().await;
    let flood=auth::VkAuth::fixture(script.clone(),true); // REAL nested Auto JS -> Auto branch.
    let error=flood.get_credentials("flood",100).await.err().expect("typed flood");
    assert!(error.downcast_ref::<auth::VkRateLimitError>().is_some());
    script.resume_second.notify_one();
    assert!(!pending.await.unwrap());
    assert_eq!(script.count("peer"),1); // Never executed the next operation.
    assert_eq!(script.count("flood"),1);
    assert_eq!(script.legacy.load(Ordering::SeqCst),0);
    // Existing cached TURN lease bypasses NEW-fetch cooldown in actual worker.
    let cached=worker::GroupCredentials::fixture("cached",auth::VkAuth::fixture(script.clone(),false),true);
    assert!(cached.readiness().await);assert_eq!(script.count("cached"),0);
}

#[tokio::test(start_paused=true)]
async fn worker_bad_hash_isolated_and_cancelled_auth_does_not_continue(){
    auth::mcrf_vk_backoff::reset_offline_scope();
    let script=Arc::new(auth::Script::default());let auth=auth::VkAuth::fixture(script.clone(),false);
    assert!(!worker::GroupCredentials::fixture("bad",auth.clone(),false).readiness().await);
    assert!(worker::GroupCredentials::fixture("owner",auth.clone(),false).readiness().await);
    assert_eq!(script.count("owner"),3);
    let peer=worker::GroupCredentials::fixture("peer",auth,false);
    let running=peer.clone();let task=tokio::spawn(async move{running.readiness().await});
    script.before_second.notified().await;peer.stop();
    assert!(!task.await.unwrap());script.resume_second.notify_one();
    assert_eq!(script.count("peer"),1);
    assert_eq!(script.legacy.load(Ordering::SeqCst),0);
}

#[tokio::test]
async fn bootstrap_typed_flood_prevents_second_attempt(){
    auth::mcrf_vk_backoff::reset_offline_scope();let calls=AtomicUsize::new(0);
    let error=bootstrap::fixture(&calls).await.unwrap_err();
    assert!(error.downcast_ref::<auth::VkRateLimitError>().is_some());
    assert_eq!(calls.load(Ordering::SeqCst),1);
    assert!(auth::mcrf_vk_backoff::provider_allowed().is_err());
}
