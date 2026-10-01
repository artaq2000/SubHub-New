package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.LineBackgroundSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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
import java.util.UUID;

/**
 * Working 322.3.36 direct-stream player with UI polish only.
 * Playback/capture remains isolated from R2/VidSrc.
 */
@androidx.media3.common.util.UnstableApi
public final class DirectStreamPlayer {
    public interface Listener {
        void closed();
        void subtitleRequested(int index);
        void serverSelected(String key, String label, String serverPageUrl);
    }

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
    private final TextView subtitle;
    private final TextView valueToast;
    private final TextView menuButton;
    private final LinearLayout toolbar;
    private final HorizontalScrollView quickStrip;
    private final LinearLayout quickRow;
    private final LinearLayout colorStrip;
    private final FrameLayout panel;
    private final LinearLayout panelBody;
    private final TextView panelTitle;
    private final JSONArray catalog;
    private final String source;
    private final String allowedHost;
    private final String resumeKey;
    private final String preferredServerKey;
    private final String preferredServerLabel;
    private final boolean interactiveSource;
    private final boolean autoServer;
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
    private int subtitleBackgroundOpacity;
    private int subtitleColor;
    private int resizeMode;
    private int previousSystemUi;
    private boolean panelOpen;
    private boolean menuOpen;
    private boolean colorStripOpen;
    private String currentSubtitleText = "";
    private Runnable valueToastHideTask;
    private long pendingResumeMs;
    private long lastResumePersistAt;
    private String pendingServerKey = "";
    private String pendingServerLabel = "";
    private long pendingServerPickedAt = 0L;
    private boolean serverChoiceReported = false;
    private boolean serverChooserShown = false;
    private boolean serverListRequested = false;
    private String discoveredServerPageUrl = "";
    private TextView manualServerButton;
    private String resolvedMainHost = "";
    private String selectedProviderLabel = "";
    private int subscriberStartupAttempt = 0;
    private int subscriberStartupGeneration = 0;
    private int subscriberStartupPhase = 0;
    private long subscriberStartupDeadlineMs = 0L;
    private boolean playbackReady = false;
    private String subscriberStageBase = "";
    private int subscriberStageDots = 0;
    private int subscriberStageAnimationGeneration = 0;
    private final String sourceChoiceToken = UUID.randomUUID().toString().replace("-", "");

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.source = source;
        this.catalog = catalog;
        this.resumeKey = resumeKey == null ? "" : resumeKey.trim();
        this.allowedHost = allowedHost == null ? "" : allowedHost.trim().toLowerCase(Locale.ROOT);
        this.preferredServerKey = preferredServerKey == null ? "" : preferredServerKey.trim();
        this.preferredServerLabel = preferredServerLabel == null ? "" : preferredServerLabel.trim();
        this.interactiveSource = interactiveSource;
        this.autoServer = !this.interactiveSource && !this.preferredServerLabel.isEmpty();
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String resumePref = resumePrefKey();
        pendingResumeMs = resumePref.isEmpty() ? 0L : Math.max(0L, prefs.getLong(resumePref, 0L));

        subtitleOffsetMs = prefs.getLong("offset_ms", 0L);
        subtitlePosition = prefs.getInt("position", 12);
        subtitleSizeSp = prefs.getInt("size_sp", 26);
        int savedBgOpacity = prefs.getInt("background_opacity", -1);
        if (savedBgOpacity < 0) savedBgOpacity = prefs.getBoolean("background", true) ? 60 : 0;
        subtitleBackgroundOpacity = Math.max(0, Math.min(100, savedBgOpacity));
        subtitleColor = prefs.getInt("subtitle_color", Color.WHITE);
        resizeMode = prefs.getInt("resize_mode", 0);

        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        parent.addView(root, new FrameLayout.LayoutParams(-1, -1));

        previousSystemUi = activity.getWindow().getDecorView().getSystemUiVisibility();
        enterImmersive();

        subtitle = new TextView(activity);
        subtitle.setTextColor(subtitleColor);
        subtitle.setTextSize(subtitleSizeSp);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        subtitle.setShadowLayer(
                1.25f * activity.getResources().getDisplayMetrics().density,
                0, 0, Color.BLACK);
        subtitle.setLineSpacing(0, 1.04f);
        subtitle.setPadding(dp(6), dp(2), dp(6), dp(3));
        subtitle.setBackgroundColor(Color.TRANSPARENT);
        subtitle.setVisibility(View.GONE);
        FrameLayout.LayoutParams subLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        subLp.leftMargin = dp(16);
        subLp.rightMargin = dp(16);
        root.addView(subtitle, subLp);
        installSubtitleGesture();

        valueToast = new TextView(activity);
        valueToast.setTextColor(Color.WHITE);
        valueToast.setTextSize(18);
        valueToast.setGravity(Gravity.CENTER);
        valueToast.setPadding(dp(16), dp(7), dp(16), dp(8));
        valueToast.setBackground(round(0xcc081826, 0x664E718B, 1, 14));
        valueToast.setVisibility(View.GONE);
        valueToast.setElevation(dp(24));
        FrameLayout.LayoutParams valueLp =
                new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(valueToast, valueLp);

        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(interactiveSource ? 15 : 16);
        status.setGravity(Gravity.CENTER);
        status.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        status.setPadding(dp(16), 0, dp(16), 0);
        status.setElevation(dp(26));
        FrameLayout.LayoutParams sp;
        if (interactiveSource) {
            status.setBackgroundColor(0xcc0b1725);
            sp = new FrameLayout.LayoutParams(-1, dp(48), Gravity.TOP);
            sp.topMargin = dp(58);
        } else {
            status.setBackground(round(0xee0b1725, 0x884E718B, 1, 18));
            sp = new FrameLayout.LayoutParams(dp(310), dp(68),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            sp.topMargin = dp(78);
        }
        root.addView(status, sp);

        menuButton = new TextView(activity);
        menuButton.setText("⋮");
        menuButton.setTextColor(Color.WHITE);
        menuButton.setTextSize(25);
        menuButton.setGravity(Gravity.CENTER);
        menuButton.setBackground(round(0x77101823, 0x664E718B, 1, 28));
        menuButton.setOnClickListener(v -> toggleMenu());
        FrameLayout.LayoutParams menuLp =
                new FrameLayout.LayoutParams(dp(50), dp(50), Gravity.TOP | Gravity.START);
        menuLp.leftMargin = dp(10);
        menuLp.topMargin = dp(8);
        root.addView(menuButton, menuLp);

        toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER);
        toolbar.setPadding(dp(3), dp(3), dp(3), dp(3));
        toolbar.setBackground(round(0x99101823, 0x443D6B8E, 1, 22));
        tool("✕", 20, this::close);
        tool("HD", 12, this::quality);
        tool("CC", 13, this::chooseSubtitle);
        toolbar.setVisibility(View.GONE);
        FrameLayout.LayoutParams toolsLp =
                new FrameLayout.LayoutParams(-2, dp(50), Gravity.TOP | Gravity.START);
        toolsLp.topMargin = dp(8);
        toolsLp.leftMargin = dp(66);
        root.addView(toolbar, toolsLp);

