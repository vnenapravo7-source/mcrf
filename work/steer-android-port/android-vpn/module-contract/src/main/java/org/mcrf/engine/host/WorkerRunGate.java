package org.mcrf.engine.host;
import org.mcrf.engine.spi.v1.Engine;

/** Host-side lifecycle policy. Does not claim native teardown, process exit or fd cleanup. */
public final class WorkerRunGate {
    public enum State { NEW, VALIDATED, RUNNING, STOPPING, TERMINATED }
    private final long generation, stopBudgetMillis;
    private State state = State.NEW;
    private long stopAt;
    private boolean deferredTunClaimed;
    public WorkerRunGate(long generation, long stopBudgetMillis) {
        if (generation <= 0 || stopBudgetMillis < 1 || stopBudgetMillis > 30000)
            throw new IllegalArgumentException("Invalid generation/stop budget");
        this.generation = generation; this.stopBudgetMillis = stopBudgetMillis;
    }
    public synchronized void validated() {
        if (state != State.NEW) throw new IllegalStateException("Validation already consumed");
        state = State.VALIDATED;
    }
    public synchronized void started() {
        if (state != State.VALIDATED) throw new IllegalStateException("One validated run per worker");
        state = State.RUNNING;
    }
    /** Monotonic elapsed time, never wall clock. Duplicate requests do not extend the deadline. */
    public synchronized void requestStop(long elapsedMillis) {
        if (elapsedMillis < 0 || elapsedMillis > Long.MAX_VALUE-stopBudgetMillis)
            throw new IllegalArgumentException("Invalid monotonic time");
        if (state == State.STOPPING || state == State.TERMINATED) return;
        state = State.STOPPING; stopAt = elapsedMillis + stopBudgetMillis;
    }
    public synchronized boolean acceptsSocket(long eventGeneration) {
        return generation == eventGeneration && state == State.RUNNING;
    }
    /** Use the HOST-retained immutable request, not a replacement supplied by the module.
     * Claim BEFORE Builder/PFD creation; failure consumes this run (no retry/second TUN).
     */
    public synchronized void claimDeferredTun(long eventGeneration,Engine.Request originalRequest) {
        if(originalRequest==null||!acceptsSocket(eventGeneration)||originalRequest.generation!=generation
                ||originalRequest.mode!=Engine.Mode.VPN||originalRequest.tunAcquisition!=Engine.TunAcquisition.DEFERRED
                ||deferredTunClaimed)throw new IllegalStateException("DEFERRED_TUN_ALREADY_CLAIMED_OR_INVALID");
        deferredTunClaimed=true;
    }
    /** Host must act on this in its watchdog, terminating ONLY its dedicated own worker. */
    public synchronized boolean mustExitWorker(long elapsedMillis) {
        return state == State.STOPPING && elapsedMillis >= stopAt;
    }
    /** Called after externally observing worker death, not merely receiving a module stop ACK. */
    public synchronized void workerDeathObserved() { state = State.TERMINATED; }
    public synchronized State state() { return state; }
}
