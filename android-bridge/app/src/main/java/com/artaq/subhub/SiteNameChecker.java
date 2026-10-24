package com.artaq.subhub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 322.3.122: «🩺 فحص» in «📦 أسماء fbox». Opens the names one after the other in a
 * hidden page of this app — the same way, on the same device and the same connection
 * as the player — and says what each page answered (NameCheckRules): works (with the
 * servers listed on the film page), blocked here (451, and through which Cloudflare
 * data centre, read from /cdn-cgi/trace), not found, the site's server down, no
 * answer, sends to another name, a check page, or not the site.
 *
 * The hidden page has no bridge to the app, opens no windows, shows no dialogs, loads
 * no images, plays nothing, and never leaves the name it checks. Nothing is saved
 * here: every answer goes to the site page as it comes, and the owner decides.
 */
final class SiteNameChecker {
    interface Listener {
        void onEvent(JSONObject event);
    }

    private static final long NAME_LIMIT_MS = 25000L;   // one name, at most
    private static final long SETTLE_MS = 12000L;       // after its page opened: time for its server list
    private static final long POLL_MS = 700L;
    private static final long TRACE_LIMIT_MS = 6000L;   // Cloudflare's trace after a 451
    private static final long NEXT_GAP_MS = 250L;

    private final Activity activity;
    private final FrameLayout parent;
    private final String probeScript;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<String> queue = new ArrayList<>();

    private boolean running = false;
    private String run = "";
    private String path = "/";
    private int index = -1;
    private int generation = 0;   // every name (and every stop) gets a new one: old callbacks are ignored
    private int pollGeneration = 0;

    private WebView page;
    private String name = "";
    private NameCheckRules.Facts facts = new NameCheckRules.Facts();
    private boolean tracing = false;
    private String colo = "";
    private String loc = "";
    private boolean ipv6 = false;
    private long startedAt = 0L;
    private long settleFrom = 0L;

    SiteNameChecker(Activity activity, FrameLayout parent, String probeScript, Listener listener) {
        this.activity = activity;
        this.parent = parent;
        this.probeScript = probeScript == null ? "" : probeScript;
        this.listener = listener;
    }

    boolean isRunning() {
        return running;
    }

    /** A new run (a run in progress stops first). */
    void start(String runId, List<String> names, String pagePath) {
        stop(false);
        if (names == null || names.isEmpty() || !NameCheckRules.validPath(pagePath) || probeScript.isEmpty()) return;
        run = runId == null ? "" : runId;
        path = pagePath;
        queue.clear();
        queue.addAll(names);
        index = -1;
        running = true;
        JSONObject e = event("start");
        put(e, "total", queue.size());
        put(e, "path", path);
        put(e, "net", networkKind());
        emit(e);
        next();
    }

    /** Stops the run; «done (stopped)» is reported when asked. */
    void stop(boolean report) {
        if (!running) return;
        running = false;
        generation++;
        handler.removeCallbacksAndMessages(null);
        destroyPage();
        queue.clear();
        if (report) {
            JSONObject e = event("done");
            put(e, "stopped", true);
            emit(e);
        }
    }

