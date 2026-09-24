package com.artaq.subhub;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://subhub-at7.pages.dev/";
    private static final String HOME_HOST = "subhub-at7.pages.dev";

    /*
     * v322 bridge2:
     * The real SubHub overlay already survives the player's fullscreen custom view
     * on the tested OnlyFlix/CDNM player. Keep the Android subtitle renderer disabled
     * so we never draw the same SubHub cue twice.
     */
    private static final boolean USE_NATIVE_FULLSCREEN_SUBTITLE = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object clockLock = new Object();

    private FrameLayout root;
    private WebView webView;
    private FrameLayout fullScreenLayer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private SubtitleTextView nativeSubtitle;

    private String clockScript = "";
    private String siteBridgeScript = "";
    private JSONObject lastSubtitleState = null;

    private String pendingClockRaw = null;
    private boolean clockDispatchScheduled = false;
    private String activeClockSource = "";
    private long activeClockSeq = -1L;
    private long activeClockSeenAt = 0L;
    private double activeClockScore = -100000.0;

    private final Runnable clockDispatchRunnable = new Runnable() {
        @Override
        public void run() {
            final String raw;
            synchronized (clockLock) {
                raw = pendingClockRaw;
                pendingClockRaw = null;
                clockDispatchScheduled = false;
            }
            if (raw != null && !raw.isEmpty()) pushClockToTopNow(raw);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.rgb(10, 14, 20));
        getWindow().setNavigationBarColor(Color.BLACK);
        buildUi();
        setupWebView();
        webView.loadUrl(HOME_URL);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(10, 14, 20));
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        installSystemBarInsets();

        fullScreenLayer = new FrameLayout(this);
        fullScreenLayer.setBackgroundColor(Color.BLACK);
        fullScreenLayer.setVisibility(View.GONE);
        root.addView(fullScreenLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        nativeSubtitle = new SubtitleTextView(this);
        nativeSubtitle.setGravity(Gravity.CENTER);
        nativeSubtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        nativeSubtitle.setTextColor(Color.WHITE);
        nativeSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        nativeSubtitle.setPadding(dp(10), dp(4), dp(10), dp(5));
        nativeSubtitle.setVisibility(View.GONE);
        nativeSubtitle.setClickable(false);
        nativeSubtitle.setFocusable(false);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void installSystemBarInsets() {
        webView.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets sys =
                        insets.getInsets(WindowInsets.Type.systemBars());
                left = sys.left;
                top = sys.top;
                right = sys.right;
                bottom = sys.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }

            /*
             * Android 15/16 can draw an SDK 35 app edge-to-edge by default.
             * Keep the normal SubHub page below the status bar on every phone.
             * The native fullscreen player is a different layer and remains truly fullscreen.
             */
            v.setPadding(left, top, right, bottom);
            return insets;
        });
        webView.requestApplyInsets();
    }

    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowContentAccess(true);
        s.setAllowFileAccess(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new NativeBridge(), "SubHubAndroidBridge");
        clockScript = readAsset("player_clock.js");
        siteBridgeScript = readAsset("site_bridge.js");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    clockScript,
                    Collections.singleton("*")
            );
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        && !clockScript.isEmpty()) {
                    view.evaluateJavascript(clockScript, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    Uri u = Uri.parse(url);
                    if (HOME_HOST.equalsIgnoreCase(u.getHost()) && !siteBridgeScript.isEmpty()) {
                        view.evaluateJavascript(siteBridgeScript, null);
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                String host = request.getUrl().getHost();
                if (host != null && host.equalsIgnoreCase(HOME_HOST)) return false;
                if (url.startsWith("about:")) return false;
                openExternal(url);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    android.os.Message resultMsg
            ) {
                return false;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }

                customView = view;
                customViewCallback = callback;

                fullScreenLayer.removeAllViews();
                fullScreenLayer.addView(
                        view,
                        new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                        )
                );

                /*
                 * Do NOT add nativeSubtitle in bridge2. The SubHub HTML overlay is
                 * already part of the fullscreen view on this player; adding a native
                 * copy is exactly what caused the two simultaneous subtitles.
                 */
                if (USE_NATIVE_FULLSCREEN_SUBTITLE) {
                    fullScreenLayer.addView(nativeSubtitle, subtitleLayoutParams(7));
                }

                webView.setVisibility(View.GONE);
                fullScreenLayer.setVisibility(View.VISIBLE);

                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );

                if (USE_NATIVE_FULLSCREEN_SUBTITLE) applySubtitleState(lastSubtitleState);
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });
    }

    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "تعذر فتح الرابط", Toast.LENGTH_SHORT).show();
        }
    }

    private FrameLayout.LayoutParams subtitleLayoutParams(double positionPct) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        int h = fullScreenLayer.getHeight();
        if (h <= 0) h = getResources().getDisplayMetrics().heightPixels;
        double pct = Math.max(2.0, Math.min(60.0, positionPct));
        lp.bottomMargin = (int) Math.round(h * pct / 100.0);
        lp.leftMargin = dp(12);
        lp.rightMargin = dp(12);
        return lp;
    }

    private void pushClockToTopNow(String raw) {
        if (webView == null) return;
        final String quoted = JSONObject.quote(raw == null ? "{}" : raw);
        final String js =
                "(function(){try{if(window.SubHubNativeClock){"
                        + "window.SubHubNativeClock(JSON.parse(" + quoted + "));"
                        + "}}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private boolean trustedPlayerPage(JSONObject p) {
        try {
            String host = p.optString("host", "").toLowerCase(Locale.US);
            return host.equals("onlyflix.to") || host.endsWith(".onlyflix.to")
                    || host.equals("cdnm.ink") || host.endsWith(".cdnm.ink")
                    || host.equals("cdnmovies-stream.online") || host.endsWith(".cdnmovies-stream.online")
                    || host.equals("cdnmvs.online") || host.endsWith(".cdnmvs.online");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isUrgentClockEvent(JSONObject p) {
        String e = p.optString("event", "");
        return "seeking".equals(e)
                || "seeked".equals(e)
                || "pause".equals(e)
                || "playing".equals(e)
                || "waiting".equals(e)
                || "stalled".equals(e);
    }

    private void queueClock(String raw, JSONObject p) {
        final long now = SystemClock.elapsedRealtime();
        final String source = p.optString("source", "");
        final long seq = p.optLong("seq", -1L);
        final double score = p.optDouble("score", 0.0);
        final boolean urgent = isUrgentClockEvent(p);

        synchronized (clockLock) {
            boolean sameSource = source.equals(activeClockSource);

            if (!sameSource) {
                boolean activeFresh = !activeClockSource.isEmpty()
                        && (now - activeClockSeenAt) < 1200L;

                /*
                 * Ignore a second/hidden media source while the current movie source
                 * is healthy. A real seek/play event is allowed to take ownership.
                 */
                if (activeFresh && !urgent && score < activeClockScore + 18.0) return;

                activeClockSource = source;
                activeClockSeq = -1L;
                activeClockScore = score;
            }

            if (seq >= 0 && seq <= activeClockSeq) {
                return; // stale/out-of-order packet after a seek
            }

            if (seq >= 0) activeClockSeq = seq;
            activeClockSeenAt = now;
            activeClockScore = score;
            pendingClockRaw = raw;

            if (urgent) {
                ui.removeCallbacks(clockDispatchRunnable);
                clockDispatchScheduled = true;
                ui.post(clockDispatchRunnable);
            } else if (!clockDispatchScheduled) {
                clockDispatchScheduled = true;
                /*
                 * Coalesce normal frame/poll traffic: only the newest packet reaches
                 * the page, so old currentTime values cannot build a queue.
                 */
                ui.postDelayed(clockDispatchRunnable, 24L);
            }
        }
    }

    private int parseCssColor(String raw, int fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();

        try {
            if (s.startsWith("#")) return Color.parseColor(s);
        } catch (Exception ignored) {}

        try {
            if (s.startsWith("rgb")) {
                int l = s.indexOf('(');
                int r = s.indexOf(')');
                if (l > 0 && r > l) {
                    String[] a = s.substring(l + 1, r).split(",");
                    int red = (int) Math.round(Double.parseDouble(a[0].trim()));
                    int green = (int) Math.round(Double.parseDouble(a[1].trim()));
                    int blue = (int) Math.round(Double.parseDouble(a[2].trim()));
                    int alpha = 255;
                    if (a.length > 3) {
                        alpha = (int) Math.round(
                                Math.max(0, Math.min(1, Double.parseDouble(a[3].trim()))) * 255
                        );
                    }
                    return Color.argb(alpha, red, green, blue);
                }
            }
        } catch (Exception ignored) {}

        return fallback;
    }

    private void applySubtitleState(JSONObject state) {
        if (!USE_NATIVE_FULLSCREEN_SUBTITLE) {
            nativeSubtitle.setVisibility(View.GONE);
            return;
        }

        if (state == null || customView == null) {
            nativeSubtitle.setVisibility(View.GONE);
            return;
        }

        boolean visible = state.optBoolean("visible", false);
        String text = state.optString("text", "");

        if (!visible || text.trim().isEmpty()) {
            nativeSubtitle.setText("");
            nativeSubtitle.setVisibility(View.GONE);
            return;
        }

        nativeSubtitle.setText(text);
        nativeSubtitle.setTextColor(
                parseCssColor(state.optString("color", "#ffffff"), Color.WHITE)
        );

        double px = state.optDouble("fontSizePx", 0);
        if (px > 4 && px < 140) {
            nativeSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, (float) px);
        }

        int weight = 500;
        try {
            weight = Integer.parseInt(state.optString("fontWeight", "500"));
        } catch (Exception ignored) {}

        nativeSubtitle.setTypeface(
                typefaceFor(state.optString("fontKey", "default")),
                weight >= 650 ? Typeface.BOLD : Typeface.NORMAL
        );

        float shadow = (float) Math.max(0, Math.min(6, state.optDouble("shadow", 1.5)));
        nativeSubtitle.setStrokeWidth(Math.max(1f, shadow));

        int bg = parseCssColor(
                state.optString("background", "rgba(0,0,0,.6)"),
                Color.argb(153, 0, 0, 0)
        );

        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadius(dp(5));
        nativeSubtitle.setBackground(gd);

        nativeSubtitle.setLayoutParams(
                subtitleLayoutParams(state.optDouble("positionPct", 7))
        );
        nativeSubtitle.setVisibility(View.VISIBLE);
        nativeSubtitle.bringToFront();
    }

    private Typeface typefaceFor(String key) {
        if ("typesetting".equals(key) || "naskh".equals(key) || "amiri".equals(key)) {
            return Typeface.SERIF;
        }
        return Typeface.DEFAULT;
    }

    private String readAsset(String name) {
        StringBuilder b = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
        } catch (Exception ignored) {}
        return b.toString();
    }

    private void exitFullScreen() {
        if (customView == null) return;

        nativeSubtitle.setVisibility(View.GONE);
        fullScreenLayer.removeAllViews();
        customView = null;
        fullScreenLayer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        webView.requestApplyInsets();

        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullScreen();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(clockDispatchRunnable);

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }

        super.onDestroy();
    }

    public final class NativeBridge {
        @JavascriptInterface
        public void mediaClock(String raw) {
            if (raw == null || raw.length() > 8192) return;

            try {
                JSONObject p = new JSONObject(raw);
                if (!trustedPlayerPage(p)) return;
                queueClock(raw, p);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void subtitleState(String raw) {
            if (raw == null || raw.length() > 20000) return;

            try {
                JSONObject p = new JSONObject(raw);
                lastSubtitleState = p;

                if (USE_NATIVE_FULLSCREEN_SUBTITLE) {
                    ui.post(() -> applySubtitleState(p));
                }
            } catch (Exception ignored) {}
        }
    }

    public static class SubtitleTextView extends TextView {
        private float strokeWidth = 1.5f;
        private int fillColor = Color.WHITE;

        public SubtitleTextView(android.content.Context context) {
            super(context);
        }

        public void setStrokeWidth(float v) {
            strokeWidth = v;
            invalidate();
        }

        @Override
        public void setTextColor(int color) {
            fillColor = color;
            super.setTextColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Paint p = getPaint();
            Paint.Style oldStyle = p.getStyle();
            float oldStroke = p.getStrokeWidth();
            int oldColor = getCurrentTextColor();

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeWidth * getResources().getDisplayMetrics().density);
            super.setTextColor(Color.BLACK);
            super.onDraw(canvas);

            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(oldStroke);
            super.setTextColor(fillColor);
            super.onDraw(canvas);

            p.setStyle(oldStyle);
            super.setTextColor(oldColor == Color.BLACK ? fillColor : oldColor);
        }
    }
}
