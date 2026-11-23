// MCRF adapter for the separately licensed CSQTT client. The surrounding
// upstream copyright, PolyForm Noncommercial license and notices are retained.
use std::{
    collections::HashMap,
    sync::{Mutex, atomic::{AtomicU64, Ordering}},
    time::{Duration, Instant},
};
use tokio::sync::oneshot;

struct Pending {
    deadline: Instant,
    sender: oneshot::Sender<String>,
}

pub struct Waiters {
    nonce: u128,
    next: AtomicU64,
    pending: Mutex<HashMap<String, Pending>>,
}

pub struct Ticket<'a> {
    owner: &'a Waiters,
    id: String,
    receiver: oneshot::Receiver<String>,
}

impl Waiters {
    pub fn new() -> Self {
        Self { nonce: uuid::Uuid::new_v4().as_u128(), next: AtomicU64::new(0), pending: Mutex::new(HashMap::new()) }
    }

    pub fn register(&self, timeout: Duration) -> Result<Ticket<'_>, &'static str> {
        if timeout.is_zero() || timeout > Duration::from_secs(120) { return Err("invalid captcha waiter budget"); }
        let deadline = Instant::now().checked_add(timeout).ok_or("invalid captcha waiter budget")?;
        self.register_at(deadline)
    }

    fn register_at(&self, deadline: Instant) -> Result<Ticket<'_>, &'static str> {
        let mut pending = self.pending.lock().map_err(|_| "captcha waiter unavailable")?;
        if pending.len() >= 32 { return Err("captcha waiter capacity reached"); }
        let sequence = self.next.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |value| value.checked_add(1))
            .map_err(|_| "captcha waiter identity exhausted")?;
        let id = format!("{:032x}{:016x}", self.nonce, sequence);
        let (sender, receiver) = oneshot::channel();
        pending.insert(id.clone(), Pending { deadline, sender });
        Ok(Ticket { owner: self, id, receiver })
    }

    /// Input is only the suffix of CAPTCHA_RESULT. No legacy/global fallback.
    /// A single bounded, nonblocking send cannot block the stdin STOP reader.
    pub fn submit(&self, result: &str) -> bool { self.submit_when(result, Instant::now) }

    #[cfg(test)]
    fn submit_at(&self, result: &str, now: Instant) -> bool {
        self.submit_when(result, || now)
    }

    fn submit_when(&self, result: &str, now: impl FnOnce() -> Instant) -> bool {
        if result.len() > 16433 { return false; }
        let Some((id, proof)) = result.split_once('|') else { return false; };
        if id.len() != 48 || !id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)) ||
            proof.is_empty() || proof.len() > 16384 || proof.chars().any(|c| c.is_control() || c == '|') { return false; }
        let Ok(mut pending) = self.pending.lock() else { return false; };
        let Some(waiter) = pending.remove(id) else { return false; };
        // Lock contention/scheduling can consume the remaining budget. Never use
        // a timestamp captured before this exact waiter was admitted for delivery.
        if now() >= waiter.deadline { return false; }
        waiter.sender.send(proof.to_owned()).is_ok()
    }
}

impl Ticket<'_> {
    pub fn id(&self) -> &str { &self.id }
    pub async fn receive(&mut self) -> Result<String, oneshot::error::RecvError> { (&mut self.receiver).await }
}

