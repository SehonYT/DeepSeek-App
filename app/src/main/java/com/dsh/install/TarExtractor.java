package com.dsh.install;

import android.system.ErrnoException;
import android.system.Os;

import com.dsh.util.FileModeUtil;
import com.dsh.util.PathSafety;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

/** Pure-Java tar fallback with path-checked regular files, directories and symlinks. */
public final class TarExtractor {

    private static final String TAG = "TarExtractor";

    private TarExtractor() {
    }

    public static int untarGz(InputStream raw, File dest) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)) {
            return untar(in, dest);
        }
    }

    /** @return number of symlinks materialized from the archive. */
    public static int untar(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
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
                pendingName = new String(readEntry(in, size), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'K') {
                pendingLink = new String(readEntry(in, size), StandardCharsets.UTF_8).trim();
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
                String pax = new String(readEntry(in, size), StandardCharsets.UTF_8);
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
                skipEntry(in, size);
                continue;
            }
            File out = new File(dest, name);
            ensureParentInside(dest, out);

            if (type == '5') {
                skipEntry(in, size);
                removeConflictingLeaf(out, true);
                if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                    throw new IOException("cannot create dir " + out);
                }
                FileModeUtil.applyMode(out, mode);
            } else if (type == '0') {
                removeConflictingLeaf(out, false);
                writeFile(in, out, size, dest);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '2') {
                skipEntry(in, size);
                validateLinkTarget(dest, out, link);
                removeConflictingLeaf(out, false);
                try {
                    Os.symlink(link, out.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("cannot create symlink " + out + " -> " + link, e);
                }
                links++;
            } else {
                skipEntry(in, size);
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

    private static void validateLinkTarget(File dest, File linkFile, String target) throws IOException {
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
        if (!PathSafety.isInside(dest, resolved)) {
            throw new IOException("tar symlink escapes env prefix: " + linkFile + " -> " + target);
        }
    }

    private static void ensureParentInside(File dest, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !PathSafety.isInside(dest, parent)) {
            throw new IOException("tar entry parent escapes env prefix: " + out);
        }
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create parent " + parent);
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

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size < 0 || size > (8L << 20)) {
            throw new IOException("unreasonable tar entry size: " + size);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        copy(in, out, size);
        skipPadding(in, size);
        return out.toByteArray();
    }

    private static void skipEntry(InputStream in, long size) throws IOException {
        skipExactly(in, size);
        skipPadding(in, size);
    }

    private static void writeFile(InputStream in, File out, long size, File dest) throws IOException {
        ensureParentInside(dest, out);
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os, size);
        }
        skipPadding(in, size);
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        if (size < 0) {
            throw new IOException("negative tar entry size: " + size);
        }
        byte[] buffer = new byte[64 * 1024];
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

    private static void skipExactly(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return;
        }
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipExactly(in, pad);
    }
}
