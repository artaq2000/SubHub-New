package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Stable 322.3.36 direct-stream player, with UI polish only.
 * Playback/capture code intentionally remains independent from R2/VidSrc.
 */
@androidx.media3.common.util.UnstableApi
public final class DirectStreamPlayer {
    public interface Listener { void closed(); void subtitleRequested(int index); }

    private static final String PREFS = "subhub_direct_stream_ui_v2";
    private static final int PANEL_BG = 0xCC0A1725;
    private static final int BUTTON_BG = 0xD91B2A3A;
    private static final int BUTTON_STROKE = 0xFF355873;
    private static final int GOLD = 0xFFFFC247;

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final StyledSubtitleView subtitle;
    private final TextView status;
    private final LinearLayout toolbar;
    private final HorizontalScrollView toolsScroll;
    private final LinearLayout toolsRow;
    private final JSONArray catalog;
    private final String source;
    private final SharedPreferences prefs;

    private WebView probe;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean closed;
    private boolean playing;
    private boolean captions = true;
    private boolean toolsOpen;
    private int selectedSubtitle = -1;
    private JSONArray cues = new JSONArray();
    private String candidate;
    private Map<String,String> candidateHeaders;
    private AlertDialog dialog;

    private long subtitleOffsetMs;
    private int subtitlePosition;
    private int subtitleSizeSp;
    private int subtitleStrokeDp;
    private boolean subtitleBackground;
    private int resizeModeIndex;

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.source = source;
        this.catalog = catalog;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);

        subtitlePosition = prefs.getInt("position", 12);
        subtitleSizeSp = prefs.getInt("size_sp", 26);
        subtitleStrokeDp = prefs.getInt("stroke_dp", 1);
        subtitleBackground = prefs.getBoolean("background", false);
        subtitleOffsetMs = prefs.getLong("offset_ms", 0L);
        resizeModeIndex = prefs.getInt("resize_mode", 0);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        parent.addView(root, new FrameLayout.LayoutParams(-1, -1));
        root.setClickable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        subtitle = new StyledSubtitleView(activity);
        subtitle.setTextSize(subtitleSizeSp);
        subtitle.setTextColor(Color.WHITE);
        subtitle.setFillColor(Color.WHITE);
        subtitle.setStrokeColor(Color.BLACK);
        subtitle.setStrokeWidthDp(subtitleStrokeDp);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        subtitle.setPadding(dp(10), dp(3), dp(10), dp(4));
        subtitle.setVisibility(View.GONE);
        applySubtitleBackground();
        FrameLayout.LayoutParams subLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        subLp.leftMargin = dp(16);
        subLp.rightMargin = dp(16);
        root.addView(subtitle, subLp);
        installSubtitleDrag();

        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12), dp(5), dp(12), dp(5));
        status.setBackground(rounded(0xD9122434, 0x663D6B8E, 1, 12));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sp.topMargin = dp(62);
        root.addView(status, sp);

        toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(5), dp(4), dp(5), dp(4));
        toolbar.setBackground(rounded(PANEL_BG, 0x553D6B8E, 1, 18));
        toolbar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        topButton("✕", "إغلاق", this::close);
        topButton("⋮", "أدوات الترجمة", this::toggleTools);
        topButton("HD", "الجودة", this::quality);
        topButton("CC", "الترجمة", this::chooseSubtitle);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(54), Gravity.TOP | Gravity.START);
        tp.leftMargin = dp(10);
        tp.topMargin = dp(8);
        root.addView(toolbar, tp);

        toolsRow = new LinearLayout(activity);
        toolsRow.setOrientation(LinearLayout.HORIZONTAL);
        toolsRow.setGravity(Gravity.CENTER_VERTICAL);
        toolsRow.setPadding(dp(6), dp(4), dp(6), dp(4));
        toolsRow.setBackground(rounded(PANEL_BG, 0x553D6B8E, 1, 16));
        toolButton("A−", "تصغير الترجمة", () -> changeSubtitleSize(-2));
        toolButton("A+", "تكبير الترجمة", () -> changeSubtitleSize(2));
        toolButton("↑", "رفع الترجمة", () -> moveSubtitle(4));
        toolButton("↓", "خفض الترجمة", () -> moveSubtitle(-4));
        toolButton("−.5", "تأخير الترجمة", () -> changeOffset(500));
        toolButton("+.5", "تقديم الترجمة", () -> changeOffset(-500));
        toolButton("▣", "خلفية الترجمة", this::toggleSubtitleBackground);
        toolButton("▭", "ملاءمة / تمديد / قص", this::cycleResizeMode);

        toolsScroll = new HorizontalScrollView(activity);
        toolsScroll.setHorizontalScrollBarEnabled(false);
        toolsScroll.setFillViewport(false);
        toolsScroll.setBackgroundColor(Color.TRANSPARENT);
        toolsScroll.addView(toolsRow, new HorizontalScrollView.LayoutParams(-2, -1));
        toolsScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams toolLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58), Gravity.TOP);
        toolLp.leftMargin = dp(10);
        toolLp.rightMargin = dp(10);
        toolLp.topMargin = dp(66);
        root.addView(toolsScroll, toolLp);

        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> positionSubtitle());

        applyImmersive();
        beginCapture();
        handler.post(tick);
    }

    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, int strokeColor, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), strokeColor);
        return d;
    }

    private void topButton(String label, String description, Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setContentDescription(description);
        b.setTextColor(Color.WHITE);
        b.setTextSize("HD".equals(label) || "CC".equals(label) ? 12 : 22);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(0,0,0,0);
        b.setBackground(rounded(BUTTON_BG, BUTTON_STROKE, 1, 18));
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        lp.setMargins(dp(3), 0, dp(3), 0);
        toolbar.addView(b, lp);
    }

    private void toolButton(String label, String description, Runnable action) {
        Button b = new Button(activity);
        b.setText(label);
        b.setContentDescription(description);
        b.setTextColor(Color.WHITE);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(4),0,dp(4),0);
        b.setBackground(rounded(BUTTON_BG, BUTTON_STROKE, 1, 14));
        b.setOnClickListener(v -> {
            action.run();
            applyImmersive();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(46));
        lp.setMargins(dp(3),0,dp(3),0);
        toolsRow.addView(b, lp);
    }

    private void toggleTools() {
        toolsOpen = !toolsOpen;
        toolsScroll.setVisibility(toolsOpen ? View.VISIBLE : View.GONE);
        status.setVisibility(View.GONE);
        applyImmersive();
    }

    public void applyImmersive() {
        try {
            Window w = activity.getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        } catch (Exception ignored) {}
    }

    private void restoreSystemBars() {
        try {
            Window w = activity.getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) c.show(WindowInsets.Type.systemBars());
            }
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        } catch (Exception ignored) {}
    }

    private void applyImmersiveToDialog() {
        if (dialog == null || dialog.getWindow() == null) return;
        try {
            Window w = dialog.getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    c.hide(WindowInsets.Type.navigationBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Exception ignored) {}
    }

    private void message(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void beginCapture() {
        message("جارٍ التقاط البث… اضغط تشغيل المصدر إذا احتاج ذلك.");
        probe = new WebView(activity);
        WebSettings s = probe.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(probe, true);
        probe.setWebChromeClient(new WebChromeClient());
        probe.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost();
                return !"https".equals(u.getScheme()) || (r.isForMainFrame()
                        && !("vidsrc.to".equals(host)
                        || (host != null && host.endsWith(".vidsrc.to"))));
            }

            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView v, WebResourceRequest r) {
                String path = r.getUrl().getPath();
                if ("https".equals(r.getUrl().getScheme())
                        && path != null
                        && path.toLowerCase(Locale.ROOT).endsWith(".m3u8")) {
                    String url = r.getUrl().toString();
                    Map<String,String> headers = new HashMap<>(r.getRequestHeaders());
                    handler.post(() -> capture(url, headers));
                }
                return null;
            }
        });

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1,-1);
        root.addView(probe, 0, p);
        handler.postDelayed(() -> {
            if (!closed && !playing) {
                message("لم يُلتقط بث بعد. اضغط تشغيل المصدر، أو أغلق وأعد المحاولة.");
            }
        }, 45000);
        probe.loadUrl(source);
    }

    private void capture(String url, Map<String,String> headers) {
        if (closed || playing) return;
        boolean first = candidate == null;
        String lower = url.toLowerCase(Locale.ROOT);
        if (first || lower.contains("master") || lower.contains("playlist")) {
            candidate = url;
            candidateHeaders = headers;
        }
        if (first) handler.postDelayed(() -> {
            if (!closed && !playing) startStream();
        }, 1400);
    }

    private void destroyProbe() {
        if (probe != null) {
            probe.stopLoading();
            probe.loadUrl("about:blank");
            root.removeView(probe);
            probe.destroy();
            probe = null;
        }
    }

    private String header(String name, String fallback) {
        if (candidateHeaders != null) {
            for (Map.Entry<String,String> e : candidateHeaders.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
            }
        }
        return fallback;
    }

    private void startStream() {
        if (candidate == null || closed) return;
        playing = true;

        String userAgent = header("User-Agent", WebSettings.getDefaultUserAgent(activity));
        String referer = header("Referer", source);
        String origin = header("Origin", "");
        destroyProbe();

        Map<String,String> headers = new HashMap<>();
        headers.put("Referer", referer);
        if (!origin.isEmpty()) headers.put("Origin", origin);

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers);

        ResolvingDataSource.Factory data = new ResolvingDataSource.Factory(http, spec -> {
            Map<String,String> scoped = new HashMap<>(spec.httpRequestHeaders);
            String cookies = CookieManager.getInstance().getCookie(spec.uri.toString());
            if (cookies != null && !cookies.isEmpty()) scoped.put("Cookie", cookies);
            return spec.withRequestHeaders(scoped);
        });

        player = new ExoPlayer.Builder(activity).build();
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build());
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true);

        playerView = new PlayerView(activity);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        playerView.setControllerShowTimeoutMs(3500);
        playerView.setControllerHideOnTouch(true);
        applyResizeMode();

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1,-1);
        root.addView(playerView, 0, pp);

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    status.setVisibility(View.GONE);
                } else if (state == Player.STATE_BUFFERING) {
                    message("جارٍ تحميل البث…");
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                message("تعذّر تشغيل البث مباشرة. أغلق وأعد المحاولة.");
            }
        });

        MediaItem item = new MediaItem.Builder()
                .setUri(candidate)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();

        player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(item));
        player.prepare();
        player.play();
        applyImmersive();
    }

    private void showDialog(AlertDialog.Builder builder) {
        if (dialog != null) dialog.dismiss();
        dialog = builder.create();
        dialog.setOnShowListener(d -> applyImmersiveToDialog());
        dialog.setOnDismissListener(d -> {
            dialog = null;
            handler.postDelayed(this::applyImmersive, 80);
        });
        dialog.show();
        applyImmersiveToDialog();
    }

    private void quality() {
        if (player == null) {
            message("الجودات تظهر بعد التقاط البث.");
            return;
        }

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<TrackSelectionOverride> choices = new ArrayList<>();
        labels.add("تلقائي");
        choices.add(null);

        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i=0;i<g.length;i++) {
                if (!g.isTrackSupported(i)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(i);
                labels.add(f.height > 0 ? (f.height + "p") : "جودة المصدر");
                choices.add(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
            }
        }

        showDialog(new AlertDialog.Builder(activity)
                .setTitle("الجودة")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (player == null) return;
                    androidx.media3.common.TrackSelectionParameters.Builder b =
                            player.getTrackSelectionParameters().buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
                    if (choices.get(which) != null) {
                        b.setOverrideForType(choices.get(which));
                    }
                    player.setTrackSelectionParameters(b.build());
                }));
    }

    private void chooseSubtitle() {
        ArrayList<String> names = new ArrayList<>();
        names.add("بدون ترجمة");
        for (int i=0;i<catalog.length();i++) {
            JSONObject o = catalog.optJSONObject(i);
            names.add(o == null ? "ترجمة SubHub" : o.optString("name","ترجمة SubHub"));
        }

        showDialog(new AlertDialog.Builder(activity)
                .setTitle("ترجمة SubHub")
                .setItems(names.toArray(new String[0]), (d, index) -> {
                    selectedSubtitle = index - 1;
                    cues = new JSONArray();
                    subtitle.setText("");
                    captions = index > 0;
                    if (captions) listener.subtitleRequested(index - 1);
                }));
    }

    public void selectDefault(int index) {
        if (index >= 0 && index < catalog.length()) {
            selectedSubtitle = index;
            captions = true;
            listener.subtitleRequested(index);
        }
    }

    public void setCues(int index, JSONArray value, String error) {
        if (closed || selectedSubtitle != index) return;
        if (error != null && !error.isEmpty()) {
            message(error);
            return;
        }
        cues = value;
    }

    private void changeSubtitleSize(int delta) {
        subtitleSizeSp = Math.max(16, Math.min(52, subtitleSizeSp + delta));
        subtitle.setTextSize(subtitleSizeSp);
        prefs.edit().putInt("size_sp", subtitleSizeSp).apply();
    }

    private void moveSubtitle(int delta) {
        subtitlePosition = Math.max(2, Math.min(70, subtitlePosition + delta));
        prefs.edit().putInt("position", subtitlePosition).apply();
        positionSubtitle();
    }

    private void changeOffset(long delta) {
        subtitleOffsetMs = Math.max(-30000, Math.min(30000, subtitleOffsetMs + delta));
        prefs.edit().putLong("offset_ms", subtitleOffsetMs).apply();
        message(String.format(Locale.US, "مزامنة الترجمة: %+.1f ث", subtitleOffsetMs / 1000.0));
        handler.postDelayed(() -> {
            if (!closed) status.setVisibility(View.GONE);
        }, 900);
    }

    private void toggleSubtitleBackground() {
        subtitleBackground = !subtitleBackground;
        prefs.edit().putBoolean("background", subtitleBackground).apply();
        applySubtitleBackground();
    }

    private void applySubtitleBackground() {
        if (subtitleBackground) {
            subtitle.setBackground(rounded(0x88000000, 0x00000000, 0, 8));
        } else {
            subtitle.setBackgroundColor(Color.TRANSPARENT);
        }
    }

    private void cycleResizeMode() {
        resizeModeIndex = (resizeModeIndex + 1) % 3;
        prefs.edit().putInt("resize_mode", resizeModeIndex).apply();
        applyResizeMode();
        String name = resizeModeIndex == 0 ? "ملاءمة"
                : (resizeModeIndex == 1 ? "تمديد" : "قص");
        message("الشاشة: " + name);
        handler.postDelayed(() -> {
            if (!closed) status.setVisibility(View.GONE);
        }, 900);
    }

    private void applyResizeMode() {
        if (playerView == null) return;
        if (resizeModeIndex == 1) {
            playerView.setResizeMode(3);
        } else if (resizeModeIndex == 2) {
            playerView.setResizeMode(4);
        } else {
            playerView.setResizeMode(0);
        }
    }

    private void installSubtitleDrag() {
        final float[] startY = {0f};
        final int[] startPosition = {subtitlePosition};

        subtitle.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                startY[0] = e.getRawY();
                startPosition[0] = subtitlePosition;
                return true;
            }

            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dy = startY[0] - e.getRawY();
                int h = Math.max(dp(180), root.getHeight());
                subtitlePosition = Math.max(2, Math.min(70,
                        startPosition[0] + Math.round((dy / h) * 100f)));
                positionSubtitle();
                return true;
            }

            if (e.getActionMasked() == MotionEvent.ACTION_UP
                    || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                prefs.edit().putInt("position", subtitlePosition).apply();
                return true;
            }
            return true;
        });
    }

    private void positionSubtitle() {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
        p.bottomMargin = Math.max(dp(54), root.getHeight() * subtitlePosition / 100);
        subtitle.setLayoutParams(p);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            String text = "";

            if (player != null && captions) {
                double time = (player.getCurrentPosition() - subtitleOffsetMs) / 1000.0;
                for (int i=0;i<cues.length();i++) {
                    JSONObject c = cues.optJSONObject(i);
                    if (c != null
                            && c.optDouble("start") <= time
                            && time < c.optDouble("end")) {
                        if (!text.isEmpty()) text += "\n";
                        text += c.optString("text");
                    }
                }
            }

            subtitle.setText(text);
            subtitle.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            handler.postDelayed(this, 100);
        }
    };

    public void pause() {
        if (player != null) player.pause();
    }

    public void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacksAndMessages(null);

        if (dialog != null) dialog.dismiss();
        destroyProbe();

        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }

        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent != null) parent.removeView(root);

        restoreSystemBars();
        listener.closed();
    }

    private static final class StyledSubtitleView extends TextView {
        private int fillColor = Color.WHITE;
        private int strokeColor = Color.BLACK;
        private float strokeWidthDp = 1f;

        StyledSubtitleView(android.content.Context context) {
            super(context);
            setIncludeFontPadding(false);
        }

        void setFillColor(int color) {
            fillColor = color;
            super.setTextColor(color);
            invalidate();
        }

        void setStrokeColor(int color) {
            strokeColor = color;
            invalidate();
        }

        void setStrokeWidthDp(float value) {
            strokeWidthDp = Math.max(0f, value);
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            Paint paint = getPaint();
            Paint.Style oldStyle = paint.getStyle();
            float oldWidth = paint.getStrokeWidth();

            if (strokeWidthDp > 0f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidthDp * getResources().getDisplayMetrics().density);
                super.setTextColor(strokeColor);
                super.onDraw(canvas);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setStrokeWidth(oldWidth);
            super.setTextColor(fillColor);
            super.onDraw(canvas);

            paint.setStyle(oldStyle);
            paint.setStrokeWidth(oldWidth);
            super.setTextColor(fillColor);
        }
    }
}
