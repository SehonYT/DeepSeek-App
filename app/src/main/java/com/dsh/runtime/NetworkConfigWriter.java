package com.dsh.runtime;

import android.content.Context;
import android.util.Log;

import com.dsh.net.DnsCollector;
import com.dsh.util.AtomicFileWriter;

import java.io.File;
import java.util.List;

/**
 * Android has no system /etc/resolv.conf.
 * In Termux bionic userspace, libc / c-ares / Node.js look for $PREFIX/etc/resolv.conf
 * (files/usr/etc/resolv.conf), while other runtime tools check files/etc/resolv.conf.
 * We write both atomically with collected DNS servers plus reliable public fallbacks.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";

    private NetworkConfigWriter() {
    }

    /** Last content written per path, so an unchanged refresh skips all flash I/O. */
    private static final java.util.Map<String, String> lastWritten = new java.util.HashMap<>();

    public static void write(Context appContext) {
        List<String> dnsList = DnsCollector.collectDnsServers(appContext);
        // Ensure robust public fallbacks (Google 8.8.8.8, Cloudflare 1.1.1.1, Google 8.8.4.4)
        if (!dnsList.contains("8.8.8.8")) {
            dnsList.add("8.8.8.8");
        }
        if (!dnsList.contains("1.1.1.1")) {
            dnsList.add("1.1.1.1");
        }
        if (!dnsList.contains("8.8.4.4")) {
            dnsList.add("8.8.4.4");
        }

        StringBuilder resolv = new StringBuilder();
        resolv.append("options timeout:2 attempts:3 rotate\n");
        for (String ip : dnsList) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        String resolvContent = resolv.toString();
        String hostsContent = "127.0.0.1 localhost\n::1 localhost\n";

        File filesDir = appContext.getFilesDir();
        File[] etcDirs = new File[] {
            new File(filesDir, "etc"),
            new File(new File(filesDir, "usr"), "etc"),
            new File(new File(filesDir, "runtime"), "etc")
        };

        for (File etc : etcDirs) {
            try {
                if (!etc.exists() && !etc.mkdirs()) {
                    continue;
                }
                writeIfChanged(new File(etc, "resolv.conf"), resolvContent);
                writeIfChanged(new File(etc, "hosts"), hostsContent);
            } catch (Exception e) {
                Log.w(TAG, "failed writing network config to " + etc, e);
            }
        }
    }

    /**
     * Atomic write that is skipped when the file already holds exactly this
     * content. Every network change used to rewrite 6 files (3 dirs x 2 files),
     * each with an fsync, and NetworkMonitor calls this on EVERY callback
     * (including onLinkPropertiesChanged), so an idle device could fsync 12+
     * times for a callback that changed nothing.
     */
    private static void writeIfChanged(File file, String content) {
        String key = file.getAbsolutePath();
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // The cache is only a hint: re-validate that the file still exists, so a
        // wipe (cold install clears runtime/) cannot leave a stale cache entry
        // suppressing the rewrite.
        synchronized (lastWritten) {
            if (content.equals(lastWritten.get(key)) && file.isFile() && file.length() == bytes.length) {
                return;
            }
        }
        try {
            if (file.isFile() && file.length() == bytes.length) {
                String existing;
                try (java.io.InputStream in = new java.io.FileInputStream(file)) {
                    existing = com.dsh.util.IoUtil.readAll(in, "UTF-8");
                }
                if (content.equals(existing)) {
                    synchronized (lastWritten) {
                        lastWritten.put(key, content);
                    }
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        AtomicFileWriter.writeText(file, content);
        synchronized (lastWritten) {
            lastWritten.put(key, content);
        }
    }
}
