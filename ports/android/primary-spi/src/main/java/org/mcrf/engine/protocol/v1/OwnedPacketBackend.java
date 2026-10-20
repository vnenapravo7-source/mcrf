package org.mcrf.engine.protocol.v1;

/** Native adapter lifecycle. The module supplies the factory, never the base APK. */
public final class OwnedPacketBackend implements PacketBackend {
    public interface SocketPreparation { boolean prepare(int borrowedFd); }
    public interface NativeSession { void close(); }
    public interface Factory {
        /** Must duplicate the borrowed TUN, own that duplicate on failure too, and sanitize errors. */
        NativeSession start(int borrowedTunFd, int mtu, String secretConfiguration, SocketPreparation protector) throws Exception;
    }
    private final Factory factory;
    private final Object lock = new Object();
    private final Object closeLock = new Object();
    private boolean claimed, stopped;
    private NativeSession session;

    public OwnedPacketBackend(Factory factory) {
        if (factory == null) throw new IllegalArgumentException("PACKET_BACKEND_FACTORY");
        this.factory = factory;
    }

    @Override public void start(Request request, Host host) throws Exception {
        if (request == null || host == null) throw new IllegalArgumentException("PACKET_BACKEND_INPUT");
        synchronized (lock) {
            if (claimed || stopped) throw new IllegalStateException("PACKET_BACKEND_ALREADY_CLAIMED");
            claimed = true;
        }
        NativeSession started = null;
        boolean accepted = false;
        try {
            if (host.stopping(request.generation)) throw new IllegalStateException("PACKET_BACKEND_STOPPED");
            started = factory.start(request.borrowedTunFd, request.mtu, request.secretConfiguration(), fd -> {
                synchronized (lock) {
                    if (stopped || fd < 0) return false;
                }
                // Do not hold the lifecycle lock across host IPC: close must never wait on Binder.
                if (host.stopping(request.generation) || !host.prepareSocket(request.generation, fd)) return false;
                if (host.stopping(request.generation)) return false;
                synchronized (lock) { return !stopped; }
            });
            if (started == null) throw new IllegalStateException("PACKET_BACKEND_NO_SESSION");
            boolean hostStopped = host.stopping(request.generation);
            synchronized (lock) {
                if (!stopped && !hostStopped) {
                    session = started;
                    accepted = true;
                }
            }
            if (!accepted) throw new IllegalStateException("PACKET_BACKEND_STOPPED");
        } catch (PacketBackend.InvalidTun rejected) {
            throw new PacketBackend.InvalidTun();
        } catch (Exception ignored) {
            // Native/host exception may contain raw UAPI/key material. Do not attach the cause.
            throw new IllegalStateException("PACKET_BACKEND_START_FAILED");
        } finally {
            if (!accepted) {
                // A late session still belongs to us even if its first close fails.
                synchronized (lock) { stopped = true; if (started != null) session = started; }
                close();
            }
        }
    }

    @Override public void close() {
        synchronized (lock) { stopped = true; }
        // Serialize native close without holding the callback/admission lock.
        // Never forget the owner until successful close; a failure is retryable.
        synchronized (closeLock) {
            NativeSession owned;
            synchronized (lock) { owned = session; }
            if (owned == null) return;
            closeOwned(owned);
            synchronized (lock) { if (session == owned) session = null; }
        }
    }

    private static void closeOwned(NativeSession owned) {
        try { owned.close(); }
        catch (RuntimeException ignored) { throw new IllegalStateException("PACKET_BACKEND_CLOSE_FAILED"); }
    }
}