        quickRow = new LinearLayout(activity);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(Gravity.CENTER_VERTICAL);
        quickRow.setPadding(dp(3), dp(2), dp(3), dp(2));
        quickRow.setBackground(round(0x99101823, 0x443D6B8E, 1, 16));
        addCompactQuick("✕", this::close);
        addCompactQuick("HD", this::quality);
        addCompactQuick("CC", this::chooseSubtitle);
        addCompactQuick("A−", () -> adjustSubtitleSize(-2));
        addCompactQuick("A+", () -> adjustSubtitleSize(2));
        addCompactQuick("↑", () -> adjustSubtitlePosition(4));
        addCompactQuick("↓", () -> adjustSubtitlePosition(-4));
        addCompactQuick("−.5", () -> adjustSync(500));
        addCompactQuick("+.5", () -> adjustSync(-500));
        addCompactQuick("◐−", () -> adjustSubtitleBackground(-15));
        addCompactQuick("◐+", () -> adjustSubtitleBackground(15));
        addCompactQuick("🎨", this::showColorOptions);
        addCompactQuick("▭", this::cycleResizeMode);

        quickStrip = new HorizontalScrollView(activity);
        quickStrip.setHorizontalScrollBarEnabled(false);
        quickStrip.setFillViewport(false);
        quickStrip.setBackgroundColor(Color.TRANSPARENT);
        quickStrip.addView(quickRow, new HorizontalScrollView.LayoutParams(-2, dp(40)));
        quickStrip.setVisibility(View.GONE);
        FrameLayout.LayoutParams quickLp =
                new FrameLayout.LayoutParams(-1, dp(42), Gravity.TOP);
        quickLp.topMargin = dp(12);
        quickLp.leftMargin = dp(66);
        quickLp.rightMargin = dp(10);
        root.addView(quickStrip, quickLp);

