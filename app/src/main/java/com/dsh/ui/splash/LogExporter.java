package com.dsh.ui.splash;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import com.dsh.core.CoreRuntimeState;
import com.dsh.runtime.RuntimeConfig;
import com.dsh.util.SensitiveDataRedactor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Collects diagnostics and core logs for user export and debugging. */
public final class LogExporter {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_LOG_BYTES = 300_000;

    /**
     * All three export actions used to run on the main thread, called from the
     * splash bridge: reading and line-by-line redacting up to 300 KiB of core log,
     * writing it out, and recursively zipping the whole projects directory. On the
     * target device an agent project tree is tens of thousands of files, so tapping
     * "Backup Projects" on the splash froze the UI for the entire archive write.
     * The heavy work now runs here and only the user-visible step returns to main.
     */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "LogExporter");
        t.setDaemon(true);
        return t;
    });

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private LogExporter() {
    }

    public static String collectDiagnostics(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DeepSeek Diagnostics ===\n");
        sb.append("Timestamp: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        String verName = RuntimeConfig.APP_VERSION_NAME;
        long verCode = RuntimeConfig.APP_VERSION_CODE;
        if (context != null) {
            try {
                android.content.pm.PackageInfo pi = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                verName = pi.versionName;
                verCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? pi.getLongVersionCode() : pi.versionCode;
            } catch (Exception ignored) {
            }
        }
        sb.append("App Version: ").append(verName).append(" (versionCode ").append(verCode).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n");
        sb.append("Android OS: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Supported ABIs: ").append(Arrays.toString(Build.SUPPORTED_ABIS)).append("\n");
        sb.append("Last Error: ").append(CoreRuntimeState.lastError != null
                ? SensitiveDataRedactor.redactLine(CoreRuntimeState.lastError) : "none").append("\n");
        sb.append("Server URL: ").append(CoreRuntimeState.serverUrl != null
                ? SensitiveDataRedactor.safeUrl(CoreRuntimeState.serverUrl) : "none").append("\n");
        sb.append("\n=== Core Log (files/logs/core.log) ===\n");

        if (context == null) {
            sb.append("(context is null; cannot locate log file)\n");
            return sb.toString();
        }

        File logFile = new File(context.getFilesDir(), "logs/core.log");
        if (!logFile.exists() || logFile.length() == 0) {
            sb.append("(logs/core.log is empty or does not exist)\n");
        } else {
            try {
                long len = logFile.length();
                long skipBytes = Math.max(0, len - MAX_LOG_BYTES);
                try (FileInputStream fis = new FileInputStream(logFile)) {
                    if (skipBytes > 0) {
                        long skipped = fis.skip(skipBytes);
                        sb.append("... [truncated ").append(skipped).append(" bytes; showing last ")
                                .append(len - skipped).append(" bytes of ").append(len).append(" total] ...\n");
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(fis, UTF8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            sb.append(SensitiveDataRedactor.redactLine(line)).append("\n");
                        }
                    }
                }
            } catch (Exception e) {
                sb.append("Error reading log file: ").append(e.getMessage()).append("\n");
            }
        }

        File errFile = new File(context.getFilesDir(), "logs/core_stderr.log");
        if (errFile.exists() && errFile.length() > 0) {
            sb.append("\n=== Core Stderr Head (files/logs/core_stderr.log) ===\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(errFile), UTF8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 15) {
                    sb.append(SensitiveDataRedactor.redactLine(line)).append("\n");
                }
            } catch (Exception ignored) {
            }
        }

        File sigillLog = new File(context.getFilesDir(), "logs/sigill.log");
        if (!sigillLog.exists() || sigillLog.length() == 0) {
            sigillLog = new File(context.getFilesDir(), "sigill.log");
        }
        if ((!sigillLog.exists() || sigillLog.length() == 0) && new File("/data/data/com.dsh/files/logs/sigill.log").exists()) {
            sigillLog = new File("/data/data/com.dsh/files/logs/sigill.log");
        }
        if (sigillLog.exists() && sigillLog.length() > 0) {
            sb.append("\n=== SIGILL Log (files/logs/sigill.log) ===\n");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(sigillLog), UTF8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("Error reading sigill log: ").append(e.getMessage()).append("\n");
            }
        }
        return sb.toString();
    }

    public static void copyToClipboard(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        IO.execute(() -> {
            String report;
            try {
                report = collectDiagnostics(app);
            } catch (Throwable t) {
                report = null;
            }
            final String finalReport = report;
            MAIN.post(() -> {
                if (finalReport == null) {
                    Toast.makeText(app, "Failed to copy logs", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("DeepSeek Logs", finalReport));
                        Toast.makeText(app, "Logs copied to clipboard", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(app, "Failed to copy logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    public static void saveAndShare(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        IO.execute(() -> {
            final String report;
            final File savedFile;
            try {
                report = collectDiagnostics(app);
                savedFile = writeReport(app, report);
            } catch (Throwable t) {
                MAIN.post(() -> Toast.makeText(app, "Failed to export logs",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            MAIN.post(() -> shareReport(app, report, savedFile));
        });
    }

    /** Writes the report to the first writable shared location; null when none worked. */
    private static File writeReport(Context context, String report) {
        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
            File target = new File(downloadsDir, "deepseek-core.log");
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                writer.write(report);
                return target;
            } catch (Exception ignored) {
            }
        }
        File direct = new File(Environment.getExternalStorageDirectory(), "Download/deepseek-core.log");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(direct), UTF8)) {
            writer.write(report);
            return direct;
        } catch (Exception ignored) {
        }
        File appExternal = context.getExternalFilesDir(null);
        if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
            File target = new File(appExternal, "deepseek-core.log");
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(target), UTF8)) {
                writer.write(report);
                return target;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** User-visible part of {@link #saveAndShare}: toast then the system share sheet. */
    private static void shareReport(Context context, String report, File savedFile) {
        try {
            if (savedFile != null) {
                Toast.makeText(context, "Logs saved to " + savedFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "Could not write logs to Downloads", Toast.LENGTH_SHORT).show();
            }

            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("text/plain");
            sendIntent.putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Logs");
            if (report.length() < MAX_LOG_BYTES) {
                sendIntent.putExtra(Intent.EXTRA_TEXT, report);
            }
            sendIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent chooser = Intent.createChooser(sendIntent, "Share logs");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
        } catch (Exception e) {
            Toast.makeText(context, "Failed to export logs: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** Exports and archives the user's projects directory to external storage so data is never lost. */
    public static void exportProjects(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        // The directory walk and the zip write are the two heaviest operations the
        // splash can trigger; they must never run on the main thread.
        IO.execute(() -> {
            final File projectsDir = new File(app.getFilesDir(), "projects");
            if (!projectsDir.exists() || !projectsDir.isDirectory()) {
                MAIN.post(() -> Toast.makeText(app, "No projects directory found to backup",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            final File[] children = projectsDir.listFiles();
            if (children == null || children.length == 0) {
                MAIN.post(() -> Toast.makeText(app, "Projects directory is empty",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            File zipped = null;
            try {
                String zipName = "deepseek-projects-backup-"
                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".zip";
                File downloadsDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir != null && (downloadsDir.exists() || downloadsDir.mkdirs())) {
                    File target = new File(downloadsDir, zipName);
                    if (writeZip(projectsDir, target)) {
                        zipped = target;
                    }
                }
                if (zipped == null) {
                    File appExternal = app.getExternalFilesDir(null);
                    if (appExternal != null && (appExternal.exists() || appExternal.mkdirs())) {
                        File target = new File(appExternal, zipName);
                        if (writeZip(projectsDir, target)) {
                            zipped = target;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            final File savedZip = zipped;
            MAIN.post(() -> shareProjectBackup(app, savedZip));
        });
    }

    /** User-visible part of {@link #exportProjects}: toast then the share sheet. */
    private static void shareProjectBackup(Context context, File savedZip) {
        try {

            if (savedZip != null) {
                Toast.makeText(context, "Projects backed up to " + savedZip.getAbsolutePath(), Toast.LENGTH_LONG).show();
                try {
                    android.net.Uri uri = com.dsh.util.AppFileProvider.getUriForFile(context, savedZip);
                    Intent sendIntent = new Intent(Intent.ACTION_SEND);
                    sendIntent.setType("application/zip");
                    sendIntent.putExtra(Intent.EXTRA_SUBJECT, "DeepSeek Projects Backup");
                    sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Intent chooser = Intent.createChooser(sendIntent, "Backup DeepSeek Projects");
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(chooser);
                } catch (Exception ignored) {
                }
            } else {
                Toast.makeText(context, "Could not write backup to storage", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(context, "Failed to backup projects: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean writeZip(File sourceDir, File zipFile) {
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new FileOutputStream(zipFile))) {
            addDirToZip(sourceDir, "", zos);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addDirToZip(File folder, String parentPath, java.util.zip.ZipOutputStream zos) throws java.io.IOException {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                addDirToZip(file, (parentPath.isEmpty() ? "" : parentPath + "/") + file.getName(), zos);
            } else {
                String entryName = (parentPath.isEmpty() ? "" : parentPath + "/") + file.getName();
                zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
    }
}
