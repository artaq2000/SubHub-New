package com.artaq.subhub;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Temporary provider probe only.
 *
 * It captures a fresh HLS URL for the current session and then destroys itself.
 * It owns no subtitle UI, no player UI and no R2 state.
 */
public final class DirectStreamCapture {
    public interface Listener {
        void captured(String url, Map<String,String> headers);
        void closed();
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final TextView status;
    private WebView probe;
    private String candidate;
    private Map<String,String> candidateHeaders = new HashMap<>();
    private boolean closed;
    private boolean delivered;

    public DirectStreamCapture(Activity activity, FrameLayout parent, String source, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        parent.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(activity);
        status.setText("جارٍ جلب الفيديو… إذا ظهر زر تشغيل المصدر اضغطه مرة واحدة.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xee0b1726);
        status.setPadding(dp(12), 0, dp(12), 0);
        FrameLayout.LayoutParams st = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP);
        root.addView(status, st);

        Button close = new Button(activity);
        close.setText("إغلاق");
        close.setTextColor(Color.WHITE);
        close.setBackgroundColor(0xcc263446);
        close.setOnClickListener(v -> close(true));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(92), dp(48), Gravity.TOP | Gravity.START);
        cp.topMargin = dp(5);
        cp.leftMargin = dp(6);
        root.addView(close, cp);

        probe = new WebView(activity);
        WebSettings s = probe.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(probe, true);

        probe.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(
                    WebView view, boolean isDialog, boolean isUserGesture,
                    android.os.Message resultMsg) {
                return false;
            }
        });

        probe.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String h = u.getHost();
                if (!"https".equalsIgnoreCase(u.getScheme())) return true;
                if (!r.isForMainFrame()) return false;
                return !("vidsrc.to".equalsIgnoreCase(h)
                        || (h != null && h.toLowerCase(Locale.ROOT).endsWith(".vidsrc.to")));
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String path = u.getPath();
                if ("https".equalsIgnoreCase(u.getScheme())
                        && path != null
                        && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                    String url = u.toString();
                    Map<String,String> headers = new HashMap<>(r.getRequestHeaders());
                    handler.post(() -> onCandidate(url, headers));
                }
                return null;
            }
        });

        FrameLayout.LayoutParams wp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        wp.topMargin = dp(58);
        root.addView(probe, 0, wp);
        probe.loadUrl(source);

        handler.postDelayed(() -> {
            if (!closed && !delivered) {
                status.setText("لم يظهر رابط بث بعد. اضغط تشغيل المصدر مرة واحدة.");
            }
        }, 12000);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private void onCandidate(String url, Map<String,String> headers) {
        if (closed || delivered || url == null || url.isEmpty()) return;
        if (candidate == null
                || url.toLowerCase(Locale.ROOT).contains("master")
                || url.toLowerCase(Locale.ROOT).contains("playlist")) {
            candidate = url;
            candidateHeaders = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }
        handler.removeCallbacks(deliver);
        handler.postDelayed(deliver, 850);
    }

    private final Runnable deliver = new Runnable() {
        @Override public void run() {
            if (closed || delivered || candidate == null) return;
            delivered = true;
            String out = candidate;
            Map<String,String> headers = new HashMap<>(candidateHeaders);
            close(false);
            listener.captured(out, headers);
        }
    };

    public void close(boolean notify) {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (probe != null) {
                probe.stopLoading();
                probe.loadUrl("about:blank");
                root.removeView(probe);
                probe.destroy();
                probe = null;
            }
        } catch (Exception ignored) {}
        try {
            ViewGroup p = (ViewGroup) root.getParent();
            if (p != null) p.removeView(root);
        } catch (Exception ignored) {}
        if (notify) listener.closed();
    }
}
