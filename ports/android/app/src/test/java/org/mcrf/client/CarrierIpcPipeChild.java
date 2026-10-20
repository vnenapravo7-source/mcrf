package org.mcrf.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/** Owned JVM pipe peer only: no Android/native/provider/configuration access. */
public final class CarrierIpcPipeChild {
    private static void await(Path marker) throws Exception {
        long end = System.nanoTime() + 15_000_000_000L;
        while (!Files.isRegularFile(marker)) {
            if (System.nanoTime() >= end) throw new IllegalStateException("Owned marker deadline");
            Thread.sleep(10);
        }
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path root = Path.of(args[1]);
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) throw new IllegalArgumentException("Owned directory required");
        System.out.println("READY"); System.out.flush();
        if (mode.equals("exit")) return;
        if (mode.equals("blocked")) await(root.resolve("read-enabled"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int count = 0;
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (line.equals("STOP")) {
                    StringBuilder hash = new StringBuilder();
                    for (byte value : digest.digest()) hash.append(String.format("%02x", value & 255));
                    System.out.println("STOP_SEEN " + count + " " + hash); System.out.flush();
                    if (mode.equals("hold")) await(root.resolve("exit-enabled"));
                    return;
                }
                digest.update((line + "\n").getBytes(StandardCharsets.US_ASCII)); count++;
            }
        }
        throw new IllegalStateException("EOF without terminal STOP");
    }
}