    private void next() {
        destroyPage();
        if (!running) return;
        if (queue.isEmpty()) {
            running = false;
            JSONObject e = event("done");
            put(e, "stopped", false);
            emit(e);
            return;
        }
        index++;
        name = queue.remove(0);
        facts = new NameCheckRules.Facts();
        facts.filmPage = !"/".equals(path);
        tracing = false;
        colo = "";
        loc = "";
        ipv6 = false;
        startedAt = SystemClock.elapsedRealtime();
        settleFrom = 0L;
        final int gen = ++generation;
        JSONObject e = event("name");
        put(e, "host", name);
        put(e, "index", index);
        emit(e);
        try {
            page = newPage(gen);
            page.loadUrl("https://" + name + path);
        } catch (Exception ex) {
            // No page could be made (WebView missing / updating): this name has no answer.
            facts.netError = -1;
            finish();
            return;
        }
        handler.postDelayed(() -> { if (gen == generation) finish(); }, NAME_LIMIT_MS);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private WebView newPage(final int gen) {
        WebView v = new WebView(activity);
        v.setAlpha(0f);
        v.setClickable(false);
        v.setFocusable(false);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);          // the sites build their pages with scripts
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);     // with onCreateWindow → false: no pop-ups at all
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBlockNetworkImage(true);          // faster and lighter: pictures are not needed
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true);
        v.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                                    Message resultMsg) {
                return false;
            }
            @Override public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                                JsPromptResult result) {
                result.cancel();
                return true;
            }
            @Override public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }
        });
        v.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest r) {
                Uri u = r == null ? null : r.getUrl();
                if (u == null || !"https".equalsIgnoreCase(u.getScheme())) return true;
                if (!r.isForMainFrame()) return false;
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (NameCheckRules.sameSite(host, name)) return false;
                // Another name while the page itself is opening (the site moved): said as it
                // is. Later — an ad, a tap — it is only stopped.
                if (gen == generation && view == page && !tracing && (r.isRedirect() || !facts.finished)) {
                    String to = NameCheckRules.cleanHost(host);
                    if (!to.isEmpty()) {
                        facts.redirectHost = to;
                        handler.post(() -> { if (gen == generation) finish(); });
                    }
                }
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (gen != generation || view != page || tracing) return;
                facts.httpStatus = 0;   // a new page of this name: its own answer counts
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest r, WebResourceResponse resp) {
                if (gen != generation || view != page || r == null || resp == null || !r.isForMainFrame() || tracing) return;
                int code = resp.getStatusCode();
                facts.httpStatus = code;
                // Not found / the site's server down: settled, no need to wait for the page.
                // 451 waits for its page to finish, then the trace says which way the
                // connection went. 403 / 503 may be a check page that goes on by itself.
                if (NameCheckRules.finalStatus(code)) handler.post(() -> { if (gen == generation) finish(); });
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest r, WebResourceError err) {
                if (gen != generation || view != page || r == null || err == null || !r.isForMainFrame()) return;
                if (!tracing) facts.netError = err.getErrorCode() == 0 ? -1 : err.getErrorCode();
                handler.post(() -> { if (gen == generation) finish(); });
            }

            // The page shows: reading starts now, not only when every ad on it has loaded.
            @Override public void onPageCommitVisible(WebView view, String url) {
                if (gen != generation || view != page || tracing || !onName(url)) return;
                if (facts.httpStatus == 451) return;   // its trace starts when it finishes
                beginReading(gen);
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (gen != generation || view != page) return;
                if (tracing) {
                    readTrace(gen);
                    return;
                }
                if (!onName(url)) return;
                facts.finished = true;
                if (facts.httpStatus == 451) {
                    startTrace(gen);
                    return;
                }
                beginReading(gen);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(360), dp(640));
        parent.addView(v, 0, lp);   // under everything: never seen, never touched
        return v;
    }

    private boolean onName(String url) {
        String host = "";
        try { host = Uri.parse(url).getHost(); } catch (Exception ignored) {}
        return NameCheckRules.sameSite(host == null ? "" : host.toLowerCase(Locale.ROOT), name);
    }

    // A page of this name is showing: read it every POLL_MS (one reading loop at a time).
    // A check page that went on to the real page: the real page gets its own time.
    private void beginReading(final int gen) {
        if (gen != generation) return;
        if (settleFrom == 0L || facts.checkPage) settleFrom = SystemClock.elapsedRealtime();
        poll(gen, ++pollGeneration);
    }

    private void poll(final int gen, final int pg) {
        if (gen != generation || pg != pollGeneration || page == null) return;
        page.evaluateJavascript(probeScript, value -> {
            if (gen != generation || pg != pollGeneration) return;
            readProbe(value);
            if (facts.blockedText) {
                startTrace(gen);
                return;
            }
            if (NameCheckRules.clearlyWorks(facts) || !facts.redirectHost.isEmpty()
                    || SystemClock.elapsedRealtime() - settleFrom >= SETTLE_MS) {
                finish();
                return;
            }
            handler.postDelayed(() -> poll(gen, pg), POLL_MS);
        });
    }

    private void readProbe(String value) {
        try {
            Object o = new JSONTokener(value == null ? "" : value).nextValue();
            if (!(o instanceof String)) return;
            JSONObject j = new JSONObject((String) o);
            // Only a page of this name counts (never a blank page between two pages).
            if (!NameCheckRules.sameSite(j.optString("host", ""), name)) return;
            if (j.optInt("len", 0) > 0) facts.finished = true;
            facts.servers = j.optInt("servers", facts.servers);
            facts.filmLinks = j.optInt("links", facts.filmLinks);
            facts.checkPage = j.optBoolean("check", false);
            if (j.optBoolean("blocked", false)) facts.blockedText = true;
            // 322.3.123: «X has a new home: Y» on the page itself — the site moved to Y.
            String moved = NameCheckRules.cleanHost(j.optString("moved", ""));
            if (!moved.isEmpty() && !NameCheckRules.sameSite(moved, name)) facts.redirectHost = moved;
        } catch (Exception ignored) {}
    }

    // After a 451: Cloudflare's own trace of this name says through which data centre
    // (colo) and as which country (loc) this connection was seen.
    private void startTrace(final int gen) {
        if (gen != generation || page == null || tracing) return;
        tracing = true;
        pollGeneration++;
        try {
            page.loadUrl("https://" + name + "/cdn-cgi/trace");
        } catch (Exception ex) {
            finish();
            return;
        }
        handler.postDelayed(() -> { if (gen == generation) finish(); }, TRACE_LIMIT_MS);
    }

    private void readTrace(final int gen) {
        if (page == null) return;
        page.evaluateJavascript("String((document.body&&document.body.innerText)||'').slice(0,2000)", value -> {
            if (gen != generation) return;
            try {
                Object o = new JSONTokener(value == null ? "" : value).nextValue();
                String text = o instanceof String ? (String) o : "";
                colo = NameCheckRules.traceValue(text, "colo");
                loc = NameCheckRules.traceValue(text, "loc");
                ipv6 = NameCheckRules.traceValue(text, "ip").contains(":");
            } catch (Exception ignored) {}
            finish();
        });
    }

    private void finish() {
        if (!running) return;
        generation++;   // anything still coming for this name is ignored
        pollGeneration++;
        handler.removeCallbacksAndMessages(null);
        JSONObject e = event("result");
        put(e, "host", name);
        put(e, "index", index);
        put(e, "state", NameCheckRules.verdict(facts));
        put(e, "code", facts.httpStatus);
        put(e, "err", facts.netError);
        put(e, "servers", Math.max(0, facts.servers));
        put(e, "links", Math.max(0, facts.filmLinks));
        put(e, "to", facts.redirectHost);
        put(e, "colo", colo);
        put(e, "loc", loc);
        put(e, "uk", NameCheckRules.ukColo(colo));
        put(e, "ipv6", ipv6);
        put(e, "ms", SystemClock.elapsedRealtime() - startedAt);
        emit(e);
        destroyPage();
        handler.postDelayed(this::next, NEXT_GAP_MS);
    }

    private void destroyPage() {
        WebView v = page;
        page = null;
        if (v == null) return;
        try { v.stopLoading(); } catch (Exception ignored) {}
        try { parent.removeView(v); } catch (Exception ignored) {}
        try { v.destroy(); } catch (Exception ignored) {}
    }

    private String networkKind() {
        try {
            ConnectivityManager cm = activity.getSystemService(ConnectivityManager.class);
            if (cm == null) return "";
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c == null) return "none";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "vpn";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "eth";
            return "other";
        } catch (Exception ignored) {
            return "";
        }
    }

    private JSONObject event(String type) {
        JSONObject e = new JSONObject();
        put(e, "type", type);
        put(e, "run", run);
        return e;
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) {}
    }

    private void emit(JSONObject e) {
        try { listener.onEvent(e); } catch (Exception ignored) {}
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
