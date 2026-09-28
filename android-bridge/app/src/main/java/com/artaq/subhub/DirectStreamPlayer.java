package com.artaq.subhub;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Working 322.3.36 direct-stream player with UI polish only.
 * Playback/capture remains isolated from R2/VidSrc.
 */
@androidx.media3.common.util.UnstableApi
public final class DirectStreamPlayer {
    public interface Listener { void closed(); void subtitleRequested(int index); }

    private static final String PREFS = "subhub_direct_stream_ui_v1";
    private static final int PANEL = Color.rgb(8, 24, 38);
    private static final int PANEL_2 = Color.rgb(12, 38, 58);
    private static final int BORDER = Color.rgb(34, 83, 116);
    private static final int GOLD = Color.rgb(247, 188, 61);

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final TextView status;
    private final SubtitleTextView subtitle;
    private final LinearLayout toolbar;
    private final FrameLayout panel;
    private final LinearLayout panelBody;
    private final TextView panelTitle;
    private final JSONArray catalog;
    private final String source;
    private final SharedPreferences prefs;

    private WebView probe;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean closed;
    private boolean playing;
    private boolean captions = true;
    private int selectedSubtitle = -1;
    private JSONArray cues = new JSONArray();
    private String candidate;
    private Map<String,String> candidateHeaders;
    private long subtitleOffsetMs;
    private int subtitlePosition;
    private int subtitleSizeSp;
    private boolean subtitleBackground;
    private int subtitleColorIndex;
    private int resizeMode;

    private static final int[] SUBTITLE_COLORS = new int[] {
            Color.WHITE,
            0xFFFFD54F,
            0xFF7FDBFF,
            0xFFA5FFB5
    };

