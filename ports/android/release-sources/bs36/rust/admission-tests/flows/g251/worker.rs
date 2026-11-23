#![allow(dead_code)]
use super::auth::{self,CallUnavailable,TurnCredentials,VkAuth,VkRateLimitError};
use std::sync::{Arc,atomic::{AtomicBool,AtomicUsize,Ordering}};
use std::time::Duration;
use tokio_util::sync::CancellationToken;
const CREDENTIAL_POST_DELAY:Duration=Duration::from_millis(100);
const CREDENTIAL_FETCH_TIMEOUT:Duration=Duration::from_secs(240);
const WORKERS_PER_CREDENTIAL:usize=10;
pub struct Events;
impl Events {fn progress(&self,_:&str){} fn call_unavailable(&self,_:&str,_:i64){}}
pub struct GroupContext{pub auth:Arc<VkAuth>,pub cancel:CancellationToken,pub events:Events}
// EXACT transformed cache structures, per-hash state and worker admission:
struct HashAvailability {
    unavailable: AtomicBool,
    cancel: CancellationToken,
}
impl HashAvailability {
    fn new(parent_cancel: &CancellationToken) -> Self {
        Self {
            unavailable: AtomicBool::new(false),
            cancel: parent_cancel.child_token(),
        }
    }

    fn mark_unavailable(&self) {
        self.unavailable.store(true, Ordering::Release);
        self.cancel.cancel();
    }
}
pub struct GroupCredentials {
    pub credential_id: usize,
    pub hash: String,
    pub context: Arc<GroupContext>,
    state: tokio::sync::Mutex<CredentialCache>,
    fetch_gate: tokio::sync::Mutex<()>,
    failures: AtomicUsize,
    availability: Arc<HashAvailability>,
    exhausted: AtomicBool,
}
struct CachedCredentials {
    value: TurnCredentials,
    generation: u64,
    allocation_attempts: usize,
    allocation_mismatches: usize,
}
#[derive(Clone)]
struct CredentialLease {
    value: TurnCredentials,
    generation: u64,
}
#[derive(Default)]
struct CredentialCache {
    current: Option<CachedCredentials>,
    quota_blocked: Option<TurnCredentials>,
    next_generation: u64,
    quota_retries: usize,
}
impl CredentialCache {
fn reserve(&mut self) -> Option<CredentialLease> {
        if self
            .current
            .as_ref()
            .is_some_and(|cached| cached.allocation_attempts >= WORKERS_PER_CREDENTIAL)
        {
            self.current = None;
        }
        let cached = self.current.as_mut()?;
        cached.allocation_attempts = cached.allocation_attempts.saturating_add(1);
        Some(CredentialLease {
            value: cached.value.clone(),
            generation: cached.generation,
        })
    }
fn store(&mut self, credentials: TurnCredentials) -> bool {
        if self
            .quota_blocked
            .as_ref()
            .is_some_and(|blocked| same_turn_credentials(blocked, &credentials))
            && self.quota_retries < 2
        {
            return false;
        }
        self.quota_blocked = None;
        self.next_generation = self.next_generation.saturating_add(1).max(1);
        self.current = Some(CachedCredentials {
            value: credentials,
            generation: self.next_generation,
            allocation_attempts: 0,
            allocation_mismatches: 0,
        });
        true
    }
fn next_quota_delay(&mut self) -> Duration {
        let delay = quota_rotation_delay(self.quota_retries);
        self.quota_retries = self.quota_retries.saturating_add(1);
        delay
    }
}
impl GroupCredentials {
fn is_unavailable(&self) -> bool {
        self.availability.unavailable.load(Ordering::Acquire)
    }
fn is_exhausted(&self) -> bool {
        self.exhausted.load(Ordering::Acquire)
    }
async fn get(&self) -> Option<CredentialLease> {
        if self.is_unavailable() || self.is_exhausted() {
            return None;
        }
        if let Some(lease) = self.state.lock().await.reserve() {
            return Some(lease);
        }
        let credential_stream_id = credential_stream_id(self.credential_id);
        let _fetch = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            guard = self.fetch_gate.lock() => guard,
        };
        if let Some(lease) = self.state.lock().await.reserve() {
            return Some(lease);
        }
        // Admission is inside auth.get_credentials: two concurrent NEW
        // requests, a short shared cooldown lock and a bounded queue wait.
        // Do not hold the historical one-slot pacer across auth/retry sleeps.
        if self.is_unavailable() || self.is_exhausted() {
            return None;
        }
        let short_hash: String = self.hash.chars().take(8).collect();
        crate::log_error!(
            "[КРЕД #{}] Запрос (хеш: {short_hash}...)",
            self.credential_id
        );
        let fetched = tokio::select! {
            _ = self.context.cancel.cancelled() => return None,
            _ = self.availability.cancel.cancelled() => return None,
            result = tokio::time::timeout(
                CREDENTIAL_FETCH_TIMEOUT,
                self.context.auth.get_credentials(&self.hash, credential_stream_id),
            ) => result.unwrap_or_else(|_| Err(anyhow::anyhow!(
                "VK не вернул credential за {CREDENTIAL_FETCH_TIMEOUT:?}"
            ))),
        };
        match fetched {
            Ok(creds) => {
                let server_addresses = creds.server_addresses.clone();
                let mut state = self.state.lock().await;
                if !state.store(creds) {
                    let delay = state.next_quota_delay();
                    drop(state);
                    crate::log_error!(
                        "[TURN][RETRY] VK вернул прежний credential после 486; новый запрос через {:?}",
                        delay
                    );
                    tokio::select! {
                        _ = self.context.cancel.cancelled() => return None,
                        _ = self.availability.cancel.cancelled() => return None,
                        _ = tokio::time::sleep(delay) => {}
                    }
                    return None;
                }
                drop(state);
                self.failures.store(0, Ordering::Relaxed);
                tokio::select! {
                    _ = self.context.cancel.cancelled() => return None,
                    _ = self.availability.cancel.cancelled() => return None,
                    _ = tokio::time::sleep(CREDENTIAL_POST_DELAY) => {}
                }
                crate::log_error!(
                    "[КРЕД #{}] OK, TURN: {:?}, до {} воркеров",
                    self.credential_id,
                    server_addresses,
                    WORKERS_PER_CREDENTIAL
                );
                self.context.events.progress("credentials");
                self.state.lock().await.reserve()
            }
            Err(error) => {
                if let Some(call) = error.downcast_ref::<CallUnavailable>() {
                    self.availability.mark_unavailable();
                    self.context.events.call_unavailable(&self.hash, call.code);
                    return None;
                }
                let failures = self.failures.fetch_add(1, Ordering::Relaxed);
                let delay = if let Some(limit) = error.downcast_ref::<VkRateLimitError>() {
                    self.failures.store(0, Ordering::Relaxed);
                    limit.cooldown.max(Duration::from_secs(30))
                } else if failures >= 5 {
                    self.failures.store(0, Ordering::Relaxed);
                    Duration::from_secs(30)
                } else {
                    credential_failure_delay(failures)
                };
                crate::log_error!(
                    "[КРЕД #{}] Ошибка: {error:#}. Повторяем через {delay:?}...",
                    self.credential_id
                );
                tokio::select! {
                    _ = self.context.cancel.cancelled() => return None,
                    _ = self.availability.cancel.cancelled() => return None,
                    _ = tokio::time::sleep(delay) => {}
                }
                None
            }
        }
    }
    pub fn fixture(hash:&str,auth:Arc<VkAuth>,cached:bool)->Arc<Self>{
        let context=Arc::new(GroupContext{auth,cancel:CancellationToken::new(),events:Events});
        let mut cache=CredentialCache::default();if cached{assert!(cache.store(auth::credentials()));}
        Arc::new(Self{credential_id:1,hash:hash.to_owned(),availability:Arc::new(HashAvailability::new(&context.cancel)),
            context,state:tokio::sync::Mutex::new(cache),fetch_gate:tokio::sync::Mutex::new(()),failures:AtomicUsize::new(0),exhausted:AtomicBool::new(false)})
    }
    pub async fn readiness(&self)->bool{self.get().await.is_some()}
    pub fn stop(&self){self.context.cancel.cancel();}
}
pub fn credential_failure_delay(failures: usize) -> Duration {
    const LADDER: [Duration; 7] = [
        Duration::from_secs(2),
        Duration::from_secs(3),
        Duration::from_secs(5),
        Duration::from_secs(10),
        Duration::from_secs(15),
        Duration::from_secs(20),
        Duration::from_secs(30),
    ];
    let index = failures.min(LADDER.len() - 1);
    LADDER[index]
}
fn quota_rotation_delay(attempt: usize) -> Duration {
    Duration::from_millis(match attempt {
        0 => 100,
        1 => 300,
        2 => 500,
        3 => 800,
        _ => 1_500,
    })
}
fn credential_stream_id(credential_id: usize) -> usize {
    credential_id * 100
}
fn same_turn_credentials(left: &TurnCredentials, right: &TurnCredentials) -> bool {
    left.username == right.username
        && left.password == right.password
        && left.server_addresses == right.server_addresses
}
