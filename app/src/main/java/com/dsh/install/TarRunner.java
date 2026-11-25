package com.dsh.install;

import android.util.Log;

import com.dsh.util.ProcessUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Extracts the bootstrap with toybox tar, then the plain system tar. */
public final class TarRunner {

    private static final String TAG = "TarRunner";

    private TarRunner() {
    }

    public static boolean runToyboxTar(File archive, File prefix, boolean gzip) {
        if (runTar(EnvPaths.TOYBOX, true, archive, prefix, gzip)) {
            return true;
        }
        return runTar(EnvPaths.TAR, false, archive, prefix, gzip);
    }

    /**
     * True when tar produced a usable prefix despite a non-zero exit.
     *
     * Android's toybox/system tar report failure for chown errors that are
     * unavoidable for an unprivileged app, so the exit code alone is not a
     * reliable signal. Check for the entries every bootstrap/runtime must have.
     */
    private static boolean looksExtracted(File prefix) {
        File bin = new File(prefix, "bin");
        if (new File(bin, "bash").isFile()) {
            return true;
        }
        // Runtime bundles have no bin/bash; accept any substantially populated tree.
        File[] top = prefix.listFiles();
        if (top == null || top.length <= 8) {
            return false;
        }
        // A partially extracted bootstrap is the dangerous case: it has some
        // entries but is missing the shells. Require at least one executable
        // entry point so a truncated extraction is never accepted.
        return new File(prefix, "bin/sh").exists()
                || new File(prefix, "bin/pkg").exists()
                || new File(prefix, "apps").isDirectory()
                || new File(prefix, "node_modules").isDirectory();
    }

    private static boolean runTar(String exe, boolean viaToybox, File archive, File prefix,
                                  boolean gzip) {
        if (!new File(exe).isFile()) {
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        if (viaToybox) {
            cmd.add("tar");
        }
        cmd.add(gzip ? "-xzf" : "-xf");
        cmd.add(archive.getAbsolutePath());
        cmd.add("-C");
        cmd.add(prefix.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!ProcessUtils.drainAndWait(p, 120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.w(TAG, "tar timed out: " + cmd);
                return false;
            }
            int rc = p.exitValue();
            if (rc != 0) {
                // Non-root Android cannot chown, so both toybox and system tar exit
                // non-zero ("chown ... Operation not permitted") even though the
                // extraction itself is complete. Treating that as failure pushed the
                // installer onto the much slower Java fallback (and, before PAX
                // support, onto a broken one). Accept the archive if it actually
                // produced the expected tree.
                if (looksExtracted(prefix)) {
                    Log.i(TAG, "tar exit " + rc + " but tree is populated; accepting: " + cmd);
                    return true;
                }
                Log.w(TAG, "tar exit " + rc + ": " + cmd);
            }
            return rc == 0;
        } catch (Exception e) {
            Log.w(TAG, "tar exec failed " + cmd + ": " + e);
            return false;
        }
    }
}
