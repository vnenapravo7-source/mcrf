// Android-only observation of the pinned client's real ActiveConnection owner.
// No change to TURN, authentication, cancellation, or worker registration.
use std::sync::Mutex;

#[derive(Default)]
struct Counter(Mutex<usize>);

impl Counter {
    fn acquire(&self, emit: impl FnOnce(usize)) {
        let mut count = self.0.lock().unwrap_or_else(|e| e.into_inner());
        *count += 1;
        emit(*count);
    }

    fn release(&self, emit: impl FnOnce(usize)) {
        let mut count = self.0.lock().unwrap_or_else(|e| e.into_inner());
        // Each Owner is non-Clone and drops once, including task cancellation.
        assert!(*count > 0);
        *count -= 1;
        emit(*count);
    }
}

static STREAMS: Counter = Counter(Mutex::new(0));

fn emit(count: usize) {
    if std::env::var("CSQTT_EVENTS").as_deref() == Ok("1") {
        crate::log_output!("__CSQTT_EVENT__|STREAMS|{{\"active\":{count}}}");
    }
}

pub(crate) struct Owner;

impl Owner {
    pub(crate) fn new() -> Self {
        STREAMS.acquire(emit);
        Self
    }
}

impl Drop for Owner {
    fn drop(&mut self) {
        STREAMS.release(emit);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn replacement_owners_retire_independently() {
        let counter = Counter::default();
        let mut seen = Vec::new();
        counter.acquire(|n| seen.push(n));
        counter.acquire(|n| seen.push(n));
        counter.release(|n| seen.push(n));
        counter.release(|n| seen.push(n));
        assert_eq!(seen, [1, 2, 1, 0]);
    }

    #[test]
    fn concurrent_owners_keep_emission_order() {
        use std::sync::{Arc, Barrier};
        let counter = Counter::default();
        let seen = Mutex::new(Vec::new());
        let barrier = Arc::new(Barrier::new(16));
        std::thread::scope(|scope| {
            for _ in 0..16 {
                let barrier = barrier.clone();
                let counter = &counter;
                let seen = &seen;
                scope.spawn(move || {
                    counter.acquire(|n| seen.lock().unwrap().push(n));
                    barrier.wait();
                    counter.release(|n| seen.lock().unwrap().push(n));
                });
            }
        });
        let expected: Vec<usize> = (1..=16).chain((0..16).rev()).collect();
        assert_eq!(*seen.lock().unwrap(), expected);
    }
}
