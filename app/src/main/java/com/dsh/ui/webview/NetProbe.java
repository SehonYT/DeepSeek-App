package com.dsh.ui.webview;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import android.webkit.CookieManager;

import com.dsh.core.CoreRuntimeState;
import com.dsh.util.SensitiveDataRedactor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes a small, Termux-readable probe report when the UI is up but the backend
 * looks unreachable. The app's own logs live in private storage that a desktop
 * shell cannot read on a stock device, so a "the UI loads but keeps reconnecting"
 * report otherwise arrives with no evidence at all.
 *
 * <p>Two files are written to shared storage, which Termux can read:
 * {@code Download/deepseek-netprobe.json} (machine readable, overwritten) and an
 * appended line in {@code Download/deepseek-netprobe.log}.</p>
 *
 * <p>The {@code detail} field is diagnostic text only. It is written verbatim
 * because the generic line redactor treats {@code cookie=} as a credential field
 * and deletes everything after it, which previously erased exactly the fields this
 * report exists to capture. Secrets never enter this field: URLs are reduced to
 * scheme://host:port and the cookie report is a boolean plus a count.</p>
 */
public final class NetProbe {

    private static final String TAG = "NetProbe";
    public static final String JSON_NAME = "deepseek-netprobe.json";
    public static final String LOG_NAME = "deepseek-netprobe.log";

    private NetProbe() {
    }

    /** Records one probe result. Never throws; diagnostics must not break the app. */
    public static void record(Context context, String stage, boolean reachable,
                              long httpStatus, long rttMs, String detail) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + " stage=" + stage
                + " reachable=" + reachable
                + " http=" + httpStatus
                + " rtt_ms=" + rttMs
                + " port=" + CoreRuntimeState.httpsPort
                + " serverUrl=" + (CoreRuntimeState.serverUrl == null
                        ? "none" : SensitiveDataRedactor.safeUrl(CoreRuntimeState.serverUrl))
                + " lastError=" + (CoreRuntimeState.lastError == null
                        ? "none" : SensitiveDataRedactor.redactLine(CoreRuntimeState.lastError))
                + " detail=" + sanitizeDetail(detail);
        Log.i(TAG, line);

        File dir = downloadsDir(context);
        if (dir == null) {
            return;
        }
        appendLine(new File(dir, LOG_NAME), line);
        writeJson(new File(dir, JSON_NAME), line);
    }

    /**
     * Reports what the platform cookie jar holds for the core origin.
     *
     * <p>The session cookie is {@code HttpOnly}, so {@code document.cookie} is always
     * empty in the page even when authentication is working. Native code can read the
     * jar, which makes this the decisive check: if the jar has no cookie after the
     * tokenized URL was loaded, the WebView never completed the token exchange.</p>
     */
    public static void recordCookieJar(Context context, String stage, int port) {
        String origin = "http://127.0.0.1:" + port + "/";
        String report;
        try {
            CookieManager manager = CookieManager.getInstance();
            String cookie = manager.getCookie(origin);
            int count = 0;
            boolean hasSession = false;
            if (cookie != null && !cookie.isEmpty()) {
                for (String part : cookie.split(";")) {
                    String name = part.trim();
                    if (name.isEmpty()) {
                        continue;
                    }
                    count++;
                    int eq = name.indexOf('=');
                    String key = eq >= 0 ? name.substring(0, eq) : name;
                    if (key.startsWith("dsh_") || key.contains("session") || key.contains("auth")) {
                        hasSession = true;
                    }
                }
            }
            report = "origin=" + origin + " cookieCount=" + count + " hasSessionCookie=" + hasSession;
        } catch (Throwable t) {
            report = "origin=" + origin + " cookieJarError=" + t.getClass().getSimpleName();
        }
        record(context, stage + ":cookiejar", true, -1, -1L, report);
    }

    /**
     * Keeps diagnostic key/value pairs intact while removing anything that looks like
     * a raw secret. URLs are collapsed to scheme://host:port; no key=value pair other
     * than a URL is rewritten.
     */
    private static String sanitizeDetail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return "";
        }
        String safe = SensitiveDataRedactor.safeUrlInText(detail);
        return safe.length() > 900 ? safe.substring(0, 900) : safe;
    }

    private static File downloadsDir(Context context) {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                return dir;
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (dir.isDirectory() || dir.mkdirs()) {
                return dir;
            }
        } catch (Throwable ignored) {
        }
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                return dir;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void appendLine(File file, String line) {
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
            w.write(line);
            w.write("\n");
        } catch (Throwable t) {
            Log.w(TAG, "cannot append probe log: " + t.getMessage());
        }
    }

    private static void writeJson(File file, String line) {
        String json = "{\"last\":" + quote(line) + "}";
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
            w.write(json);
            w.write("\n");
        } catch (Throwable t) {
            Log.w(TAG, "cannot write probe json: " + t.getMessage());
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}
