package org.mcrf.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Pure preflight policy. No implicit migration, network, native loading or preference writes. */
public final class ClientPrimaryCorePolicy {
    private ClientPrimaryCorePolicy() {}
    public enum Core {
        SINGBOX("singbox", "sing-box", "sing-box", "org.mcrf.engines.singbox", "org.mcrf.engines.singbox.SingBoxEngine"),
        STEER("steer", "steer", "Steer", "org.mcrf.engines.steer", "org.mcrf.engines.steer.SteerEngine");
        public final String id, kind, label, packageName, entryClass;
        Core(String id, String kind, String label, String packageName, String entryClass) {
            this.id=id;this.kind=kind;this.label=label;this.packageName=packageName;this.entryClass=entryClass;
        }
        public static Core parse(String id) {
            for(Core core:values()) if(core.id.equals(id)) return core;
            throw new IllegalArgumentException("PRIMARY_CORE_UNKNOWN");
        }
    }
    public enum Action { CHOOSE, INSTALL, PREFLIGHT }
    /** Installed must mean verified identity, not merely PackageManager presence. */
    public static Action action(String selected, Set<String> verifiedInstalled) {
        if(selected==null || selected.isEmpty()) return Action.CHOOSE;
        Core core=Core.parse(selected);
        return verifiedInstalled.contains(core.id)?Action.PREFLIGHT:Action.INSTALL;
    }
    /** Required semantic features come from the complete saved snapshot, not UI defaults. */
    public static Set<String> unsupported(Set<String> required, Set<String> supported) {
        Set<String> missing=new HashSet<>(required);missing.removeAll(supported);
        return Collections.unmodifiableSet(missing);
    }
    public static void requireCompatible(Set<String> required, Set<String> supported) {
        if(!unsupported(required,supported).isEmpty()) throw new IllegalArgumentException("PRIMARY_CORE_CAPABILITIES");
    }
    public static void requireIdentity(Core requested, String packageName, String kind,
                                       int spi, int coreApi, int expectedCoreApi, String entry,
                                       boolean enabled, boolean exactCurrentSigner) {
        if(!enabled || !exactCurrentSigner || !requested.packageName.equals(packageName)
                || !requested.kind.equals(kind) || spi!=1 || expectedCoreApi<=0
                || coreApi!=expectedCoreApi || !requested.entryClass.equals(entry))
            throw new SecurityException("PRIMARY_CORE_IDENTITY");
    }
}
