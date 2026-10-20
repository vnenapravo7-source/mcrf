package org.mcrf.engine.protocol.v1;

/** Optional packet backend, not a primary-engine choice. Parent-loaded; no Android/Go types. */
public interface PacketBackend {
    int API_VERSION = 1;

    /** One start per fresh isolated worker. Caller already verified module identity and TUN policy. */
    void start(Request request, Host host) throws Exception;
    /** Closes only module-owned TUN duplicate/sockets. Host still retires the isolated worker. */
    void close();

    /** Closed, non-secret native rejection code; no native message or cause crosses the boundary. */
    final class InvalidTun extends Exception {
        public InvalidTun() { super("PACKET_NATIVE_TUN_REJECTED", null, false, false); }
    }

    interface Host {
        /** Must protect AND bind on captured physical network; stale generations return false. */
        boolean prepareSocket(long generation, int borrowedSocketFd);
        boolean stopping(long generation);
    }

    final class Request {
        public final long generation;
        public final int borrowedTunFd, mtu;
        private final String configuration;
        public Request(long generation, int borrowedTunFd, int mtu, String configuration) {
            if (generation <= 0 || borrowedTunFd < 0 || mtu < 1280 || mtu > 9000 ||
                configuration == null || configuration.isEmpty() ||
                configuration.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536 ||
                configuration.indexOf('\0') >= 0) throw new IllegalArgumentException("PACKET_BACKEND_INPUT");
            this.generation = generation; this.borrowedTunFd = borrowedTunFd; this.mtu = mtu;
            this.configuration = configuration;
        }
        /** Secret UAPI. Never log, serialize, put in Intent extras, or include in errors. */
        public String secretConfiguration() { return configuration; }
        @Override public String toString() { return "PacketBackend.Request(redacted)"; }
    }
}
