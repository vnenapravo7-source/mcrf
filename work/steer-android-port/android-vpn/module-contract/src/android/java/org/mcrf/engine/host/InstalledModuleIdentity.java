package org.mcrf.engine.host;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.mcrf.engine.spi.v1.Engine;

/**
 * Android collector candidate. Reads PackageManager, signer certificates and APK bytes;
 * never creates a module Context/ClassLoader or initializes module classes/native code.
 * A snapshot is NOT a lifetime lease or proof against package replacement at load time.
 */
public final class InstalledModuleIdentity {
    private InstalledModuleIdentity() {}
    public static final String META_SPI = "org.mcrf.engine.spi.version";
    public static final String META_API = "org.mcrf.engine.core.api";
    public static final String META_ENTRY = "org.mcrf.engine.entry";

    /** Supplied by trusted HOST distribution policy, never by the untrusted module itself. */
    public static final class TrustSpec {
        public final long versionCode, apkBytes;
        public final String apkSha256, processAbi;
        public TrustSpec(long versionCode, long apkBytes, String apkSha256, String processAbi) {
            if (versionCode < 1 || apkBytes < 1 || apkSha256 == null
                    || !apkSha256.matches("[0-9a-f]{64}")
                    || !("arm64-v8a".equals(processAbi) || "x86_64".equals(processAbi)))
                throw new IllegalArgumentException("Invalid trusted module specification");
            this.versionCode = versionCode; this.apkBytes = apkBytes;
            this.apkSha256 = apkSha256; this.processAbi = processAbi;
        }
    }
    public static final class Receipt {
        public final String packageName, apkPath, apkSha256, processAbi;
        public final long versionCode, lastUpdateTime, apkBytes;
        public final Set<String> currentSignerSha256, moduleAbis;
        private Receipt(PackageInfo info, File apk, TrustSpec spec, Set<String> signers, Set<String> abis) {
            packageName = info.packageName; apkPath = apk.getPath(); apkSha256 = spec.apkSha256;
            processAbi = spec.processAbi; versionCode = info.getLongVersionCode();
            lastUpdateTime = info.lastUpdateTime; apkBytes = spec.apkBytes;
            currentSignerSha256 = Collections.unmodifiableSet(new HashSet<>(signers));
            moduleAbis = Collections.unmodifiableSet(new HashSet<>(abis));
        }
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
    private static Set<String> signers(PackageInfo info) {
        if (info.signingInfo == null) throw new SecurityException("MODULE_SIGNING_INFO");
        Signature[] current = info.signingInfo.getApkContentsSigners();
        if (current == null || current.length == 0) throw new SecurityException("MODULE_SIGNING_INFO");
        Set<String> result = new HashSet<>();
        for (Signature signer : current) result.add(hex(digest().digest(signer.toByteArray())));
        // Current signer sets must match exactly. Historical lineage alone is insufficient.
        return result;
    }
    private static PackageInfo installed(PackageManager pm, String name) throws PackageManager.NameNotFoundException {
        return pm.getPackageInfo(name, PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_META_DATA);
    }
    private static int metadataInt(Bundle metadata, String key) {
        Object value = metadata == null ? null : metadata.get(key);
        if (!(value instanceof Integer)) throw new SecurityException("MODULE_METADATA_" + key);
        return (Integer)value;
    }
    private static void checkBytes(File apk, TrustSpec spec) throws IOException {
        if (!apk.isFile() || apk.canWrite() || apk.length() != spec.apkBytes)
            throw new SecurityException("MODULE_APK_SOURCE");
        MessageDigest digest = digest(); long total = 0;
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > spec.apkBytes) throw new SecurityException("MODULE_APK_SIZE");
                digest.update(buffer, 0, count);
            }
        }
        if (total != spec.apkBytes || !hex(digest.digest()).equals(spec.apkSha256))
            throw new SecurityException("MODULE_APK_DIGEST");
    }
    public static Receipt collect(Context host, TrustSpec spec) throws PackageManager.NameNotFoundException, IOException {
        if (host == null || spec == null || host.getApplicationInfo().uid != Process.myUid())
            throw new SecurityException("HOST_CONTEXT_UID");
        // These candidates are 64-bit only. Host must supply its actual process ABI, not device ABI list.
        if (!Process.is64Bit()) throw new SecurityException("HOST_PROCESS_ABI");
        String arch = System.getProperty("os.arch", "");
        if (!("arm64-v8a".equals(spec.processAbi) && ("aarch64".equals(arch) || "arm64".equals(arch)))
                && !("x86_64".equals(spec.processAbi) && ("x86_64".equals(arch) || "amd64".equals(arch))))
            throw new SecurityException("HOST_PROCESS_ABI");
        PackageManager pm = host.getPackageManager();
        PackageInfo hostInfo = installed(pm, host.getPackageName());
        PackageInfo info = installed(pm, Engine.MODULE_PACKAGE);
        ApplicationInfo app = info.applicationInfo;
        if (app == null || !app.enabled || app.sourceDir == null
                || (app.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0
                || (app.splitSourceDirs != null && app.splitSourceDirs.length != 0))
            throw new SecurityException("MODULE_INSTALL_LAYOUT");
        if (info.getLongVersionCode() != spec.versionCode) throw new SecurityException("MODULE_VERSION");
        Bundle meta = app.metaData;
        int spi = metadataInt(meta, META_SPI), api = metadataInt(meta, META_API);
        if (!Engine.ENTRY_CLASS.equals(meta.get(META_ENTRY))) throw new SecurityException("MODULE_ENTRY");
        File apk = new File(app.sourceDir).getCanonicalFile();
        // Narrow first candidate to internal installed APKs. Adopted/split layouts need separate proof.
        if (!apk.getPath().startsWith("/data/app/") || !apk.getPath().equals(app.sourceDir))
            throw new SecurityException("MODULE_APK_LOCATION");
        Set<String> moduleSigners = signers(info), hostSigners = signers(hostInfo);
        // Reject identity/API before reading large ZIP data; no code is executed by this collector.
        ModuleIdentityGate.verify(info.packageName, hostSigners, moduleSigners, spi, api,
                spec.processAbi, Collections.singleton(spec.processAbi), true, true);
        checkBytes(apk, spec);
        Set<String> abis = new HashSet<>();
        try (ZipFile zip = new ZipFile(apk)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            Set<String> paths = new HashSet<>();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); String path = entry.getName();
                if (!paths.add(path) || paths.size() > 65536) throw new SecurityException("MODULE_ZIP_LAYOUT");
                if (path.matches("lib/(arm64-v8a|x86_64)/libsteer_vpn\\.so")) {
                    String abi = path.split("/")[1]; byte[] header = new byte[20]; int filled = 0;
                    try (InputStream input = zip.getInputStream(entry)) {
                        while (filled < header.length) {
                            int count = input.read(header, filled, header.length - filled);
                            if (count < 0) throw new SecurityException("MODULE_NATIVE_HEADER");
                            filled += count;
                        }
                    }
                    int machine = (header[18] & 255) | ((header[19] & 255) << 8);
                    if (header[0] != 127 || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                            || header[4] != 2 || header[5] != 1
                            || machine != ("arm64-v8a".equals(abi) ? 183 : 62))
                        throw new SecurityException("MODULE_NATIVE_ABI");
                    abis.add(abi);
                }
                if (path.endsWith("/libbox.so")) throw new SecurityException("MODULE_FOREIGN_CORE");
            }
        }
        ModuleIdentityGate.verify(info.packageName, hostSigners, moduleSigners, spi, api,
                spec.processAbi, abis, true, true);
        PackageInfo after = installed(pm, Engine.MODULE_PACKAGE);
        if (after.getLongVersionCode() != info.getLongVersionCode() || after.lastUpdateTime != info.lastUpdateTime
                || after.applicationInfo == null || !after.applicationInfo.enabled
                || !app.sourceDir.equals(after.applicationInfo.sourceDir) || !moduleSigners.equals(signers(after)))
            throw new SecurityException("MODULE_CHANGED_DURING_CHECK");
        checkBytes(apk, spec);
        return new Receipt(info, apk, spec, moduleSigners, abis);
    }
}
