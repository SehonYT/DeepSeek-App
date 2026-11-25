package com.dsh.install;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/** Stages and extracts the pinned bootstrap in a single streaming pass (zero temp copy). */
public final class BootstrapExtractor {

    private static final String TAG = "BootstrapExtractor";

    private BootstrapExtractor() {
    }

    public static void extract(AssetManager assets, String asset, File prefix) {
        extract(null, assets, asset, prefix, null, 0);
    }

    public static void extract(AssetManager assets, String asset, File prefix,
                               String expectedSha256, int expectedSymlinkCount) {
        extract(null, assets, asset, prefix, expectedSha256, expectedSymlinkCount);
    }

    public static void extract(Context context, AssetManager assets, String asset, File prefix,
                               String expectedSha256, int expectedSymlinkCount) {
        boolean isZst = asset.endsWith(".zst") || asset.endsWith(".tzst");
        boolean isGz = asset.endsWith(".gz") || asset.endsWith(".tgz");
        Log.i(TAG, "streaming bootstrap " + asset + " format=" + (isZst ? "zstd" : (isGz ? "gzip" : "plain-tar")));

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            int extractedLinks;
            try (InputStream raw = new BufferedInputStream(assets.open(asset), 64 * 1024);
                 DigestInputStream digestStream = new DigestInputStream(raw, md)) {
                if (isZst) {
                    extractedLinks = TarExtractor.untarZst(context, digestStream, prefix);
                } else if (isGz) {
                    extractedLinks = TarExtractor.untarGz(digestStream, prefix);
                } else {
                    extractedLinks = TarExtractor.untar(digestStream, prefix);
                }
                // Drain any trailing tar zero blocks / compression trailer so SHA-256 covers the whole file.
                byte[] drain = new byte[16 * 1024];
                while (digestStream.read(drain) != -1) {
                }
            }

            if (expectedSha256 != null) {
                String actual = toHex(md.digest());
                if (!expectedSha256.equalsIgnoreCase(actual)) {
                    throw new IOException("bootstrap sha256 mismatch: got " + actual
                            + " want " + expectedSha256);
                }
            }
            if (extractedLinks < expectedSymlinkCount) {
                throw new IOException("bootstrap symlink count mismatch: extracted "
                        + extractedLinks + " expected at least " + expectedSymlinkCount);
            }
            verifyRequiredFiles(prefix, expectedSymlinkCount);
            Log.i(TAG, "bootstrap streamed cleanly; symlinks=" + extractedLinks);
        } catch (Exception e) {
            throw new IllegalStateException("bootstrap extraction failed (" + asset + "): " + e.getMessage(), e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static void verifyRequiredFiles(File prefix, int expectedSymlinkCount) throws IOException {
        File bin = new File(prefix, "bin");
        File bash = new File(bin, "bash");
        File sh = new File(bin, "sh");
        if (!bin.isDirectory() || !bash.isFile() || !sh.isFile()) {
            throw new IOException("bootstrap is missing required shell files under " + bin);
        }
        if (expectedSymlinkCount > 0 && !TextFileDetector.isSymlink(sh)) {
            throw new IOException("bootstrap bin/sh symlink was not preserved");
        }
    }
}
