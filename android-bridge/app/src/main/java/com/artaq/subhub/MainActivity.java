package com.artaq.subhub;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.util.Base64;
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
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.provider.MediaStore;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://subhub-at7.pages.dev/";
    private static final String HOME_HOST = "subhub-at7.pages.dev";
    private static final String UPDATES_WORKER_URL = "https://subhub-updates.artaq2000.workers.dev";
    private static final String NATIVE_VERSION = "322.3.7";
    private static final int NATIVE_VERSION_CODE = 16;
    private static final String KEY_UPDATE_CHECK = "updateLastAttempt";
    private static final String KEY_UPDATE_META = "updateMetadata";
    private boolean updateCheckBusy = false;
    private volatile boolean updateDownloadBusy = false;
    private static final String NATIVE_PREFS = "subhub_native_pair_v1";
    private static final String KEY_APP_DEVICE_ID = "appDeviceId";
    private static final String KEY_APP_SECRET = "appSecret";
    private static final String KEY_APP_PAIRED = "appPaired";
    private static final String KEY_PENDING_PAIR = "pendingPairId";
    private static final long MAX_SUBTITLE_DOWNLOAD_BYTES = 16L * 1024L * 1024L;
    private final String downloadBridgeToken = UUID.randomUUID().toString().replace("-", "");

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

    private SharedPreferences nativePrefs;
    private volatile boolean pairingBusy = false;
    private volatile boolean pairStatusBusy = false;
    private volatile boolean sessionBusy = false;
    private volatile boolean homePageReady = false;
    private JSONObject pendingNativeSession = null;

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
        nativePrefs = getSharedPreferences(NATIVE_PREFS, MODE_PRIVATE);
        ensureNativeIdentity();
        buildUi();
        setupWebView();
        webView.loadUrl(HOME_URL);
        ui.postDelayed(() -> handlePairIntent(getIntent()), 700L);
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
        root.setOnApplyWindowInsetsListener((v, insets) -> {
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
             * API 35+ may draw apps edge-to-edge. Move the WHOLE WebView below
             * the real system bars instead of only padding WebView's internals.
             * The extra 6dp gives the header a little breathing room on phones
             * with tall status bars/notches. Fullscreen video stays fullscreen.
             */
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) webView.getLayoutParams();
            lp.leftMargin = left;
            lp.topMargin = top + dp(6);
            lp.rightMargin = right;
            lp.bottomMargin = bottom;
            webView.setLayoutParams(lp);
            webView.setPadding(0, 0, 0, 0);
            return insets;
        });
        root.requestApplyInsets();
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
        installDownloadSupport();
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
                try {
                    Uri u = Uri.parse(url);
                    if (HOME_HOST.equalsIgnoreCase(u.getHost())) homePageReady = false;
                } catch (Exception ignored) {}

                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                        && !clockScript.isEmpty()) {
                    view.evaluateJavascript(clockScript, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                try {
                    Uri u = Uri.parse(url);
                    if (HOME_HOST.equalsIgnoreCase(u.getHost())) {
                        homePageReady = true;
                        if (!siteBridgeScript.isEmpty()) {
                            view.evaluateJavascript(siteBridgeScript, null);
                        }
                        installDownloadInterceptor(view);
                        injectNativeSessionIfReady();
                        notifyUpdateState();
                    }
                } catch (Exception ignored) {}
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                String url = request.getUrl().toString();
                if ("subhub-update://download".equals(url)) {
                    Uri page = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
                    if (request.hasGesture() && "https".equals(page.getScheme())
                            && HOME_HOST.equalsIgnoreCase(page.getHost())) downloadNativeUpdate();
                    return true;
                }
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



    private boolean isTrustedHomePage() {
        if (webView == null) return false;
        try {
            Uri page = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
            return "https".equalsIgnoreCase(page.getScheme())
                    && HOME_HOST.equalsIgnoreCase(page.getHost());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String safeDownloadName(String raw, String url, String mimeType) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            try { name = URLUtil.guessFileName(url, null, mimeType); }
            catch (Exception ignored) {}
        }
        if (name == null || name.trim().isEmpty()) name = "SubHub-subtitle.srt";
        name = name.replace((char) 92, '_').replace('/', '_').replace(':', '_')
                .replace('*', '_').replace('?', '_').replace('"', '_')
                .replace('<', '_').replace('>', '_').replace('|', '_')
                .replace((char) 13, '_').replace((char) 10, '_').trim();
        if (name.isEmpty()) name = "SubHub-subtitle.srt";

        String lower = name.toLowerCase(Locale.US);
        if (!lower.contains(".")) {
            String mt = mimeType == null ? "" : mimeType.toLowerCase(Locale.US);
            if (mt.contains("vtt")) name += ".vtt";
            else if (mt.contains("zip")) name += ".zip";
            else name += ".srt";
        }
        if (name.length() > 140) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && dot >= name.length() - 10 ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 140 - ext.length())) + ext;
        }
        return name;
    }

    private void installDownloadSupport() {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (!isTrustedHomePage()) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    openExternal(url);
                }
                return;
            }
            String name;
            try { name = URLUtil.guessFileName(url, contentDisposition, mimeType); }
            catch (Exception ignored) { name = "SubHub-subtitle.srt"; }
            handleDownloadRequest(url, name, userAgent, mimeType);
        });
    }

    private void installDownloadInterceptor(WebView view) {
        if (view == null || !isTrustedHomePage()) return;
        String token = JSONObject.quote(downloadBridgeToken);
        String js =
                "(function(){try{"
                        + "if(window.__subhubAndroidDownloadV1)return;"
                        + "window.__subhubAndroidDownloadV1=true;"
                        + "var TOKEN=" + token + ";"
                        + "document.addEventListener('click',function(ev){try{"
                        + "var t=ev.target;"
                        + "var a=t&&t.closest?t.closest('a'):null;"
                        + "if(!a)return;"
                        + "var href=String(a.href||'');"
                        + "var dl=a.hasAttribute('download');"
                        + "if(!dl&&href.indexOf('blob:')!==0&&href.indexOf('data:')!==0)return;"
                        + "var name=String(a.getAttribute('download')||'');"
                        + "if(href.indexOf('http://')===0||href.indexOf('https://')===0){"
                        + "ev.preventDefault();ev.stopPropagation();"
                        + "SubHubAndroidBridge.downloadUrl(TOKEN,href,name);return;}"
                        + "if(href.indexOf('data:')===0){"
                        + "ev.preventDefault();ev.stopPropagation();"
                        + "SubHubAndroidBridge.saveDataUrl(TOKEN,href,name);return;}"
                        + "if(href.indexOf('blob:')===0){"
                        + "ev.preventDefault();ev.stopPropagation();"
                        + "fetch(href).then(function(r){return r.blob();}).then(function(b){"
                        + "if(b.size>" + MAX_SUBTITLE_DOWNLOAD_BYTES + ")throw new Error('too-large');"
                        + "var fr=new FileReader();"
                        + "fr.onload=function(){SubHubAndroidBridge.saveDataUrl(TOKEN,String(fr.result||''),name);};"
                        + "fr.readAsDataURL(b);"
                        + "}).catch(function(){SubHubAndroidBridge.downloadFailed(TOKEN);});"
                        + "}"
                        + "}catch(e){}},true);"
                        + "}catch(e){}})();";
        view.evaluateJavascript(js, null);
    }

    private void captureBlobFromPage(String blobUrl, String suggestedName) {
        if (webView == null || !isTrustedHomePage()) return;
        String token = JSONObject.quote(downloadBridgeToken);
        String js =
                "(function(){try{fetch(" + JSONObject.quote(blobUrl) + ")"
                        + ".then(function(r){return r.blob();})"
                        + ".then(function(b){if(b.size>" + MAX_SUBTITLE_DOWNLOAD_BYTES
                        + ")throw new Error('too-large');var fr=new FileReader();"
                        + "fr.onload=function(){SubHubAndroidBridge.saveDataUrl(" + token
                        + ",String(fr.result||'')," + JSONObject.quote(suggestedName == null ? "" : suggestedName)
                        + ");};fr.readAsDataURL(b);})"
                        + ".catch(function(){SubHubAndroidBridge.downloadFailed(" + token + ");});"
                        + "}catch(e){SubHubAndroidBridge.downloadFailed(" + token + ");}})();";
        webView.evaluateJavascript(js, null);
    }

    private void handleDownloadRequest(
            String url,
            String suggestedName,
            String userAgent,
            String mimeType
    ) {
        if (!isTrustedHomePage() || url == null || url.trim().isEmpty()) return;
        String cleanUrl = url.trim();

        if (cleanUrl.startsWith("blob:")) {
            captureBlobFromPage(cleanUrl, suggestedName);
            return;
        }
        if (cleanUrl.startsWith("data:")) {
            final String data = cleanUrl;
            final String name = suggestedName;
            new Thread(() -> saveDataUrlInternal(data, name), "SubHub-data-download").start();
            return;
        }

        Uri uri;
        try { uri = Uri.parse(cleanUrl); }
        catch (Exception e) { return; }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) return;

        String fileName = safeDownloadName(suggestedName, cleanUrl, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            request.setTitle(fileName);
            request.setDescription("ترجمة SubHub");
            request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            );
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);

            if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);

            String ua = userAgent;
            if (ua == null || ua.trim().isEmpty()) {
                try { ua = webView.getSettings().getUserAgentString(); }
                catch (Exception ignored) {}
            }
            if (ua != null && !ua.trim().isEmpty()) request.addRequestHeader("User-Agent", ua);

            String cookies = CookieManager.getInstance().getCookie(cleanUrl);
            if (cookies != null && !cookies.trim().isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }

            String referer = webView.getUrl();
            if (referer != null && referer.startsWith("https://")) {
                request.addRequestHeader("Referer", referer);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            }

            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("download-manager-unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "بدأ تنزيل الترجمة", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذر بدء تنزيل الترجمة", Toast.LENGTH_LONG).show();
        }
    }

    private String mimeFromDataUrl(String header) {
        if (header == null || !header.startsWith("data:")) return "text/plain";
        int semi = header.indexOf(';');
        String mime = semi > 5 ? header.substring(5, semi) : header.substring(5);
        return mime == null || mime.trim().isEmpty() ? "text/plain" : mime.trim();
    }

    private void saveDataUrlInternal(String dataUrl, String suggestedName) {
        try {
            if (dataUrl == null || dataUrl.length() > 24 * 1024 * 1024) {
                throw new IllegalArgumentException("subtitle-download-too-large");
            }
            int comma = dataUrl.indexOf(',');
            if (comma <= 4) throw new IllegalArgumentException("bad-data-url");

            String header = dataUrl.substring(0, comma);
            String payload = dataUrl.substring(comma + 1);
            byte[] bytes;
            if (header.toLowerCase(Locale.US).contains(";base64")) {
                bytes = Base64.decode(payload, Base64.DEFAULT);
            } else {
                bytes = URLDecoder.decode(payload, "UTF-8").getBytes(StandardCharsets.UTF_8);
            }
            if (bytes.length <= 0 || bytes.length > MAX_SUBTITLE_DOWNLOAD_BYTES) {
                throw new IllegalArgumentException("subtitle-download-size");
            }

            String mime = mimeFromDataUrl(header);
            String name = safeDownloadName(suggestedName, "", mime);
            writeDownloadedBytes(bytes, name, mime);
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, "تم تنزيل الترجمة", Toast.LENGTH_SHORT).show();
                }
            });
        } catch (Exception e) {
            ui.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(this, "تعذر تنزيل الترجمة", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    private void writeDownloadedBytes(byte[] bytes, String fileName, String mimeType) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE,
                    mimeType == null || mimeType.isEmpty() ? "application/octet-stream" : mimeType);
            values.put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/SubHub"
            );
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) throw new IllegalStateException("download-create-failed");
            boolean ok = false;
            try (OutputStream out = resolver.openOutputStream(item, "w")) {
                if (out == null) throw new IllegalStateException("download-open-failed");
                out.write(bytes);
                out.flush();
                ok = true;
            } finally {
                if (ok) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(item, done, null, null);
                } else {
                    resolver.delete(item, null, null);
                }
            }
            return;
        }

        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "SubHub");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("download-dir-failed");

        File outFile = new File(dir, fileName);
        if (outFile.exists()) {
            int dot = fileName.lastIndexOf('.');
            String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
            String ext = dot > 0 ? fileName.substring(dot) : "";
            int n = 2;
            while (outFile.exists() && n < 1000) {
                outFile = new File(dir, stem + "-" + n + ext);
                n++;
            }
        }

        try (FileOutputStream out = new FileOutputStream(outFile)) {
            out.write(bytes);
            out.flush();
        }
    }

    private void ensureNativeIdentity() {
        if (nativePrefs == null) return;
        String id = nativePrefs.getString(KEY_APP_DEVICE_ID, "");
        String secret = nativePrefs.getString(KEY_APP_SECRET, "");

        if (id == null || id.length() < 8) {
            id = "app_" + UUID.randomUUID().toString().replace("-", "");
            nativePrefs.edit().putString(KEY_APP_DEVICE_ID, id).apply();
        }
        if (secret == null || secret.length() < 32) {
            secret = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            nativePrefs.edit().putString(KEY_APP_SECRET, secret).apply();
        }
    }

    private String appDeviceId() {
        ensureNativeIdentity();
        return nativePrefs == null ? "" : nativePrefs.getString(KEY_APP_DEVICE_ID, "");
    }

    private String appSecret() {
        ensureNativeIdentity();
        return nativePrefs == null ? "" : nativePrefs.getString(KEY_APP_SECRET, "");
    }

    private boolean isNativeAppPaired() {
        return nativePrefs != null && nativePrefs.getBoolean(KEY_APP_PAIRED, false);
    }

    private void startPairingFlow() {
        if (isNativeAppPaired()) {
            syncNativeSubscription(true);
            return;
        }
        if (pairingBusy) return;
        pairingBusy = true;
        Toast.makeText(this, "جارٍ تجهيز ربط الاشتراك…", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("appDeviceId", appDeviceId());
                body.put("appSecret", appSecret());

                JSONObject res = postWorkerJson("/pair/start", body);
                if (!res.optBoolean("ok", false)) {
                    throw new Exception(res.optString("error", "pair-start-failed"));
                }

                final String pairId = res.optString("pairId", "");
                final String browserUrl = res.optString("browserUrl", "");
                if (pairId.isEmpty() || browserUrl.isEmpty()) {
                    throw new Exception("pair-start-invalid");
                }

                nativePrefs.edit().putString(KEY_PENDING_PAIR, pairId).apply();
                ui.post(() -> {
                    pairingBusy = false;
                    openPairBrowser(browserUrl);
                });
            } catch (Exception e) {
                ui.post(() -> {
                    pairingBusy = false;
                    Toast.makeText(
                            MainActivity.this,
                            "تعذر بدء ربط الاشتراك. حاول مرة أخرى.",
                            Toast.LENGTH_LONG
                    ).show();
                });
            }
        }).start();
    }

    private void openPairBrowser(String url) {
        try {
            Intent target = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            Intent chooser = Intent.createChooser(
                    target,
                    "اختر المتصفح الذي يظهر فيه اشتراك SubHub"
            );
            startActivity(chooser);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "تعذر فتح المتصفح", Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePairIntent(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if (!"subhub".equalsIgnoreCase(data.getScheme())) return;
        if (!"paired".equalsIgnoreCase(data.getHost())) return;

        String pairId = data.getQueryParameter("pairId");
        if (pairId == null || pairId.trim().isEmpty()) return;
        pairId = pairId.trim();
        nativePrefs.edit().putString(KEY_PENDING_PAIR, pairId).apply();
        checkPairStatus(pairId, true);
    }

    private void checkPendingPair(boolean showFeedback) {
        if (nativePrefs == null || isNativeAppPaired()) return;
        String pairId = nativePrefs.getString(KEY_PENDING_PAIR, "");
        if (pairId == null || pairId.isEmpty()) return;
        checkPairStatus(pairId, showFeedback);
    }

    private void checkPairStatus(String pairId, boolean showFeedback) {
        if (pairStatusBusy) return;
        pairStatusBusy = true;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("appDeviceId", appDeviceId());
                body.put("appSecret", appSecret());
                body.put("pairId", pairId);

                JSONObject res = postWorkerJson("/pair/status", body);
                boolean paired = res.optBoolean("ok", false)
                        && res.optBoolean("paired", false);

                ui.post(() -> {
                    pairStatusBusy = false;
                    if (paired) {
                        nativePrefs.edit()
                                .putBoolean(KEY_APP_PAIRED, true)
                                .remove(KEY_PENDING_PAIR)
                                .apply();
                        if (showFeedback) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "تم ربط التطبيق بالاشتراك ✅",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                        syncNativeSubscription(false);
                    }
                });
            } catch (Exception e) {
                ui.post(() -> pairStatusBusy = false);
            }
        }).start();
    }

    private void syncNativeSubscription(boolean showFeedback) {
        if (!isNativeAppPaired()) {
            if (showFeedback) {
                Toast.makeText(this, "اربط التطبيق باشتراكك أولاً", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (sessionBusy) return;
        sessionBusy = true;

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("appDeviceId", appDeviceId());
                body.put("appSecret", appSecret());

                JSONObject res = postWorkerJson("/app/session", body);
                if (!res.optBoolean("ok", false)) {
                    throw new Exception(res.optString("error", "session-failed"));
                }

                ui.post(() -> {
                    sessionBusy = false;
                    pendingNativeSession = res;
                    injectNativeSessionIfReady();
                    if (showFeedback) {
                        Toast.makeText(
                                MainActivity.this,
                                "تمت مزامنة الاشتراك ✅",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            } catch (Exception e) {
                ui.post(() -> {
                    sessionBusy = false;
                    if (showFeedback) {
                        Toast.makeText(
                                MainActivity.this,
                                "تعذرت مزامنة الاشتراك الآن",
                                Toast.LENGTH_SHORT
                        ).show();
                    }
                });
            }
        }).start();
    }

    private void injectNativeSessionIfReady() {
        if (!homePageReady || webView == null || pendingNativeSession == null) return;

        JSONObject d = pendingNativeSession;
        pendingNativeSession = null;

        String webDeviceId = d.optString("webDeviceId", "");
        String code = d.optString("code", "");
        long expiresAt = d.optLong("expiresAt", 0L);
        String tier = "vip".equalsIgnoreCase(d.optString("tier", "")) ? "vip" : "free";

        if (webDeviceId.isEmpty() || code.isEmpty() || expiresAt <= System.currentTimeMillis()) {
            return;
        }

        String js =
                "(function(){try{"
                        + "localStorage.setItem('subhub_device_id'," + JSONObject.quote(webDeviceId) + ");"
                        + "localStorage.setItem('subhub_code'," + JSONObject.quote(code) + ");"
                        + "localStorage.setItem('subhub_unlock_until'," + JSONObject.quote(String.valueOf(expiresAt)) + ");"
                        + "localStorage.setItem('subhub_tier'," + JSONObject.quote(tier) + ");"
                        + "localStorage.setItem('subhub_subcheck_at',String(Date.now()));"
                        + "if(typeof refreshAfterSubscribe==='function'){try{refreshAfterSubscribe();}catch(e){}}"
                        + "setTimeout(function(){location.replace(" + JSONObject.quote(HOME_URL) + ");},80);"
                        + "}catch(e){location.replace(" + JSONObject.quote(HOME_URL) + ");}})();";

        webView.evaluateJavascript(js, null);
    }

    private JSONObject postWorkerJson(String path, JSONObject body) throws Exception {
        return workerJson(path, body);
    }

    private JSONObject workerJson(String path, JSONObject body) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(UPDATES_WORKER_URL + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod(body == null ? "GET" : "POST");
            conn.setDoOutput(body != null);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream os = conn.getOutputStream()) { os.write(bytes); }
            }

            int status = conn.getResponseCode();
            InputStream in = (status >= 200 && status < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            StringBuilder raw = new StringBuilder();
            if (in != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                )) {
                    String line;
                    while ((line = br.readLine()) != null) raw.append(line);
                }
            }

            JSONObject out;
            try {
                out = raw.length() == 0 ? new JSONObject() : new JSONObject(raw.toString());
            } catch (Exception ignored) {
                out = new JSONObject();
            }
            out.put("_http", status);
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private JSONObject cachedUpdate() {
        try { return new JSONObject(nativePrefs.getString(KEY_UPDATE_META, "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    private boolean newerUpdate(JSONObject d) {
        return d.optBoolean("ok", false)
                && getPackageName().equals(d.optString("packageName", ""))
                && d.optInt("versionCode", 0) > NATIVE_VERSION_CODE;
    }

    private String nativeUpdateState() {
        JSONObject d = cachedUpdate();
        try {
            JSONObject out = new JSONObject();
            out.put("available", newerUpdate(d));
            out.put("versionName", d.optString("versionName", ""));
            out.put("currentVersion", NATIVE_VERSION);
            out.put("busy", updateDownloadBusy);
            return out.toString();
        } catch (Exception e) { return "{}"; }
    }

    private void notifyUpdateState() {
        if (isFinishing() || isDestroyed() || webView == null || !homePageReady) return;
        Uri page = Uri.parse(webView.getUrl() == null ? "" : webView.getUrl());
        if (!"https".equals(page.getScheme()) || !HOME_HOST.equalsIgnoreCase(page.getHost())) return;
        webView.evaluateJavascript("window.dispatchEvent(new Event('subhub-update-state'));", null);
    }

    private void checkNativeUpdateIfDue() {
        if (nativePrefs == null || updateCheckBusy) return;
        long now = System.currentTimeMillis();
        long last = nativePrefs.getLong(KEY_UPDATE_CHECK, 0L);
        if (!UpdatePolicy.isDue(now, last)) return;
        // Persist BEFORE starting the request: failures and restarts cannot cause a request loop.
        if (!nativePrefs.edit().putLong(KEY_UPDATE_CHECK, now).commit()) return;
        updateCheckBusy = true;
        new Thread(() -> {
            try {
                JSONObject d = workerJson("/latest?current=" + NATIVE_VERSION_CODE, null);
                if (d.optBoolean("ok", false) && getPackageName().equals(d.optString("packageName", ""))) {
                    nativePrefs.edit().putString(KEY_UPDATE_META, d.toString()).apply();
                }
            } catch (Exception ignored) {
                // Keep any known update; retry only on a later foreground visit after 24 hours.
            } finally {
                ui.post(() -> { updateCheckBusy = false; notifyUpdateState(); });
            }
        }, "SubHub-update-check").start();
    }

    private void downloadNativeUpdate() {
        if (updateDownloadBusy || !newerUpdate(cachedUpdate())) return;
        if (!isNativeAppPaired()) {
            Toast.makeText(this, "اربط اشتراكك بالتطبيق أولاً لتنزيل التحديث", Toast.LENGTH_LONG).show();
            return;
        }
        updateDownloadBusy = true;
        notifyUpdateState();
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("appDeviceId", appDeviceId());
                body.put("appSecret", appSecret());
                JSONObject d = postWorkerJson("/update", body);
                if (!d.optBoolean("ok", false)) throw new Exception(d.optString("error", "update-failed"));
                if (d.optInt("versionCode", 0) <= NATIVE_VERSION_CODE) {
                    nativePrefs.edit().remove(KEY_UPDATE_META).apply();
                    ui.post(() -> Toast.makeText(this, "أنت تستخدم أحدث إصدار", Toast.LENGTH_SHORT).show());
                    return;
                }
                String download = d.optString("downloadUrl", "");
                Uri link = Uri.parse(download);
                Uri worker = Uri.parse(UPDATES_WORKER_URL);
                if (!"https".equals(link.getScheme()) || !worker.getHost().equals(link.getHost())
                        || link.getPort() != -1 || link.getUserInfo() != null
                        || !"/download".equals(link.getPath())) throw new Exception("bad-download");
                ui.post(() -> { if (!isFinishing() && !isDestroyed()) openExternal(download); });
            } catch (Exception e) {
                ui.post(() -> {
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(this,
                            "تعذر تنزيل التحديث. تحقق من اتصالك وصلاحية اشتراكك ثم حاول مجدداً", Toast.LENGTH_LONG).show();
                });
            } finally {
                ui.post(() -> { updateDownloadBusy = false; notifyUpdateState(); });
            }
        }, "SubHub-update-download").start();
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
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePairIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNativeUpdateIfDue();
        ui.postDelayed(() -> checkPendingPair(false), 900L);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);

        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.destroy();
        }

        super.onDestroy();
    }

    public final class NativeBridge {
        @JavascriptInterface
        public String getUpdateState() { return nativeUpdateState(); }

        @JavascriptInterface
        public String getNativeVersion() {
            return NATIVE_VERSION;
        }

        @JavascriptInterface
        public boolean isAppPaired() {
            return isNativeAppPaired();
        }

        @JavascriptInterface
        public void startPairing() {
            ui.post(MainActivity.this::startPairingFlow);
        }

        @JavascriptInterface
        public void syncSubscription() {
            ui.post(() -> syncNativeSubscription(true));
        }


        @JavascriptInterface
        public void downloadUrl(String token, String url, String suggestedName) {
            if (!downloadBridgeToken.equals(token)) return;
            ui.post(() -> {
                if (!isTrustedHomePage()) return;
                handleDownloadRequest(
                        url,
                        suggestedName,
                        webView == null ? "" : webView.getSettings().getUserAgentString(),
                        ""
                );
            });
        }

        @JavascriptInterface
        public void saveDataUrl(String token, String dataUrl, String suggestedName) {
            if (!downloadBridgeToken.equals(token)) return;
            if (dataUrl == null || dataUrl.length() > 24 * 1024 * 1024) return;
            ui.post(() -> {
                if (!isTrustedHomePage()) return;
                new Thread(
                        () -> saveDataUrlInternal(dataUrl, suggestedName),
                        "SubHub-blob-download"
                ).start();
            });
        }

        @JavascriptInterface
        public void downloadFailed(String token) {
            if (!downloadBridgeToken.equals(token)) return;
            ui.post(() -> {
                if (isTrustedHomePage() && !isFinishing() && !isDestroyed()) {
                    Toast.makeText(
                            MainActivity.this,
                            "تعذر تنزيل الترجمة",
                            Toast.LENGTH_LONG
                    ).show();
                }
            });
        }

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

