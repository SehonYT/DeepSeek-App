package com.dsh.core;

import android.content.Context;
import android.util.Log;

import com.dsh.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the environment for the glibc core child process. */
public final class CoreEnvironment {

    private static final String TAG = "CoreEnvironment";

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager rm, ProcessBuilder pb) {
        File files = rm.appContext().getFilesDir();
        File bin = rm.getBinDir();
        Map<String, String> env = pb.environment();
        // HOME is the app files dir and never a temp dir; it is stable across
        // builds so the core always finds $HOME/.gemini (token, config, chats).
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", rm.appContext().getCacheDir().getAbsolutePath());

        // DSH_HOME points to filesDir/.dsh and must be created
        File dshHome = rm.getDshHome();
        if (!dshHome.exists()) {
            dshHome.mkdirs();
        }
        env.put("DSH_HOME", dshHome.getAbsolutePath());

        File dshData = new File(dshHome, "data");
        if (!dshData.exists()) {
            dshData.mkdirs();
        }
        env.put("DSH_DATA", dshData.getAbsolutePath());
        Log.i(TAG, "core env: HOME=" + files.getAbsolutePath()
                + " DSH_HOME=" + dshHome.getAbsolutePath()
                + " appDataDir=" + rm.getAppDataDir().getAbsolutePath()
                + " projectsDir=" + rm.getProjectsDir().getAbsolutePath()
                + " TMPDIR=" + rm.appContext().getCacheDir().getAbsolutePath());
        File prefix = new File(files, com.dsh.install.EnvPaths.PREFIX_DIR);
        File prefixCert = new File(prefix, "etc/tls/cert.pem");
        File certFile = prefixCert.isFile() ? prefixCert : rm.getCertFile();
        if (certFile != null && certFile.exists()) {
            env.put("SSL_CERT_FILE", certFile.getAbsolutePath());
            env.put("NODE_EXTRA_CA_CERTS", certFile.getAbsolutePath());
            env.put("CURL_CA_BUNDLE", certFile.getAbsolutePath());
            env.put("GIT_SSL_CAINFO", certFile.getAbsolutePath());
        }
        File opensslConf = new File(prefix, "etc/tls/openssl.cnf");
        if (opensslConf.isFile()) {
            env.put("OPENSSL_CONF", opensslConf.getAbsolutePath());
        }
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        String inheritedPath = env.get("PATH");

        // The core's PTY spawner uses $SHELL (or falls back to /bin/bash, which
        // does not exist on Android). Prefer the bridge wrapper in runtime/bin
        // which configures LD_LIBRARY_PATH, PATH, and unsets LD_PRELOAD before
        // execing the bionic bash binary.
        File prefixBash = new File(prefix, com.dsh.install.EnvPaths.BASH_REL);
        File runtimeBash = new File(bin, "bash");
        env.put("PREFIX", prefix.getAbsolutePath());
        File prefixBin = new File(prefix, "bin");
        String finalPath = bin.getAbsolutePath() + ":" + prefixBin.getAbsolutePath();
        if (inheritedPath != null && !inheritedPath.isEmpty()) {
            finalPath += ":" + inheritedPath;
        }
        env.put("PATH", finalPath);
        if (runtimeBash.isFile() && runtimeBash.canExecute()) {
            env.put("SHELL", runtimeBash.getAbsolutePath());
        } else if (prefixBash.isFile() && prefixBash.canExecute()) {
            env.put("SHELL", prefixBash.getAbsolutePath());
        } else {
            env.put("SHELL", "/system/bin/sh");
        }
        // PTY children need a terminal type, and the bootstrap prefix ships the
        // terminfo database that resolves it.
        env.put("TERM", "xterm-256color");
        env.put("PS1", "\\[\\e[0;32m\\]\\w\\[\\e[0m\\] \\$ ");
        File terminfo = new File(prefix, "share/terminfo");
        if (terminfo.isDirectory()) {
            env.put("TERMINFO", terminfo.getAbsolutePath());
        }
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");

        // Pure-Go resolver keeps DNS independent of Android's missing /etc.
        DeviceProfile profile = DeviceProfile.detect(rm.appContext());
        String godebug = env.get("GODEBUG");
        StringBuilder godebugBuilder = new StringBuilder();
        if (godebug != null && !godebug.isEmpty()) {
            godebugBuilder.append(godebug).append(",");
        }
        godebugBuilder.append("netdns=go,asyncpreemptoff=1");
        if (!profile.hasHardwareCryptoAndLse) {
            Log.w(TAG, "CPU lacks ARMv8 crypto/atomics extension; disabling Go hardware crypto acceleration");
            godebugBuilder.append(",cpu.all=off,arm64.aes=off,arm64.pmull=off,arm64.sha1=off,arm64.sha2=off,arm64.sha3=off,arm64.sha512=off,arm64.crc32=off,arm64.atomics=off,arm64.cpuid=off");
        }
        env.put("GODEBUG", godebugBuilder.toString());

        // Tune Node/V8 heap and libuv threadpool for the detected SoC/RAM tier.
        String nodeOpts = env.get("NODE_OPTIONS");
        if (nodeOpts == null || !nodeOpts.contains("--max-old-space-size")) {
            String memFlag = "--max-old-space-size=" + profile.nodeMaxOldSpaceMb;
            env.put("NODE_OPTIONS", (nodeOpts == null || nodeOpts.trim().isEmpty())
                    ? memFlag
                    : (nodeOpts.trim() + " " + memFlag));
        }
        if (!env.containsKey("UV_THREADPOOL_SIZE")) {
            env.put("UV_THREADPOOL_SIZE", String.valueOf(profile.uvThreadpoolSize));
        }
        env.put("DSH_DEVICE_TIER", profile.tier.id);
        env.put("DSH_SOC_FAMILY", profile.socFamily);
        env.put("DSH_TARGET_ARCH", com.dsh.BuildConfig.TARGET_ARCH);
        env.put("DSH_FORK_WALL_MS", String.valueOf(System.currentTimeMillis()));
        configureNodeCompileCache(rm.appContext(), profile.is32Bit, env);
        Log.i(TAG, "device profile: tier=" + profile.tier.id
                + " soc=" + profile.socFamily
                + " 32bit=" + profile.is32Bit
                + " cryptoLse=" + profile.hasHardwareCryptoAndLse
                + " ramMb=" + profile.totalRamMb
                + " maxOldSpaceMb=" + profile.nodeMaxOldSpaceMb);

        env.put("NODE_ENV", "production");
        env.put("DEEPSEEK_HOST", "127.0.0.1");
        env.put("DEEPSEEK_PORT", String.valueOf(rm.getHttpsPort()));
        env.put("DSH_TELEMETRY_DISABLED", "1");

        // NODE_PATH: Ensure Node resolves modules from both root dsh and apps/cli
        File dshDir = new File(rm.getRuntimeDir(), "dsh");
        File rootNodeModules = new File(dshDir, "node_modules");
        File cliNodeModules = new File(dshDir, "apps/cli/node_modules");
        String dshNodePath = rootNodeModules.getAbsolutePath() + ":" + cliNodeModules.getAbsolutePath();
        File prefixNodeModules = new File(prefix, "lib/node_modules");
        if (prefixNodeModules.isDirectory()) {
            dshNodePath += ":" + prefixNodeModules.getAbsolutePath();
        }
        String inheritedNodePath = env.get("NODE_PATH");
        if (inheritedNodePath != null && !inheritedNodePath.isEmpty()) {
            dshNodePath += ":" + inheritedNodePath;
        }
        env.put("NODE_PATH", dshNodePath);

        // Configure browser and desktop environment so core OAuth/open tools find the browser
        File xdgOpen = new File(bin, "xdg-open");
        env.put("BROWSER", xdgOpen.getAbsolutePath());
        env.put("DISPLAY", ":0");
        env.put("WAYLAND_DISPLAY", "wayland-0");
        env.put("XDG_CURRENT_DESKTOP", "X-Generic");
        env.put("XDG_DATA_DIRS", rm.getRuntimeDir().getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(files, ".local/share").getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(files, ".config").getAbsolutePath());

        // Bionic loader needs LD_LIBRARY_PATH pointing to $PREFIX/lib
        File prefixLib = new File(prefix, "lib");
        if (prefixLib.isDirectory()) {
            env.put("LD_LIBRARY_PATH", prefixLib.getAbsolutePath());
        }
        env.remove("LD_PRELOAD");
    }

    private static void configureNodeCompileCache(Context context, boolean is32Bit, Map<String, String> env) {
        if (context == null || env.containsKey("NODE_COMPILE_CACHE")) {
            return;
        }
        try {
            File noBackup = context.getNoBackupFilesDir();
            String envId = com.dsh.install.EnvInstaller.readBundledBundleId(context);
            if (envId.isEmpty()) {
                envId = com.dsh.install.EnvInstaller.readInstalledBundleId(context);
            }
            String coreHash = com.dsh.runtime.CoreInstaller.getExpectedRuntimeHash(context);
            String envPart = envId.length() > 12 ? envId.substring(0, 12) : (envId.isEmpty() ? "0" : envId);
            String corePart = coreHash.length() > 12 ? coreHash.substring(0, 12) : (coreHash.isEmpty() ? "0" : coreHash);
            String cacheName = "v8-cache-" + (is32Bit ? "arm32" : "arm64") + "-" + envPart + "-" + corePart;
            File cacheDir = new File(noBackup, cacheName);
            if (!cacheDir.isDirectory()) {
                cacheDir.mkdirs();
            }
            env.put("NODE_COMPILE_CACHE", cacheDir.getAbsolutePath());
            pruneStaleCompileCachesAsync(noBackup, cacheName);
        } catch (Throwable t) {
            Log.w(TAG, "cannot configure NODE_COMPILE_CACHE: " + t.getMessage());
        }
    }

    private static void pruneStaleCompileCachesAsync(File noBackup, String activeName) {
        File[] children = noBackup.listFiles();
        if (children == null) {
            return;
        }
        boolean hasStale = false;
        for (File child : children) {
            if (child.isDirectory() && child.getName().startsWith("v8-cache-")
                    && !activeName.equals(child.getName())) {
                hasStale = true;
                break;
            }
        }
        if (!hasStale) {
            return;
        }
        Thread cleaner = new Thread(() -> {
            for (File child : children) {
                if (child.isDirectory() && child.getName().startsWith("v8-cache-")
                        && !activeName.equals(child.getName())) {
                    deleteDirQuietly(child);
                }
            }
        }, "V8CachePruner");
        cleaner.setDaemon(true);
        cleaner.start();
    }

    private static void deleteDirQuietly(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteDirQuietly(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * Determines whether the current device is capable of running background sidecars
     * (Automations / Scheduled Tasks) without triggering OOM (LMKD) or execution loops.
     */
    public static boolean isSidecarsSupported(Context context) {
        DeviceProfile profile = DeviceProfile.detect(context);
        if (!profile.sidecarsSupported) {
            Log.i(TAG, "sidecars disabled: tier=" + profile.tier.id
                    + " cryptoLse=" + profile.hasHardwareCryptoAndLse
                    + " 32bit=" + profile.is32Bit
                    + " lowRam=" + profile.isLowRamDevice
                    + " ramMb=" + profile.totalRamMb);
        }
        return profile.sidecarsSupported;
    }

    /**
     * Checks whether the current CPU supports ARMv8 Cryptography Extensions (AES & PMULL)
     * and LSE atomics.
     */
    public static boolean hasArmHardwareCrypto() {
        return DeviceProfile.hasArmHardwareCrypto();
    }
}
