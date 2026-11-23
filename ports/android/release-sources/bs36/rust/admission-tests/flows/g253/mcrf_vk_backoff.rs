//! Scope: all NEW VK credential fetches in one native process. Cached TURN
//! leases are returned by GroupCredentials before entering this gate.
use std::{sync::OnceLock, time::{Duration, Instant}};
use super::{TurnCredentials, VkAuth, VkRateLimitError, VkRateLimitType};

#[derive(Default)]
struct Pause {
    until: Option<Instant>,
    kind: Option<VkRateLimitType>,
}
impl Pause {
    fn remaining(&self, now: Instant) -> Option<VkRateLimitError> {
        let duration = self.until?.checked_duration_since(now)?;
        if duration.is_zero() { return None; }
        Some(VkRateLimitError {
            limit_type: self.kind?, code: 0,
            message: "активна общая пауза VK; новый запрос не отправлен".to_owned(),
            cooldown: Duration::from_secs(duration.as_secs().saturating_add(u64::from(duration.subsec_nanos() > 0))),
        })
    }
    fn record(&mut self, limit: &VkRateLimitError, now: Instant) {
        // Never shorten a concurrent longer pause. Bound corrupt durations.
        let until = now + limit.cooldown.max(Duration::from_secs(1)).min(Duration::from_secs(3600));
        if self.until.is_none_or(|old| until > old) {
            self.until = Some(until);
            self.kind = Some(limit.limit_type);
        }
    }
}

// At most two new credential fetches; one slow/CAPTCHA hash leaves a slot
// for a valid owner hash. The old worker held a one-slot pacer across auth
// AND retry sleeps; the overlay removes that outer whole-request lock.
struct Gate { pause: std::sync::Mutex<Pause>, admission: tokio::sync::Semaphore }
impl Default for Gate {
    fn default() -> Self { Self { pause: std::sync::Mutex::new(Pause::default()), admission: tokio::sync::Semaphore::new(2) } }
}

impl Gate {
async fn run<T, F, Fut, Clock>(&self, now: Clock, request: F) -> anyhow::Result<T>
where F: FnOnce() -> Fut, Fut: std::future::Future<Output = anyhow::Result<T>>,
      Clock: Fn() -> Instant {
    let remaining = || self.pause.lock().unwrap_or_else(|p| p.into_inner()).remaining(now());
    if let Some(limit) = remaining() { return Err(limit.into()); }
    // Cancellation drops either waiter or permit. No detached retry tasks.
    let _permit = tokio::time::timeout(Duration::from_secs(10), self.admission.acquire())
        .await.map_err(|_| anyhow::anyhow!("VK credential admission wait expired; no request sent"))?
        .map_err(|_| anyhow::anyhow!("VK credential admission closed"))?;
    // A different in-flight request may have received Flood while we queued.
    if let Some(limit) = remaining() { return Err(limit.into()); }
    let result = request().await;
    if let Err(error) = &result {
        if let Some(limit) = error.downcast_ref::<VkRateLimitError>().filter(|limit| provider_reply(limit)) {
            self.pause.lock().unwrap_or_else(|p| p.into_inner()).record(limit, now());
            // Fixed, secret-free IPC. Existing code=0 local lockouts are not
            // provider replies and must never slide the host's pause forward.
            if provider_reply(limit) {
                let kind = match limit.limit_type {
                    VkRateLimitType::FloodControl => "FLOOD",
                    VkRateLimitType::RateLimit => "RATE",
                    VkRateLimitType::CaptchaLimit => "CAPTCHA",
                };
                let seconds = limit.cooldown.as_secs().clamp(1, 3600);
                crate::log_error!("MCRF_VK_LIMIT|{kind}|{seconds}");
            }
        }
    }
    result
}
}

