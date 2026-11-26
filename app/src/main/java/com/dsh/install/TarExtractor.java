package com.dsh.install;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.dsh.util.FileModeUtil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

/** Path-checked streaming tar extractor with zstd/gzip support and inline chmod/symlinks. */
public final class TarExtractor {

    private static final String TAG = "TarExtractor";
    private static final String ZSTD_LIB_NAME = "libdsh_zstd.so";

    private TarExtractor() {
    }

    public static File findZstdBinary(Context context) {
        if (context == null) {
            return null;
        }
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (nativeDir == null || nativeDir.isEmpty()) {
            return null;
        }
        File zstd = new File(nativeDir, ZSTD_LIB_NAME);
        return (zstd.isFile() && zstd.canExecute()) ? zstd : null;
    }

    public static int untarGz(InputStream raw, File dest) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)) {
            return untar(in, dest);
        }
    }

    public static int untarZst(Context context, InputStream raw, File dest) throws IOException {
        File zstd = findZstdBinary(context);
        if (zstd == null) {
            throw new IOException("native zstd binary not available");
        }
        ProcessBuilder pb = new ProcessBuilder(zstd.getAbsolutePath(), "-d", "-c", "--no-progress");
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process proc = pb.start();
        final IOException[] writerError = new IOException[1];
        Thread feeder = new Thread(() -> {
            try (OutputStream os = proc.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = raw.read(buf)) != -1) {
                    os.write(buf, 0, n);
                }
                os.flush();
            } catch (IOException e) {
                writerError[0] = e;
            }
        }, "ZstdFeedThread");
        feeder.start();

        int links;
        try (InputStream zstdOut = new BufferedInputStream(proc.getInputStream(), 1 << 16)) {
            links = untar(zstdOut, dest);
        } finally {
            try {
                feeder.join(10_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (writerError[0] != null) {
            proc.destroyForcibly();
            throw writerError[0];
        }
        try {
            if (!proc.waitFor(30, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("zstd process timed out");
            }
            if (proc.exitValue() != 0) {
                throw new IOException("zstd exited with code " + proc.exitValue());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new IOException("interrupted waiting for zstd", ie);
        }
        return links;
    }

    /** @return number of symlinks materialized from the archive. */
    public static int untar(InputStream in, File dest) throws IOException {
        if (!dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("cannot create dest dir " + dest);
        }
        String destCanon = dest.getCanonicalPath();
        String destCanonPrefix = destCanon.endsWith(File.separator) ? destCanon : destCanon + File.separator;
        String[] initialEntries = dest.list();
        boolean freshDest = initialEntries == null || initialEntries.length == 0;
        Set<String> verifiedDirs = new HashSet<>();
        Set<String> seenEntries = new HashSet<>();
        Set<String> createdDirs = new HashSet<>();
        Set<String> createdSymlinks = new HashSet<>();

        byte[] header = new byte[512];
        byte[] ioBuf = new byte[64 * 1024];
        String pendingName = null;
        String pendingLink = null;
        int links = 0;
        while (TarHeader.readBlock(in, header)) {
            if (TarHeader.isZeroBlock(header)) {
                break;
            }
            String name = TarHeader.readField(header, 0, 100);
            long mode = TarHeader.readOctal(header, 100, 8);
            long size = TarHeader.readOctal(header, 124, 12);
            int type = header[156] & 0xff;
            String link = TarHeader.readField(header, 157, 100);
            if (pendingLink != null) {
                link = pendingLink;
                pendingLink = null;
            }
            String dirPrefix = TarHeader.readField(header, 345, 155);
            if (type == 0) {
                type = '0';
            }
            if (type == 'L') {
                pendingName = new String(readEntry(in, size, ioBuf), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'K') {
                pendingLink = new String(readEntry(in, size, ioBuf), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'x' || type == 'g') {
                // PAX extended header. GNU tar emits one for EVERY entry (it stores
                // mtime with sub-second precision), and long paths (>100 bytes,
                // or >155 for the ustar prefix) are stored ONLY here as "path=".
                // Skipping these silently truncates every long member name to the
                // 100-byte ustar field, which makes distinct files collide onto one
                // directory name and breaks extraction with
                // "cannot replace non-empty tar directory".
                String pax = new String(readEntry(in, size, ioBuf), StandardCharsets.UTF_8);
                if (type == 'x') {
                    String paxPath = paxValue(pax, "path");
                    if (paxPath != null) {
                        pendingName = paxPath;
                    }
                    String paxLink = paxValue(pax, "linkpath");
                    if (paxLink != null) {
                        pendingLink = paxLink;
                    }
                }
                continue;
            }
            boolean nameIsComplete = false;
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
                // Both a PAX path= value and a GNU 'L' long name carry the complete
                // member path already.
                nameIsComplete = true;
            }
            // The ustar "prefix" field only applies to the truncated 100-byte
            // header name. Prepending it to an already-complete long name would
            // corrupt the path.
            if (!nameIsComplete && dirPrefix != null && !dirPrefix.isEmpty()) {
                name = dirPrefix + "/" + name;
            }
            name = TarPath.normalize(name);
            if (name == null) {
                throw new IOException("unsafe tar entry path");
            }
            if (name.isEmpty()) {
                skipEntry(in, size, ioBuf);
                continue;
            }
            File out = new File(dest, name);
            boolean hasSymlinkParent = hasSymlinkAncestor(name, createdSymlinks);
            ensureParentInsideCached(destCanon, destCanonPrefix, name, out,
                    verifiedDirs, createdDirs, hasSymlinkParent);
            boolean mayExist = !freshDest || hasSymlinkParent || seenEntries.contains(name);

            if (type == '5') {
                skipEntry(in, size, ioBuf);
                if (mayExist) {
                    removeConflictingLeaf(out, true);
                }
                if (mayExist || !createdDirs.contains(name)) {
                    if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                        throw new IOException("cannot create dir " + out);
                    }
                }
                if (!hasSymlinkParent) {
                    recordDirs(name, verifiedDirs, createdDirs);
                }
                createdSymlinks.remove(name);
                seenEntries.add(name);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '0') {
                if (mayExist || createdDirs.contains(name)) {
                    invalidateTree(verifiedDirs, name);
                    invalidateTree(createdDirs, name);
                    createdSymlinks.remove(name);
                    removeConflictingLeaf(out, false);
                }
                writeFile(in, out, size, ioBuf);
                seenEntries.add(name);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '1') {
                skipEntry(in, size, ioBuf);
                String targetNorm = TarPath.normalize(link);
                if (targetNorm == null || targetNorm.isEmpty()) {
                    throw new IOException("unsafe hardlink target: " + link);
                }
                File targetFile = new File(dest, targetNorm);
                if (!isInsideCanon(destCanon, destCanonPrefix, targetFile)) {
                    throw new IOException("hardlink escapes dest: " + targetNorm);
                }
                if (mayExist || createdDirs.contains(name)) {
                    invalidateTree(verifiedDirs, name);
                    invalidateTree(createdDirs, name);
                    createdSymlinks.remove(name);
                    removeConflictingLeaf(out, false);
                }
                try {
                    Os.link(targetFile.getAbsolutePath(), out.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("cannot create hardlink " + out + " -> " + targetFile, e);
                }
                seenEntries.add(name);
            } else if (type == '2') {
                skipEntry(in, size, ioBuf);
                validateLinkTarget(destCanon, destCanonPrefix, out, link);
                invalidateTree(verifiedDirs, name);
                invalidateTree(createdDirs, name);
                if (mayExist || createdDirs.contains(name)) {
                    removeConflictingLeaf(out, false);
                }
                try {
                    Os.symlink(link, out.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("cannot create symlink " + out + " -> " + link, e);
                }
                createdSymlinks.add(name);
                seenEntries.add(name);
                links++;
            } else {
                skipEntry(in, size, ioBuf);
                throw new IOException("unsupported tar entry type " + (char) type + " at " + name);
            }
        }
        return links;
    }

    /**
     * Reads one key from a PAX extended header payload.
     *
     * PAX records are "LEN key=value" records terminated by a newline, where LEN
     * counts the whole record including the length digits, the space and the
     * newline. Values are UTF-8 and may contain "=", so split on the first "="
     * that follows the length + space prefix instead of on the first "=" in the
     * line.
     */
    private static String paxValue(String payload, String key) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        String wanted = key + "=";
        int pos = 0;
        int len = payload.length();
        while (pos < len) {
            int space = payload.indexOf(' ', pos);
            if (space < 0) {
                break;
            }
            int recordLen;
            try {
                recordLen = Integer.parseInt(payload.substring(pos, space).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (recordLen <= 0 || pos + recordLen > len) {
                return null;
            }
            int keyStart = space + 1;
            if (payload.startsWith(wanted, keyStart)) {
                int valueStart = keyStart + wanted.length();
                int valueEnd = pos + recordLen;
                while (valueEnd > valueStart
                        && (payload.charAt(valueEnd - 1) == 0x0a
                            || payload.charAt(valueEnd - 1) == 0x0d)) {
                    valueEnd--;
                }
                return payload.substring(valueStart, valueEnd);
            }
            pos += recordLen;
        }
        return null;
    }

    private static boolean isInsideCanon(String destCanon, String destCanonPrefix, File file) throws IOException {
        String fileCanon = file.getCanonicalPath();
        return fileCanon.equals(destCanon) || fileCanon.startsWith(destCanonPrefix);
    }

    private static void validateLinkTarget(
            String destCanon, String destCanonPrefix, File linkFile, String target) throws IOException {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0) {
            throw new IOException("empty/invalid tar symlink target at " + linkFile);
        }
        if (target.startsWith("/")) {
            Matcher matcher = EnvPaths.TERMUX_ANY_PREFIX.matcher(target);
            if (!matcher.lookingAt()) {
                throw new IOException("absolute tar symlink escapes env prefix: " + target);
            }
            return;
        }
        File resolved = new File(linkFile.getParentFile(), target);
        if (!isInsideCanon(destCanon, destCanonPrefix, resolved)) {
            throw new IOException("tar symlink escapes env prefix: " + linkFile + " -> " + target);
        }
    }

    private static boolean hasSymlinkAncestor(String relPath, Set<String> createdSymlinks) {
        if (createdSymlinks.isEmpty() || relPath.isEmpty()) {
            return false;
        }
        int idx = relPath.indexOf('/');
        while (idx > 0) {
            if (createdSymlinks.contains(relPath.substring(0, idx))) {
                return true;
            }
            idx = relPath.indexOf('/', idx + 1);
        }
        return false;
    }

    private static void recordDirs(String dirRel, Set<String> verifiedDirs, Set<String> createdDirs) {
        if (dirRel.isEmpty()) {
            return;
        }
        verifiedDirs.add(dirRel);
        int idx = dirRel.indexOf('/');
        while (idx > 0) {
            createdDirs.add(dirRel.substring(0, idx));
            idx = dirRel.indexOf('/', idx + 1);
        }
        createdDirs.add(dirRel);
    }

    private static void invalidateTree(Set<String> paths, String prefix) {
        paths.remove(prefix);
        String slashPrefix = prefix + "/";
        for (java.util.Iterator<String> it = paths.iterator(); it.hasNext(); ) {
            if (it.next().startsWith(slashPrefix)) {
                it.remove();
            }
        }
    }

    private static void ensureParentInsideCached(
            String destCanon,
            String destCanonPrefix,
            String relPath,
            File out,
            Set<String> verifiedDirs,
            Set<String> createdDirs,
            boolean hasSymlinkParent
    ) throws IOException {
        int slash = relPath.lastIndexOf('/');
        if (slash <= 0) {
            return;
        }
        String parentRel = relPath.substring(0, slash);
        if (!hasSymlinkParent && verifiedDirs.contains(parentRel)) {
            return;
        }
        File parent = out.getParentFile();
        if (parent == null) {
            return;
        }
        if (!isInsideCanon(destCanon, destCanonPrefix, parent)) {
            throw new IOException("tar entry parent escapes env prefix: " + out);
        }
        if (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create parent " + parent);
        }
        if (!hasSymlinkParent) {
            recordDirs(parentRel, verifiedDirs, createdDirs);
        }
    }

    private static void removeConflictingLeaf(File out, boolean allowDirectory) throws IOException {
        if (TextFileDetector.isSymlink(out)) {
            try {
                Os.remove(out.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("cannot replace tar symlink " + out, e);
            }
            return;
        }
        if (!out.exists()) {
            return;
        }
        if (out.isDirectory()) {
            if (allowDirectory) {
                return;
            }
            // A file/symlink entry collided with an existing directory. This can
            // happen when an archive is extracted twice, or when two members share
            // a name. Recurse so a populated directory is replaced instead of
            // failing the whole install with a dead end: the stage directory is
            // disposable, and the archive entry we are about to write wins.
            deleteRecursively(out);
            return;
        }
        if (!out.delete()) {
            throw new IOException("cannot replace tar file " + out);
        }
    }

    /** Deletes a path and anything beneath it (symlinks are unlinked, not followed). */
    private static void deleteRecursively(File target) throws IOException {
        if (target == null || !target.exists()) {
            if (target != null && TextFileDetector.isSymlink(target)) {
                try {
                    Os.remove(target.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("cannot remove " + target, e);
                }
            }
            return;
        }
        if (TextFileDetector.isSymlink(target)) {
            try {
                Os.remove(target.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("cannot remove symlink " + target, e);
            }
            return;
        }
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (!target.delete() && target.exists()) {
            throw new IOException("cannot remove " + target);
        }
    }

    private static byte[] readEntry(InputStream in, long size, byte[] buffer) throws IOException {
        if (size < 0 || size > (8L << 20)) {
            throw new IOException("unreasonable tar entry size: " + size);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        copy(in, out, size, buffer);
        skipPadding(in, size, buffer);
        return out.toByteArray();
    }

    private static void skipEntry(InputStream in, long size, byte[] buffer) throws IOException {
        skipExactly(in, size, buffer);
        skipPadding(in, size, buffer);
    }

    private static void writeFile(InputStream in, File out, long size, byte[] buffer) throws IOException {
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os, size, buffer);
        }
        skipPadding(in, size, buffer);
    }

    private static void copy(InputStream in, OutputStream out, long size, byte[] buffer) throws IOException {
        if (size < 0) {
            throw new IOException("negative tar entry size: " + size);
        }
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(InputStream in, long size, byte[] buffer) throws IOException {
        if (size <= 0) {
            return;
        }
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size, byte[] buffer) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipExactly(in, pad, buffer);
    }
}