impl Drop for Ticket<'_> {
    fn drop(&mut self) {
        if let Ok(mut pending) = self.owner.pending.lock() { pending.remove(&self.id); }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[tokio::test]
    async fn exact_waiter_and_repeat_envelope_do_not_share_a_result_channel() {
        let waiters = Waiters::new();
        let mut first = waiters.register(Duration::from_secs(10)).unwrap();
        let mut second = waiters.register(Duration::from_secs(10)).unwrap();
        assert_ne!(first.id(), second.id());
        assert!(waiters.submit(&format!("{}|fixture-second", second.id())));
        assert_eq!(second.receive().await.unwrap(), "fixture-second");
        assert!(matches!(first.receiver.try_recv(), Err(oneshot::error::TryRecvError::Empty)));
        assert!(!waiters.submit(&format!("{}|duplicate", second.id())));
        let retired = first.id().to_owned();
        drop(first);
        let mut third = waiters.register(Duration::from_secs(10)).unwrap();
        assert_ne!(third.id(), retired);
        assert!(!waiters.submit(&format!("{retired}|late-proof")));
        assert!(waiters.submit(&format!("{}|fixture-third", third.id())));
        assert_eq!(third.receive().await.unwrap(), "fixture-third");
    }

    #[test]
    fn malformed_and_untagged_replies_cannot_retarget_an_active_waiter() {
        let waiters = Waiters::new();
        let ticket = waiters.register(Duration::from_secs(10)).unwrap();
        for value in ["untagged".to_owned(), format!("{}|", ticket.id()), format!("{}|bad|proof", ticket.id()),
            format!("{}|bad\nproof", ticket.id()), format!("{}|{}", ticket.id(), "a".repeat(16385))] { assert!(!waiters.submit(&value)); }
        assert!(waiters.submit(&format!("{}|fixture-proof", ticket.id())));
    }

    #[tokio::test]
    async fn expired_result_is_rejected_before_delivery_without_sleeps() {
        let waiters = Waiters::new();
        let start = Instant::now();
        let mut ticket = waiters.register_at(start + Duration::from_secs(10)).unwrap();
        assert!(!waiters.submit_at(&format!("{}|late-proof", ticket.id()), start + Duration::from_secs(10)));
        assert!(ticket.receive().await.is_err());
        assert!(waiters.pending.lock().unwrap().is_empty());
    }

    #[test]
    fn clock_is_sampled_inside_the_exact_delivery_lock() {
        let waiters = Waiters::new();
        let deadline = Instant::now() + Duration::from_secs(10);
        let mut ticket = waiters.register_at(deadline).unwrap();
        let result = format!("{}|fresh-proof", ticket.id());
        let mut sampled = false;
        assert!(waiters.submit_when(&result, || {
            assert!(matches!(waiters.pending.try_lock(), Err(std::sync::TryLockError::WouldBlock)));
            sampled = true;
            deadline - Duration::from_secs(1)
        }));
        assert!(sampled);
        assert_eq!(ticket.receiver.try_recv().unwrap(), "fresh-proof");
    }

    #[test]
    fn late_delivery_clock_closes_waiter_without_accepting_proof() {
        let waiters = Waiters::new();
        let deadline = Instant::now() + Duration::from_secs(10);
        let mut ticket = waiters.register_at(deadline).unwrap();
        assert!(!waiters.submit_when(&format!("{}|late-proof", ticket.id()), || {
            assert!(matches!(waiters.pending.try_lock(), Err(std::sync::TryLockError::WouldBlock)));
            deadline + Duration::from_nanos(1)
        }));
        assert!(matches!(ticket.receiver.try_recv(), Err(oneshot::error::TryRecvError::Closed)));
        assert!(waiters.pending.lock().unwrap().is_empty());
    }

    #[test]
    fn malformed_or_unknown_reply_never_samples_delivery_clock() {
        let waiters = Waiters::new();
        assert!(!waiters.submit_when("untagged-proof", || panic!("not admitted")));
        assert!(!waiters.submit_when(&format!("{}|proof", "0".repeat(48)), || panic!("unknown waiter")));
    }

    #[tokio::test]
    async fn cancellation_drops_the_exact_ticket_and_allows_next_attempt() {
        let waiters = Arc::new(Waiters::new());
        let owned = waiters.clone();
        let (announced, next) = oneshot::channel();
        let (stop, stopping) = oneshot::channel::<()>();
        let task = tokio::spawn(async move {
            let mut ticket = owned.register(Duration::from_secs(120)).unwrap();
            announced.send(ticket.id().to_owned()).unwrap();
            tokio::select! { _ = stopping => {}, _ = ticket.receive() => {} }
        });
        let id = next.await.unwrap();
        stop.send(()).unwrap();task.await.unwrap();
        assert!(waiters.pending.lock().unwrap().is_empty());
        let next = waiters.register(Duration::from_secs(10)).unwrap();
        assert_ne!(id, next.id());assert!(!waiters.submit(&format!("{id}|late-proof")));
    }

    #[test]
    fn capacity_is_bounded_and_counter_never_wraps_into_an_old_waiter() {
        let waiters = Waiters::new();
        let tickets: Vec<_> = (0..32).map(|_| waiters.register(Duration::from_secs(10)).unwrap()).collect();
        assert!(waiters.register(Duration::from_secs(10)).is_err());
        drop(tickets);assert!(waiters.pending.lock().unwrap().is_empty());
        waiters.next.store(u64::MAX, Ordering::Relaxed);
        assert!(waiters.register(Duration::from_secs(10)).is_err());
        assert!(waiters.register(Duration::ZERO).is_err());
    }

    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn concurrent_single_use_replies_stay_with_their_owned_tickets() {
        let waiters = Arc::new(Waiters::new());
        let mut tasks = Vec::new();
        for index in 0..32 {
            let owned = waiters.clone();
            tasks.push(tokio::spawn(async move {
                let mut ticket = owned.register(Duration::from_secs(10)).unwrap();
                let proof = format!("fixture-proof-{index}");
                assert!(owned.submit(&format!("{}|{proof}", ticket.id())));
                assert!(!owned.submit(&format!("{}|duplicate", ticket.id())));
                assert_eq!(ticket.receive().await.unwrap(), proof);
            }));
        }
        for task in tasks { task.await.unwrap(); }
        assert!(waiters.pending.lock().unwrap().is_empty());
    }
}
