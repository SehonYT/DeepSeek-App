package com.dsh.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.dsh.util.MimeTypes;
import com.dsh.util.TrustedCoreOrigin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Serves extracted static web assets directly from app storage on loopback
 * requests so the WebView does not block on the single-threaded Node process
 * for megabytes of JS/CSS bundles. Dynamic routes, index.html, and API/WS
 * endpoints are passed through to the core.
 */
public final class LocalAssetServer {

    private static final String TAG = "WebViewHost";
    private static final Pattern HASHED_ASSET_PATTERN =
            Pattern.compile("^/assets/.+-[a-zA-Z0-9_-]{6,}\\.[a-z0-9]+$");

    public WebResourceResponse intercept(Context context, Uri uri, String method, int enginePort) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!TrustedCoreOrigin.matches(uri, enginePort)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path) || path.endsWith("/")) {
            return null;
        }
        // Never intercept index.html, auth/session routes, RPCs, or streaming endpoints.
        if ("/index.html".equals(path) || "/api".equals(path) || path.startsWith("/api/")
                || path.startsWith("/auth/") || "/events".equals(path)
                || path.startsWith("/exa.") || path.startsWith("/jetski.")
                || path.contains("Service") || path.contains("connect-websocket")) {
            return null;
        }
        String relPath = path.startsWith("/") ? path.substring(1) : path;
        File filesDir = context.getFilesDir();
        File target = findStaticFile(new File(filesDir, "web"), relPath);
        if (target == null) {
            target = findStaticFile(new File(filesDir, "runtime/dsh/apps/web/dist"), relPath);
        }
        if (target != null) {
            try {
                String mime = MimeTypes.getMimeType(target.getName());
                String encoding = MimeTypes.isTextMime(mime) ? "UTF-8" : null;
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                // Only cache content-hashed Vite bundles immutably; keep unhashed
                // resources strictly revalidating so updates take effect cleanly.
                headers.put("Cache-Control", HASHED_ASSET_PATTERN.matcher(path).matches()
                        ? "public, max-age=31536000, immutable"
                        : "no-cache, must-revalidate");
                headers.put("Content-Length", String.valueOf(target.length()));
                InputStream bis = new BufferedInputStream(new FileInputStream(target), 64 * 1024);
                return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
            } catch (Exception e) {
                Log.w(TAG, "intercept failed for " + path, e);
            }
        }
        return null;
    }

    private static File findStaticFile(File root, String relPath) {
        if (!root.isDirectory()) {
            return null;
        }
        File candidate = new File(root, relPath);
        if (!candidate.isFile() || !candidate.canRead()) {
            return null;
        }
        try {
            String canonicalRoot = root.getCanonicalPath();
            String canonicalTarget = candidate.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalRoot + File.separator)) {
                return null;
            }
            return candidate;
        } catch (Exception e) {
            return null;
        }
    }
}
