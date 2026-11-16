package org.mcrf.engine.host;

import java.util.Set;
import java.util.Arrays;
import org.mcrf.engine.spi.v1.Engine;

/** Pure policy. Android collector must provide actual PackageManager/ZIP/signature facts. */
public final class ModuleIdentityGate {
    private ModuleIdentityGate() {}
    public static void verify(String packageName, Set<String> hostCurrentSigners,
                              Set<String> moduleCurrentSigners, int spi, int coreApi,
                              String expectedAbi, Set<String> moduleAbis, boolean enabled,
                              boolean sourceIsInstalledReadOnlyApk) {
        if (!Engine.MODULE_PACKAGE.equals(packageName)) throw new SecurityException("MODULE_PACKAGE");
        if (hostCurrentSigners == null || hostCurrentSigners.isEmpty()
                || moduleCurrentSigners == null || !hostCurrentSigners.equals(moduleCurrentSigners))
            throw new SecurityException("MODULE_SIGNER");
        for (String sha : hostCurrentSigners)
            if (sha == null || !sha.matches("[0-9a-f]{64}")) throw new SecurityException("MODULE_SIGNER_FORMAT");
        if (spi != Engine.SPI_VERSION || coreApi != 2) throw new SecurityException("MODULE_API");
        if (expectedAbi == null || !Arrays.asList("arm64-v8a", "x86_64").contains(expectedAbi)
                || moduleAbis == null || !moduleAbis.contains(expectedAbi)) throw new SecurityException("MODULE_ABI");
        if (!enabled || !sourceIsInstalledReadOnlyApk) throw new SecurityException("MODULE_INSTALL_SOURCE");
    }
}
