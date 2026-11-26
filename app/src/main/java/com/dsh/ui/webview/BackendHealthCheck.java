package com.dsh.ui.webview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;

import com.dsh.core.CoreRuntimeState;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes the loopback core from the app process and records the outcome for offline
 * analysis.
 *
 * <p>The three probes together isolate where authentication breaks:</p>
 * <ol>
 *   <li>{@code root} - does the launcher's tokenized URL still exchange for a cookie
 *       (303 + Set-Cookie), i.e. is the token valid?</li>
 *   <li>{@code aj} - is that cookie accepted by {@code /api} (200) or rejected (401)?</li>
 *   <li>{@code api} - the same request without a cookie, as a control (expect 401).</li>
 * </ol>
 * <p>If {@code aj} succeeds and the page still reports an unauthenticated API, the
 * defect is in how the WebView stores or sends the cookie. If {@code aj} also fails,
 * the cookie itself was never minted or is bound to a different authority.</p>
 */
public final class BackendHealthCheck {

    private static final String TAG = "BackendHealth";
    private static final AtomicBoolean sScheduled = new AtomicBoolean(false);
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BackendHealthCheck");
        t.setDaemon(true);
        return t;
    });

    private BackendHealthCheck() {
    }

    /** Runs the probe battery shortly after the UI is shown, once per process boot. */
    public static void probeAfterUiLoaded(Context context) {
        if (context == null || !sScheduled.compareAndSet(false, true)) {
            return;
        }
        final Context app = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        new Handler(Looper.getMainLooper()).postDelayed(() -> POOL.execute(() -> {
            try {
                probeOnce(app, "t+3s");
            } catch (Throwable t) {
                Log.w(TAG, "probe failed: " + t.getMessage());
            }
        }), 3000L);
    }

    /** Probes root, cookie-authenticated API, and anonymous API. */
    public static void probeOnce(Context context, String stage) {
        int port = CoreRuntimeState.httpsPort;
        probeUrl(context, stage + ":root", CoreRuntimeState.serverUrl, null);

        String jarCookie = cookieJar(port);
        NetProbe.record(context, stage + ":cookiejar", jarCookie != null, -1, -1L,
                "origin=http://127.0.0.1:" + port + " cookieCount=" + countCookies(jarCookie)
                        + " raw=" + describeCookieNames(jarCookie));

        // Decisive: replay the WebView's own cookie to /api from native code.
        probeUrl(context, stage + ":apiWithJarCookie",
                "http://127.0.0.1:" + port + "/api", jarCookie);
        // Control: same request without the cookie (documents the expected 401).
        probeUrl(context, stage + ":apiNoCookie",
                "http://127.0.0.1:" + port + "/api", null);

        // The UI's "Reconnecting" state comes from the Gateway WebSocket. Send a
        // real RFC6455 handshake with the jar cookie, and if upgraded (101), open
        // the $events stream and wait for the {"type":"ready"} frame so broken
        // server WebSocket framing (where 101 succeeds but sends drop silently)
        // cannot look healthy.
        probeWebSocket(context, stage + ":mux", port, jarCookie);
    }

    /**
     * Performs a raw WebSocket upgrade request against the Gateway stream mux,
     * opens the {@code $events} stream on 101, and checks for a {@code ready}
     * frame. Uses a plain socket so no WebSocket client library or TLS setup is
     * involved.
     */
    private static void probeWebSocket(Context context, String stage, int port, String cookie) {
        java.net.Socket socket = null;
        long started = SystemClock.elapsedRealtime();
        int code = -1;
        String statusLine = "";
        boolean ready = false;
        try {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 4000);
            socket.setSoTimeout(4000);
            StringBuilder req = new StringBuilder();
            req.append("GET /api/remote.mux HTTP/1.1\r\n");
            req.append("Host: 127.0.0.1:").append(port).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
            req.append("Origin: http://127.0.0.1:").append(port).append("\r\n");
            if (cookie != null) {
                req.append("Cookie: ").append(cookie).append("\r\n");
            }
            req.append("\r\n");
            java.io.OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(
                    socket.getInputStream(), 4096);
            String head = readHttpHeaders(in);
            int eol = head.indexOf("\r\n");
            statusLine = eol > 0 ? head.substring(0, eol) : head.trim();
            try {
                String[] parts = statusLine.split(" ");
                if (parts.length > 1) {
                    code = Integer.parseInt(parts[1]);
                }
            } catch (Throwable ignored) {
            }
            if (code == 101) {
                ready = awaitEventsReady(in, out);
                try {
                    writeMaskedFrame(out, 0x8, new byte[0]);
                } catch (Throwable ignored) {
                }
            }
            long rtt = SystemClock.elapsedRealtime() - started;
            NetProbe.record(context, stage, code == 101 && ready, code, rtt,
                    "sentCookie=" + (cookie != null) + " ready=" + ready
                            + " status=\"" + statusLine + "\"");
        } catch (Throwable t) {
            NetProbe.record(context, stage, false, code,
                    SystemClock.elapsedRealtime() - started,
                    "sentCookie=" + (cookie != null) + " ready=" + ready
                            + " error=" + t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readHttpHeaders(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
        int match = 0;
        int b;
        while (headerBytes.size() < 8192 && (b = in.read()) != -1) {
            headerBytes.write(b);
            if (b == '\r') {
                match = (match == 2) ? 3 : 1;
            } else if (b == '\n') {
                if (match == 1) {
                    match = 2;
                } else if (match == 3) {
                    break;
                } else {
                    match = 0;
                }
            } else {
                match = 0;
            }
        }
        return headerBytes.toString("UTF-8");
    }

    private static boolean awaitEventsReady(java.io.InputStream in, java.io.OutputStream out)
            throws java.io.IOException {
        byte[] openPayload = ("{\"type\":\"open\",\"streamId\":\"netprobe\","
                + "\"endpoint\":\"$events\",\"payload\":{\"args\":{}}}").getBytes("UTF-8");
        writeMaskedFrame(out, 0x1, openPayload);
        StringBuilder textMsg = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            int b0 = in.read();
            if (b0 < 0) {
                break;
            }
            int b1 = in.read();
            if (b1 < 0) {
                break;
            }
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                byte[] ext = new byte[2];
                readFully(in, ext);
                len = ((ext[0] & 0xff) << 8) | (ext[1] & 0xff);
            } else if (len == 127) {
                byte[] ext = new byte[8];
                readFully(in, ext);
                len = 0;
                for (int j = 0; j < 8; j++) {
                    len = (len << 8) | (ext[j] & 0xff);
                }
            }
            if (len < 0 || len > 65536) {
                throw new java.io.IOException("Unexpected WebSocket frame length: " + len);
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (mask != null) {
                for (int j = 0; j < payload.length; j++) {
                    payload[j] = (byte) (payload[j] ^ mask[j & 3]);
                }
            }
            if (opcode == 0x8) {
                break;
            }
            if (opcode == 0x9) {
                writeMaskedFrame(out, 0xA, payload);
                continue;
            }
            if (opcode == 0xA) {
                continue;
            }
            if (opcode == 0x1 || opcode == 0x0) {
                textMsg.append(new String(payload, "UTF-8"));
                if (fin) {
                    String msg = textMsg.toString();
                    textMsg.setLength(0);
                    if (msg.contains("\"type\":\"ready\"")
                            || msg.contains("\"type\": \"ready\"")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void writeMaskedFrame(java.io.OutputStream out, int opcode, byte[] payload)
            throws java.io.IOException {
        out.write(0x80 | (opcode & 0x0f));
        if (payload.length < 126) {
            out.write(0x80 | payload.length);
        } else if (payload.length <= 65535) {
            out.write(0x80 | 126);
            out.write((payload.length >> 8) & 0xff);
            out.write(payload.length & 0xff);
        } else {
            throw new java.io.IOException("Frame too large: " + payload.length);
        }
        byte[] mask = new byte[4];
        new java.util.Random().nextBytes(mask);
        out.write(mask);
        byte[] masked = new byte[payload.length];
        for (int i = 0; i < payload.length; i++) {
            masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        out.write(masked);
        out.flush();
    }

    private static void readFully(java.io.InputStream in, byte[] dst) throws java.io.IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw new java.io.EOFException("Unexpected EOF reading WebSocket frame");
            }
            off += n;
        }
    }

    private static String cookieJar(int port) {
        try {
            String cookie = CookieManager.getInstance().getCookie("http://127.0.0.1:" + port + "/");
            return cookie == null || cookie.isEmpty() ? null : cookie;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countCookies(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String part : cookie.split(";")) {
            if (!part.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String describeCookieNames(String cookie) {
        if (cookie == null || cookie.isEmpty()) {
            return "none";
        }
        StringBuilder names = new StringBuilder();
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = eq >= 0 ? trimmed.substring(0, eq) : trimmed;
            if (names.length() > 0) {
                names.append(',');
            }
            // Names only: the values are session credentials.
            names.append(name).append('(').append(eq >= 0 ? trimmed.length() - eq - 1 : 0).append("B)");
        }
        return names.length() == 0 ? "none" : names.toString();
    }

    private static void probeUrl(Context context, String stage, String url, String cookie) {
        if (url == null || url.isEmpty()) {
            NetProbe.record(context, stage, false, -1, -1L, "no url");
            return;
        }
        HttpURLConnection conn = null;
        long started = SystemClock.elapsedRealtime();
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("GET");
            if (cookie != null) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            long rtt = SystemClock.elapsedRealtime() - started;
            String detail = "";
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) {
                    byte[] buf = new byte[256];
                    int n = in.read(buf);
                    if (n > 0) {
                        detail = new String(buf, 0, n, "UTF-8").replaceAll("\\s+", " ").trim();
                    }
                }
            } catch (Throwable ignored) {
            }
            NetProbe.record(context, stage, code > 0 && code < 400, code, rtt,
                    "sentCookie=" + (cookie != null)
                            + " location=" + conn.getHeaderField("location")
                            + " body=" + detail);
        } catch (Throwable t) {
            long rtt = SystemClock.elapsedRealtime() - started;
            NetProbe.record(context, stage, false, -1, rtt,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
