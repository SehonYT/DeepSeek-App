package com.dsh.util;

import android.net.Uri;

/** Exact origin for the embedded core UI; a loopback hostname alone is not trusted. */
public final class TrustedCoreOrigin {

    private TrustedCoreOrigin() {
    }

    public static boolean matches(Uri uri, int expectedPort) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        boolean validScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        return validScheme
                && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()))
                && uri.getUserInfo() == null
                && (expectedPort <= 0 || uri.getPort() == expectedPort || uri.getPort() == -1);
    }
}
