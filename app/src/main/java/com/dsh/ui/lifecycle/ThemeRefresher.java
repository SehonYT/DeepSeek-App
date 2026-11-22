package com.dsh.ui.lifecycle;

import android.app.Activity;
import android.view.View;

import com.dsh.theme.ThemeManager;
import com.dsh.ui.splash.SplashController;
import com.dsh.ui.webview.WebViewHost;

/** Re-applies the resolved theme to the activity, splash and host WebView. */
public final class ThemeRefresher {

    private ThemeRefresher() {
    }

    public static void refresh(Activity activity, ThemeManager theme, View rootLayout,
                               SplashController splash, WebViewHost host) {
        if (theme != null) {
            theme.updateContext(activity);
            theme.applyToActivity(activity);
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(theme != null ? theme.getBackgroundColor() : 0xFF101010);
        }
        if (splash != null) {
            splash.refresh();
        }
        if (host != null && theme != null) {
            theme.applyToWebView(host.getWebView());
        }
    }
}