        colorStrip = new LinearLayout(activity);
        colorStrip.setOrientation(LinearLayout.HORIZONTAL);
        colorStrip.setGravity(Gravity.CENTER);
        colorStrip.setPadding(dp(6), dp(4), dp(6), dp(4));
        colorStrip.setBackground(round(0xb30a1b29, 0x553D6B8E, 1, 18));
        addColorDot(Color.WHITE);
        addColorDot(Color.rgb(255, 222, 89));
        addColorDot(Color.rgb(107, 224, 255));
        addColorDot(Color.rgb(168, 255, 180));
        colorStrip.setVisibility(View.GONE);
        colorStrip.setElevation(dp(20));
        FrameLayout.LayoutParams colorLp =
                new FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP | Gravity.END);
        colorLp.topMargin = dp(56);
        colorLp.rightMargin = dp(16);
        root.addView(colorStrip, colorLp);

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
        TextView closePanel = chip("✕", 18, this::hidePanel);
        header.addView(closePanel, new LinearLayout.LayoutParams(dp(46), dp(42)));
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
                new FrameLayout.LayoutParams(dp(420), dp(220), Gravity.TOP | Gravity.END);
        panelLp.topMargin = dp(68);
        panelLp.rightMargin = dp(14);
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
        v.setBackground(round(0xcc101923, Color.TRANSPARENT, 0, 24));
        v.setOnClickListener(x -> {
            enterImmersive();
            action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
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
        v.setPadding(dp(12), 0, dp(12), 0);
        v.setBackground(round(PANEL_2, BORDER, 1, 14));
        v.setOnClickListener(x -> {
            enterImmersive();
            action.run();
        });
        return v;
    }

    private void message(String text) {
        status.setVisibility(View.GONE);
        if (text == null) return;
        if (text.startsWith("تعذّر") || text.startsWith("لم يُلتقط")) {
            showTransientValue(text, 1900);
        }
    }

    private void showStage(String text) {
        if (closed || text == null || text.trim().isEmpty()) return;
        status.setText(text);
        status.setVisibility(View.VISIBLE);
        status.bringToFront();
    }

    private String startupStageText(int phase) {
        switch (phase) {
            case 1: return "جارٍ فتح صفحة السيرفرات";
            case 2: return "جارٍ اختيار السيرفر";
            case 3: return "جارٍ فتح السيرفر";
            case 4: return "جارٍ جلب رابط الفيديو";
            case 5: return "جارٍ تجهيز الفيديو";
            case 6: return "جارٍ تشغيل الفيديو";
            default: return "جارٍ التجهيز";
        }
    }

    private void showSubscriberStage(String base) {
        if (closed || interactiveSource || base == null || base.trim().isEmpty()) return;
        subscriberStageBase = base.trim();
        subscriberStageDots = 0;
        subscriberStageAnimationGeneration++;
        int animationGeneration = subscriberStageAnimationGeneration;
        renderSubscriberStage(animationGeneration);
    }

    private void renderSubscriberStage(int animationGeneration) {
        if (closed || interactiveSource || playbackReady
                || animationGeneration != subscriberStageAnimationGeneration) return;
        subscriberStageDots = (subscriberStageDots % 3) + 1;
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < subscriberStageDots; i++) dots.append("•");
        status.setText(subscriberStageBase + "  " + dots);
        status.setVisibility(View.VISIBLE);
        status.bringToFront();
        handler.postDelayed(() -> renderSubscriberStage(animationGeneration), 420L);
    }

    private void stopSubscriberStageAnimation() {
        subscriberStageAnimationGeneration++;
        subscriberStageBase = "";
        subscriberStageDots = 0;
    }

    private void startSubscriberStartupTimer(int phase) {
        if (interactiveSource || closed) return;
        subscriberStartupPhase = phase;
        long timeoutMs;
        if (phase == 4) timeoutMs = 30000L;
        else if (phase >= 5) timeoutMs = 15000L;
        else timeoutMs = 18000L;
        subscriberStartupDeadlineMs = SystemClock.elapsedRealtime() + timeoutMs;
        subscriberStartupGeneration++;
        int generation = subscriberStartupGeneration;
        showSubscriberStage(startupStageText(phase));
        handler.postDelayed(() -> subscriberStartupTick(generation), 1000L);
    }

    private void subscriberStartupTick(int generation) {
        if (closed || interactiveSource || playbackReady
                || generation != subscriberStartupGeneration) return;

        if (SystemClock.elapsedRealtime() < subscriberStartupDeadlineMs) {
            handler.postDelayed(() -> subscriberStartupTick(generation), 1000L);
            return;
        }

        if (subscriberStartupPhase <= 4 && probe != null) {
            subscriberStartupGeneration++;
            stopSubscriberStageAnimation();
            showStage("اضغط داخل شاشة الفيديو للتشغيل.");
            return;
        }

        retrySubscriberStartup();
    }

    private void cleanupPlayerForRetry() {
        if (playerView != null) {
            playerView.setPlayer(null);
            ViewGroup parent = (ViewGroup) playerView.getParent();
            if (parent != null) parent.removeView(playerView);
            playerView = null;
        }
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playing = false;
        playbackReady = false;
        candidate = null;
        candidateHeaders = null;
        resolvedMainHost = "";
        selectedProviderLabel = "";
        pendingServerKey = "";
        pendingServerLabel = "";
        pendingServerPickedAt = 0L;
        serverChoiceReported = false;
    }

    private void retrySubscriberStartup() {
        if (closed || interactiveSource || playbackReady) return;
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        if (subscriberStartupAttempt >= 2) {
            cleanupPlayerForRetry();
            destroyProbe();
            subscriberStartupPhase = 0;
            showStage("تعذّر الاتصال. حاول مرة أخرى.");
            return;
        }

        subscriberStartupAttempt++;
        showSubscriberStage("جارٍ إعادة المحاولة");
        cleanupPlayerForRetry();
        destroyProbe();
        handler.postDelayed(() -> {
            if (closed || interactiveSource) return;
            beginCapture();
        }, 450L);
    }

    private String normalizeServerLabel(String raw) {
        if (raw == null) return "";
        String v = raw.replace("⭐", "").replace("★", "").replace("☆", "")
                .replaceAll("\\s+", " ").trim();
        if (v.length() > 48) v = v.substring(0, 48).trim();
        return v;
    }

    private boolean isProviderServerLabel(String raw) {
        String v = normalizeServerLabel(raw);
        if (v.isEmpty() || v.length() > 48) return false;
        String lower = v.toLowerCase(Locale.ROOT);
        return !(lower.equals("watch now")
                || lower.equals("play")
                || lower.equals("play now")
                || lower.equals("home")
                || lower.equals("movies")
                || lower.equals("select server")
                || lower.equals("trailer")
                || lower.equals("download")
                || lower.equals("settings"));
    }

    private String serverKey(String label) {
        String v = normalizeServerLabel(label).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return v.length() > 60 ? v.substring(0, 60) : v;
    }

    private void recordProviderChoice(String rawLabel) {
        final String label = normalizeServerLabel(rawLabel);
        if (label.isEmpty() || !isProviderServerLabel(label)) return;
        final String key = serverKey(label);
        if (key.isEmpty()) return;
        if (closed || playing) return;
        selectedProviderLabel = label;
        pendingServerKey = key;
        pendingServerLabel = label;
        pendingServerPickedAt = SystemClock.elapsedRealtime();
        if (interactiveSource) {
            showStage("تم اختيار السيرفر " + label + "…");
        } else {
            startSubscriberStartupTimer(3);
        }
    }

    private void dispatchProviderTap(double normalizedX, double normalizedY) {
        if (probe == null || closed || playing || candidate != null) return;
        double nx = Math.max(0.02d, Math.min(0.98d, normalizedX));
        double ny = Math.max(0.02d, Math.min(0.98d, normalizedY));
        float x = (float) (probe.getWidth() * nx);
        float y = (float) (probe.getHeight() * ny);
        long downAt = SystemClock.uptimeMillis();

        MotionEvent down = MotionEvent.obtain(
                downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0
        );
        MotionEvent up = MotionEvent.obtain(
                downAt, downAt + 70L, MotionEvent.ACTION_UP, x, y, 0
        );
        try {
            probe.dispatchTouchEvent(down);
            probe.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void showServerChooser(String json) {
        if (closed || playing || autoServer || !interactiveSource
                || !serverListRequested || serverChooserShown) return;
        try {
            JSONArray arr = new JSONArray(json);
            ArrayList<String> names = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                String name = normalizeServerLabel(arr.optString(i));
                if (isProviderServerLabel(name) && !names.contains(name)) names.add(name);
            }
            if (names.isEmpty()) {
                serverListRequested = false;
                showStage("لم أجد السيرفرات بعد. ادخل إلى صفحة الفيلم ثم اضغط «جلب السيرفرات» مرة أخرى.");
                if (manualServerButton != null) manualServerButton.setVisibility(View.VISIBLE);
                return;
            }
            serverListRequested = false;
            serverChooserShown = true;
            showStage("اختر السيرفر الذي تريد تجربته…");
            CharSequence[] items = new CharSequence[names.size()];
            for (int i = 0; i < names.size(); i++) items[i] = names.get(i);
            new AlertDialog.Builder(activity)
                    .setTitle("سيرفرات Moviesmod")
                    .setItems(items, (dialog, which) -> {
                        serverChooserShown = false;
                        enterImmersive();
                        if (which >= 0 && which < names.size()) {
                            selectProviderServer(names.get(which));
                        }
                    })
                    .setOnCancelListener(dialog -> {
                        serverChooserShown = false;
                        enterImmersive();
                    })
                    .show();
        } catch (Exception ignored) {}
    }

    private void requestServerList() {
        if (probe == null || closed || playing || autoServer || !interactiveSource) return;
        serverListRequested = true;
        showStage("جارٍ قراءة قائمة السيرفرات…");
        try {
            probe.evaluateJavascript(providerPickerScript(), null);
            handler.postDelayed(() -> {
                if (probe == null || closed || playing) return;
                try {
                    probe.evaluateJavascript(
                            "window.__subhubRequestServerList && window.__subhubRequestServerList()",
                            null
                    );
                } catch (Exception ignored) {}
            }, 220L);
        } catch (Exception ignored) {}
    }

    private void showManualServerButton() {
        if (!interactiveSource || autoServer || closed || manualServerButton != null) return;
        manualServerButton = chip("جلب السيرفرات", 15, this::requestServerList);
        manualServerButton.setBackground(round(0xee102a40, 0xff2f93c7, 1, 18));
        manualServerButton.setElevation(dp(28));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(168), dp(46), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL
        );
        lp.bottomMargin = dp(18);
        root.addView(manualServerButton, lp);
        manualServerButton.bringToFront();
    }

    private void selectProviderServer(String rawLabel) {
        String label = normalizeServerLabel(rawLabel);
        if (probe == null || closed || playing || !isProviderServerLabel(label)) return;
        selectedProviderLabel = label;
        if (manualServerButton != null) manualServerButton.setVisibility(View.GONE);
        probe.setAlpha(interactiveSource ? 0.02f : 1.0f);
        recordProviderChoice(label);
        showStage("جارٍ فتح السيرفر " + label + "…");
        String js = "window.__subhubSelectProvider && window.__subhubSelectProvider("
                + JSONObject.quote(label) + ")";
        try { probe.evaluateJavascript(js, null); } catch (Exception ignored) {}
    }

    private void recordServerPage(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty() || rawUrl.length() > 2200) return;
        try {
            Uri u = Uri.parse(rawUrl.trim());
            String host = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || host == null) return;
            String h = host.toLowerCase(Locale.ROOT);
            if (!h.equals(allowedHost) && !h.endsWith("." + allowedHost)) return;
            discoveredServerPageUrl = u.toString();
        } catch (Exception ignored) {}
    }

    private void openResolvedProvider(String rawLabel, String rawUrl) {
        if (probe == null || closed || playing) return;
        String label = normalizeServerLabel(rawLabel);
        if (!label.isEmpty() && isProviderServerLabel(label)) {
            selectedProviderLabel = label;
            recordProviderChoice(label);
        }
        try {
            Uri u = Uri.parse(rawUrl == null ? "" : rawUrl.trim());
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return;
            String host = u.getHost().toLowerCase(Locale.ROOT);
            if (host.equals(allowedHost) || host.endsWith("." + allowedHost)) return;
            resolvedMainHost = host;
            if (interactiveSource) {
                showStage("جارٍ الاتصال بالسيرفر " +
                        (selectedProviderLabel.isEmpty() ? "" : selectedProviderLabel) + "…");
            } else {
                startSubscriberStartupTimer(4);
            }
            probe.setAlpha(interactiveSource ? 0.02f : 1.0f);
            if (!interactiveSource) root.post(this::applySubscriberProbePreviewLayout);
            Map<String,String> headers = new HashMap<>();
            headers.put("Referer", source);
            probe.loadUrl(u.toString(), headers);
        } catch (Exception ignored) {}
    }

    private final class SourceChoiceBridge {
        @JavascriptInterface
        public void serverPage(String token, String rawUrl) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> recordServerPage(rawUrl));
        }

        @JavascriptInterface
        public void servers(String token, String json) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> showServerChooser(json));
        }

        @JavascriptInterface
        public void picked(String token, String rawLabel) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> recordProviderChoice(rawLabel));
        }

        @JavascriptInterface
        public void resolved(String token, String rawLabel, String rawUrl) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> openResolvedProvider(rawLabel, rawUrl));
        }

        @JavascriptInterface
        public void autoPick(String token, String rawLabel, double normalizedX, double normalizedY) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> {
                recordProviderChoice(rawLabel);
                handler.postDelayed(
                        () -> dispatchProviderTap(normalizedX, normalizedY),
                        140L
                );
            });
        }

        @JavascriptInterface
        public void playTarget(String token, double normalizedX, double normalizedY) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> dispatchProviderTap(normalizedX, normalizedY));
        }
    }

    private String providerPickerScript() {
        String wanted = JSONObject.quote(normalizeServerLabel(preferredServerLabel));
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{"
                + "var bridgeToken=" + bridgeToken + ",wanted=" + wanted + ";"
                + "function clean(v){return String(v||'').replace(/[⭐★☆]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function norm(v){return clean(v).toLowerCase();}"
                + "function bad(t){t=norm(t);return !t||/^(watch now|play|play now|home|movies|select server|trailer|download|settings|view details)$/.test(t);}"
                + "function known(t){t=norm(t);return /^(vidsrc\\.mov|vidsrc\\.fyi|vidrock|vidnest|vidking|vidlink|vidfast|vidup|videasy|111movies|2embed|multiembed|superflix|peachify)$/.test(t);}"
                + "function serverEls(){var all=[].slice.call(document.querySelectorAll('button,a,[role=button],[data-server],[data-src],[data-url],[data-embed]'));"
                + "return all.filter(function(e){var t=clean(e.innerText||e.textContent||e.getAttribute('data-server')||'');if(bad(t)||t.length>48)return false;"
                + "if(e.hasAttribute('data-server'))return true;"
                + "if((e.hasAttribute('data-src')||e.hasAttribute('data-url')||e.hasAttribute('data-embed'))&&known(t))return true;"
                + "return known(t);});}"
                + "function sendList(){try{var names=[],seen={};serverEls().forEach(function(e){var t=clean(e.innerText||e.textContent||e.getAttribute('data-server')||'');var k=norm(t);if(k&&!seen[k]){seen[k]=1;names.push(t);}});"
                + "if(names.length&&window.SubHubSourceChoice&&window.SubHubSourceChoice.serverPage)window.SubHubSourceChoice.serverPage(bridgeToken,String(location.href||''));"
                + "if(window.SubHubSourceChoice)window.SubHubSourceChoice.servers(bridgeToken,JSON.stringify(names));}catch(_){}}"
                + "function point(e){var r=e.getBoundingClientRect(),vw=Math.max(1,innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,innerHeight||document.documentElement.clientHeight||1);return [Math.max(.02,Math.min(.98,(r.left+r.width/2)/vw)),Math.max(.02,Math.min(.98,(r.top+r.height/2)/vh))];}"
                + "function uncovered(e){try{var r=e.getBoundingClientRect(),x=Math.max(1,Math.min((innerWidth||9999)-1,r.left+r.width/2)),y=Math.max(1,Math.min((innerHeight||9999)-1,r.top+r.height/2)),top=document.elementFromPoint(x,y);return !!top&&(top===e||e.contains(top)||top.contains(e));}catch(_){return false;}}"
                + "function direct(e){var vals=[e.getAttribute('data-src'),e.getAttribute('data-url'),e.getAttribute('data-embed'),e.getAttribute('href')];"
                + "for(var i=0;i<vals.length;i++){var v=String(vals[i]||'').trim();if(/^https:\\/\\//i.test(v)&&v.indexOf(location.host)<0)return v;}return '';}"
                + "function reportFrame(label){try{var frames=[].slice.call(document.querySelectorAll('iframe[src]'));for(var i=0;i<frames.length;i++){var u=String(frames[i].src||'');if(/^https:\\/\\//i.test(u)&&u.indexOf(location.host)<0){window.SubHubSourceChoice.resolved(bridgeToken,label,u);return true;}}}catch(_){}return false;}"
                + "function activate(label){try{var want=norm(label),els=serverEls(),hit=els.find(function(e){return norm(e.innerText||e.textContent||e.getAttribute('data-server')||'')===want;});"
                + "if(!hit||!uncovered(hit))return false;var lab=clean(hit.innerText||hit.textContent||hit.getAttribute('data-server')||label);"
                + "if(window.SubHubSourceChoice)window.SubHubSourceChoice.picked(bridgeToken,lab);"
                + "var d=direct(hit);if(d){window.SubHubSourceChoice.resolved(bridgeToken,lab,d);return true;}"
                + "var obs=new MutationObserver(function(){if(reportFrame(lab)){try{obs.disconnect();}catch(_){}}});"
                + "try{obs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});}catch(_){}"
                + "try{hit.click();}catch(_){}"
                + "setTimeout(function(){if(reportFrame(lab))return;var p=point(hit);if(window.SubHubSourceChoice&&window.SubHubSourceChoice.autoPick)window.SubHubSourceChoice.autoPick(bridgeToken,lab,p[0],p[1]);},180);"
                + "for(var n=1;n<=20;n++)setTimeout(function(){reportFrame(lab);},n*300);return true;}catch(_){return false;}}"
                + "window.__subhubSelectProvider=activate;"
                + "window.__subhubRequestServerList=function(){sendList();var n=0,t=setInterval(function(){n++;sendList();if(n>=8)clearInterval(t);},350);};"
                + "sendList();"
                + "if(wanted){var tries=0,t=setInterval(function(){tries++;sendList();if(activate(wanted)||tries>=240)clearInterval(t);},500);}"
                + "}catch(_){}})();";
    }

    private String providerPlayScript() {
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{"
                + "if(window.__subhubProviderKickV3265)return;window.__subhubProviderKickV3265=true;"
                + "var bridgeToken=" + bridgeToken + ";"
                + "function vis(e){if(!e)return false;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>25&&r.height>20&&r.bottom>0&&r.right>0&&r.top<(innerHeight||99999)&&s.display!=='none'&&s.visibility!=='hidden';}"
                + "function point(e){var r=e.getBoundingClientRect(),vw=Math.max(1,innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,innerHeight||document.documentElement.clientHeight||1);return [Math.max(.02,Math.min(.98,(r.left+r.width/2)/vw)),Math.max(.02,Math.min(.98,(r.top+r.height/2)/vh))];}"
                + "function kick(){try{var vids=[].slice.call(document.querySelectorAll('video')).filter(vis);vids.forEach(function(v){try{v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(_){}});"
                + "var els=[].slice.call(document.querySelectorAll('button,a,[role=button],[class*=play],[id*=play]')).filter(vis);"
                + "var b=els.find(function(e){var t=String(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title||'').trim().toLowerCase();return /(^|\\s)(play|watch|start|continue|resume)(\\s|$)/.test(t)||/(play|triangle)/.test(String(e.className||'').toLowerCase());});"
                + "if(!b){var frames=[].slice.call(document.querySelectorAll('iframe,[class*=player],[id*=player]')).filter(function(e){if(!vis(e))return false;var r=e.getBoundingClientRect();return r.width>160&&r.height>90;});b=frames[0];}"
                + "if(b&&window.SubHubSourceChoice&&window.SubHubSourceChoice.playTarget){var p=point(b);window.SubHubSourceChoice.playTarget(bridgeToken,p[0],p[1]);}"
                + "}catch(_){}}"
                + "kick();var n=0,t=setInterval(function(){n++;kick();if(n>=14)clearInterval(t);},850);"
                + "}catch(_){}})();";
    }

    private String resumePrefKey() {
        if (resumeKey.isEmpty()) return "";
        return "resume_" + resumeKey.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void clearResumePosition() {
        String key = resumePrefKey();
        if (!key.isEmpty()) prefs.edit().remove(key).apply();
        pendingResumeMs = 0L;
    }

    private void persistResume(boolean force) {
        if (resumeKey.isEmpty() || player == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastResumePersistAt < 5000L) return;
        lastResumePersistAt = now;

        long position = Math.max(0L, player.getCurrentPosition());
        long duration = player.getDuration();
        if (player.getPlaybackState() == Player.STATE_ENDED
                || (duration > 0L && position >= Math.max(0L, duration - 45000L))) {
            clearResumePosition();
            return;
        }
        if (position < 5000L) return;
        String key = resumePrefKey();
        if (!key.isEmpty()) prefs.edit().putLong(key, position).apply();
    }

    private boolean isAllowedMainHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        boolean providerPage = !allowedHost.isEmpty()
                && (h.equals(allowedHost) || h.endsWith("." + allowedHost));
        boolean resolved = !resolvedMainHost.isEmpty()
                && (h.equals(resolvedMainHost) || h.endsWith("." + resolvedMainHost));
        return providerPage || resolved;
    }

    private void applySubscriberProbePreviewLayout() {
        if (interactiveSource || probe == null || root == null || closed) return;
        int rw = root.getWidth();
        int rh = root.getHeight();
        if (rw <= 0 || rh <= 0) {
            root.post(this::applySubscriberProbePreviewLayout);
            return;
        }

        int maxW = Math.max(dp(240), rw - dp(24));
        int maxH = Math.max(dp(150), rh - dp(210));
        int width;
        int height;

        if (rw > rh) {
            height = Math.min(maxH, rh - dp(90));
            width = Math.min(maxW, Math.max(dp(260), Math.round(height * 16f / 9f)));
            if (width >= maxW) {
                width = maxW;
                height = Math.min(maxH, Math.round(width * 9f / 16f));
            }
        } else {
            width = maxW;
            height = Math.min(maxH, Math.round(width * 9f / 16f));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max(dp(240), width),
                Math.max(dp(150), height),
                Gravity.CENTER
        );
        probe.setLayoutParams(lp);
        probe.setAlpha(1.0f);
        probe.setVisibility(View.VISIBLE);
        probe.setBackgroundColor(Color.BLACK);
        probe.bringToFront();
        status.bringToFront();
        menuButton.bringToFront();
        if (toolbar.getVisibility() == View.VISIBLE) toolbar.bringToFront();
        if (quickStrip.getVisibility() == View.VISIBLE) quickStrip.bringToFront();
        if (panel.getVisibility() == View.VISIBLE) panel.bringToFront();
    }

    private void beginCapture() {
        if (interactiveSource) {
            showStage("ادخل يدوياً إلى الفيلم وأغلق الإعلانات، ثم اضغط «جلب السيرفرات».");
        } else {
            if (subscriberStartupAttempt == 0) subscriberStartupAttempt = 1;
            if (subscriberStartupAttempt > 1) showSubscriberStage("جارٍ إعادة المحاولة");
        }
        probe = new WebView(activity);
        probe.setClickable(true);
        probe.setFocusable(true);
        probe.setFocusableInTouchMode(true);
        probe.setOnTouchListener((v, ev) -> {
            ViewParent vp = v.getParent();
            if (vp != null) vp.requestDisallowInterceptTouchEvent(true);
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                v.requestFocusFromTouch();
            }
            return false;
        });
        WebSettings s = probe.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(probe, true);
        probe.addJavascriptInterface(new SourceChoiceBridge(), "SubHubSourceChoice");
        probe.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(
                    WebView view, boolean isDialog, boolean isUserGesture,
                    android.os.Message resultMsg) {
                // Provider popups/ads never get a second WebView or external window.
                return false;
            }
        });
        probe.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (closed || v != probe) return;
                String host = "";
                try { host = Uri.parse(url).getHost(); } catch (Exception ignored) {}
                if (host != null && !resolvedMainHost.isEmpty()
                        && (host.equalsIgnoreCase(resolvedMainHost)
                        || host.toLowerCase(Locale.ROOT).endsWith("." + resolvedMainHost))) {
                    if (interactiveSource) showStage("جارٍ تشغيل السيرفر…");
                    else startSubscriberStartupTimer(4);
                    try { v.evaluateJavascript(providerPlayScript(), null); } catch (Exception ignored) {}
                } else {
                    if (interactiveSource) {
                        showStage("ادخل يدوياً إلى الفيلم وأغلق الإعلانات، ثم اضغط «جلب السيرفرات».");
                    } else {
                        startSubscriberStartupTimer(2);
                    }
                    try { v.evaluateJavascript(providerPickerScript(), null); } catch (Exception ignored) {}
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String host = u.getHost();
                if (!"https".equals(u.getScheme())) return true;
                if (!r.isForMainFrame()) return false;
                if (isAllowedMainHost(host)) return false;
                // External top-frame navigations are ads often enough that we do
                // not trust them as provider URLs. A real provider URL is accepted
                // only after we resolve it from the selected server element/iframe.
                return true;
            }

            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView v, WebResourceRequest r) {
                String url = r.getUrl().toString();
                if ("https".equals(r.getUrl().getScheme())
                        && url.toLowerCase(Locale.ROOT).contains(".m3u8")
                        && ((!interactiveSource && autoServer) || !selectedProviderLabel.isEmpty())) {
                    Map<String,String> headers = new HashMap<>(r.getRequestHeaders());
                    handler.post(() -> capture(url, headers));
                }
                return null;
            }
        });

        FrameLayout.LayoutParams p;
        if (interactiveSource) {
            p = new FrameLayout.LayoutParams(-1, -1);
            p.topMargin = dp(60);
        } else {
            p = new FrameLayout.LayoutParams(dp(320), dp(180), Gravity.CENTER);
        }
        root.addView(probe, p);

        // During this test phase the subscriber can see and touch only the
        // embedded provider/player rectangle. SubHub controls, subtitles, and
        // settings stay in their own overlay layer and are not replaced.
        probe.setAlpha(1.0f);
        probe.setBackgroundColor(Color.BLACK);
        if (!interactiveSource) {
            probe.bringToFront();
            status.bringToFront();
            menuButton.bringToFront();
        }
        probe.loadUrl(source);
        if (interactiveSource) {
            showManualServerButton();
        } else {
            root.post(this::applySubscriberProbePreviewLayout);
            startSubscriberStartupTimer(1);
        }

        handler.postDelayed(() -> {
            if (interactiveSource && !closed && !playing) {
                message("لم يُلتقط بث بعد. اختر سيرفراً آخر أو أعد المحاولة.");
            }
        }, 45000);
    }

    private void capture(String url, Map<String,String> headers) {
        if (closed || playing) return;
        if (manualServerButton != null) manualServerButton.setVisibility(View.GONE);
        boolean first = candidate == null;
        if (first || Uri.parse(url).getLastPathSegment()
                .toLowerCase(Locale.ROOT).contains("master")) {
            candidate = url;
            candidateHeaders = headers;
        }
        if (first) {
            if (interactiveSource) {
                showStage("تم العثور على البث… جارٍ تجهيز الفيديو…");
            } else {
                startSubscriberStartupTimer(5);
            }
            if (!serverChoiceReported) {
                String key = pendingServerKey;
                String label = pendingServerLabel;
                if ((key.isEmpty() || label.isEmpty()) && autoServer) {
                    key = preferredServerKey.isEmpty() ? serverKey(preferredServerLabel) : preferredServerKey;
                    label = preferredServerLabel;
                }
                long age = SystemClock.elapsedRealtime() - pendingServerPickedAt;
                if (!key.isEmpty() && !label.isEmpty() && (autoServer || (age >= 0L && age < 30000L))) {
                    serverChoiceReported = true;
                    listener.serverSelected(key, label, discoveredServerPageUrl);
                }
            }
            handler.postDelayed(() -> {
                if (!closed && !playing) startStream();
            }, interactiveSource ? 1200L : 300L);
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
        if (interactiveSource) showStage("جارٍ تشغيل الفيديو…");
        else startSubscriberStartupTimer(6);

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
                    playbackReady = true;
                    subscriberStartupGeneration++;
                    subscriberStartupPhase = 0;
                    stopSubscriberStageAnimation();
                    long duration = player == null ? 0L : player.getDuration();
                    if (pendingResumeMs > 0L && duration > 0L
                            && pendingResumeMs >= Math.max(0L, duration - 45000L)) {
                        if (player != null) player.seekTo(0L);
                        clearResumePosition();
                    }
                    status.setVisibility(View.GONE);
                    showTransientValue("تم تشغيل الفيديو", 700);
                } else if (state == Player.STATE_BUFFERING) {
                    if (interactiveSource) showStage("جارٍ تحميل الفيديو…");
                    else showSubscriberStage("جارٍ تشغيل الفيديو");
                } else if (state == Player.STATE_ENDED) {
                    clearResumePosition();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                if (!interactiveSource && subscriberStartupAttempt < 2) {
                    retrySubscriberStartup();
                } else if (!interactiveSource) {
                    subscriberStartupGeneration++;
                    subscriberStartupPhase = 0;
                    stopSubscriberStageAnimation();
                    cleanupPlayerForRetry();
                    destroyProbe();
                    showStage("تعذّر تشغيل الفيديو. حاول مرة أخرى.");
                } else {
                    message("تعذّر تشغيل البث مباشرة. أغلق وأعد المحاولة.");
                }
            }
        });

        MediaItem item = new MediaItem.Builder()
                .setUri(candidate)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();

        player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(item));
        player.prepare();
        if (pendingResumeMs >= 5000L) player.seekTo(pendingResumeMs);
        player.play();
        showStage("جارٍ تشغيل الفيديو…");
        enterImmersive();
    }

    private void toggleMenu() {
        menuOpen = !menuOpen;
        toolbar.setVisibility(View.GONE);
        quickStrip.setVisibility(menuOpen ? View.VISIBLE : View.GONE);
        if (!menuOpen) {
            hideColorStrip();
            hidePanel();
        }
        enterImmersive();
    }

    private void collapseMenu() {
        menuOpen = false;
        toolbar.setVisibility(View.GONE);
        quickStrip.setVisibility(View.GONE);
        hideColorStrip();
        hidePanel();
        enterImmersive();
    }

    private void addCompactQuick(String text, Runnable action) {
        TextView v = chip(text, 13, action);
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(36));
        lp.setMargins(dp(2), 0, dp(2), 0);
        quickRow.addView(v, lp);
    }

    private void showPanel(boolean listPanel) {
        panelOpen = true;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        boolean landscape = root.getWidth() > root.getHeight();
        int maxWidth = Math.max(dp(260), root.getWidth() - dp(28));
        lp.width = Math.min(landscape ? dp(380) : dp(420), maxWidth);
        lp.height = listPanel
                ? (landscape ? dp(220) : dp(280))
                : (landscape ? dp(190) : dp(240));
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(68);
        lp.rightMargin = dp(14);
        panel.setLayoutParams(lp);
        panel.setVisibility(View.VISIBLE);
        enterImmersive();
    }

    private void hidePanel() {
        panelOpen = false;
        panel.setVisibility(View.GONE);
        enterImmersive();
    }

    private void showColorOptions() {
        hidePanel();
        colorStripOpen = !colorStripOpen;
        refreshColorDots();
        colorStrip.setVisibility(colorStripOpen ? View.VISIBLE : View.GONE);
        enterImmersive();
    }

    private void hideColorStrip() {
        colorStripOpen = false;
        colorStrip.setVisibility(View.GONE);
    }

    private void addColorDot(int color) {
        TextView dot = new TextView(activity);
        dot.setTag(color);
        dot.setGravity(Gravity.CENTER);
        dot.setText("");
        dot.setOnClickListener(v -> {
            subtitleColor = color;
            prefs.edit().putInt("subtitle_color", subtitleColor).apply();
            subtitle.setTextColor(subtitleColor);
            applySubtitleText(currentSubtitleText);
            refreshColorDots();
            hideColorStrip();
            showTransientValue("●", 550);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMargins(dp(4), 0, dp(4), 0);
        colorStrip.addView(dot, lp);
    }

    private void refreshColorDots() {
        for (int i = 0; i < colorStrip.getChildCount(); i++) {
            View child = colorStrip.getChildAt(i);
            Object tag = child.getTag();
            if (!(tag instanceof Integer)) continue;
            int color = (Integer) tag;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(color);
            d.setStroke(dp(color == subtitleColor ? 3 : 1),
                    color == subtitleColor ? Color.WHITE : 0x667A9AB0);
            child.setBackground(d);
        }
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
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
            currentSubtitleText = "";
            applySubtitleText("");
            subtitle.setVisibility(View.GONE);
            hidePanel();
        });
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(-1, dp(40));
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
                currentSubtitleText = "";
                applySubtitleText("");
                listener.subtitleRequested(index);
                hidePanel();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
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
        showSubtitleSizePercent();
    }

    private void showSubtitleSizePercent() {
        int percent = Math.round((subtitleSizeSp / 26f) * 20f) * 5;
        showTransientValue(toArabicDigits(percent) + "٪", 750);
    }

    private void showTransientValue(String text, long durationMs) {
        valueToast.setText(text);
        valueToast.setVisibility(View.VISIBLE);
        if (valueToastHideTask != null) handler.removeCallbacks(valueToastHideTask);
        valueToastHideTask = () -> {
            if (!closed) valueToast.setVisibility(View.GONE);
        };
        handler.postDelayed(valueToastHideTask, durationMs);
    }

    private String toArabicDigits(int value) {
        String western = String.valueOf(value);
        StringBuilder out = new StringBuilder(western.length());
        String arabic = "٠١٢٣٤٥٦٧٨٩";
        for (int i = 0; i < western.length(); i++) {
            char c = western.charAt(i);
            out.append(c >= '0' && c <= '9' ? arabic.charAt(c - '0') : c);
        }
        return out.toString();
    }

    private void adjustSubtitlePosition(int delta) {
        subtitlePosition = Math.max(0, Math.min(72, subtitlePosition + delta));
        prefs.edit().putInt("position", subtitlePosition).apply();
        positionSubtitle();
    }

    private void adjustSync(long deltaMs) {
        subtitleOffsetMs += deltaMs;
        prefs.edit().putLong("offset_ms", subtitleOffsetMs).apply();
    }

    private void adjustSubtitleBackground(int delta) {
        subtitleBackgroundOpacity = Math.max(0, Math.min(100, subtitleBackgroundOpacity + delta));
        prefs.edit()
                .putInt("background_opacity", subtitleBackgroundOpacity)
                .putBoolean("background", subtitleBackgroundOpacity > 0)
                .apply();
        applySubtitleText(currentSubtitleText);
        showTransientValue("خلفية " + toArabicDigits(subtitleBackgroundOpacity) + "٪", 750);
    }

    private void applySubtitleText(String text) {
        currentSubtitleText = text == null ? "" : text;
        subtitle.setTextColor(subtitleColor);
        subtitle.setBackgroundColor(Color.TRANSPARENT);

        if (currentSubtitleText.isEmpty()) {
            subtitle.setText("");
            return;
        }

        if (subtitleBackgroundOpacity <= 0) {
            subtitle.setText(currentSubtitleText);
            return;
        }

        SpannableString styled = new SpannableString(currentSubtitleText);
        int alpha = Math.round(255f * subtitleBackgroundOpacity / 100f);
        styled.setSpan(
                new RoundedLineBackgroundSpan(
                        Color.argb(alpha, 0, 0, 0),
                        dp(8),
                        dp(7),
                        dp(2)
                ),
                0,
                styled.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        );
        subtitle.setText(styled);
    }

    private static final class RoundedLineBackgroundSpan implements LineBackgroundSpan {
        private final int color;
        private final float radius;
        private final float horizontalPadding;
        private final float verticalPadding;

        RoundedLineBackgroundSpan(int color, float radius, float horizontalPadding,
                                  float verticalPadding) {
            this.color = color;
            this.radius = radius;
            this.horizontalPadding = horizontalPadding;
            this.verticalPadding = verticalPadding;
        }

        @Override
        public void drawBackground(Canvas canvas, Paint paint, int left, int right,
                                   int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lineNumber) {
            int visibleEnd = end;
            while (visibleEnd > start) {
                char c = text.charAt(visibleEnd - 1);
                if (c == '\n' || c == '\r') visibleEnd--;
                else break;
            }
            if (visibleEnd <= start) return;

            float width = paint.measureText(text.subSequence(start, visibleEnd).toString());
            float center = (left + right) / 2f;
            float rectLeft = center - width / 2f - horizontalPadding;
            float rectRight = center + width / 2f + horizontalPadding;
            RectF rect = new RectF(
                    rectLeft,
                    top + verticalPadding,
                    rectRight,
                    bottom - verticalPadding
            );

            int oldColor = paint.getColor();
            Paint.Style oldStyle = paint.getStyle();
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, radius, radius, paint);
            paint.setStyle(oldStyle);
            paint.setColor(oldColor);
        }
    }

    private void cycleResizeMode() {
        resizeMode = (resizeMode + 1) % 3;
        prefs.edit().putInt("resize_mode", resizeMode).apply();
        applyResizeMode();
        if (resizeMode == 0) showTransientValue("▭  ملاءمة", 800);
        else if (resizeMode == 1) showTransientValue("▭  تمديد", 800);
        else showTransientValue("▭  قص", 800);
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
        int base = Math.max(dp(6), root.getHeight() * subtitlePosition / 100);
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
                            0,
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

            applySubtitleText(text);
            subtitle.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
            persistResume(false);
            handler.postDelayed(this, 100);
        }
    };

    public void applyImmersive() {
        if (!closed) enterImmersive();
    }

    public void pause() {
        persistResume(true);
        if (player != null) player.pause();
    }

    public boolean handleBack() {
        if (colorStripOpen) {
            hideColorStrip();
            return true;
        }
        if (panelOpen || menuOpen) {
            collapseMenu();
            return true;
        }
        return false;
    }

    public void close() {
        if (closed) return;
        closed = true;

        handler.removeCallbacksAndMessages(null);
        hidePanel();
        destroyProbe();

        persistResume(true);
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
