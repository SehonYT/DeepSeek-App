package com.dsh.core;

import android.util.Log;

import com.dsh.util.SensitiveDataRedactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/** Appends core logs to rotating files under files/logs/. */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    public static final long DEFAULT_MAX_BYTES = 1_500_000L;
    public static final long STDERR_MAX_BYTES = 512_000L;

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        return open(filesDir, "core.log", DEFAULT_MAX_BYTES);
    }

    public static Writer open(File filesDir, String filename, long maxBytes) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            File logFile = new File(dir, filename);
            if (logFile.exists() && logFile.length() > maxBytes) {
                File oldLog = new File(dir, filename + ".old");
                if (oldLog.exists()) {
                    oldLog.delete();
                }
                logFile.renameTo(oldLog);
            }
            return new OutputStreamWriter(new FileOutputStream(logFile, true),
                    Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "cannot open log " + filename, e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        try {
            writeLinePreRedacted(log, SensitiveDataRedactor.redactLine(line));
        } catch (Exception ignored) {
        }
    }

    /**
     * Writes a line that the caller already ran through
     * {@link SensitiveDataRedactor#redactLine}. The core stdout/stderr streamers
     * redact every line for logcat and the shared ring buffer, then called
     * writeLine() -- which redacted the same string a second time. That is three
     * extra regex passes (2 URLs + 3 secret patterns) per log line, on the arm32
     * target, for output the core emits in bursts during boot.
     */
    public static void writeLinePreRedacted(Writer log, String redactedLine) {
        if (log == null || redactedLine == null) {
            return;
        }
        try {
            log.write(redactedLine);
            log.write('\n');
            log.flush();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
