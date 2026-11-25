package com.dsh.runtime;

import android.content.Context;
import android.util.Log;

import com.dsh.install.EnvInstaller;
import com.dsh.util.AssetUnpacker;
import com.dsh.util.FileModeUtil;

import java.io.File;
import java.io.IOException;

/** Phase 1: extract + verify the core runtime and the web UI (fail-closed). */
public final class CoreInstaller {

    private static final String TAG = "CoreInstaller";

    private CoreInstaller() {
    }

    private static void extractArchive(Context ctx, String assetName, File destDir) throws IOException {
        File tmp = File.createTempFile("dsh_archive", ".tar.gz", ctx.getCacheDir());
        try {
            AssetUnpacker.copyFile(ctx.getAssets(), assetName, tmp);
            if (com.dsh.install.TarRunner.runToyboxTar(tmp, destDir, true)) {
                Log.i(TAG, "Extracted " + assetName + " with system/toybox tar");
                return;
            }
            Log.w(TAG, "System tar failed; using TarExtractor Java fallback for " + assetName);
            try (java.io.InputStream in = new java.io.FileInputStream(tmp)) {
                com.dsh.install.TarExtractor.untarGz(in, destDir);
            }
        } finally {
            com.dsh.util.IoUtil.deleteQuietly(tmp);
        }
    }

