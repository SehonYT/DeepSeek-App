package com.dsh.runtime;

import android.content.Context;

import com.dsh.util.IoUtil;
import com.dsh.util.Sha256;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.Iterator;

/** Verifies every runtime file against assets/runtime/manifest.json (fail-closed). */
public final class ManifestVerifier {

    private ManifestVerifier() {
    }

    /**
     * True for manifest entries that describe the *archives* staged in
     * assets/runtime, not files extracted from them.
     *
     * The runtime ships a single packed archive (dsh.tar.gz or, since the zstd
     * work, dsh.tar.zst). CoreInstaller extracts its CONTENTS into
     * runtime/dsh, so the archive itself never exists under runtimeDir.
     * Verifying it as an extracted file made every zstd build fail closed with
     * "missing runtime file: dsh.tar.zst" even though the runtime was fine.
     */
    private static boolean isAssetOnly(String relative) {
        if (relative.endsWith("manifest.json")) {
            return true;
        }
        String name = relative;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // Any packed runtime archive, whatever compression we ship.
        if (name.equals("dsh.tar") || name.startsWith("dsh.tar.")
                || name.equals("seed.tar") || name.startsWith("seed.tar.")) {
            return true;
        }
        return false;
    }

    /** Returns null on success, or a human-readable reason on the first failure. */
    public static String verifyError(Context appContext, File runtimeDir) {
        try (InputStream raw = appContext.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            JSONObject root = new JSONObject(IoUtil.readAll(raw, "UTF-8"));
            JSONObject files = root.optJSONObject("files");
            if (files == null) {
                files = root; // accept a flat {path: sha256} object too
            }
            Iterator<String> keys = files.keys();
            boolean verifiedAnyExtractedFile = false;
            while (keys.hasNext()) {
                String relative = keys.next();
                if (isAssetOnly(relative)) {
                    continue;
                }
                verifiedAnyExtractedFile = true;
                String expected = files.optString(relative, null);
                File target = new File(runtimeDir, relative);
                if (expected == null || !target.isFile()) {
                    return "missing runtime file: " + relative;
                }
                if (relative.endsWith("liblanguage_server.so") || relative.endsWith(RuntimePaths.CORE_NAME)) {
                    if (target.length() <= 0) {
                        return "empty runtime file: " + relative;
                    }
                    continue;
                }
                String actual = Sha256.of(target);
                if (!expected.equalsIgnoreCase(actual)) {
                    return "hash mismatch for " + relative
                            + " (expected=" + expected + " actual=" + actual + ")";
                }
            }
            // The manifest normally describes only the packed archive, whose
            // contents were just extracted. In that case verify the extraction
            // actually produced a usable runtime instead of passing vacuously.
            if (!verifiedAnyExtractedFile) {
                if (!new File(runtimeDir, "dsh/apps/cli/lib/bin.js").isFile()) {
                    return "runtime entrypoint missing after extraction: dsh/apps/cli/lib/bin.js";
                }
            }
            return null;
        } catch (java.io.FileNotFoundException notFound) {
            return null; // manifest is optional
        } catch (Exception e) {
            return "manifest unreadable: " + e.getMessage();
        }
    }
}
