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

    /**
     * Picks the bootstrap asset for this device ABI, preferring zstd when the
     * native decompressor is available. Returns null when no userspace for this
     * ABI is bundled (e.g. the wrong split APK was installed).
     */
    private static String pickBootstrapForArch(Context ctx, android.content.res.AssetManager assets) {
        boolean hasZstd = TarExtractor.findZstdBinary(ctx) != null;
        switch (deviceArch()) {
            case ARM64:
                return hasZstd
                        ? AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP_ZST,
                                EnvPaths.ASSET_BOOTSTRAP, EnvPaths.ASSET_BOOTSTRAP_PLAIN)
                        : AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP,
                                EnvPaths.ASSET_BOOTSTRAP_PLAIN);
            case ARM32:
                return hasZstd
                        ? AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP_ARM_ZST,
                                EnvPaths.ASSET_BOOTSTRAP_ARM)
                        : AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP_ARM);
            case X86_64:
                return AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP_X64);
            case X86:
            default:
                return AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP_X86);
        }
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
        // Verify the prefix can actually execute on EVERY ABI. A prefix left
        // half-written by an interrupted extraction (or built for a different ABI
        // by an older build) satisfies the file checks above but cannot run, and
        // previously stayed "installed" forever because nothing re-tested it.
        // The smoke marker lets a known-good prefix skip the (slower) exec check.
        if (!hasSmokeMarker(context, prefix) && !bashSmokeTest(context, prefix)) {
            Log.w(TAG, "installed prefix bash failed its smoke test; a reinstall is required");
            return false;
        }
        return true;
    }

    public static String readInstalledBundleId(Context context) {
        File prefix = prefix(context);
        Properties state = readState(new File(prefix, EnvPaths.MARKER));
        return state.getProperty("bundle_id", "");
    }

    public static String readBundledBundleId(Context context) {
        try {
            return EnvBundleManifest.load(context.getAssets()).bundleId;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean hasSmokeMarker(Context context, File prefix) {
        File smoke = new File(prefix, ".smoke-ok");
        if (!smoke.isFile()) {
            return false;
        }
        String bundleId = readInstalledBundleId(context);
        if (bundleId.isEmpty()) {
            return false;
        }
        try {
            String content = com.dsh.util.IoUtil.readAll(new FileInputStream(smoke), "UTF-8").trim();
            return (bundleId + ":" + context.getPackageName()).equals(content);
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeSmokeMarker(Context context, File prefix, String bundleId) {
        try {
            AtomicFileWriter.writeText(new File(prefix, ".smoke-ok"),
                    bundleId + ":" + context.getPackageName() + "\n");
        } catch (Exception ignored) {
        }
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
        // Choose the userspace that matches this device ABI. Prefer a zstd
        // archive when the native decompressor is available (faster install),
        // and fall back to gzip. Selected purely by ABI, so a split APK that
        // bundles only its own tarball resolves correctly and a mismatched split
        // fails with an actionable message instead of an exec failure.
        String bootstrapAsset = pickBootstrapForArch(ctx, assets);
        if (bootstrapAsset == null) {
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
            // Resolve the manifest entry describing the bootstrap actually used,
            // including ABI-specific zstd variants, so a swapped or truncated
            // archive still fails closed.
            String expectedSha = manifest.sha256ForArch(deviceArch());
            int expectedSymlinks = manifest.symlinkCountForArch(deviceArch());
            BootstrapExtractor.extract(context, assets, bootstrapAsset, stage,
                    expectedSha, expectedSymlinks);
            OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, stage);
            PkgInstaller.install(assets, stage, targetPrefix);
            prepareTree(context, assets, stage, targetPrefix, manifest);
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
            writeSmokeMarker(context, prefix, manifest.bundleId);
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
        boolean isArm = EnvPaths.ASSET_BOOTSTRAP_ARM.equals(bootstrapAsset)
                || EnvPaths.ASSET_BOOTSTRAP_ARM_ZST.equals(bootstrapAsset);
        String targetBootstrapSha = manifest.expectedBootstrapSha256(isArm);
        boolean bootstrapKnown = oldBootstrap != null && !"legacy".equals(oldBootstrap);
        boolean bootstrapMatches = targetBootstrapSha != null && targetBootstrapSha.equals(oldBootstrap);
        boolean bootstrapPending = !bootstrapMatches;
        String appliedBootstrap = bootstrapKnown ? oldBootstrap : "legacy";
        if (bootstrapKnown && bootstrapPending) {
            Log.w(TAG, "bootstrap changed; preserving mutable prefix until an explicit migration "
                    + "is available (installed=" + oldBootstrap + ", bundled="
                    + targetBootstrapSha + ")");
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
                targetBootstrapSha, bootstrapPending, targetPrefix);
        Log.i(TAG, "env managed layers ready: overlay=" + (overlayChanged ? "updated" : "current")
                + " pkg=" + (pkgChanged ? "updated" : "current")
                + " bootstrap=" + (bootstrapPending ? "pending" : "current"));
    }

    private static void prepareTree(Context context, AssetManager assets, File root,
                                    String targetPrefix, EnvBundleManifest manifest) {
        boolean prepatched = isPrepatchedForTarget(context, root, targetPrefix, manifest);
        if (prepatched) {
            Log.i(TAG, "bootstrap pre-patched for " + targetPrefix + "; skipping runtime tree rewrite");
        } else {
            // Repair every path representation before anything can execute from the prefix.
            TermuxSymlinkFixer.fix(root, targetPrefix);
            TermuxShebangRewriter.rewrite(root, targetPrefix);
            TermuxPrefixRewriter.rewrite(root, targetPrefix);
        }
        // Only ensure overlay/pkg scripts have exec bits (tar extraction already set inline modes).
        FileModeUtil.recursiveSetExec(new File(root, "bin"));
        FileModeUtil.setExec(new File(root, "libexec/pkg/pkg"));
        File fixPaths = new File(root, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        BusInstaller.install(assets, root);
        TermuxHooksRemover.remove(root);
    }

    private static boolean isPrepatchedForTarget(Context context, File root, String targetPrefix,
                                                 EnvBundleManifest manifest) {
        if (context == null || manifest == null) {
            return false;
        }
        String pkg = context.getPackageName();
        String expectedPrefix = "/data/user/0/" + pkg + "/files/" + EnvPaths.PREFIX_DIR;
        if (!expectedPrefix.equals(targetPrefix)) {
            return false;
        }
        if (pkg.equals(manifest.prepatchedPackage)) {
            return true;
        }
        File prepatchedMarker = new File(root, ".dsh-prepatched");
        if (prepatchedMarker.isFile()) {
            try {
                String bakedPkg = com.dsh.util.IoUtil.readAll(new FileInputStream(prepatchedMarker), "UTF-8").trim();
                return pkg.equals(bakedPkg);
            } catch (Exception ignored) {
            }
        }
        return false;
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
