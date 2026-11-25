package com.dsh.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.dsh.theme.ThemeManager;
import com.dsh.util.ViewParams;

/**
 * Owns the native splash container: displays the lightweight NativeSplashDotsView
 * during startup (avoiding a second WebView allocation on the happy path) and lazily
 * allocates an overlay WebView only when transient/fatal error details must be shown.
 */
public final class SplashController {

    public static final int MODE_LOADING = 0;
    public static final int MODE_TRANSIENT = 1;
    public static final int MODE_ERROR = 2;

    private final Context context;
    private final ThemeManager theme;
    private final FrameLayout root;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final FrameLayout container;
    private final NativeSplashDotsView nativeLoader;
    private WebView overlayWebView;
    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean fatalShown = false;
    private int mode = MODE_LOADING;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.context = context;
        this.theme = theme;
        this.root = root;
        this.container = new FrameLayout(context);
        this.container.setBackgroundColor(theme.getBackgroundColor());
        this.container.setClickable(true);
        this.container.setFocusable(true);

        this.nativeLoader = new NativeSplashDotsView(context);
        this.nativeLoader.setDark(theme.isDark());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        this.container.addView(this.nativeLoader, lp);
    }

    public View view() {
        return container;
    }

    public int mode() {
        return mode;
    }

    public boolean isFatalShown() {
        return fatalShown;
    }

    public void setRetryListener(SplashRetryListener listener) {
        this.retryListener = listener;
        if (overlayWebView != null) {
            SplashRetryBridge.bind(overlayWebView, context, listener);
        }
    }

    private WebView ensureOverlayWebView() {
        if (overlayWebView == null) {
            WebView wv = new WebView(context);
            wv.setBackgroundColor(Color.TRANSPARENT);
            android.webkit.WebSettings settings = wv.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            if (retryListener != null) {
                SplashRetryBridge.bind(wv, context, retryListener);
            }
            container.addView(wv, ViewParams.full());
            overlayWebView = wv;
        } else if (overlayWebView.getParent() == null) {
            container.addView(overlayWebView, ViewParams.full());
        }
        return overlayWebView;
    }

    private void destroyOverlayWebView() {
        WebView wv = overlayWebView;
        if (wv == null) {
            return;
        }
        overlayWebView = null;
        try {
            wv.setVisibility(View.GONE);
            wv.stopLoading();
            wv.loadUrl("about:blank");
            wv.onPause();
        } catch (Throwable ignored) {
        }
        try {
            ViewParent parent = wv.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(wv);
            }
        } catch (Throwable ignored) {
        }
        try {
            wv.removeAllViews();
            wv.destroy();
        } catch (Throwable ignored) {
        }
    }

    /** First render of the loading splash (before any show* call). */
    public void loadInitial() {
        mode = MODE_LOADING;
        container.setBackgroundColor(theme.getBackgroundColor());
        nativeLoader.setDark(theme.isDark());
        nativeLoader.setVisibility(View.VISIBLE);
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        setVisible();
        if (mode != MODE_LOADING) {
            mode = MODE_LOADING;
            destroyOverlayWebView();
            nativeLoader.setDark(theme.isDark());
            nativeLoader.setVisibility(View.VISIBLE);
        }
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (fatalShown) {
            return;
        }
        setVisible();
        if (mode != MODE_TRANSIENT) {
            mode = MODE_TRANSIENT;
            nativeLoader.setVisibility(View.GONE);
            WebView wv = ensureOverlayWebView();
            SplashOverlayHtml.show(wv, "Restarting DeepSeek", message,
                    foreground(), background(), false);
        }
    }

    /** Fail-closed startup failure: readable message with a Retry action. */
    public void showFatalError(String message) {
        if (fatalShown) {
            return;
        }
        fatalShown = true;
        mode = MODE_ERROR;
        setVisible();
        nativeLoader.setVisibility(View.GONE);
        WebView wv = ensureOverlayWebView();
        SplashRetryBridge.bind(wv, context, retryListener);
        SplashOverlayHtml.show(wv, "DeepSeek failed to start", message,
                foreground(), background(), retryListener != null);
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
    }

    public void setVisible() {
        dismissed = false;
        if (container.getParent() == null && root != null) {
            root.addView(container, ViewParams.full());
        }
        container.animate().cancel();
        container.setAlpha(1f);
        container.setBackgroundColor(theme.getBackgroundColor());
        if (container.getVisibility() != View.VISIBLE) {
            container.setVisibility(View.VISIBLE);
        }
        if (root != null) {
            root.bringChildToFront(container);
        }
    }

    /** Idempotent; fades out then tears down splash animation and any overlay WebView. */
    public void dismiss() {
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (dismissed) {
                    return;
                }
                dismissed = true;
                container.animate().cancel();
                container.animate()
                        .alpha(0f)
                        .setDuration(240)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (!dismissed) {
                                    return;
                                }
                                nativeLoader.stopAnimation();
                                container.setVisibility(View.GONE);
                                destroyOverlayWebView();
                            }
                        })
                        .start();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
        }
    }

    /** Immediately destroys any active overlay WebView and stops animations. */
    public void destroy() {
        dismissed = true;
        container.animate().cancel();
        nativeLoader.stopAnimation();
        container.setVisibility(View.GONE);
        destroyOverlayWebView();
    }

    /** Re-render the splash after a theme/configuration change. */
    public void refresh() {
        if (dismissed) {
            return;
        }
        container.setBackgroundColor(theme.getBackgroundColor());
        nativeLoader.setDark(theme.isDark());
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        SplashExitInstaller.install(activity, container);
    }

    private String foreground() {
        return theme.getForegroundHex();
    }

    private String background() {
        return theme.getBackgroundHex();
    }
}
