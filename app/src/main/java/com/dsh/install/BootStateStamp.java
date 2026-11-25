package com.dsh.install;

import android.content.Context;
import android.util.Log;

import com.dsh.util.AtomicFileWriter;
import com.dsh.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;

/**
 * Single composite warm-boot stamp stored in noBackupFilesDir so warm launches
 * validate env + runtime + bridges with one file read and one node.canExecute() check.
 */
public final class BootStateStamp {

    private static final String TAG = "BootStateStamp";
    private static final String STAMP_NAME = "boot_state.stamp";
    private static final String SCHEMA = "1";

    private BootStateStamp() {
    }

    public static File stampFile(Context context) {
        return new File(context.getNoBackupFilesDir(), STAMP_NAME);
    }

    public static String expectedKey(Context context, String envBundleId, String coreHash, int bridgeFormat) {
        long lastUpdate = 0L;
        try {
            lastUpdate = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (Exception ignored) {
        }
        String abi = EnvInstaller.is32BitUserspace() ? "arm32" : "arm64";
        return "schema=" + SCHEMA
                + "|pkg=" + context.getPackageName()
                + "|ver=" + com.dsh.BuildConfig.APP_VERSION_CODE
                + "|lastUpdate=" + lastUpdate
                + "|abi=" + abi
                + "|env=" + safe(envBundleId)
                + "|core=" + safe(coreHash)
                + "|bridges=" + bridgeFormat;
    }

    public static boolean isWarmReady(Context context, File prefix, File runtimeDir, File binDir,
                                      String envBundleId, String coreHash, int bridgeFormat) {
        File stamp = stampFile(context);
        if (!stamp.isFile()) {
            return false;
        }
        String expected = expectedKey(context, envBundleId, coreHash, bridgeFormat);
        try {
            String actual = IoUtil.readAll(new FileInputStream(stamp), "UTF-8").trim();
            if (!expected.equals(actual)) {
                return false;
            }
        } catch (Exception e) {
            return false;
        }
        // Single fast stat() sanity guard in case user data was partially cleared.
        File nodeBin = new File(prefix, "bin/node");
        File dshEntry = new File(runtimeDir, "dsh/apps/cli/lib/bin.js");
        File bridgeBash = new File(binDir, "bash");
        if (!nodeBin.canExecute() || !dshEntry.isFile() || !bridgeBash.canExecute()) {
            Log.w(TAG, "boot_state.stamp present but critical binary missing; falling back to full check");
            return false;
        }
        return true;
    }

    public static void write(Context context, String envBundleId, String coreHash, int bridgeFormat) {
        try {
            File stamp = stampFile(context);
            File parent = stamp.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            AtomicFileWriter.writeText(stamp, expectedKey(context, envBundleId, coreHash, bridgeFormat) + "\n");
        } catch (Exception e) {
            Log.w(TAG, "cannot write boot_state.stamp: " + e.getMessage());
        }
    }

    public static void invalidate(Context context) {
        try {
            File stamp = stampFile(context);
            if (stamp.exists()) {
                stamp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