pub(super) async fn fetch(auth: &VkAuth, link: &str, stream_id: usize) -> anyhow::Result<TurnCredentials> {
    shared().run(Instant::now, || auth.mcrf_fetch_credentials(link, stream_id)).await
}
fn shared() -> &'static Gate {
    static SCOPE: OnceLock<Gate> = OnceLock::new();
    SCOPE.get_or_init(Gate::default)
}
/// Rechecked at EVERY provider-operation boundary, including after CAPTCHA or
/// an earlier network await. Admission to a fetch is not a multi-request lease.
pub(crate) fn provider_allowed() -> anyhow::Result<()> {
    let pause=shared().pause.lock().unwrap_or_else(|p|p.into_inner());
    if let Some(limit)=pause.remaining(Instant::now()){return Err(limit.into());}
    Ok(())
}
/// Publish at the point the API response becomes typed, before unwinding through
/// auth/retry/fallback. Do not renew synthetic local cooldown errors.
pub(crate) fn limited(limit:VkRateLimitError) -> anyhow::Error {
    if provider_reply(&limit) {
        shared().pause.lock().unwrap_or_else(|p|p.into_inner()).record(&limit,Instant::now());
        let kind=match limit.limit_type {VkRateLimitType::FloodControl=>"FLOOD",VkRateLimitType::RateLimit=>"RATE",VkRateLimitType::CaptchaLimit=>"CAPTCHA"};
        let seconds=limit.cooldown.as_secs().clamp(1,3600);
        crate::log_error!("MCRF_VK_LIMIT|{kind}|{seconds}");
    }
    limit.into()
}
fn provider_reply(limit:&VkRateLimitError)->bool {
    limit.code!=0 && !limit.message.starts_with("активна ")
}
#[cfg(test)]
pub(crate) fn reset_offline_scope() {
    *shared().pause.lock().unwrap_or_else(|p|p.into_inner())=Pause::default();
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{Arc, atomic::{AtomicUsize, Ordering}};
    use super::super::CallUnavailable;
    #[tokio::test]
    async fn synthetic_pause_does_not_create_or_extend_provider_deadline() {
        let gate=Gate::default();
        let result: anyhow::Result<()> = gate.run(Instant::now,||async {
            Err(VkRateLimitError {limit_type:VkRateLimitType::CaptchaLimit,code:14,
                message:"активна глобальная пауза капчи".to_owned(),cooldown:Duration::from_secs(3600)}.into())
        }).await;
        assert!(result.is_err());
        assert!(gate.pause.lock().unwrap().remaining(Instant::now()).is_none());
    }
    #[test]
    fn pause_does_not_shorten_and_expires_at_the_deadline() {
        let now = Instant::now();
        let mut pause = Pause::default();
        let limit = |seconds| VkRateLimitError { limit_type: VkRateLimitType::FloodControl,
            code: 9, message: String::new(), cooldown: Duration::from_secs(seconds) };
        pause.record(&limit(60), now);
        pause.record(&limit(10), now + Duration::from_secs(5));
        assert_eq!(pause.remaining(now + Duration::from_secs(30)).unwrap().cooldown.as_secs(), 30);
        assert!(pause.remaining(now + Duration::from_secs(60)).is_none());
    }
    #[test]
    fn remaining_is_rounded_up_without_extending_the_deadline() {
        let now = Instant::now();
        let mut pause = Pause::default();
        pause.record(&VkRateLimitError { limit_type: VkRateLimitType::RateLimit, code: 6,
            message: String::new(), cooldown: Duration::from_secs(1) }, now);
        assert_eq!(pause.remaining(now + Duration::from_millis(999)).unwrap().cooldown.as_secs(), 1);
        assert!(pause.remaining(now + Duration::from_secs(1)).is_none());
    }
    #[tokio::test]
    async fn concurrent_24_supplements_make_at_most_two_fetches_then_none_before_flood_expiry() {
        let gate = Arc::new(Gate::default());
        let calls = Arc::new(AtomicUsize::new(0));
        let now = Instant::now();
        let mut requests = Vec::new();
        for _ in 0..24 {
            let gate = gate.clone(); let calls = calls.clone();
            requests.push(tokio::spawn(async move {
                gate.run(|| now, || async {
                    calls.fetch_add(1, Ordering::SeqCst);
                    tokio::task::yield_now().await;
                    Err::<(), _>(VkRateLimitError { limit_type: VkRateLimitType::FloodControl,
                        code: 9, message: String::new(), cooldown: Duration::from_secs(60) }.into())
                }).await
            }));
        }
        for request in requests {
            assert!(request.await.unwrap().unwrap_err().downcast_ref::<VkRateLimitError>().is_some());
        }
        let admitted = calls.load(Ordering::SeqCst);
        assert!((1..=2).contains(&admitted));
        assert!(gate.run(|| now + Duration::from_secs(59), || async {
            calls.fetch_add(1, Ordering::SeqCst); Ok(())
        }).await.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), admitted);
        assert!(gate.run(|| now + Duration::from_secs(60), || async {
            calls.fetch_add(1, Ordering::SeqCst); Ok(())
        }).await.is_ok());
        assert_eq!(calls.load(Ordering::SeqCst), admitted + 1);
    }
    #[tokio::test]
    async fn unavailable_one_hash_does_not_poison_next_valid_owner_hash() {
        let gate = Gate::default(); let now = Instant::now(); let mut calls = 0;
        let bad = gate.run(|| now, || async {
            calls += 1;
            Err::<(), _>(CallUnavailable {code: 951, message: "fixture unavailable call".to_owned()}.into())
        }).await.unwrap_err();
        assert!(bad.downcast_ref::<CallUnavailable>().is_some());
        let valid = gate.run(|| now, || async { calls += 1; Ok("owner credentials") }).await;
        assert_eq!(valid.unwrap(), "owner credentials");
        assert_eq!(calls, 2);
    }
    #[tokio::test]
    async fn cancelling_an_inflight_fetch_releases_admission_without_a_retry_task() {
        let gate = Arc::new(Gate::default()); let acquired = Arc::new(tokio::sync::Notify::new());
        let owned = gate.clone(); let signal = acquired.clone();
        let request = tokio::spawn(async move {
            owned.run(Instant::now, || async {
                signal.notify_one(); std::future::pending::<anyhow::Result<()>>().await
            }).await
        });
        acquired.notified().await; request.abort();
        assert!(request.await.unwrap_err().is_cancelled());
        assert!(gate.run(Instant::now, || async {Ok(())}).await.is_ok());
    }
    #[tokio::test]
    async fn slow_captcha_hash_leaves_capacity_for_valid_owner_hash() {
        let gate = Arc::new(Gate::default()); let entered = Arc::new(tokio::sync::Notify::new());
        let slow_gate = gate.clone(); let signal = entered.clone();
        let slow = tokio::spawn(async move {
            slow_gate.run(Instant::now, || async {
                signal.notify_one(); std::future::pending::<anyhow::Result<()>>().await
            }).await
        });
        entered.notified().await;
        let valid = tokio::time::timeout(Duration::from_secs(1), gate.run(Instant::now,
            || async {Ok("owner hash credentials")})).await.unwrap().unwrap();
        assert_eq!(valid, "owner hash credentials");
        slow.abort(); let _ = slow.await;
    }
    #[tokio::test]
    async fn twenty_four_healthy_requests_obey_two_inflight_bound() {
        let gate = Arc::new(Gate::default());
        let active = Arc::new(AtomicUsize::new(0)); let maximum = Arc::new(AtomicUsize::new(0));
        let calls = Arc::new(AtomicUsize::new(0)); let mut tasks = Vec::new();
        for _ in 0..24 {
            let gate = gate.clone(); let active = active.clone(); let maximum = maximum.clone(); let calls = calls.clone();
            tasks.push(tokio::spawn(async move { gate.run(Instant::now, || async {
                calls.fetch_add(1, Ordering::SeqCst);
                maximum.fetch_max(active.fetch_add(1, Ordering::SeqCst) + 1, Ordering::SeqCst);
                for _ in 0..5 { tokio::task::yield_now().await; }
                active.fetch_sub(1, Ordering::SeqCst); Ok(())
            }).await }));
        }
        for task in tasks {task.await.unwrap().unwrap();}
        assert_eq!(calls.load(Ordering::SeqCst), 24);
        assert_eq!(maximum.load(Ordering::SeqCst), 2);
        assert_eq!(active.load(Ordering::SeqCst), 0);
    }
    #[tokio::test(start_paused = true)]
    async fn cancelled_waiter_and_wait_timeout_never_send_requests() {
        let gate = Arc::new(Gate::default());
        let first = gate.admission.acquire().await.unwrap();
        let second = gate.admission.acquire().await.unwrap();
        let queued_gate = gate.clone();
        let queued = tokio::spawn(async move {queued_gate.run(Instant::now, || async {
            panic!("cancelled queued request must not run"); #[allow(unreachable_code)] Ok::<(), anyhow::Error>(())
        }).await});
        tokio::task::yield_now().await; queued.abort(); assert!(queued.await.unwrap_err().is_cancelled());
        let error = gate.run(Instant::now, || async {panic!("timed out request must not run"); #[allow(unreachable_code)] Ok::<(), anyhow::Error>(())}).await.unwrap_err();
        assert!(error.to_string().contains("no request sent"));
        drop(first); drop(second);
        assert!(gate.run(Instant::now, || async {Ok(())}).await.is_ok());
    }
}