    private static final String[] SUBTITLE_COLOR_NAMES = new String[] {
            "أبيض", "أصفر", "سماوي", "أخضر فاتح"
    };
    private int previousSystemUi;
    private boolean panelOpen;

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.source = source;
        this.catalog = catalog;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);

        subtitleOffsetMs = prefs.getLong("offset_ms", 0L);
        subtitlePosition = prefs.getInt("position", 12);
        subtitleSizeSp = prefs.getInt("size_sp", 26);
        subtitleBackground = prefs.getBoolean("background", true);
        subtitleColorIndex = Math.max(
                0,
                Math.min(SUBTITLE_COLORS.length - 1, prefs.getInt("color_index", 0))
        );
        resizeMode = prefs.getInt("resize_mode", 0);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        parent.addView(root, new FrameLayout.LayoutParams(-1, -1));

        previousSystemUi = activity.getWindow().getDecorView().getSystemUiVisibility();
        enterImmersive();

        subtitle = new SubtitleTextView(activity);
        subtitle.setTextColor(SUBTITLE_COLORS[subtitleColorIndex]);
        subtitle.setTextSize(subtitleSizeSp);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        subtitle.setShadowLayer(dp(1), 0, 0, Color.BLACK);
        subtitle.setLineSpacing(0, 1.05f);
        subtitle.setPadding(dp(12), dp(5), dp(12), dp(5));
        subtitle.setVisibility(View.GONE);
        applySubtitleBackground();
        FrameLayout.LayoutParams subLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subLp.leftMargin = dp(16);
        subLp.rightMargin = dp(16);
        root.addView(subtitle, subLp);
        installSubtitleGesture();

        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        status.setBackgroundColor(0xcc0b1725);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP);
        sp.topMargin = dp(58);
        root.addView(status, sp);

        toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        tool("⋮", 25, this::toggleQuickSettings);
        FrameLayout.LayoutParams toolsLp =
                new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.TOP | Gravity.END);
        toolsLp.topMargin = dp(10);
        toolsLp.rightMargin = dp(12);
        root.addView(toolbar, toolsLp);

        panel = new FrameLayout(activity);
        panel.setBackground(round(PANEL, BORDER, 1, 20));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setElevation(dp(16));

        LinearLayout panelOuter = new LinearLayout(activity);
        panelOuter.setOrientation(LinearLayout.VERTICAL);
        panelOuter.setPadding(dp(10), dp(8), dp(10), dp(10));
        panel.addView(panelOuter, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        panelTitle = new TextView(activity);
        panelTitle.setTextColor(Color.WHITE);
        panelTitle.setTextSize(17);
        panelTitle.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        panelTitle.setTextDirection(View.TEXT_DIRECTION_RTL);
        panelTitle.setPadding(dp(8), 0, dp(8), 0);
        header.addView(panelTitle, new LinearLayout.LayoutParams(0, dp(42), 1f));
        panelOuter.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));

        panelBody = new LinearLayout(activity);
        panelBody.setOrientation(LinearLayout.VERTICAL);
        panelBody.setGravity(Gravity.CENTER);
        panelOuter.addView(panelBody, new LinearLayout.LayoutParams(-1, 0, 1f));

        FrameLayout.LayoutParams panelLp =
                new FrameLayout.LayoutParams(dp(760), dp(112), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        panelLp.topMargin = dp(72);
        root.addView(panel, panelLp);

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            subtitle.setMaxWidth(Math.max(dp(160), root.getWidth() - dp(36)));
            positionSubtitle();
            if (!closed) enterImmersive();
        });
        root.setOnSystemUiVisibilityChangeListener(v -> {
            if (!closed) handler.postDelayed(this::enterImmersive, 220);
        });

        beginCapture();
        handler.post(tick);
    }

    private int dp(int n) {
        return Math.round(n * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(int color, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private void enterImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.systemBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    );
                }
            } else {
                activity.getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                );
            }
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } catch (Exception ignored) {}
    }

    private void exitImmersive() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) controller.show(WindowInsets.Type.systemBars());
            }
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            activity.getWindow().getDecorView().setSystemUiVisibility(previousSystemUi);
        } catch (Exception ignored) {}
    }

    private void tool(String text, float sizeSp, Runnable action) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setBackground(round(0x660A1725, 0x77406480, 1, 26));
        v.setOnClickListener(x -> {
            enterImmersive();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(50));
        lp.setMargins(dp(3), 0, dp(3), 0);
        toolbar.addView(v, lp);
    }

    private TextView chip(String text, float sizeSp, Runnable action) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        v.setPadding(dp(10), 0, dp(10), 0);
        v.setBackground(round(0xD90B1D2C, 0x88406480, 1, 12));
        v.setOnClickListener(x -> {
            enterImmersive();
            action.run();
        });
        return v;
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

        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -1);
        p.topMargin = dp(60);
        root.addView(probe, 0, p);
        probe.loadUrl(source);

        handler.postDelayed(() -> {
            if (!closed && !playing) {
                message("لم يُلتقط بث بعد. اضغط تشغيل المصدر، أو أغلق وأعد المحاولة.");
            }
        }, 45000);
    }

    private void capture(String url, Map<String,String> headers) {
        if (closed || playing) return;
        boolean first = candidate == null;
        if (first || Uri.parse(url).getLastPathSegment()
                .toLowerCase(Locale.ROOT).contains("master")) {
            candidate = url;
            candidateHeaders = headers;
        }
        if (first) {
            handler.postDelayed(() -> {
                if (!closed && !playing) startStream();
            }, 1400);
        }
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

        DefaultHttpDataSource.Factory http =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent(userAgent)
                        .setDefaultRequestProperties(headers);

        ResolvingDataSource.Factory data =
                new ResolvingDataSource.Factory(http, spec -> {
                    Map<String,String> scoped = new HashMap<>(spec.httpRequestHeaders);
                    String cookies = CookieManager.getInstance().getCookie(spec.uri.toString());
                    if (cookies != null && !cookies.isEmpty()) scoped.put("Cookie", cookies);
                    return spec.withRequestHeaders(scoped);
                });

        player = new ExoPlayer.Builder(activity).build();
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
        );
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                true
        );

        playerView = new PlayerView(activity);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        playerView.setUseController(true);
        applyResizeMode();

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -1);
        pp.topMargin = 0;
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
        message("جارٍ تشغيل البث المباشر…");
        enterImmersive();
    }

    private void toggleQuickSettings() {
        if (panelOpen) {
            hidePanel();
        } else {
            showQuickSettings();
        }
    }

    public boolean handleBack() {
        if (panelOpen) {
            hidePanel();
            return true;
        }
        return false;
    }

    private void showQuickSettings() {
        panelTitle.setText("أدوات");
        panelBody.removeAllViews();

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);

        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);

        addQuick(row, "✕", this::close);
        addQuick(row, "HD", this::quality);
        addQuick(row, "CC", this::chooseSubtitle);
        addQuick(row, "A−", () -> adjustSubtitleSize(-2));
        addQuick(row, "A+", () -> adjustSubtitleSize(2));
        addQuick(row, "↑", () -> adjustSubtitlePosition(4));
        addQuick(row, "↓", () -> adjustSubtitlePosition(-4));
        addQuick(row, "−.5", () -> adjustSync(500));
        addQuick(row, "+.5", () -> adjustSync(-500));
        addQuick(row, "▣", () -> {
            subtitleBackground = !subtitleBackground;
            prefs.edit().putBoolean("background", subtitleBackground).apply();
            applySubtitleBackground();
        });
        addQuick(row, "🎨", this::cycleSubtitleColor);
        addQuick(row, "▭", this::cycleResizeMode);

        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, dp(46)));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, dp(42)));
        showPanel(false);
    }

    private void addQuick(LinearLayout row, String text, Runnable action) {
        TextView v = chip(text, 14, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(58), dp(42));
        lp.setMargins(dp(3), dp(2), dp(3), dp(2));
        row.addView(v, lp);
    }

    private void showPanel(boolean listPanel) {
        panelOpen = true;

        boolean landscape = root.getWidth() > root.getHeight();
        int maxW = Math.max(dp(260), root.getWidth() - dp(24));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(72);
        lp.bottomMargin = 0;
        lp.leftMargin = 0;
        lp.rightMargin = 0;

        if (listPanel) {
            lp.width = Math.min(maxW, landscape ? dp(500) : maxW);
            lp.height = Math.min(
                    Math.max(dp(190), root.getHeight() - dp(120)),
                    landscape ? dp(280) : dp(360)
            );
        } else {
            lp.width = Math.min(maxW, landscape ? dp(820) : maxW);
            lp.height = dp(108);
        }

        panel.setLayoutParams(lp);
        panel.setVisibility(View.VISIBLE);
        positionSubtitle();
        enterImmersive();
    }

    private void hidePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        positionSubtitle();
        enterImmersive();
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
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(i);
                labels.add(f.height > 0
                        ? String.format(new Locale("ar"), "%d بكسل", f.height)
                        : "جودة المصدر");
                choices.add(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
            }
        }

        panelTitle.setText("الجودة");
        panelBody.removeAllViews();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(12));

        for (int i = 0; i < labels.size(); i++) {
            final int which = i;
            TextView item = chip(labels.get(i), 15, () -> {
                if (player == null) return;
                androidx.media3.common.TrackSelectionParameters.Builder b =
                        player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
                if (choices.get(which) != null) b.setOverrideForType(choices.get(which));
                player.setTrackSelectionParameters(b.build());
                hidePanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.setMargins(0, dp(2), 0, dp(2));
            list.addView(item, lp);
        }

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
    }

    private void chooseSubtitle() {
        panelTitle.setText("ترجمة SubHub");
        panelBody.removeAllViews();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(12));

        TextView off = chip("بدون ترجمة", 15, () -> {
            selectedSubtitle = -1;
            captions = false;
            cues = new JSONArray();
            subtitle.setText("");
            subtitle.setVisibility(View.GONE);
            hidePanel();
        });
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(-1, dp(42));
        offLp.setMargins(0, dp(2), 0, dp(2));
        list.addView(off, offLp);

        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            String name = o == null
                    ? "ترجمة SubHub"
                    : o.optString("name", "ترجمة SubHub");
            final int index = i;
            TextView item = chip(name, 15, () -> {
                selectedSubtitle = index;
                captions = true;
                cues = new JSONArray();
                subtitle.setText("");
                listener.subtitleRequested(index);
                hidePanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.setMargins(0, dp(2), 0, dp(2));
            list.addView(item, lp);
        }

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
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

    private void adjustSubtitleSize(int delta) {
        subtitleSizeSp = Math.max(14, Math.min(54, subtitleSizeSp + delta));
        subtitle.setTextSize(subtitleSizeSp);
        prefs.edit().putInt("size_sp", subtitleSizeSp).apply();
    }

    private void adjustSubtitlePosition(int delta) {
        subtitlePosition = Math.max(2, Math.min(72, subtitlePosition + delta));
        prefs.edit().putInt("position", subtitlePosition).apply();
        positionSubtitle();
    }

    private void adjustSync(long deltaMs) {
        subtitleOffsetMs += deltaMs;
        prefs.edit().putLong("offset_ms", subtitleOffsetMs).apply();
    }

    private void applySubtitleBackground() {
        subtitle.setLineBackgroundEnabled(subtitleBackground);
    }

    private void cycleSubtitleColor() {
        subtitleColorIndex = (subtitleColorIndex + 1) % SUBTITLE_COLORS.length;
        int color = SUBTITLE_COLORS[subtitleColorIndex];
        subtitle.setTextColor(color);
        prefs.edit().putInt("color_index", subtitleColorIndex).apply();

        message("لون الترجمة: " + SUBTITLE_COLOR_NAMES[subtitleColorIndex]);
        handler.postDelayed(() -> {
            if (!closed) status.setVisibility(View.GONE);
        }, 900);
    }

    private void cycleResizeMode() {
        resizeMode = (resizeMode + 1) % 3;
        prefs.edit().putInt("resize_mode", resizeMode).apply();
        applyResizeMode();
    }

    private void applyResizeMode() {
        if (playerView == null) return;
        int mode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        if (resizeMode == 1) mode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
        else if (resizeMode == 2) mode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        playerView.setResizeMode(mode);
    }

    private void positionSubtitle() {
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) subtitle.getLayoutParams();
        int base = Math.max(dp(58), root.getHeight() * subtitlePosition / 100);
        p.bottomMargin = base;
        subtitle.setLayoutParams(p);
    }

    private void installSubtitleGesture() {
        final float[] startY = {0f};
        final int[] startPosition = {subtitlePosition};
        final float[] pinchDistance = {0f};
        final int[] startSize = {subtitleSizeSp};

        subtitle.setOnTouchListener((v, ev) -> {
            int action = ev.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                startY[0] = ev.getRawY();
                startPosition[0] = subtitlePosition;
                pinchDistance[0] = 0f;
                return true;
            }

            if (action == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() >= 2) {
                float dx = ev.getX(0) - ev.getX(1);
                float dy = ev.getY(0) - ev.getY(1);
                pinchDistance[0] = (float)Math.sqrt(dx * dx + dy * dy);
                startSize[0] = subtitleSizeSp;
                return true;
            }

            if (action == MotionEvent.ACTION_MOVE) {
                if (ev.getPointerCount() >= 2 && pinchDistance[0] > 0f) {
                    float dx = ev.getX(0) - ev.getX(1);
                    float dy = ev.getY(0) - ev.getY(1);
                    float now = (float)Math.sqrt(dx * dx + dy * dy);
                    float ratio = now / Math.max(1f, pinchDistance[0]);
                    subtitleSizeSp = Math.max(14, Math.min(54, Math.round(startSize[0] * ratio)));
                    subtitle.setTextSize(subtitleSizeSp);
                } else if (ev.getPointerCount() == 1) {
                    float dy = startY[0] - ev.getRawY();
                    int h = Math.max(dp(180), root.getHeight());
                    subtitlePosition = Math.max(
                            2,
                            Math.min(72, startPosition[0] + Math.round(dy / h * 100f))
                    );
                    positionSubtitle();
                }
                return true;
            }

            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                prefs.edit()
                        .putInt("size_sp", subtitleSizeSp)
                        .putInt("position", subtitlePosition)
                        .apply();
                return true;
            }

            return true;
        });
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;

            String text = "";
            if (player != null && captions) {
                double time = (player.getCurrentPosition() - subtitleOffsetMs) / 1000.0;
                for (int i = 0; i < cues.length(); i++) {
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

    public void applyImmersive() {
        if (!closed) enterImmersive();
    }

    public void pause() {
        if (player != null) player.pause();
    }

    private static final class SubtitleTextView extends TextView {
        private boolean lineBackgroundEnabled;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SubtitleTextView(android.content.Context context) {
            super(context);
            setIncludeFontPadding(false);
            backgroundPaint.setColor(0x77000000);
        }

        void setLineBackgroundEnabled(boolean enabled) {
            lineBackgroundEnabled = enabled;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            if (lineBackgroundEnabled && getLayout() != null && getText() != null
                    && getText().length() > 0) {
                android.text.Layout layout = getLayout();
                float density = getResources().getDisplayMetrics().density;
                float padX = 8f * density;
                float padY = 2f * density;
                float radius = 8f * density;

                for (int i = 0; i < layout.getLineCount(); i++) {
                    float left = layout.getLineLeft(i) + getPaddingLeft();
                    float right = layout.getLineRight(i) + getPaddingLeft();
                    if (right < left) {
                        float t = left;
                        left = right;
                        right = t;
                    }
                    float top = layout.getLineTop(i) + getPaddingTop() - padY;
                    float bottom = layout.getLineBottom(i) + getPaddingTop() + padY;

                    RectF rect = new RectF(
                            Math.max(0, left - padX),
                            Math.max(0, top),
                            Math.min(getWidth(), right + padX),
                            Math.min(getHeight(), bottom)
                    );
                    canvas.drawRoundRect(rect, radius, radius, backgroundPaint);
                }
            }
            super.onDraw(canvas);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;

        handler.removeCallbacksAndMessages(null);
        hidePanel();
        destroyProbe();

        if (playerView != null) playerView.setPlayer(null);
        if (player != null) {
            player.release();
            player = null;
        }

        exitImmersive();

        ViewGroup parent = (ViewGroup) root.getParent();
        if (parent != null) parent.removeView(root);
        listener.closed();
    }
}
