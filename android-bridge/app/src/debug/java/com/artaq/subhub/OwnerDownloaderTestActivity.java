package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Debug-only browser: observes URLs requested by the embedded WebView.
 * No decryption, interception of response bodies, or DRM circumvention.
 * Some players use blob/MSE or external browsers and will not be detected.
 */
public final class OwnerDownloaderTestActivity extends Activity {
    private final Object foundLock = new Object();
    private final LinkedHashSet<String> found = new LinkedHashSet<>();
    private WebView browser;
    private TextView status;
    private FrameLayout browserHost;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("رابط صفحة فيديو https://...");
        root.addView(address);
        LinearLayout actions = new LinearLayout(this);
        Button open = new Button(this);
        open.setText("فتح الموقع");
        actions.addView(open, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button inspect = new Button(this);
        inspect.setText("فحص الفيديو");
        actions.addView(inspect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(actions);

        status = new TextView(this);
        status.setText("افتح موقعاً، شغّل الفيديو ثم اضغط فحص الفيديو. روابط M3U8 وMP4 المكتشفة فقط.");
        root.addView(status);
        browserHost = new FrameLayout(this);
        browser = new WebView(this);
        browserHost.addView(browser, new FrameLayout.LayoutParams(-1, -1));
        root.addView(browserHost, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        browser.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null) remember(request.getUrl().toString());
                return null; // Observe only; let WebView handle all network requests.
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                browser.setVisibility(View.GONE);
                browserHost.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });
        open.setOnClickListener(v -> {
            String url = address.getText().toString().trim();
            if (!url.startsWith("https://") && !url.startsWith("http://")) {
                status.setText("أدخل رابط موقع HTTP أو HTTPS صالحاً");
                return;
            }
            synchronized (foundLock) { found.clear(); }
            status.setText("جاري فتح الصفحة... شغّل الفيديو أولاً");
            browser.loadUrl(url);
        });
        inspect.setOnClickListener(v -> showCandidates());
    }

    private void remember(String raw) {
        try {
            URI uri = URI.create(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) return;
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (!(path.endsWith(".m3u8") || path.endsWith(".mp4"))) return;
            synchronized (foundLock) {
                if (found.size() >= 60) return;
                found.add(raw);
                int count = found.size();
                runOnUiThread(() -> status.setText("تم رصد " + count + " رابط فيديو محتمل. اضغط فحص الفيديو"));
            }
        } catch (Exception ignored) {}
    }

    private void showCandidates() {
        List<String> links;
        synchronized (foundLock) { links = new ArrayList<>(found); }
        if (links.isEmpty()) {
            status.setText("لم يُكتشف رابط مدعوم بعد. شغّل الفيديو وانتظر قليلاً. قد يستخدم المشغل blob أو حماية أو رابطاً غير ظاهر.");
            return;
        }
        String[] labels = new String[links.size()];
        for (int i = 0; i < links.size(); i++) {
            String link = links.get(i);
            String host;
            try { host = URI.create(link).getHost(); } catch (Exception e) { host = "رابط"; }
            labels[i] = (i + 1) + ". " + host + " — " + (link.toLowerCase(Locale.ROOT).contains(".m3u8") ? "HLS" : "MP4");
        }
        new AlertDialog.Builder(this)
            .setTitle("روابط الفيديو المكتشفة")
            .setItems(labels, (dialog, index) -> {
                String selected = links.get(index);
                OwnerVideoDownloadDialog.show(this, selected);
            }).setNegativeButton("إغلاق", null).show();
    }

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        browserHost.removeView(fullscreenView);
        fullscreenView = null;
        browser.setVisibility(View.VISIBLE);
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
    }

    @Override public void onBackPressed() {
        if (fullscreenView != null) exitFullscreen();
        else if (browser != null && browser.canGoBack()) browser.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (browser != null) {
            browser.stopLoading();
            browser.destroy();
        }
        super.onDestroy();
    }
}
