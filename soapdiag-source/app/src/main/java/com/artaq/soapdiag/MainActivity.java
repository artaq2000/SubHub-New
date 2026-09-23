package com.artaq.soapdiag;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String DEFAULT_URL = "https://onlyflix.to/resident-evil-2/";
    private static final int MAX_LOG_CHARS = 180_000;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\\"'<>]+", Pattern.CASE_INSENSITIVE);

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder report = new StringBuilder();
    private final Set<String> seenWebViewRequests = new LinkedHashSet<>();
    private final Map<String, Integer> hostCounts = new HashMap<>();
    private final Set<String> foundUrls = new LinkedHashSet<>();

    private FrameLayout root;
    private LinearLayout normalUi;
    private FrameLayout fullScreenLayer;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private EditText urlInput;
    private TextView logView;
    private TextView statusView;
    private WebView webView;
    private String lastCdnUrl = "";
    private String lastServer1Url = "";
    private String lastServer1EmbedUrl = "";
    private boolean server1EmbedOpened = false;
    private boolean server1AutoOpened = false;
    private String diagScript = "";

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        buildUi();
        setupWebView();
        appendHeader();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 12, 24));
        setContentView(root);

        normalUi = new LinearLayout(this);
        normalUi.setOrientation(LinearLayout.VERTICAL);
        normalUi.setPadding(dp(10), dp(10), dp(10), dp(8));
        normalUi.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.addView(normalUi, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView title = text("اختبار المشغل — شبكة مباشرة", 22, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        normalUi.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statusView = text("Server 1 تلقائي — الصق رابط صفحة الفيلم أو المشغل", 13, Color.rgb(155, 180, 215));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(2), 0, dp(8));
        normalUi.addView(statusView, statusLp);

        urlInput = new EditText(this);
        urlInput.setText(DEFAULT_URL);
        urlInput.setTextColor(Color.WHITE);
        urlInput.setHintTextColor(Color.GRAY);
        urlInput.setBackgroundColor(Color.rgb(12, 27, 45));
        urlInput.setTextDirection(View.TEXT_DIRECTION_LTR);
        urlInput.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        urlInput.setTextSize(13);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setMaxLines(3);
        urlInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        normalUi.addView(urlInput, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        row1.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button start = button("استخراج Server 1");
        Button clear = button("مسح السجل");
        row1.addView(start, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row1.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        normalUi.addView(row1);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.56f);
        webLp.setMargins(0, dp(4), 0, dp(6));
        normalUi.addView(webView, webLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);
        row2.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Button copyReport = button("نسخ التقرير");
        Button copyCdn = button("نسخ Server 1");
        row2.addView(copyReport, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row2.addView(copyCdn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        normalUi.addView(row2);

        ScrollView logScroll = new ScrollView(this);
        logScroll.setFillViewport(true);
        logScroll.setBackgroundColor(Color.rgb(7, 18, 31));
        logView = text("", 11, Color.rgb(228, 238, 250));
        logView.setTextDirection(View.TEXT_DIRECTION_LTR);
        logView.setGravity(Gravity.LEFT | Gravity.TOP);
        logView.setPadding(dp(10), dp(8), dp(10), dp(8));
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logScroll.addView(logView, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        normalUi.addView(logScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.44f));

        fullScreenLayer = new FrameLayout(this);
        fullScreenLayer.setBackgroundColor(Color.BLACK);
        fullScreenLayer.setVisibility(View.GONE);
        root.addView(fullScreenLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        start.setOnClickListener(v -> startTest());
        clear.setOnClickListener(v -> clearReport());
        copyReport.setOnClickListener(v -> copyToClipboard("SoapDiag report", report.toString()));
        copyCdn.setOnClickListener(v -> {
            if (lastServer1Url.isEmpty()) Toast.makeText(this, "لم يظهر رابط Server 1 بعد", Toast.LENGTH_SHORT).show();
            else copyToClipboard("Server 1 URL", lastServer1Url);
        });
    }

    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new DiagBridge(), "SoapDiagBridge");
        diagScript = readAsset("diag.js");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, diagScript, java.util.Collections.singleton("*"));
            append("[DIAG] document-start hook: ON");
        } else {
            append("[DIAG] document-start hook غير مدعوم؛ سيتم استخدام fallback بعد بدء الصفحة.");
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                append("[PAGE] start " + url);
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && !diagScript.isEmpty()) {
                    view.evaluateJavascript(diagScript, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                append("[PAGE] finished " + url);
                if (isServer1Media(url) && url.equals(activeQualityUrl)) {
                    if (!activeQualityFailed) {
                        lastServer1Url = url;
                        lastCdnUrl = url;
                        statusView.setText("Server 1 يعمل — جودة " + activeQuality + "p");
                        append("[QUALITY OK] " + activeQuality + "p\n" + url);
                    }
                } else if (lastServer1Url.isEmpty()) {
                    statusView.setText("جارٍ البحث عن Server 1 تلقائياً…");
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String u = request.getUrl().toString();
                if (isAllowedMainFrame(u)) return false;
                append("[POPUP/NAV BLOCKED] " + u);
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                recordWebViewRequest(request);
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                String u = request.getUrl().toString();
                if (isRelevant(u)) {
                    append("[HTTP ERROR] " + errorResponse.getStatusCode() + " " + request.getMethod() + " " + u);
                }
                if (!activeQualityUrl.isEmpty() && sameQualityPath(u, activeQualityUrl) && errorResponse.getStatusCode() >= 400) {
                    failActiveQuality("HTTP " + errorResponse.getStatusCode());
                }
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                String u = request.getUrl().toString();
                if (isRelevant(u)) {
                    append("[NET ERROR] " + request.getMethod() + " " + u + " :: " + error.getErrorCode() + " " + error.getDescription());
                }
                if (!activeQualityUrl.isEmpty() && sameQualityPath(u, activeQualityUrl)) {
                    failActiveQuality("NET " + error.getErrorCode());
                }
                super.onReceivedError(view, request, error);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                append("[POPUP BLOCKED] window.open / new window");
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
                fullScreenLayer.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                normalUi.setVisibility(View.GONE);
                fullScreenLayer.setVisibility(View.VISIBLE);
                getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                );
            }

            @Override
            public void onHideCustomView() {
                exitFullScreen();
            }
        });
    }

    private void startTest() {
        String u = urlInput.getText().toString().trim();
        if (!(u.startsWith("https://") || u.startsWith("http://"))) {
            Toast.makeText(this, "أدخل رابط صفحة المشغل كاملاً", Toast.LENGTH_SHORT).show();
            return;
        }
        clearReport();
        appendHeader();
        append("[TEST] Auto Server 1 extraction");
        append("[TEST] " + u);
        statusView.setText("جاري تحميل الصفحة والبحث عن Server 1…");
        webView.stopLoading();
        webView.loadUrl(u);
    }

    private void clearReport() {
        synchronized (report) { report.setLength(0); }
        synchronized (seenWebViewRequests) { seenWebViewRequests.clear(); }
        synchronized (hostCounts) { hostCounts.clear(); }
        synchronized (foundUrls) { foundUrls.clear(); }
        lastCdnUrl = "";
        lastServer1Url = "";
        lastServer1EmbedUrl = "";
        server1EmbedOpened = false;
        server1AutoOpened = false;
        ui.post(() -> logView.setText(""));
    }

    private void appendHeader() {
        PackageInfo p = WebView.getCurrentWebViewPackage();
        append("=== SoapDiag 1.6 ===");
        append("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android API " + Build.VERSION.SDK_INT);
        append("WebView: " + (p == null ? "unknown" : p.packageName + " " + p.versionName));
        append("Targets: OnlyFlix / CDNM iframe / cdnmvs(Server 1) / nontongo / workers.dev / medmedia05");
        append("Cookies/Authorization values are redacted.");
    }

    private void recordWebViewRequest(WebResourceRequest r) {
        String u = r.getUrl().toString();
        if (!isRelevant(u)) return;
        String method = r.getMethod();
        String key = method + " " + u;
        synchronized (seenWebViewRequests) {
            if (!seenWebViewRequests.add(key)) return;
        }

        String host = host(u);
        int n;
        synchronized (hostCounts) {
            n = hostCounts.getOrDefault(host, 0) + 1;
            hostCounts.put(host, n);
        }
        int cap = host.contains("medmedia05") ? 35 : (host.contains("workers.dev") ? 25 : 50);
        if (n > cap) {
            if (n == cap + 1) append("[WEBVIEW] … المزيد من طلبات " + host + " تم اختصارها");
            return;
        }

        StringBuilder b = new StringBuilder();
        b.append("[WEBVIEW] ").append(method).append(' ').append(u);
        Map<String, String> h = r.getRequestHeaders();
        addHeaderIfPresent(b, h, "Origin");
        addHeaderIfPresent(b, h, "Referer");
        addHeaderIfPresent(b, h, "Range");
        addHeaderIfPresent(b, h, "Accept");
        if (hasHeader(h, "Cookie")) b.append("\n  Cookie: [present, value redacted]");
        if (hasHeader(h, "Authorization")) b.append("\n  Authorization: [present, value redacted]");
        append(b.toString());
        rememberUrl(u, "WEBVIEW");
    }

    private boolean hasHeader(Map<String, String> h, String wanted) {
        if (h == null) return false;
        for (String k : h.keySet()) if (k.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    private void addHeaderIfPresent(StringBuilder b, Map<String, String> h, String wanted) {
        if (h == null) return;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if (e.getKey().equalsIgnoreCase(wanted)) {
                b.append("\n  ").append(wanted).append(": ").append(safe(e.getValue(), 1000));
                return;
            }
        }
    }

    private boolean isAllowedMainFrame(String u) {
        String h = host(u);
        return h.endsWith("onlyflix.to") || h.endsWith("cdnm.ink") || h.endsWith("nontongo.day") || h.endsWith("nontongo.stream") || h.endsWith("ilove2day.com") ||
               h.endsWith("cdnmvs.online") || h.endsWith("soapsoap123.workers.dev") || h.endsWith("medmedia05.mom") || h.isEmpty();
    }

    private boolean isRelevant(String u) {
        String h = host(u);
        return h.endsWith("onlyflix.to") || h.endsWith("cdnm.ink") || h.endsWith("ilove2day.com") || h.endsWith("nontongo.day") || h.endsWith("nontongo.stream") ||
               h.endsWith("cdnmvs.online") || h.endsWith("soapsoap123.workers.dev") || h.endsWith("medmedia05.mom");
    }

    private String host(String u) {
        try {
            String h = Uri.parse(u).getHost();
            return h == null ? "" : h.toLowerCase(Locale.US);
        } catch (Exception e) { return ""; }
    }

    private void rememberUrl(String u, String source) {
        if (u == null || u.isEmpty() || !isRelevant(u)) return;
        String h = host(u);
        boolean server1 = isServer1Media(u);
        boolean server1Embed = isServer1Embed(u);
        boolean importantCdn = server1 || server1Embed || h.endsWith("soapsoap123.workers.dev") || h.endsWith("medmedia05.mom");
        synchronized (foundUrls) {
            if (!foundUrls.add(u)) {
                if (server1 && lastServer1Url.isEmpty()) handleServer1Url(u, source);
                if (server1Embed && lastServer1EmbedUrl.isEmpty()) handleServer1Embed(u, source);
                return;
            }
        }
        if (server1) {
            handleServer1Url(u, source);
        } else if (server1Embed) {
            handleServer1Embed(u, source);
        } else if (importantCdn) {
            lastCdnUrl = u;
            append("[FOUND " + source + "] " + (h.contains("workers.dev") ? "WORKERS CDN" : "MEDIA CDN") + "\n" + u);
        }
    }

    private boolean isServer1Embed(String u) {
        if (u == null) return false;
        String h = host(u);
        String l = u.toLowerCase(Locale.US);
        return h.endsWith("cdnm.ink") && l.contains("/embed/imdb/");
    }

    private void handleServer1Embed(String u, String source) {
        if (!isServer1Embed(u) || u.equals(lastServer1EmbedUrl)) return;
        lastServer1EmbedUrl = u;
        append("[SERVER 1 EMBED FOUND " + source + "]\n" + u);
        ui.post(() -> {
            statusView.setText("تم العثور على Server 1 — تضمينه داخل OnlyFlix…");
            if (server1EmbedOpened) return;
            server1EmbedOpened = true;

            String quoted = JSONObject.quote(u);
            String js =
                "(function(){" +
                "try{" +
                "var old=document.getElementById('__soapdiag_server1');" +
                "if(old) old.remove();" +
                "var f=document.createElement('iframe');" +
                "f.id='__soapdiag_server1';" +
                "f.src=" + quoted + ";" +
                "f.allow='autoplay; fullscreen; picture-in-picture';" +
                "f.setAttribute('allowfullscreen','');" +
                "f.style.cssText='width:100%;height:72vh;border:0;background:#000;display:block;position:relative;z-index:2147483646;';" +
                "var target=document.querySelector('main')||document.body;" +
                "target.insertBefore(f,target.firstChild);" +
                "return 'iframe-injected';" +
                "}catch(e){return 'iframe-error:'+e;}" +
                "})();";

            webView.evaluateJavascript(js, value -> append("[IFRAME] " + value));
        });
    }

    private boolean isServer1Media(String u) {
        if (u == null) return false;
        String h = host(u);
        String l = u.toLowerCase(Locale.US);
        return h.equals("s1.cdnmvs.online") && (l.contains(".m3u8") || l.contains("/index-"));
    }

    private void handleServer1Url(String u, String source) {
        if (!isServer1Media(u)) return;
        append("[SERVER 1 FOUND " + source + "]\n" + u);
        if (lastServer1Url.isEmpty()) {
            lastServer1Url = u;
            lastCdnUrl = u;
        }
        ui.post(() -> statusView.setText("Server 1 — فحص الجودات داخل المشغّل…"));
    }

    private String safe(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("(?i)(authorization\\s*[:=]\\s*)[^&\\s,}]+", "$1[redacted]")
             .replaceAll("(?i)(cookie\\s*[:=]\\s*)[^\\n]+", "$1[redacted]");
        return s.length() <= max ? s : s.substring(0, max) + "\n…[truncated]";
    }

    private void scanUrls(String body, String source) {
        if (body == null || body.isEmpty()) return;
        Matcher m = URL_PATTERN.matcher(body.replace("\\/", "/"));
        int count = 0;
        while (m.find() && count < 20) {
            String u = m.group();
            if (isRelevant(u)) {
                rememberUrl(u, source);
                count++;
            }
        }
    }

    private String formatJsHeaders(JSONObject headers) {
        if (headers == null) return "";
        StringBuilder b = new StringBuilder();
        java.util.Iterator<String> it = headers.keys();
        int shown = 0;
        while (it.hasNext() && shown < 20) {
            String k = it.next();
            String v = headers.optString(k, "");
            String kl = k.toLowerCase(Locale.US);
            if (kl.contains("cookie") || kl.contains("authorization") || kl.contains("token") || kl.contains("secret") || kl.matches(".*api[-_]?key.*")) {
                v = "[present, value redacted]";
            } else {
                v = safe(v, 1200);
            }
            b.append("\n  ").append(k).append(": ").append(v);
            shown++;
        }
        return b.toString();
    }

    private void append(String line) {
        final String stamp;
        synchronized (timeFormat) { stamp = timeFormat.format(new Date()); }
        final String stamped = "[" + stamp + "] " + line + "\n";
        synchronized (report) {
            if (report.length() + stamped.length() > MAX_LOG_CHARS) {
                int remove = Math.min(report.length(), 30_000);
                report.delete(0, remove);
                report.insert(0, "[...older log trimmed...]\n");
            }
            report.append(stamped);
        }
        ui.post(() -> {
            logView.setText(report.toString());
            View parent = (View) logView.getParent();
            if (parent != null && parent.getParent() instanceof ScrollView) {
                ((ScrollView) parent.getParent()).post(() -> ((ScrollView) parent.getParent()).fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void copyToClipboard(String label, String value) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText(label, value));
        Toast.makeText(this, "تم النسخ", Toast.LENGTH_SHORT).show();
    }

    private String readAsset(String name) {
        StringBuilder b = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) b.append(line).append('\n');
        } catch (Exception e) {
            append("[DIAG] تعذر قراءة " + name + ": " + e);
        }
        return b.toString();
    }

    private void exitFullScreen() {
        if (customView == null) return;
        fullScreenLayer.removeView(customView);
        customView = null;
        fullScreenLayer.setVisibility(View.GONE);
        normalUi.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (customView != null) { exitFullScreen(); return; }
        if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }
        super.onDestroy();
    }

    public final class DiagBridge {
        @JavascriptInterface
        public void emit(String raw) {
            if (raw == null || raw.length() > 80_000) return;
            try {
                JSONObject root = new JSONObject(raw);
                String kind = root.optString("kind", "js");
                JSONObject d = root.optJSONObject("data");
                if (d == null) d = new JSONObject();
                String u = d.optString("url", d.optString("page", ""));
                if (!u.isEmpty() && !isRelevant(u) && !"diag-installed".equals(kind)) return;

                switch (kind) {
                    case "diag-installed":
                        append("[JS HOOK] installed at document start: " + root.optString("page", ""));
                        break;
                    case "fetch-request":
                    case "xhr-request":
                    case "beacon-request": {
                        String method = d.optString("method", "GET");
                        String body = safe(d.optString("body", ""), 16_000);
                        String headers = formatJsHeaders(d.optJSONObject("headers"));
                        append("[JS REQUEST] " + method + " " + u + headers + (body.isEmpty() ? "" : "\n  BODY:\n" + body));
                        rememberUrl(u, "JS");
                        scanUrls(body, "POST BODY");
                        break;
                    }
                    case "fetch-request-body": {
                        String body = safe(d.optString("body", ""), 16_000);
                        append("[JS REQUEST BODY] " + u + "\n" + body);
                        scanUrls(body, "POST BODY");
                        break;
                    }
                    case "fetch-response-head":
                        append("[JS RESPONSE] HTTP " + d.optInt("status", 0) + " " + u + formatJsHeaders(d.optJSONObject("headers")));
                        break;
                    case "fetch-response-body":
                    case "xhr-response": {
                        String body = safe(d.optString("body", ""), 16_000);
                        append("[JS RESPONSE BODY] HTTP " + d.optInt("status", 0) + " " + u + (body.isEmpty() ? "" : "\n" + body));
                        scanUrls(body, "RESPONSE");
                        break;
                    }
                    case "fetch-error":
                    case "xhr-error":
                    case "fetch-response-body-error":
                        append("[JS ERROR] " + kind + " " + u + " :: " + d.optString("error", ""));
                        break;
                    case "dom-url":
                    case "candidate-url":
                        rememberUrl(u, "DOM");
                        break;
                    case "server1-click":
                        append("[AUTO] تم الضغط على خيار Server 1 داخل الصفحة");
                        break;
                    case "quality-probe-context":
                        append("[QUALITY CONTEXT] " + root.optString("page", "") + "\n" + u);
                        break;
                    case "quality-probe-start":
                        append("[QUALITY PROBE TRY] " + d.optInt("quality", 0) + "p\n" + u);
                        break;
                    case "quality-probe":
                        append("[QUALITY PROBE] " + d.optInt("quality", 0) + "p :: HTTP " + d.optInt("status", 0) +
                            (d.optBoolean("ok", false) ? " OK" : " FAIL") + "\n" + u);
                        break;
                    case "quality-best": {
                        int q = d.optInt("quality", 0);
                        if (q > 0 && !u.isEmpty()) {
                            lastServer1Url = u;
                            lastCdnUrl = u;
                            ui.post(() -> statusView.setText("Server 1 يعمل — جودة " + q + "p"));
                            append("[QUALITY BEST] " + q + "p\n" + u);
                        } else {
                            append("[QUALITY BEST] لم تظهر جودة أعلى؛ إبقاء الرابط الأصلي");
                        }
                        break;
                    }
                    case "quality-switch":
                        append("[QUALITY SWITCHED] " + d.optInt("quality", 0) + "p داخل المشغّل");
                        break;
                    case "quality-switch-error":
                        append("[QUALITY SWITCH ERROR] " + d.optInt("quality", 0) + "p :: " + d.optString("error", ""));
                        break;
                    default:
                        if (!u.isEmpty()) append("[JS " + kind + "] " + u);
                }
            } catch (Exception e) {
                append("[BRIDGE PARSE ERROR] " + e.getClass().getSimpleName());
            }
        }
    }
}
