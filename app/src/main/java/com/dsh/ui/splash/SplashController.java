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
import android.widget.TextView;

import com.dsh.theme.ThemeColors;
import com.dsh.theme.ThemeManager;
import com.dsh.util.ViewParams;

/**
 * Owns the native splash container: displays the lightweight NativeSplashDotsView
 * (brand logo) plus one line of staged boot status text during startup, and
 * lazily allocates an overlay WebView only when transient/fatal error details
 * must be shown.
 *
 * <p>The container background is set from the resolved theme in the constructor
 * and re-applied on every show/refresh, so the splash surface is never pure
 * black regardless of what the WebView is doing.</p>
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
    private final TextView statusView;
    private WebView overlayWebView;
    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean fatalShown = false;
    private int mode = MODE_LOADING;
    private String statusText = null;
    private String transientMessage = "";
    /** A status was published but suppressed until its clock is armed. */
    private boolean statusPending = false;
    /** True once the activity has resumed; before that the OS starting window covers us. */
    private boolean statusClockArmed = false;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.context = context;
        this.theme = theme;
        this.root = root;
        this.container = new FrameLayout(context);
        this.container.setBackgroundColor(backgroundInt());
        this.container.setClickable(true);
        this.container.setFocusable(true);

        this.nativeLoader = new NativeSplashDotsView(context);
        this.nativeLoader.setDark(theme != null && theme.isDark());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        this.container.addView(this.nativeLoader, lp);

        this.statusView = new TextView(context);
        this.statusView.setTextSize(12f);
        this.statusView.setGravity(Gravity.CENTER);
        this.statusView.setAlpha(0.62f);
        this.statusView.setVisibility(View.GONE);
        // Wrap by available width (not a fixed px cap) so the line fits narrow
        // 480x854 panels without clipping.
        this.statusView.setSingleLine(false);
        this.statusView.setPadding(
                (int) (24 * context.getResources().getDisplayMetrics().density), 0,
                (int) (24 * context.getResources().getDisplayMetrics().density), 0);
        this.applyStatusColors();
        // Wrap-content + centrally weighted layout: the dots are centered and the
        // status line sits centred below them, following the native loader's
        // actual measured height instead of a magic offset.
        FrameLayout.LayoutParams statusLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        statusLp.topMargin = (int) (96 * context.getResources().getDisplayMetrics().density);
        this.container.addView(this.statusView, statusLp);
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

    /** True once the error overlay owns the splash (no more boot text updates). */
    public boolean isErrorMode() {
        return mode == MODE_ERROR || mode == MODE_TRANSIENT;
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
            // The overlay HTML paints its own background; paint the native
            // surface too so the first composited frame is never black.
            wv.setBackgroundColor(backgroundInt());
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
        applyBackground();
        nativeLoader.setDark(theme != null && theme.isDark());
        nativeLoader.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.GONE);
    }

    /**
     * Arms the "status line becomes visible" clock. Called when the activity
     * resumes: the splash is already on screen from the OS starting window, so a
     * boot that is still running shortly after resume is slow enough to deserve
     * the status text, while a warm start keeps the clean three-dot splash.
     */
    public void startLoadingClock() {
        statusClockArmed = true;
        if (statusPending && statusText != null) {
            statusPending = false;
            revealStatus();
        }
    }

    private void revealStatus() {
        if (statusText == null || mode != MODE_LOADING || dismissed) {
            return;
        }
        applyStatusColors();
        statusView.setText(statusText);
        if (statusView.getVisibility() != View.VISIBLE) {
            statusView.setVisibility(View.VISIBLE);
            statusView.setAlpha(0f);
            statusView.animate().alpha(0.62f).setDuration(200).start();
        }
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        setVisible();
        if (mode != MODE_LOADING) {
            mode = MODE_LOADING;
            transientMessage = "";
            destroyOverlayWebView();
            nativeLoader.setDark(theme != null && theme.isDark());
            nativeLoader.setVisibility(View.VISIBLE);
            statusView.setVisibility(View.GONE);
            statusPending = false;
            // Re-reveal the last status text: after a reload the same phase text
            // is published again and would otherwise be suppressed as unchanged.
            if (statusClockArmed) {
                revealStatus();
            }
        }
    }

    /**
     * Advances the staged boot status line. Only the boot phases call this; the
     * text is ignored while an error overlay owns the splash, and stays pending
     * until {@link #startLoadingClock()} so a warm start that finishes before the
     * activity resumes keeps the clean three-dot brand splash.
     */
    public void showStatus(String text) {
        if (text == null) {
            return;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.equals(statusText)) {
            return;
        }
        statusText = trimmed;
        if (mode != MODE_LOADING || dismissed) {
            return;
        }
        if (!statusClockArmed) {
            statusPending = true;
            return;
        }
        statusPending = false;
        revealStatus();
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (fatalShown) {
            return;
        }
        setVisible();
        boolean changed = !message.equals(transientMessage);
        if (mode != MODE_TRANSIENT || changed) {
            mode = MODE_TRANSIENT;
            transientMessage = message;
            nativeLoader.setVisibility(View.GONE);
            statusView.setVisibility(View.GONE);
            WebView wv = ensureOverlayWebView();
            // Re-rendered only when the attempt/backoff text actually changes, so
            // the restart progress ("retrying in 2s", then attempt 2/3, ...) stays
            // visible without reloading the overlay on every poll.
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
        statusView.setVisibility(View.GONE);
        WebView wv = ensureOverlayWebView();
        SplashRetryBridge.bind(wv, context, retryListener);
        SplashOverlayHtml.show(wv, "DeepSeek failed to start", message,
                foreground(), background(), retryListener != null);
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
        transientMessage = "";
    }

    public void setVisible() {
        dismissed = false;
        if (container.getParent() == null && root != null) {
            root.addView(container, ViewParams.full());
        }
        container.animate().cancel();
        container.setAlpha(1f);
        applyBackground();
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
        applyBackground();
        nativeLoader.setDark(theme != null && theme.isDark());
        applyStatusColors();
        if (overlayWebView != null) {
            overlayWebView.setBackgroundColor(backgroundInt());
        }
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        SplashExitInstaller.install(activity, container);
    }

    /**
     * The splash background color. Never pure black: when the theme is missing
     * the branded dark surface (#101010) is used, so a mis-resolved theme cannot
     * turn the splash into a black screen.
     */
    private int backgroundInt() {
        return theme != null ? theme.getBackgroundColor() : ThemeColors.COLOR_DARK;
    }

    /** Opaque theme background on the container and on any overlay WebView. */
    private void applyBackground() {
        int color = backgroundInt();
        container.setBackgroundColor(color);
        if (overlayWebView != null) {
            overlayWebView.setBackgroundColor(color);
        }
    }

    private void applyStatusColors() {
        int color = theme != null && theme.isDark() ? Color.WHITE : 0xFF101010;
        statusView.setTextColor(color);
    }

    private String foreground() {
        return theme != null ? theme.getForegroundHex() : "#ffffff";
    }

    private String background() {
        return theme != null ? theme.getBackgroundHex() : "#101010";
    }
}
