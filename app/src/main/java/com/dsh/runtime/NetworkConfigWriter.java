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
                AtomicFileWriter.writeText(new File(etc, "resolv.conf"), resolvContent);
                AtomicFileWriter.writeText(new File(etc, "hosts"), hostsContent);
            } catch (Exception e) {
                Log.w(TAG, "failed writing network config to " + etc, e);
            }
        }
    }
}
