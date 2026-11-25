package com.dsh.install;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.dsh.util.AssetPicker;
import com.dsh.util.AtomicFileWriter;
import com.dsh.util.FileModeUtil;
import com.dsh.util.PathSafety;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Installs and migrates the mutable Bionic prefix without deleting package/user state. */
public final class EnvInstaller {

    private static final String TAG = "EnvInstaller";
    private static final String STATE_SCHEMA = "2";

    private EnvInstaller() {
    }

    public static File prefix(Context context) {
        return EnvPaths.prefix(context);
    }

    /** The ABI family the bundled userspace must match for this device. */
    public enum Arch {
        ARM64,
        ARM32,
        X86,
        X86_64
    }

    /**
     * Resolves the primary ABI the app process actually runs under. This is the
     * authoritative signal: Build.SUPPORTED_ABIS[0] is the ABI Android picked for
     * this APK, so a 128-bit-silicon phone with a 32-bit userspace reports arm32.
     */
    public static Arch deviceArch() {
        String abi = primaryAbi();
        if (abi == null) {
            // Very old API levels: fall back to os.arch.
            String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.US);
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                return Arch.ARM64;
            }
            if (arch.contains("arm")) {
                return Arch.ARM32;
            }
            if (arch.contains("x86_64") || arch.contains("amd64")) {
                return Arch.X86_64;
            }
            return Arch.X86;
        }
        if (abi.startsWith("arm64")) {
            return Arch.ARM64;
        }
        if (abi.startsWith("armeabi") || abi.startsWith("arm")) {
            return Arch.ARM32;
        }
        if (abi.startsWith("x86_64")) {
            return Arch.X86_64;
        }
        return Arch.X86;
    }

    /** The ABI Android selected for this process (null on pre-Lollipop). */
    public static String primaryAbi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            String[] abis = android.os.Build.SUPPORTED_ABIS;
            if (abis != null && abis.length > 0) {
                return abis[0];
            }
        }
        return null;
    }

    /**
     * True when this device runs a 32-bit Android userspace, which cannot load
     * 64-bit ELFs even on 64-bit silicon (e.g. armv8l kernels that expose only
     * the arm32 ABI to apps). Such devices need the 32-bit bootstrap and the
     * matching 32-bit native node-pty prebuild.
     */
    public static boolean is32BitUserspace() {
        Arch arch = deviceArch();
        return arch == Arch.ARM32 || arch == Arch.X86;
    }

    /** The bundled bootstrap asset that matches this device ABI, if any. */
    private static String bootstrapAssetForArch(android.content.res.AssetManager assets) {
        String candidate;
        switch (deviceArch()) {
            case ARM64:
                candidate = EnvPaths.ASSET_BOOTSTRAP;
                break;
            case ARM32:
                candidate = EnvPaths.ASSET_BOOTSTRAP_ARM;
                break;
            case X86_64:
                candidate = EnvPaths.ASSET_BOOTSTRAP_X64;
                break;
            case X86:
            default:
                candidate = EnvPaths.ASSET_BOOTSTRAP_X86;
                break;
        }
        String picked = AssetPicker.pick(assets, candidate);
        if (picked == null) {
            Log.w(TAG, "no bootstrap bundled for ABI " + primaryAbi()
                    + " (wanted " + candidate + "); falling back to aarch64/arm assets");
        }
        return picked;
    }

    public static boolean isInstalled(Context context) {
        File prefix = prefix(context);
        boolean basic = new File(prefix, EnvPaths.MARKER).isFile()
                && new File(prefix, EnvPaths.BASH_REL).isFile()
                && new File(prefix, "bin/node").isFile()
                && new File(prefix, "lib/libtermux-exec.so").exists()
                && hasLibrary(new File(prefix, "lib"), "libsqlite3.so");
        if (!basic) {
            return false;
        }
        // Verify the prefix can actually execute on EVERY ABI, not only 32-bit.
        // A prefix left half-written by an interrupted extraction (or built for a
        // different ABI by an older build) satisfies the file checks above but
        // cannot run, and previously stayed "installed" forever because nothing
        // re-tested it.
        if (!bashSmokeTest(context, prefix)) {
            Log.w(TAG, "installed prefix bash failed its smoke test; a reinstall is required");
            return false;
        }
        return true;
    }

    /** @return true when the prefix was built in this call. */
    public static synchronized boolean install(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("null context");
        }
        Context ctx = context.getApplicationContext();
        if (ctx == null) {
            ctx = context;
        }
        AssetManager assets = ctx.getAssets();
        File prefix = prefix(ctx);
        validatePrefixLocation(ctx, prefix);

        EnvBundleManifest manifest = EnvBundleManifest.load(assets);
        String bootstrapAsset = bootstrapAssetForArch(assets);
        if (bootstrapAsset == null) {
            // No userspace for this device ABI. Per-ABI split APKs only bundle the
            // matching tarball, so this means the user installed the wrong split
            // (or an ABI we do not build). Falling back to the aarch64 bootstrap
            // would guarantee an exec failure; report the real cause instead.
            throw new IllegalStateException("this build does not include a userspace for "
                    + primaryAbi() + " (device ABI); install the matching APK split or the"
                    + " universal build");
        }
        Log.i(TAG, "device ABI=" + primaryAbi() + " arch=" + deviceArch()
                + " bootstrap=" + bootstrapAsset);
        if (isInstalled(ctx)) {
            updateManagedLayers(ctx, assets, prefix, bootstrapAsset, manifest);
            return false;
        }
        installFresh(ctx, assets, prefix, bootstrapAsset, manifest);
        return true;
    }

    private static void validatePrefixLocation(Context context, File prefix) {
        try {
            File filesDir = context.getFilesDir();
            File userData = new File(filesDir, EnvPaths.USER_DATA_DIR);
            if (!PathSafety.isInside(filesDir, prefix)
                    || PathSafety.isInside(userData, prefix)
                    || PathSafety.isInside(prefix, userData)) {
                throw new IllegalStateException(
                        "refusing unsafe env prefix location: " + prefix);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve prefix " + prefix, e);
        }
    }

    private static void installFresh(Context context, AssetManager assets, File prefix,
                                     String bootstrapAsset, EnvBundleManifest manifest) {
        File parent = prefix.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IllegalStateException("cannot create prefix parent: " + parent);
        }
        File stage = new File(parent, ".usr-stage-" + UUID.randomUUID());
        File quarantined = null;
        try {
            if (!stage.mkdirs() && !stage.isDirectory()) {
                throw new IOException("cannot create staging prefix: " + stage);
            }
            String targetPrefix = prefix.getAbsolutePath();
            // Pick the manifest entry that describes the bootstrap actually used.
            // Each bundled ABI is inside the signed manifest, so a swapped or
            // truncated archive still fails closed.
            String expectedSha = manifest.bootstrapSha256;
            int expectedSymlinks = manifest.bootstrapSymlinkCount;
            if (EnvPaths.ASSET_BOOTSTRAP_ARM.equals(bootstrapAsset)) {
                if (manifest.bootstrapArmSha256 != null) {
                    expectedSha = manifest.bootstrapArmSha256;
                    expectedSymlinks = manifest.bootstrapArmSymlinkCount;
                }
            } else if (EnvPaths.ASSET_BOOTSTRAP_X86.equals(bootstrapAsset)) {
                if (manifest.bootstrapX86Sha256 != null) {
                    expectedSha = manifest.bootstrapX86Sha256;
                    expectedSymlinks = manifest.bootstrapX86SymlinkCount;
                }
            } else if (EnvPaths.ASSET_BOOTSTRAP_X64.equals(bootstrapAsset)) {
                if (manifest.bootstrapX64Sha256 != null) {
                    expectedSha = manifest.bootstrapX64Sha256;
                    expectedSymlinks = manifest.bootstrapX64SymlinkCount;
                }
            }
            BootstrapExtractor.extract(assets, bootstrapAsset, stage,
                    expectedSha, expectedSymlinks);
            OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, stage);
            PkgInstaller.install(assets, stage, targetPrefix);
            prepareTree(assets, stage, targetPrefix);
            writeMarker(stage, manifest, expectedSha,
                    expectedSha, false, targetPrefix);
            validateTree(stage, expectedSymlinks);

            // Preserve a previous interrupted/legacy prefix instead of deleting it.
            if (prefix.exists() && !TextFileDetector.isSymlink(prefix)) {
                quarantined = new File(parent, ".usr-incomplete-" + UUID.randomUUID());
                if (!prefix.renameTo(quarantined)) {
                    throw new IOException("cannot preserve incomplete prefix " + prefix);
                }
            } else if (TextFileDetector.isSymlink(prefix)) {
                quarantined = new File(parent, ".usr-incomplete-" + UUID.randomUUID());
                if (!prefix.renameTo(quarantined)) {
                    throw new IOException("cannot preserve prefix link " + prefix);
                }
            }
            if (!stage.renameTo(prefix)) {
                restoreQuarantine(prefix, quarantined);
                throw new IOException("cannot atomically publish staging prefix " + stage);
            }
            if (!bashSmokeTest(context, prefix)) {
                File failed = new File(parent, ".usr-failed-" + UUID.randomUUID());
                if (!prefix.renameTo(failed)) {
                    Log.e(TAG, "new prefix failed smoke test and could not be quarantined: " + prefix);
                }
                restoreQuarantine(prefix, quarantined);
                throw new IOException("installed bash failed its smoke test; prefix preserved for diagnosis");
            }
            Log.i(TAG, "env prefix installed atomically: " + prefix
                    + " bundle=" + manifest.bundleId);
        } catch (Exception e) {
            deleteTreeQuietly(stage, context.getFilesDir());
            throw new IllegalStateException("env install failed: " + e.getMessage(), e);
        }
    }

    /** Refreshes AGY-owned layers but never wipes the mutable prefix or .gemini data. */
    private static void updateManagedLayers(Context context, AssetManager assets, File prefix,
                                            String bootstrapAsset, EnvBundleManifest manifest) {
        File nodeBin = new File(prefix, "bin/node");
        File sqliteLib = new File(prefix, "lib/libsqlite3.so");
        if (!nodeBin.isFile() || !sqliteLib.exists()) {
            Log.w(TAG, "existing prefix lacks node or libsqlite3; forcing fresh install for DeepSeek...");
            installFresh(context, assets, prefix, bootstrapAsset, manifest);
            return;
        }
        Properties previous = readState(new File(prefix, EnvPaths.MARKER));
        String oldBootstrap = previous.getProperty("bootstrap_sha256");
        String oldOverlay = previous.getProperty("overlay_sha256");
        String oldPkg = previous.getProperty("pkg_sha256");
        String oldPath = previous.getProperty("prefix_path");
        String targetPrefix = prefix.getAbsolutePath();

        // A full bootstrap replacement can overwrite package-managed/user edits.
        // Preserve legacy/mutable prefixes and report the pending base migration
        // instead of silently deleting or rebasing their contents.
        boolean bootstrapKnown = oldBootstrap != null && !"legacy".equals(oldBootstrap);
        boolean bootstrapMatches = manifest.bootstrapSha256.equals(oldBootstrap);
        boolean bootstrapPending = !bootstrapMatches;
        String appliedBootstrap = bootstrapKnown ? oldBootstrap : "legacy";
        if (bootstrapKnown && bootstrapPending) {
            Log.w(TAG, "bootstrap changed; preserving mutable prefix until an explicit migration "
                    + "is available (installed=" + oldBootstrap + ", bundled="
                    + manifest.bootstrapSha256 + ")");
        } else if (!bootstrapKnown && bootstrapPending) {
            Log.w(TAG, "legacy prefix has no base version; preserving it and refreshing only "
                    + "AGY-managed overlay/pkg layers");
        }

        boolean overlayChanged = !manifest.overlaySha256.equals(oldOverlay);
        boolean pkgChanged = !manifest.pkgSha256.equals(oldPkg);
        if (overlayChanged) {
            OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, prefix);
        }
        if (pkgChanged) {
            PkgInstaller.install(assets, prefix, targetPrefix);
        }

        boolean prefixMoved = !targetPrefix.equals(oldPath);
        if (prefixMoved || overlayChanged || pkgChanged) {
            TermuxSymlinkFixer.fix(prefix, targetPrefix);
            TermuxShebangRewriter.rewrite(prefix, targetPrefix);
            TermuxPrefixRewriter.rewrite(prefix, targetPrefix);
        }
        if (overlayChanged || pkgChanged) {
            FileModeUtil.recursiveSetExec(new File(prefix, "bin"));
        }
        FileModeUtil.setExec(new File(prefix, "libexec/pkg/pkg"));
        File fixPaths = new File(prefix, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        BusInstaller.install(assets, prefix);
        TermuxHooksRemover.remove(prefix);
        validateTree(prefix, 0);
        writeMarker(prefix, manifest, appliedBootstrap,
                manifest.bootstrapSha256, bootstrapPending, targetPrefix);
        Log.i(TAG, "env managed layers ready: overlay=" + (overlayChanged ? "updated" : "current")
                + " pkg=" + (pkgChanged ? "updated" : "current")
                + " bootstrap=" + (bootstrapPending ? "pending" : "current"));
    }

    private static void prepareTree(AssetManager assets, File root, String targetPrefix) {
        // Repair every path representation before anything can execute from the prefix.
        TermuxSymlinkFixer.fix(root, targetPrefix);
        TermuxShebangRewriter.rewrite(root, targetPrefix);
        TermuxPrefixRewriter.rewrite(root, targetPrefix);
        FileModeUtil.recursiveSetExec(new File(root, "bin"));
        FileModeUtil.setExec(new File(root, "libexec/pkg/pkg"));
        File fixPaths = new File(root, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        BusInstaller.install(assets, root);
        TermuxHooksRemover.remove(root);
    }

    private static void validateTree(File prefix, int minimumSymlinks) {
        File bin = new File(prefix, "bin");
        requireFile(new File(bin, "bash"), "bash");
        requireFile(new File(bin, "sh"), "sh");
        requireFile(new File(bin, "pkg"), "pkg wrapper");
        requireFile(new File(bin, "node"), "node");
        // The 32-bit ARM bundle resolves libsqlite3 to a versioned soname
        // (libsqlite3.so.3.54.1) instead of the unversioned symlink, so accept
        // either form when validating the tree.
        requireAny(new File(prefix, "lib"), "libsqlite3.so", "libsqlite3.so");
        requireFile(new File(prefix, "lib/libtermux-exec.so"), "libtermux-exec.so");
        if (minimumSymlinks > 0) {
            int actual = countSymlinks(prefix);
            if (actual < minimumSymlinks) {
                throw new IllegalStateException("bootstrap symlink set incomplete: " + actual
                        + " < " + minimumSymlinks);
            }
        }
    }

    private static void requireFile(File file, String name) {
        if (!file.isFile()) {
            throw new IllegalStateException("required env file missing: " + name + " at " + file);
        }
    }

    /**
     * Checks a library by base name, tolerating a versioned soname
     * (e.g. libsqlite3.so.3.54.1) or a symlink in the same directory.
     */
    private static boolean hasLibrary(File dir, String baseName) {
        if (new File(dir, baseName).exists()) {
            return true;
        }
        File[] siblings = dir.listFiles();
        if (siblings != null) {
            for (File sibling : siblings) {
                if (sibling.getName().startsWith(baseName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void requireAny(File dir, String baseName, String label) {
        if (!hasLibrary(dir, baseName)) {
            throw new IllegalStateException("required env file missing: " + label + " in " + dir);
        }
    }

    private static int countSymlinks(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                count++;
            } else if (child.isDirectory()) {
                count += countSymlinks(child);
            }
        }
        return count;
    }

    private static Properties readState(File marker) {
        Properties state = new Properties();
        if (!marker.isFile()) {
            return state;
        }
        try (FileInputStream in = new FileInputStream(marker)) {
            state.load(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot read env state " + marker + ": " + e.getMessage());
        }
        return state;
    }

    private static void writeMarker(File prefix, EnvBundleManifest manifest,
                                    String appliedBootstrap, String targetBootstrap,
                                    boolean pending, String targetPrefix) {
        String content = "schema=" + STATE_SCHEMA + "\n"
                + "bundle_id=" + manifest.bundleId + "\n"
                + "bootstrap_sha256=" + appliedBootstrap + "\n"
                + "bootstrap_target_sha256=" + targetBootstrap + "\n"
                + "bootstrap_update_pending=" + pending + "\n"
                + "overlay_sha256=" + manifest.overlaySha256 + "\n"
                + "pkg_sha256=" + manifest.pkgSha256 + "\n"
                + "prefix_path=" + targetPrefix + "\n";
        File marker = new File(prefix, EnvPaths.MARKER);
        AtomicFileWriter.writeText(marker, content);
        Properties written = readState(marker);
        if (!manifest.bundleId.equals(written.getProperty("bundle_id"))) {
            throw new IllegalStateException("cannot persist env state marker: " + marker);
        }
    }

    private static boolean bashSmokeTest(Context context, File prefix) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    new File(prefix, "bin/bash").getAbsolutePath(), "-c", "exit 0");
            builder.directory(context.getFilesDir());
            builder.redirectErrorStream(true);
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("HOME", new File(prefix.getParentFile(), "home").getAbsolutePath());
            builder.environment().put("TMPDIR", new File(prefix, "tmp").getAbsolutePath());
            builder.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
            builder.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
            File preload = new File(prefix, "lib/libtermux-exec.so");
            if (preload.isFile()) {
                builder.environment().put("LD_PRELOAD", preload.getAbsolutePath());
            }
            builder.environment().put("AGY_APPLICATION_ID", context.getPackageName());
            process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Log.w(TAG, "bash smoke test failed: " + e.getMessage());
            return false;
        }
    }

    private static void restoreQuarantine(File prefix, File quarantined) {
        if (quarantined != null && quarantined.exists() && !prefix.exists()
                && !quarantined.renameTo(prefix)) {
            Log.e(TAG, "cannot restore preserved prefix " + quarantined + " -> " + prefix);
        }
    }

    private static void deleteTreeQuietly(File root, File filesDir) {
        if (root == null || !root.exists() || TextFileDetector.isSymlink(root)) {
            if (root != null && TextFileDetector.isSymlink(root)) {
                root.delete();
            }
            return;
        }
        try {
            if (!PathSafety.isInside(filesDir, root) || root.equals(filesDir)) {
                Log.e(TAG, "refusing to clean unsafe staging path: " + root);
                return;
            }
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTreeQuietly(child, filesDir);
                }
            }
            if (!root.delete()) {
                Log.w(TAG, "cannot remove failed staging path: " + root);
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot validate failed staging path: " + e.getMessage());
        }
    }
}