    public static void installCore(RuntimeManager rm) {
        Context ctx = rm.appContext();
        File runtimeDir = rm.getRuntimeDir();
        long lastUpdate = 0;
        try {
            lastUpdate = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        String manifestHash = getAssetManifestHash(ctx);
        String expectedHash = (manifestHash != null && !manifestHash.isEmpty())
                ? manifestHash
                : (com.dsh.BuildConfig.APP_VERSION_CODE + ":" + lastUpdate);
        boolean upToDate = isRuntimeUpToDate(rm, expectedHash)
                && new File(runtimeDir, "dsh/apps/cli/lib/bin.js").isFile();

        if (!upToDate) {
            Log.i(TAG, "extracting assets/runtime (first launch or runtime update)");
            try {
                wipe(rm, runtimeDir);
                if (hasAsset(ctx, "runtime/dsh.tar.gz")) {
                    Log.i(TAG, "unpacking runtime/dsh.tar.gz bundle into runtime/dsh");
                    File dshDir = new File(runtimeDir, "dsh");
                    if (!dshDir.exists()) {
                        dshDir.mkdirs();
                    }
                    extractArchive(ctx, "runtime/dsh.tar.gz", dshDir);
                } else {
                    AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.RUNTIME_ASSET, runtimeDir);
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "cannot unpack assets/runtime into " + runtimeDir
                                + ": " + e.getMessage(), e);
            }
            FileModeUtil.recursiveSetReadableExecutable(rm.getBinDir());
            FileModeUtil.recursiveSetReadableExecutable(rm.getLibDir());
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir);
            if (manifestError != null) {
                throw new IllegalStateException(
                        "runtime integrity check failed: " + manifestError);
            }
            writeMarker(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER, expectedHash);
        } else {
            Log.i(TAG, "runtime up to date (manifest hash matched); skipping extract");
        }
        if (hasAssetTree(ctx, RuntimePaths.WEB_ASSET)) {
            if (!isWebExtracted(rm)) {
                try {
                    AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.WEB_ASSET, rm.getWebDir());
                    writeMarker(rm.getWebDir(), RuntimePaths.WEB_MARKER);
                } catch (IOException e) {
                    Log.w(TAG, "cannot unpack assets/web into " + rm.getWebDir()
                            + ": " + e.getMessage());
                }
            }
        } else {
            rm.getWebDir().mkdirs();
            writeMarker(rm.getWebDir(), RuntimePaths.WEB_MARKER);
        }
        // Exec bits are set once at extraction (and on every generated bridge), so
        // no recursive chmod is needed on a warm launch.
        rm.writeNetworkConfig();
        rm.startNetworkMonitor();
        // Ensure CA certificates are mirrored across expected legacy and prefix paths
        File certSrc = new File(ctx.getFilesDir(), "usr/etc/tls/cert.pem");
        if (certSrc.exists()) {
            File rtCerts = new File(ctx.getFilesDir(), "runtime/certs");
            if (!rtCerts.exists()) {
                rtCerts.mkdirs();
            }
            File rtCertFile = new File(rtCerts, "ca-certificates.crt");
            if (!rtCertFile.exists()) {
                try {
                    java.nio.file.Files.copy(certSrc.toPath(), rtCertFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                }
            }
        }
        OnboardingSeeder.seed(ctx, rm.getAppDataDir(), rm.getProjectsDir());
        // Always ensure fallback shells and open-url shims are installed
        // in runtime/bin immediately regardless of whether the bionic prefix is extracted.
        ShellBridgeInstaller.installFallbackBridges(rm.getBinDir(), ctx);
        ShellBridgeInstaller.installOpenUrlShims(rm.getBinDir(), ctx);
        // If the env prefix survived a previous launch, its bridges are cheap to
        // (re)write now. A fresh env install rewrites them when it finishes.
        if (EnvInstaller.isInstalled(ctx)) {
            File prefix = EnvInstaller.prefix(ctx);
            File prefixBash = new File(prefix, com.dsh.install.EnvPaths.BASH_REL);
            if (prefixBash.isFile()) {
                com.dsh.install.TermuxPrefixRewriter.patchBashBinary(prefixBash);
            }
            com.dsh.install.TermuxPrefixRewriter.patchElfBinaries(prefix, prefix.getAbsolutePath());
            ShellBridgeInstaller.install(rm);
        }
    }

    private static boolean isRuntimeUpToDate(RuntimeManager rm, String expectedHash) {
        if (expectedHash == null || expectedHash.isEmpty()) {
            return false;
        }
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try {
            String actual = com.dsh.util.IoUtil.readAll(new java.io.FileInputStream(marker), "UTF-8").trim();
            return expectedHash.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getAssetManifestHash(Context ctx) {
        try (java.io.InputStream in = ctx.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            return com.dsh.util.Sha256.of(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot hash asset manifest: " + e.getMessage());
            return "";
        }
    }

    private static boolean hasAsset(Context ctx, String assetPath) {
        try (java.io.InputStream in = ctx.getAssets().open(assetPath)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasAssetTree(Context ctx, String assetDir) {
        try {
            String[] list = ctx.getAssets().list(assetDir);
            return list != null && list.length > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isWebExtracted(RuntimeManager rm) {
        File marker = new File(rm.getWebDir(), RuntimePaths.WEB_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try {
            long lastUpdate = rm.appContext().getPackageManager()
                    .getPackageInfo(rm.appContext().getPackageName(), 0).lastUpdateTime;
            if (marker.lastModified() < lastUpdate) {
                Log.i(TAG, "APK updated; refreshing assets/web");
                return false;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static void writeMarker(File dir, String name) {
        writeMarker(dir, name, "1");
    }

    private static void writeMarker(File dir, String name, String content) {
        try {
            com.dsh.util.AtomicFileWriter.writeText(new File(dir, name), content + "\n");
        } catch (Exception e) {
            Log.w(TAG, "cannot write marker " + name + ": " + e.getMessage());
        }
    }

    private static void wipe(RuntimeManager rm, File dir) throws IOException {
        if (dir == null) {
            return;
        }
        if (com.dsh.install.TextFileDetector.isSymlink(dir)) {
            try {
                android.system.Os.remove(dir.getAbsolutePath());
            } catch (Exception e) {
                dir.delete();
            }
            return;
        }
        assertSafeToWipe(rm, dir);
        if (!dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (com.dsh.install.TextFileDetector.isSymlink(child)) {
                    try {
                        android.system.Os.remove(child.getAbsolutePath());
                    } catch (Exception e) {
                        child.delete();
                    }
                } else if (child.isDirectory()) {
                    wipe(rm, child);
                } else {
                    child.delete();
                }
            }
        }
        if (!dir.delete()) {
            dir.deleteOnExit();
        }
    }

    /**
     * Hard safety net: only regenerable state may be wiped. Refuses the app
     * files dir itself, user data, any ancestor of it,
     * and anything outside {@code getFilesDir()}.
     */
    private static void assertSafeToWipe(RuntimeManager rm, File dir) throws IOException {
        if (com.dsh.install.TextFileDetector.isSymlink(dir)) {
            return;
        }
        File files = rm.appContext().getFilesDir();
        String filesPath = files.getAbsolutePath();
        String dshPath = new File(files, ".dsh").getAbsolutePath();
        String geminiPath = new File(files, RuntimePaths.GEMINI_DIR).getAbsolutePath();
        String target = dir.getAbsolutePath();
        if (!target.startsWith(filesPath + File.separator)) {
            throw new IOException("refusing to wipe outside filesDir: " + dir);
        }
        if (target.equals(dshPath)
                || target.startsWith(dshPath + File.separator)
                || dshPath.startsWith(target + File.separator)) {
            throw new IOException("refusing to wipe user data under .dsh: " + dir);
        }
        if (target.equals(geminiPath)
                || target.startsWith(geminiPath + File.separator)
                || geminiPath.startsWith(target + File.separator)) {
            throw new IOException("refusing to wipe user data under .gemini: " + dir);
        }
    }
}
