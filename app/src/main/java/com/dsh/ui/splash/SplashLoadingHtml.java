package com.dsh.ui.splash;

import android.webkit.WebView;

/**
 * The authentic DeepSeek loading dots (three 3px dots, 2px gap, vertical
 * bounce, 1.6s ease-in-out, delays 0/120/240ms) plus one line of staged boot
 * status text so a 30-45s cold start never looks like a black screen.
 *
 * <p>Colors are pre-resolved for the current mode; no prefers-color-scheme
 * media query (WebView would resolve that from the system scheme and override
 * an in-app choice). The page paints the background on {@code html}, {@code
 * body} and a fixed full-bleed layer so no frame is ever rendered against the
 * WebView's own (transparent/black) surface before the document renders.</p>
 */
public final class SplashLoadingHtml {

    private SplashLoadingHtml() {
    }

    /** No status text yet (initial render before the first BootMetrics sample). */
    public static final String STATUS_NONE = "";

    public static String getLoadingHtml(String foregroundColor, String backgroundColor) {
        return getLoadingHtml(foregroundColor, backgroundColor, STATUS_NONE);
    }

    public static String getLoadingHtml(String foregroundColor, String backgroundColor,
                                        String status) {
        String safeStatus = escape(status == null ? STATUS_NONE : status);
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<meta name=\"color-scheme\" content=\"" + colorScheme(backgroundColor) + "\">\n"
                + "<style>\n"
                + "  :root {\n"
                + "    --bg: " + backgroundColor + ";\n"
                + "    --fg: " + foregroundColor + ";\n"
                + "  }\n"
                + "  html {\n"
                + "    margin: 0; padding: 0; height: 100%; width: 100%;\n"
                + "    background-color: var(--bg);\n"
                + "  }\n"
                + "  body {\n"
                + "    margin: 0;\n"
                + "    padding: 0;\n"
                + "    height: 100%;\n"
                + "    width: 100%;\n"
                + "    background-color: var(--bg);\n"
                + "    color: var(--fg);\n"
                + "    display: flex;\n"
                + "    flex-direction: column;\n"
                + "    align-items: center;\n"
                + "    justify-content: center;\n"
                + "    overflow: hidden;\n"
                + "    -webkit-user-select: none;\n"
                + "  }\n"
                + "  /* Fixed backdrop: covers the first composited frame even before\n"
                + "     layout settles, so the splash is never a pure black surface. */\n"
                + "  #backdrop {\n"
                + "    position: fixed;\n"
                + "    top: 0; right: 0; bottom: 0; left: 0;\n"
                + "    background-color: var(--bg);\n"
                + "    z-index: -1;\n"
                + "  }\n"
                + "  .splash-box {\n"
                + "    display: flex;\n"
                + "    flex-direction: column;\n"
                + "    align-items: center;\n"
                + "    justify-content: center;\n"
                + "    gap: 14px;\n"
                + "    max-width: 82vw;\n"
                + "    text-align: center;\n"
                + "  }\n"
                + "  @keyframes dot-bounce {\n"
                + "    0%, 100% { transform: translateY(0); }\n"
                + "    15% { transform: translateY(-2px); }\n"
                + "    30% { transform: translateY(0); }\n"
                + "  }\n"
                + "  .animate-dot-bounce {\n"
                + "    display: inline-block;\n"
                + "    width: 3px;\n"
                + "    height: 3px;\n"
                + "    border-radius: 50%;\n"
                + "    background-color: currentColor;\n"
                + "    animation: dot-bounce 1.6s ease-in-out infinite;\n"
                + "  }\n"
                + "  #status {\n"
                + "    font-family: system-ui, -apple-system, sans-serif;\n"
                + "    font-size: 12px;\n"
                + "    line-height: 1.4;\n"
                + "    letter-spacing: 0.01em;\n"
                + "    opacity: 0.62;\n"
                + "    min-height: 17px;\n"
                + "    transition: opacity 180ms ease;\n"
                + "  }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div id=\"backdrop\"></div>\n"
                + "  <div class=\"splash-box\">\n"
                + "    <span id=\"dots\" style=\"display:inline-flex;align-items:center;gap:2px;height:16px;padding:0 4px;opacity:0.5;\" aria-label=\"Loading\">\n"
                + "      <span class=\"animate-dot-bounce\" style=\"animation-delay:0ms;\"></span>\n"
                + "      <span class=\"animate-dot-bounce\" style=\"animation-delay:120ms;\"></span>\n"
                + "      <span class=\"animate-dot-bounce\" style=\"animation-delay:240ms;\"></span>\n"
                + "    </span>\n"
                + "    <div id=\"status\" role=\"status\" aria-live=\"polite\">" + safeStatus + "</div>\n"
                + "  </div>\n"
                + "</body>\n"
                + "</html>\n";
    }

    public static void load(WebView splashView, String foregroundColor, String backgroundColor) {
        load(splashView, foregroundColor, backgroundColor, STATUS_NONE);
    }

    public static void load(WebView splashView, String foregroundColor, String backgroundColor,
                            String status) {
        if (splashView == null) {
            return;
        }
        // Paint the native surface first: the base background must be on screen
        // from the very first frame, before any HTML is parsed.
        try {
            splashView.setBackgroundColor(parseColor(backgroundColor));
        } catch (Throwable ignored) {
        }
        splashView.loadDataWithBaseURL(null, getLoadingHtml(foregroundColor, backgroundColor, status),
                "text/html", "utf-8", null);
    }

    /** Fallback decode when the theme hands us a non-#RRGGBB value. */
    static int parseColor(String color) {
        try {
            return android.graphics.Color.parseColor(color);
        } catch (Throwable ignored) {
            return 0xFF101010;
        }
    }

    private static String colorScheme(String backgroundColor) {
        int color = parseColor(backgroundColor);
        double luminance = (0.299 * android.graphics.Color.red(color)
                + 0.587 * android.graphics.Color.green(color)
                + 0.114 * android.graphics.Color.blue(color)) / 255.0;
        return luminance > 0.5 ? "light" : "dark";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
