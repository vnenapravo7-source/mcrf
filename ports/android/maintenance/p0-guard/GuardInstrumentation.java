package org.mcrf.guard;

import android.app.Instrumentation;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/** Android framework only. No app symbols, UI, preferences writes or network. */
public final class GuardInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    private int count;
    private long total;
    private void file(Map<String,String> result, File root, String name) throws Exception {
        File path = new File(root, name);
        if (!path.exists()) { result.put(name, "absent"); return; }
        if (++count > 1024 || !path.isFile() || Files.isSymbolicLink(path.toPath()) ||
                !path.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator) ||
                path.length() > 16L * 1024 * 1024 || (total += path.length()) > 64L * 1024 * 1024)
            throw new IllegalStateException("Protected inventory boundary failed");
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(path)) {
            byte[] buffer = new byte[65536]; int read;
            while ((read = in.read(buffer)) >= 0) if (read > 0) hash.update(buffer, 0, read);
        }
        StringBuilder value = new StringBuilder();
        for (byte b : hash.digest()) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        result.put(name, value.toString());
    }
    private void stores(Map<String,String> result, File root, String prefix) throws Exception {
        for (String store : new String[]{"profile.enc", "connections.enc", "routing.enc", "preferences.enc"})
            for (String suffix : new String[]{"", ".bak", ".new"}) file(result, root, prefix + store + suffix);
    }
    private String inventory() throws Exception {
        count = 0; total = 0;
        File files = getTargetContext().getFilesDir().getCanonicalFile();
        File data = files.getParentFile();
        Map<String,String> values = new TreeMap<>();
        stores(values, data, "files/");
        for (String suffix : new String[]{"", ".bak", ".new"}) file(values, data, "files/active-profile.txt" + suffix);
        File[] directories = files.listFiles();
        if (directories == null || directories.length > 1024) throw new IllegalStateException("Protected directory inventory failed");
        for (File directory : directories) if (directory.getName().matches("mcrf-store-[0-9a-f-]{36}")) {
            if (!directory.isDirectory() || Files.isSymbolicLink(directory.toPath())) throw new IllegalStateException("Invalid profile generation");
            stores(values, data, "files/" + directory.getName() + "/");
        }
        File prefs = new File(data, "shared_prefs");
        if (prefs.exists()) {
            if (!prefs.isDirectory() || Files.isSymbolicLink(prefs.toPath())) throw new IllegalStateException("Invalid preferences directory");
            File[] entries = prefs.listFiles();
            if (entries == null || entries.length > 1024) throw new IllegalStateException("Preference inventory limit");
            for (File entry : entries) if (entry.getName().matches("[A-Za-z0-9_.-]+\\.xml(?:\\.bak)?"))
                file(values, data, "shared_prefs/" + entry.getName());
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String,String> entry : values.entrySet()) result.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        return result.toString();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String before = inventory(), after = inventory();
            result.putString("protectedHashes", before);
            result.putBoolean("stable", before.equals(after));
            result.putBoolean("passed", before.equals(after));
            result.putInt("presentFiles", count);
        } catch (Throwable failure) {
            result.putBoolean("passed", false);
            result.putString("error", failure.getClass().getSimpleName());
        }
        finish(result.getBoolean("passed") ? android.app.Activity.RESULT_OK : android.app.Activity.RESULT_CANCELED, result);
    }
}
