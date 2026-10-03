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
import androidx.media3.datasource.DefaultDataSource;
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
        void serverSelected(String key, String label, String serverPageUrl, String embedUrl);
        void saveRequested(long subtitleOffsetMs);
        void reopenManual(String serverPageUrl);
        // 322.3.82: owner pins the selected subtitle to this server.
        void pinRequested(int index);
        // 322.3.92: exclusive subtitles — ask JS for the 4-second piece at this time.
        void segmentRequested(int index, double time);
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
    private final SubtitlePillTextView subtitle;
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
    private final boolean ownerMode;
    private final boolean openChooser;
    // 322.3.79: saved server player URL (yellow card). When set, automatic
    // playback opens it directly instead of the Moviesmod server list.
    private final String directEmbedUrl;
    // 322.3.84: teal Vidnest card. Vidnest pages request two stream families:
    // a clean self-contained "/proxy?url=...m3u8&headers=..." link (https all the
    // way, headers embedded) and a "/hls/<base64>" chain that goes through a plain
    // http IP and fails in ExoPlayer. In this mode only the /proxy link is used
    // (the other is a late fallback), the provider page is never allowed to load
    // the held link itself, and ExoPlayer starts at once.
    private final boolean vidnestMode;
    private boolean vidnestProxyCaptured = false;
    private boolean vidnestCandidateIsProxy = false;
    private String vidnestFallbackUrl = null;
    private Map<String,String> vidnestFallbackHeaders = null;
    private int vidnestFallbackGeneration = 0;
    private static final long VIDNEST_FALLBACK_WAIT_MS = 4000L;
    // 322.3.85: the playlist is fetched once by the app before ExoPlayer starts
    // (like the Termux test that worked) to pick headers the server accepts.
    // null = not probed yet; otherwise the chosen request style.
    private VidnestPlan vidnestPlan = null;
    private String vidnestProbeReport = "";
    // Owner-only detail shown inside the «لم يعمل» bar (stays visible).
    private String failureDetail = "";
    // 322.3.86: Vidnest pages request several /proxy playlists (upcloud, megacloud…)
    // and some are broken. All of them are collected, then checked deeply
    // (playlist → first quality → first segment); the first that passes wins.
    private final ArrayList<String> vidnestCandidates = new ArrayList<>();
    private final ArrayList<Map<String,String>> vidnestCandidateHeaders = new ArrayList<>();
    private int vidnestEvaluated = 0;
    private boolean vidnestEvalScheduled = false;
    private boolean vidnestEvaluating = false;
    private int vidnestEvalWaits = 0;
    private final StringBuilder vidnestEvalReport = new StringBuilder();
    private static final long VIDNEST_COLLECT_MS = 2500L;
    private static final long VIDNEST_MORE_WAIT_MS = 5000L;
    private static final int VIDNEST_MAX_CANDIDATES = 8;
    // 322.3.87: every quality of every /proxy link is checked, like the Web Video
    // Cast list. Working qualities are rebuilt into a clean master playlist
    // (data: URI) so ExoPlayer's automatic quality never switches to a broken one.
    private final ArrayList<VidnestStream> vidnestStreams = new ArrayList<>();
    private boolean vidnestListShown = false;
    private boolean vidnestEvalPending = false;

    static final class VidnestStream {
        String host = "";
        String label = "";
        String duration = "";
        boolean ok;
        boolean adaptive;
        String problem = "";
        String playUrl = "";
        VidnestPlan plan;
        Map<String,String> headers;
    }

    private static final class VidnestPlan {
        final String name;
        final String userAgent;   // null = platform default (like a plain client)
        final boolean sendReferer;
        VidnestPlan(String name, String userAgent, boolean sendReferer) {
            this.name = name; this.userAgent = userAgent; this.sendReferer = sendReferer;
        }
    }
    private int directKickRounds = 0;
    // 322.3.80: clean SubHub cover over the hidden provider page (direct mode only).
    private FrameLayout directCover;
    private TextView directCoverStage;
    private final ArrayList<android.animation.Animator> coverAnimators = new ArrayList<>();
    private int directRevealGeneration = 0;
    private boolean chooserAutoRequested = false;
    private boolean decisionShown = false;
    private boolean reopening = false;
    private LinearLayout decisionBar;
    private TextView decisionText;
    private TextView decisionSave;
    private final SharedPreferences prefs;

    private WebView probe;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean closed;
    private boolean playing;
    private boolean captions = true;
    private int selectedSubtitle = -1;
    // 322.3.92: segment (exclusive) subtitle state, driven by the player clock.
    private boolean segmentMode = false;
    private long segmentBucket = Long.MIN_VALUE;
    private long segmentAnswered = Long.MIN_VALUE;
    private long segmentAskedAt = 0L;
    private JSONArray cues = new JSONArray();
    private String candidate;
    private Map<String,String> candidateHeaders;
    private long subtitleOffsetMs;
    private int subtitlePosition;
    private int subtitleSizeSp;
    private int subtitleBackgroundOpacity;
    private int subtitleColor;
    private int resizeMode;
    private TextView resizeModeButton;
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
    // 322.3.78: the provider player URL itself (e.g. the server's embed page),
    // saved with the owner's choice so the website can play it without the app.
    private String resolvedProviderUrl = "";
    private String selectedProviderLabel = "";
    private int subscriberStartupAttempt = 0;
    private int subscriberStartupGeneration = 0;
    private int subscriberStartupPhase = 0;
    private long subscriberStartupDeadlineMs = 0L;
    private boolean playbackReady = false;
    private boolean subscriberProviderVisible = false;
    private String subscriberStageBase = "";
    private int subscriberStageDots = 0;
    private int subscriberStageAnimationGeneration = 0;
    private final String sourceChoiceToken = UUID.randomUUID().toString().replace("-", "");

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, Listener listener) {
        this(activity, parent, source, catalog, resumeKey, allowedHost,
                preferredServerKey, preferredServerLabel, interactiveSource,
                ownerMode, openChooser, "", listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl, Listener listener) {
        this(activity, parent, source, catalog, resumeKey, allowedHost,
                preferredServerKey, preferredServerLabel, interactiveSource,
                ownerMode, openChooser, directEmbedUrl, false, listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl,
                              boolean vidnestMode, Listener listener) {
        this.vidnestMode = vidnestMode;
        this.activity = activity;
        this.directEmbedUrl = (directEmbedUrl == null || interactiveSource) ? "" : directEmbedUrl.trim();
        this.listener = listener;
        this.source = source;
        this.catalog = catalog;
        this.resumeKey = resumeKey == null ? "" : resumeKey.trim();
        this.allowedHost = allowedHost == null ? "" : allowedHost.trim().toLowerCase(Locale.ROOT);
        this.preferredServerKey = preferredServerKey == null ? "" : preferredServerKey.trim();
        this.preferredServerLabel = preferredServerLabel == null ? "" : preferredServerLabel.trim();
        this.interactiveSource = interactiveSource;
        this.autoServer = !this.interactiveSource && !this.preferredServerLabel.isEmpty();
        this.ownerMode = ownerMode || interactiveSource;
        this.openChooser = openChooser && interactiveSource;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        String resumePref = resumePrefKey();
        pendingResumeMs = resumePref.isEmpty() ? 0L : Math.max(0L, prefs.getLong(resumePref, 0L));

        // Subtitle sync belongs to the selected movie/server, not to every video
        // ever opened in this player. The saved value is injected from SubHub.
        subtitleOffsetMs = 0L;
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

        subtitle = new SubtitlePillTextView(activity);
        subtitle.setTextColor(subtitleColor);
        subtitle.setTextSize(subtitleSizeSp);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        subtitle.setShadowLayer(
                1.25f * activity.getResources().getDisplayMetrics().density,
                0, 0, Color.BLACK);
        subtitle.setLineSpacing(0, 1.04f);
        // Padding (larger than the pill reach) is set by SubtitlePillTextView itself.
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
        if (this.ownerMode) tool("حفظ", 12, this::requestSave);
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
        if (this.ownerMode) addCompactQuick("حفظ", this::requestSave);
        addCompactQuick("A−", () -> adjustSubtitleSize(-2));
        addCompactQuick("A+", () -> adjustSubtitleSize(2));
        addCompactQuick("↑", () -> adjustSubtitlePosition(4));
        addCompactQuick("↓", () -> adjustSubtitlePosition(-4));
        addCompactQuick("−.5", () -> adjustSync(500));
        addCompactQuick("+.5", () -> adjustSync(-500));
        addCompactQuick("◐−", () -> adjustSubtitleBackground(-SUBTITLE_BG_STEP));
        addCompactQuick("◐+", () -> adjustSubtitleBackground(SUBTITLE_BG_STEP));
        addCompactQuick("🎨", this::showColorOptions);
        resizeModeButton = addCompactQuick(resizeModeButtonLabel(), this::cycleResizeMode);

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

        buildDecisionBar();
        beginCapture();
        handler.post(tick);
    }

    // 322.3.77: owner decision bar shown while the video is playing:
    // «حفظ» keeps this server for subscribers, «سيرفر آخر» goes back to the list.
    private void buildDecisionBar() {
        if (!ownerMode) return;
        decisionBar = new LinearLayout(activity);
        decisionBar.setOrientation(LinearLayout.VERTICAL);
        decisionBar.setGravity(Gravity.CENTER);
        decisionBar.setPadding(dp(12), dp(8), dp(12), dp(10));
        decisionBar.setBackground(round(0xee0b1725, 0xccF7BC3D, 1, 18));
        decisionBar.setElevation(dp(30));
        decisionBar.setClickable(true);

        decisionText = new TextView(activity);
        decisionText.setTextColor(Color.WHITE);
        decisionText.setTextSize(14);
        decisionText.setGravity(Gravity.CENTER);
        decisionText.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        decisionText.setPadding(0, 0, 0, dp(6));
        decisionBar.addView(decisionText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        decisionSave = chip("حفظ", 15, this::requestSave);
        decisionSave.setBackground(round(0xff12301d, 0xff22c55e, 1, 14));
        TextView another = chip("سيرفر آخر", 15, this::chooseAnotherServer);
        TextView hide = chip("✕", 15, this::hideDecisionBar);

        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(42), 1f);
        a.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(42), 1f);
        b.setMargins(dp(4), 0, dp(4), 0);
        LinearLayout.LayoutParams c = new LinearLayout.LayoutParams(dp(46), dp(42));
        c.setMargins(dp(4), 0, dp(4), 0);
        row.addView(decisionSave, a);
        row.addView(another, b);
        row.addView(hide, c);
        decisionBar.addView(row, new LinearLayout.LayoutParams(-1, -2));

        decisionBar.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(340), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(66);
        root.addView(decisionBar, lp);
    }

    private String currentServerName() {
        if (!pendingServerLabel.isEmpty()) return pendingServerLabel;
        if (!selectedProviderLabel.isEmpty()) return selectedProviderLabel;
        return preferredServerLabel;
    }

    private void showDecisionBar(boolean works) {
        if (!ownerMode || closed || decisionBar == null) return;
        String name = currentServerName();
        if (works) {
            decisionText.setText("تم التقاط رابط الفيديو ✓"
                    + (name.isEmpty() ? "" : "\nالسيرفر: " + name)
                    + "\nهل يعمل؟ احفظه للمشتركين، أو جرّب سيرفراً آخر.");
            decisionSave.setVisibility(View.VISIBLE);
        } else {
            decisionText.setText("لم يعمل"
                    + (name.isEmpty() ? "" : " السيرفر " + name)
                    + "\nاضغط «سيرفر آخر» للرجوع إلى القائمة."
                    + (failureDetail.isEmpty() ? "" : "\n\n" + failureDetail));
            decisionSave.setVisibility(View.GONE);
        }
        decisionBar.setVisibility(View.VISIBLE);
        decisionBar.bringToFront();
        status.setVisibility(View.GONE);
    }

    private void hideDecisionBar() {
        if (decisionBar != null) decisionBar.setVisibility(View.GONE);
    }

    private void chooseAnotherServer() {
        if (closed || reopening) return;
        reopening = true;
        String page = discoveredServerPageUrl;
        if (page.isEmpty()) {
            try {
                Uri u = Uri.parse(source);
                String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if (h.equals(allowedHost) || h.endsWith("." + allowedHost)) page = source;
            } catch (Exception ignored) {}
        }
        final String target = page;
        close();
        listener.reopenManual(target);
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

    // 322.3.81: list rows grow with long names (two lines) instead of clipping them.
    private TextView listRow(TextView v) {
        v.setPadding(dp(12), dp(8), dp(12), dp(8));
        v.setMinHeight(dp(40));
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    // 322.3.80: the current choice in the quality / subtitle lists is coloured.
    private void markSelected(TextView v) {
        v.setBackground(round(0xff1d4d33, 0xff4ade80, 2, 14));
        v.setTextColor(Color.WHITE);
        v.setText("✓  " + v.getText());
    }

    private boolean isQualityChoiceActive(TrackSelectionOverride choice) {
        if (player == null) return false;
        java.util.Map<androidx.media3.common.TrackGroup, TrackSelectionOverride> active =
                player.getTrackSelectionParameters().overrides;
        boolean anyVideoOverride = false;
        for (TrackSelectionOverride o : active.values()) {
            if (o.getType() != C.TRACK_TYPE_VIDEO) continue;
            anyVideoOverride = true;
            if (choice != null && o.mediaTrackGroup.equals(choice.mediaTrackGroup)
                    && o.trackIndices.equals(choice.trackIndices)) return true;
        }
        return choice == null && !anyVideoOverride;
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
        if (!directEmbedUrl.isEmpty()) {
            switch (phase) {
                case 3: return "جارٍ الاتصال بالسيرفر";
                case 4: return "جارٍ تحضير الفيديو";
                case 5: return "تم العثور على الفيديو";
                case 6: return "جارٍ تشغيل الفيديو";
                default: break;
            }
        }
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
        if (directCover != null) {
            subscriberStageAnimationGeneration++;
            subscriberStageBase = base.trim();
            status.setVisibility(View.GONE);
            setDirectCoverStage(subscriberStageBase);
            return;
        }
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
            removeDirectCover();
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
        subscriberProviderVisible = false;
        candidate = null;
        candidateHeaders = null;
        resolvedMainHost = "";
        resolvedProviderUrl = "";
        selectedProviderLabel = "";
        pendingServerKey = "";
        pendingServerLabel = "";
        pendingServerPickedAt = 0L;
        serverChoiceReported = false;
        vidnestProxyCaptured = false;
        vidnestCandidateIsProxy = false;
        vidnestFallbackUrl = null;
        vidnestFallbackHeaders = null;
        vidnestFallbackGeneration++;
        vidnestPlan = null;
        vidnestProbeReport = "";
        vidnestCandidates.clear();
        vidnestCandidateHeaders.clear();
        vidnestEvaluated = 0;
        vidnestEvalScheduled = false;
        vidnestEvaluating = false;
        vidnestEvalWaits = 0;
        vidnestEvalReport.setLength(0);
        vidnestStreams.clear();
        vidnestListShown = false;
        vidnestEvalPending = false;
    }

    private void retrySubscriberStartup() {
        if (closed || interactiveSource || playbackReady) return;
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        if (subscriberStartupAttempt >= 2) {
            cleanupPlayerForRetry();
            destroyProbe();
            removeDirectCover();
            subscriberStartupPhase = 0;
            showStage("تعذّر الاتصال. حاول مرة أخرى.");
            if (ownerMode) showDecisionBar(false);
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
            resolvedProviderUrl = u.toString();
            if (interactiveSource) {
                showStage("جارٍ الاتصال بالسيرفر " +
                        (selectedProviderLabel.isEmpty() ? "" : selectedProviderLabel) + "…");
            } else {
                startSubscriberStartupTimer(4);
            }
            if (!interactiveSource) {
                subscriberProviderVisible = false;
                probe.setAlpha(0.0f);
            } else {
                probe.setAlpha(0.02f);
            }
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

    private String providerFocusScript() {
        return "(function(){try{"
                + "function v(e){if(!e)return 0;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>160&&r.height>90&&r.bottom>0&&r.right>0&&s.display!=='none'&&s.visibility!=='hidden';}"
                + "function q(e){try{var r=e.getBoundingClientRect(),n=r.width*r.height,t=(e.tagName||'').toLowerCase();if(t==='video')n+=2e9;else if(/fullscreen|autoplay/i.test(e.getAttribute('allow')||'')||e.hasAttribute('allowfullscreen'))n+=1e9;return n;}catch(_){return 0;}}"
                + "function f(){try{var a=[].slice.call(document.querySelectorAll('iframe[src],video')).filter(v);if(!a.length)return false;"
                + "a.sort(function(x,y){return q(y)-q(x);});var e=a[0],h=document.getElementById('__subhub_player_host');"
                + "if(!h){h=document.createElement('div');h.id='__subhub_player_host';(document.body||document.documentElement).appendChild(h);}"
                + "h.style.cssText='position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;background:#000!important;z-index:2147483645!important;overflow:hidden!important;pointer-events:auto!important;';"
                + "if(e.parentNode!==h)h.appendChild(e);"
                + "e.style.cssText+=';position:absolute!important;inset:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;margin:0!important;z-index:2147483646!important;pointer-events:auto!important;';"
                + "try{document.body.style.overflow='hidden';window.scrollTo(0,0);}catch(_){}return true;}catch(_){return false;}}"
                + "var n=0;(function r(){n++;f();if(n<100)setTimeout(r,180);})();"
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
                + "kick();setTimeout(kick,120);setTimeout(kick,320);setTimeout(kick,650);"
                + "var n=0,t=setInterval(function(){n++;kick();if(n>=18)clearInterval(t);},420);"
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

        long position = Math.max(0L, player.getContentPosition());
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
        probe.setAlpha(subscriberProviderVisible ? 1.0f : 0.0f);
        probe.setVisibility(View.VISIBLE);
        probe.setBackgroundColor(Color.BLACK);
        probe.bringToFront();
        if (directCover != null) directCover.bringToFront();
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
                    if (interactiveSource) {
                        showStage("جارٍ تشغيل السيرفر…");
                        try { v.evaluateJavascript(providerPlayScript(), null); } catch (Exception ignored) {}
                    } else {
                        startSubscriberStartupTimer(4);
                        try { v.evaluateJavascript(providerFocusScript(), null); } catch (Exception ignored) {}
                        handler.postDelayed(() -> {
                            if (closed || probe == null || v != probe) return;
                            subscriberProviderVisible = true;
                            applySubscriberProbePreviewLayout();
                            try { v.evaluateJavascript(providerPlayScript(), null); } catch (Exception ignored) {}
                            scheduleDirectKick(v);
                        }, 180L);
                    }
                } else {
                    if (interactiveSource) {
                        showStage("ادخل يدوياً إلى الفيلم وأغلق الإعلانات، ثم اضغط «جلب السيرفرات».");
                    } else {
                        startSubscriberStartupTimer(2);
                    }
                    try { v.evaluateJavascript(providerPickerScript(), null); } catch (Exception ignored) {}
                    if (openChooser && !chooserAutoRequested && !playing) {
                        chooserAutoRequested = true;
                        handler.postDelayed(() -> {
                            if (!closed && !playing && probe == v && !serverChooserShown) requestServerList();
                        }, 1200L);
                    }
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
                    if (vidnestMode) {
                        if (isVidnestProxyPlaylist(r.getUrl())) {
                            handler.post(() -> captureVidnest(url, headers, true));
                            // Hold the fresh link for ExoPlayer: the provider page's own
                            // player gets an empty playlist and never loads it.
                            return heldPlaylistResponse();
                        }
                        handler.post(() -> captureVidnest(url, headers, false));
                        // 322.3.87: the hidden provider player never plays in the background.
                        return heldPlaylistResponse();
                    }
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
        probe.setAlpha(interactiveSource ? 1.0f : 0.0f);
        probe.setBackgroundColor(Color.BLACK);
        if (!interactiveSource) {
            subscriberProviderVisible = false;
            probe.bringToFront();
            status.bringToFront();
            menuButton.bringToFront();
        }
        if (!interactiveSource && !directEmbedUrl.isEmpty()) {
            // Skip the Moviesmod server list: open the saved player URL with the
            // Moviesmod page as Referer, exactly as after a successful server click.
            showDirectCover();
            root.post(this::applySubscriberProbePreviewLayout);
            startSubscriberStartupTimer(3);
            directKickRounds = 0;
            openResolvedProvider(preferredServerLabel, directEmbedUrl);
        } else {
            probe.loadUrl(source);
            if (interactiveSource) {
                showManualServerButton();
            } else {
                root.post(this::applySubscriberProbePreviewLayout);
                startSubscriberStartupTimer(1);
            }
        }

        handler.postDelayed(() -> {
            if (interactiveSource && !closed && !playing && !vidnestListShown && !vidnestEvaluating) {
                message("لم يُلتقط بث بعد. اختر سيرفراً آخر أو أعد المحاولة.");
                if (!selectedProviderLabel.isEmpty()) showDecisionBar(false);
            }
        }, 45000);
    }

    // 322.3.79: these players often need the play button pressed two or three
    // times (the first taps hit an invisible ad layer). Keep tapping the play
    // target every few seconds until the stream is captured.
    private void scheduleDirectKick(WebView v) {
        if (directEmbedUrl.isEmpty()) return;
        handler.postDelayed(() -> {
            if (closed || playing || probe == null || v != probe || interactiveSource) return;
            if (directKickRounds >= 3) return;
            directKickRounds++;
            try {
                v.evaluateJavascript("window.__subhubProviderKickV3265=false;" + providerPlayScript(), null);
            } catch (Exception ignored) {}
            scheduleDirectKick(v);
        }, 2000L);
        if (directKickRounds == 0) scheduleDirectReveal(v);
    }

    // If the automatic taps did not start the video within a few seconds, show
    // the provider player so the viewer can tap it once by hand.
    private void scheduleDirectReveal(WebView v) {
        directRevealGeneration++;
        final int generation = directRevealGeneration;
        handler.postDelayed(() -> {
            if (generation != directRevealGeneration || closed || playing
                    || candidate != null || probe == null || v != probe) return;
            if (directCover == null) return;
            subscriberStartupGeneration++;
            stopSubscriberStageAnimation();
            removeDirectCover();
            showStage("اضغط داخل الفيديو للتشغيل.");
        }, 7000L);
    }

    // 322.3.81: animated SubHub loading screen. Letters rise in one by one,
    // the name then pulses softly, three gold dots bounce, and the real stage
    // text sits under them (no separate box) until the video starts.
    private void showDirectCover() {
        if (directEmbedUrl.isEmpty() || interactiveSource || closed) return;
        removeDirectCover();
        directCover = new FrameLayout(activity);
        directCover.setBackgroundColor(Color.BLACK);
        // The cover only hides the provider page; touches we dispatch go to the
        // probe directly, and the cover itself swallows stray taps on ads.
        directCover.setClickable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(LinearLayout.HORIZONTAL);
        brand.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        brand.setGravity(Gravity.CENTER);
        String name = "SubHub";
        for (int i = 0; i < name.length(); i++) {
            TextView letter = new TextView(activity);
            letter.setText(String.valueOf(name.charAt(i)));
            letter.setTextColor(GOLD);
            letter.setTextSize(34);
            letter.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            letter.setShadowLayer(dp(10), 0, 0, 0x99F7BC3D);
            letter.setAlpha(0f);
            letter.setTranslationY(dp(18));
            brand.addView(letter, new LinearLayout.LayoutParams(-2, -2));
            letter.animate().alpha(1f).translationY(0f)
                    .setStartDelay(90L * i).setDuration(420L)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
        column.addView(brand, new LinearLayout.LayoutParams(-2, -2));
        android.animation.ObjectAnimator pulse =
                android.animation.ObjectAnimator.ofFloat(brand, "alpha", 1f, 0.55f);
        pulse.setStartDelay(90L * name.length() + 450L);
        pulse.setDuration(1100L);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        pulse.start();
        coverAnimators.add(pulse);

        LinearLayout dots = new LinearLayout(activity);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);
        for (int i = 0; i < 3; i++) {
            View dot = new View(activity);
            dot.setBackground(round(GOLD, GOLD, 0, 99));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
            dl.setMargins(dp(5), 0, dp(5), 0);
            dots.addView(dot, dl);
            android.animation.ObjectAnimator hop =
                    android.animation.ObjectAnimator.ofFloat(dot, "translationY", 0f, -dp(9));
            hop.setStartDelay(160L * i);
            hop.setDuration(380L);
            hop.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            hop.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            hop.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
            hop.start();
            coverAnimators.add(hop);
        }
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-2, dp(26));
        dotsLp.topMargin = dp(18);
        column.addView(dots, dotsLp);

        directCoverStage = new TextView(activity);
        directCoverStage.setTextColor(0xCCFFFFFF);
        directCoverStage.setTextSize(14);
        directCoverStage.setGravity(Gravity.CENTER);
        directCoverStage.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        LinearLayout.LayoutParams stageLp = new LinearLayout.LayoutParams(-2, -2);
        stageLp.topMargin = dp(10);
        column.addView(directCoverStage, stageLp);

        directCover.addView(column, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(directCover, new FrameLayout.LayoutParams(-1, -1));
        directCover.bringToFront();
        status.setVisibility(View.GONE);
        menuButton.bringToFront();
    }

    private void setDirectCoverStage(String text) {
        if (directCoverStage == null || text == null) return;
        final String next = text.trim();
        if (next.equals(String.valueOf(directCoverStage.getText()))) return;
        directCoverStage.animate().cancel();
        directCoverStage.animate().alpha(0f).setDuration(140L).withEndAction(() -> {
            if (directCoverStage == null) return;
            directCoverStage.setText(next);
            directCoverStage.animate().alpha(1f).setDuration(200L).start();
        }).start();
    }

    private void stopCoverAnimators() {
        for (android.animation.Animator a : coverAnimators) {
            try { a.cancel(); } catch (Exception ignored) {}
        }
        coverAnimators.clear();
    }

    private void removeDirectCover() {
        directRevealGeneration++;
        stopCoverAnimators();
        directCoverStage = null;
        if (directCover != null) {
            ViewGroup parent = (ViewGroup) directCover.getParent();
            if (parent != null) parent.removeView(directCover);
            directCover = null;
        }
    }

    // When the video is ready the cover fades away instead of vanishing.
    private void fadeOutDirectCover() {
        final FrameLayout cover = directCover;
        if (cover == null) return;
        directCover = null;
        directCoverStage = null;
        directRevealGeneration++;
        cover.setClickable(false);
        cover.animate().alpha(0f).setDuration(380L).withEndAction(() -> {
            stopCoverAnimators();
            ViewGroup parent = (ViewGroup) cover.getParent();
            if (parent != null) parent.removeView(cover);
        }).start();
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
                // 322.3.77: no 30-second window — a slow server is still the server
                // the owner picked (capture only runs after a server was selected).
                if (key.isEmpty() && !selectedProviderLabel.isEmpty()) {
                    key = serverKey(selectedProviderLabel);
                    label = selectedProviderLabel;
                }
                if (!key.isEmpty() && !label.isEmpty()) {
                    serverChoiceReported = true;
                    listener.serverSelected(key, label, discoveredServerPageUrl, resolvedProviderUrl);
                }
            }
            // 322.3.84: Vidnest starts at once (the link is fresh and held).
            long startDelay = vidnestMode ? 150L : (interactiveSource ? 1200L : 300L);
            handler.postDelayed(() -> {
                if (!closed && !playing) startStream();
            }, startDelay);
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

    // 322.3.84 — Vidnest helpers ------------------------------------------------

    /** "https://<x>.animanga.fun/proxy?url=<...m3u8>&headers=<json>" (not /ts-proxy). */
    static boolean isVidnestProxyPlaylist(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String path = u.getPath();
        if (path == null) return false;
        String p = path.toLowerCase(Locale.ROOT);
        if (!(p.equals("/proxy") || p.endsWith("/proxy"))) return false;
        String inner;
        try { inner = u.getQueryParameter("url"); } catch (Exception e) { return false; }
        return inner != null && inner.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static WebResourceResponse heldPlaylistResponse() {
        Map<String,String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-store");
        return new WebResourceResponse("application/vnd.apple.mpegurl", "utf-8", 200, "OK", h,
                new java.io.ByteArrayInputStream("#EXTM3U\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void captureVidnest(String url, Map<String,String> headers, boolean proxyFormat) {
        if (closed || playing) return;
        if (proxyFormat) {
            // 322.3.86: collect every /proxy playlist, then pick a verified one.
            if (vidnestCandidates.contains(url) || vidnestCandidates.size() >= VIDNEST_MAX_CANDIDATES) return;
            vidnestCandidates.add(url);
            vidnestCandidateHeaders.add(headers);
            vidnestProxyCaptured = true;
            vidnestFallbackGeneration++; // cancel any pending /hls fallback
            if (!vidnestEvalScheduled) {
                vidnestEvalScheduled = true;
                if (interactiveSource) showStage("تم العثور على روابط… جارٍ فحصها…");
                handler.postDelayed(this::evaluateVidnestCandidates, VIDNEST_COLLECT_MS);
            } else if (!vidnestEvalPending) {
                // A link that shows up after the first check is checked too.
                vidnestEvalPending = true;
                handler.postDelayed(() -> {
                    vidnestEvalPending = false;
                    evaluateVidnestCandidates();
                }, VIDNEST_COLLECT_MS);
            }
            return;
        }
        // Not the clean /proxy link: keep the first one as a late fallback only.
        if (vidnestProxyCaptured || vidnestFallbackUrl != null) return;
        vidnestFallbackUrl = url;
        vidnestFallbackHeaders = headers;
        final int gen = ++vidnestFallbackGeneration;
        handler.postDelayed(() -> {
            if (closed || playing || gen != vidnestFallbackGeneration
                    || vidnestProxyCaptured || vidnestFallbackUrl == null) return;
            vidnestCandidateIsProxy = false;
            capture(vidnestFallbackUrl, vidnestFallbackHeaders);
        }, VIDNEST_FALLBACK_WAIT_MS);
    }

    /**
     * 322.3.86/87: check the collected /proxy playlists off the UI thread. Every
     * quality becomes one row (like Web Video Cast). The owner picks from the list;
     * subscribers (and the owner's saved-server test) start the first working one.
     */
    private void evaluateVidnestCandidates() {
        if (closed || playing || vidnestEvaluating) return;
        if (vidnestEvaluated >= vidnestCandidates.size()) {
            if (hasWorkingVidnestStream()) return; // the list is already showing / playing
            // Nothing new to check: give the page a little longer to try other sources.
            if (vidnestEvalWaits < 1) {
                vidnestEvalWaits++;
                handler.postDelayed(this::evaluateVidnestCandidates, VIDNEST_MORE_WAIT_MS);
                return;
            }
            vidnestProbeReport = vidnestEvalReport.length() == 0
                    ? "لم يظهر رابط proxy" : vidnestEvalReport.toString();
            vidnestProbeFailed();
            return;
        }
        vidnestEvaluating = true;
        final int from = vidnestEvaluated;
        final ArrayList<String> urls = new ArrayList<>(vidnestCandidates.subList(from, vidnestCandidates.size()));
        final ArrayList<Map<String,String>> hdrs =
                new ArrayList<>(vidnestCandidateHeaders.subList(from, vidnestCandidateHeaders.size()));
        vidnestEvaluated = vidnestCandidates.size();
        final String defaultUa = WebSettings.getDefaultUserAgent(activity);
        final String fallbackReferer = source;
        final boolean stopAtFirst = !interactiveSource;
        new Thread(() -> {
            final ArrayList<VidnestStream> found = new ArrayList<>();
            for (int i = 0; i < urls.size() && !closed; i++) {
                Map<String,String> h = hdrs.get(i);
                String ua = headerIn(h, "User-Agent", defaultUa);
                String ref = headerIn(h, "Referer", fallbackReferer);
                String org = headerIn(h, "Origin", "");
                ArrayList<VidnestStream> rows = analyzeVidnestLink(urls.get(i), ua, ref, org);
                for (VidnestStream r : rows) r.headers = h;
                found.addAll(rows);
                if (stopAtFirst && firstWorking(rows) != null) break;
            }
            handler.post(() -> {
                vidnestEvaluating = false;
                if (closed || playing) return;
                vidnestStreams.addAll(found);
                for (VidnestStream r : found) {
                    if (vidnestEvalReport.length() > 0) vidnestEvalReport.append('\n');
                    vidnestEvalReport.append(r.ok ? "✓ " : "✗ ").append(rowText(r));
                }
                vidnestProbeReport = vidnestEvalReport.toString();
                if (!hasWorkingVidnestStream()) {
                    evaluateVidnestCandidates();
                } else if (interactiveSource) {
                    showVidnestList();
                } else {
                    playVidnestStream(firstWorking(vidnestStreams));
                }
            });
        }, "vidnest-eval").start();
    }

    private boolean hasWorkingVidnestStream() {
        return firstWorking(vidnestStreams) != null;
    }

    private static VidnestStream firstWorking(ArrayList<VidnestStream> rows) {
        for (VidnestStream r : rows) if (r.ok) return r; // adaptive rows come first
        return null;
    }

    static String rowText(VidnestStream r) {
        StringBuilder b = new StringBuilder(r.host);
        if (!r.label.isEmpty()) b.append(" · ").append(r.label);
        if (r.ok) {
            if (!r.duration.isEmpty()) b.append(" · ").append(r.duration);
        } else if (!r.problem.isEmpty()) {
            b.append(" · ").append(r.problem);
        }
        return b.toString();
    }

    private void playVidnestStream(VidnestStream r) {
        if (r == null || closed || playing) return;
        vidnestListShown = false;
        hidePanel();
        vidnestPlan = r.plan;
        vidnestCandidateIsProxy = true;
        capture(r.playUrl, r.headers);
    }

    /** Owner list of captured links, green = working, orange = broken. */
    private void showVidnestList() {
        if (closed || playing) return;
        vidnestListShown = true;
        status.setVisibility(View.GONE);
        int working = 0;
        for (VidnestStream r : vidnestStreams) if (r.ok) working++;
        panelTitle.setText("روابط البث — " + working + " سليم من " + vidnestStreams.size());
        panelBody.removeAllViews();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(12));
        for (VidnestStream r : vidnestStreams) {
            final VidnestStream row = r;
            String text = (r.ok ? "▶  " : "✗  ") + rowText(r);
            TextView item = listRow(chip(text, 13, () -> {
                if (row.ok) playVidnestStream(row);
                else showTransientValue("هذا الرابط معطوب — اختر رابطاً أخضر", 1600);
            }));
            item.setTextColor(r.ok ? 0xff86efac : 0xfffbbf24);
            if (r.adaptive && r.ok) item.setBackground(round(0xff12301d, 0xff4ade80, 1, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(2), 0, dp(2));
            list.addView(item, lp);
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
        panel.bringToFront();
    }

    /**
     * 322.3.87: one /proxy link → rows. A master playlist gives an «تلقائي» row
     * (clean master of the working qualities) plus one row per quality.
     */
    static ArrayList<VidnestStream> analyzeVidnestLink(String url, String browserUa, String referer, String origin) {
        ArrayList<VidnestStream> rows = new ArrayList<>();
        String host = "";
        try { host = new java.net.URL(url).getHost(); } catch (Exception ignored) {}
        host = host.replaceFirst("\\.animanga\\.fun$", "");
        VidnestPlan[] plans = new VidnestPlan[] {
                new VidnestPlan("بلا Referer", browserUa, false),
                new VidnestPlan("عميل بسيط", null, false),
                new VidnestPlan("بالترويسات", browserUa, true)
        };
        VidnestPlan plan = null;
        String top = null;
        String lastProblem = "";
        for (VidnestPlan p : plans) {
            Object[] r = fetchHead(url, p, referer, origin, 512 * 1024);
            top = asPlaylist(r);
            if (top != null) { plan = p; break; }
            lastProblem = "القائمة: " + describe(r);
        }
        if (top == null) {
            VidnestStream bad = new VidnestStream();
            bad.host = host; bad.label = "proxy"; bad.problem = lastProblem;
            rows.add(bad);
            return rows;
        }

        if (!top.contains("#EXT-X-STREAM-INF")) {
            VidnestStream one = new VidnestStream();
            one.host = host; one.label = "المصدر"; one.plan = plan;
            String problem = checkMedia(url, top, plan, referer, origin);
            one.ok = problem == null;
            one.problem = problem == null ? "" : problem;
            one.duration = formatDuration(totalDuration(top));
            one.playUrl = url;
            rows.add(one);
            return rows;
        }

        // Master playlist: check audio renditions and every quality.
        String[] lines = top.split("\\r?\\n");
        ArrayList<String> header = new ArrayList<>();
        java.util.LinkedHashMap<String,String> audioOk = new java.util.LinkedHashMap<>(); // group → rewritten line(s)
        ArrayList<String> infLines = new ArrayList<>();
        ArrayList<String> uris = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i].trim();
            if (l.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || l.startsWith("#EXT-X-VERSION")) header.add(l);
            if (l.startsWith("#EXT-X-MEDIA:") && l.contains("TYPE=AUDIO")) {
                String u = attr(l, "URI");
                String g = attr(l, "GROUP-ID");
                if (u == null) {
                    audioOk.merge(g == null ? "" : g, l, (a, b) -> a + "\n" + b);
                } else {
                    String abs = resolveUrl(url, u);
                    String pl = asPlaylist(fetchHead(abs, plan, referer, origin, 512 * 1024));
                    if (pl != null && pl.contains("#EXTINF")) {
                        String fixed = l.replace("URI=\"" + u + "\"", "URI=\"" + abs + "\"");
                        audioOk.merge(g == null ? "" : g, fixed, (a, b) -> a + "\n" + b);
                    }
                }
            }
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                for (int j = i + 1; j < lines.length; j++) {
                    String n = lines[j].trim();
                    if (n.isEmpty() || n.startsWith("#")) continue;
                    infLines.add(l);
                    uris.add(resolveUrl(url, n));
                    break;
                }
            }
        }

        ArrayList<VidnestStream> qualities = new ArrayList<>();
        ArrayList<String> okInf = new ArrayList<>();
        ArrayList<String> okUri = new ArrayList<>();
        String duration = "";
        boolean segmentChecked = false;
        for (int i = 0; i < uris.size(); i++) {
            String inf = cleanStreamInf(infLines.get(i), audioOk);
            VidnestStream q = new VidnestStream();
            q.host = host; q.plan = plan;
            String res = attr(infLines.get(i), "RESOLUTION");
            String bw = attr(infLines.get(i), "BANDWIDTH");
            String bwDigits = bw == null ? "" : bw.replaceAll("[^0-9]", "");
            q.label = res != null ? res
                    : (!bwDigits.isEmpty() && bwDigits.length() < 12 ? (Long.parseLong(bwDigits) / 1000) + " kbps" : "جودة " + (i + 1));
            Object[] r = fetchHead(uris.get(i), plan, referer, origin, 512 * 1024);
            String media = asPlaylist(r);
            if (media == null) {
                q.problem = "القائمة: " + describe(r);
            } else if (!media.contains("#EXTINF")) {
                q.problem = "القائمة: بلا مقاطع";
            } else {
                String problem = segmentChecked ? null : checkMedia(uris.get(i), media, plan, referer, origin);
                if (problem == null) {
                    segmentChecked = true;
                    q.ok = true;
                    q.duration = formatDuration(totalDuration(media));
                    if (duration.isEmpty()) duration = q.duration;
                    okInf.add(inf);
                    okUri.add(uris.get(i));
                    q.playUrl = masterDataUri(header, audioOk, java.util.Collections.singletonList(inf),
                            java.util.Collections.singletonList(uris.get(i)));
                } else {
                    q.problem = problem;
                }
            }
            qualities.add(q);
        }
        if (!okUri.isEmpty()) {
            VidnestStream auto = new VidnestStream();
            auto.host = host; auto.plan = plan; auto.ok = true; auto.adaptive = true;
            auto.label = "تلقائي — " + okUri.size() + (okUri.size() == 1 ? " جودة" : " جودات");
            auto.duration = duration;
            auto.playUrl = masterDataUri(header, audioOk, okInf, okUri);
            rows.add(auto);
        }
        rows.addAll(qualities);
        return rows;
    }

    /** null when the media playlist's first segment answers with media bytes. */
    static String checkMedia(String mediaUrl, String media, VidnestPlan plan, String referer, String origin) {
        if (!media.contains("#EXTINF")) return "القائمة: بلا مقاطع";
        String seg = firstUriAfter(media, "#EXTINF");
        if (seg == null) return "المقطع: لا يوجد رابط";
        Object[] g = fetchHead(resolveUrl(mediaUrl, seg), plan, referer, origin, 188);
        int code = (Integer) g[0];
        byte[] b = (byte[]) g[2];
        if (code < 200 || code >= 300 || b.length == 0 || b[0] == '<') return "المقطع: " + describe(g);
        return null;
    }

    static String attr(String line, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|[:,])" + java.util.regex.Pattern.quote(name) + "=(\"[^\"]*\"|[^,]*)")
                .matcher(line);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.startsWith("\"") ? v.substring(1, v.length() - 1) : v;
    }

    static String stripAttr(String line, String name) {
        String out = line.replaceAll("(?<=[:,])" + java.util.regex.Pattern.quote(name) + "=(\"[^\"]*\"|[^,]*),?", "");
        return out.endsWith(",") ? out.substring(0, out.length() - 1) : out;
    }

    /** Drop subtitle groups; drop an audio group that has no working rendition. */
    static String cleanStreamInf(String inf, java.util.Map<String,String> audioOk) {
        String out = stripAttr(inf, "SUBTITLES");
        String g = attr(out, "AUDIO");
        if (g != null && !audioOk.containsKey(g)) out = stripAttr(out, "AUDIO");
        return out;
    }

    static String masterDataUri(java.util.List<String> header, java.util.Map<String,String> audio,
                                java.util.List<String> infs, java.util.List<String> uris) {
        StringBuilder m = new StringBuilder("#EXTM3U\n");
        for (String h : header) m.append(h).append('\n');
        java.util.HashSet<String> usedGroups = new java.util.HashSet<>();
        for (String inf : infs) { String g = attr(inf, "AUDIO"); if (g != null) usedGroups.add(g); }
        for (java.util.Map.Entry<String,String> e : audio.entrySet()) {
            if (usedGroups.contains(e.getKey())) m.append(e.getValue()).append('\n');
        }
        for (int i = 0; i < infs.size(); i++) m.append(infs.get(i)).append('\n').append(uris.get(i)).append('\n');
        return "data:application/vnd.apple.mpegurl;base64," + java.util.Base64.getEncoder()
                .encodeToString(m.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static double totalDuration(String media) {
        double sum = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#EXTINF:([0-9.]+)").matcher(media);
        while (m.find()) {
            try { sum += Double.parseDouble(m.group(1)); } catch (Exception ignored) {}
        }
        return sum;
    }

    static String formatDuration(double seconds) {
        if (seconds < 1) return "";
        long s = Math.round(seconds);
        return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    private static String headerIn(Map<String,String> h, String name, String fallback) {
        if (h != null) {
            for (Map.Entry<String,String> e : h.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey()) && e.getValue() != null) return e.getValue();
            }
        }
        return fallback;
    }

    /** {VidnestPlan or null, short result}: playlist, first quality, first segment. */
    static Object[] deepProbeVidnest(String url, String browserUa, String referer, String origin) {
        VidnestPlan[] plans = new VidnestPlan[] {
                new VidnestPlan("بلا Referer", browserUa, false),
                new VidnestPlan("عميل بسيط", null, false),
                new VidnestPlan("بالترويسات", browserUa, true)
        };
        String last = "";
        for (VidnestPlan plan : plans) {
            String problem = deepCheck(url, plan, referer, origin);
            if (problem == null) return new Object[] { plan, plan.name };
            last = problem;
            // A playlist that answered but is broken further down will not be
            // fixed by other headers; only retry when the first fetch failed.
            if (!problem.startsWith("القائمة")) break;
        }
        return new Object[] { null, last };
    }

    /** null when playable; otherwise "<step>: <detail>". */
    static String deepCheck(String url, VidnestPlan plan, String referer, String origin) {
        Object[] p = fetchHead(url, plan, referer, origin, 512 * 1024);
        String playlist = asPlaylist(p);
        if (playlist == null) return "القائمة: " + describe(p);
        String media = playlist;
        String mediaUrl = url;
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            String variant = firstUriAfter(playlist, "#EXT-X-STREAM-INF");
            if (variant == null) return "الجودة: لا يوجد رابط";
            mediaUrl = resolveUrl(url, variant);
            Object[] v = fetchHead(mediaUrl, plan, referer, origin, 512 * 1024);
            media = asPlaylist(v);
            if (media == null) return "الجودة: " + describe(v);
        }
        if (!media.contains("#EXTINF")) return "القائمة: بلا مقاطع";
        String seg = firstUriAfter(media, "#EXTINF");
        if (seg == null) return "المقطع: لا يوجد رابط";
        Object[] g = fetchHead(resolveUrl(mediaUrl, seg), plan, referer, origin, 188);
        int code = (Integer) g[0];
        byte[] b = (byte[]) g[2];
        if (code < 200 || code >= 300 || b.length == 0 || b[0] == '<') return "المقطع: " + describe(g);
        return null;
    }

    /** {Integer code, String contentType, byte[] head} — code -1 on network error. */
    static Object[] fetchHead(String url, VidnestPlan plan, String referer, String origin, int max) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "*/*");
            if (plan.userAgent != null && !plan.userAgent.isEmpty()) c.setRequestProperty("User-Agent", plan.userAgent);
            if (plan.sendReferer) {
                if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
                if (origin != null && !origin.isEmpty()) c.setRequestProperty("Origin", origin);
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[8192];
                int r;
                while (out.size() < max && (r = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) {
                    out.write(buf, 0, r);
                }
                try { in.close(); } catch (Exception ignored) {}
            }
            return new Object[] { code, c.getContentType(), out.toByteArray() };
        } catch (Exception e) {
            String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return new Object[] { -1, m, new byte[0] };
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    static String asPlaylist(Object[] r) {
        int code = (Integer) r[0];
        if (code < 200 || code >= 300) return null;
        String t = new String((byte[]) r[2], java.nio.charset.StandardCharsets.UTF_8).replace("\uFEFF", "").trim();
        return t.startsWith("#EXTM3U") ? t : null;
    }

    static String describe(Object[] r) {
        int code = (Integer) r[0];
        if (code < 0) return String.valueOf(r[1]);
        String t = new String((byte[]) r[2], java.nio.charset.StandardCharsets.UTF_8)
                .replace("\uFEFF", "").trim().replaceAll("\\s+", " ");
        if (t.length() > 60) t = t.substring(0, 60);
        return "HTTP " + code + (r[1] == null ? "" : " · " + r[1]) + (t.isEmpty() ? " · (فارغ)" : " · " + t);
    }

    static String firstUriAfter(String playlist, String tag) {
        String[] lines = playlist.split("\\r?\\n");
        boolean armed = false;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.startsWith(tag)) { armed = true; continue; }
            if (armed && !l.isEmpty() && !l.startsWith("#")) return l;
        }
        return null;
    }

    static String resolveUrl(String base, String rel) {
        try { return new java.net.URL(new java.net.URL(base), rel).toString(); }
        catch (Exception e) { return rel; }
    }

    /** Owner-only diagnostic line shown when ExoPlayer fails on the Vidnest card. */
    private String vidnestDiagnostic(PlaybackException error) {
        String host = "";
        try { host = Uri.parse(candidate == null ? "" : candidate).getHost(); } catch (Exception ignored) {}
        if ((host == null || host.isEmpty()) && candidate != null && candidate.startsWith("data:")) host = "قائمة نظيفة";
        if (host == null) host = "";
        String code = "";
        try { code = error == null ? "" : error.getErrorCodeName(); } catch (Exception ignored) {}
        String cause = "";
        try {
            Throwable c = error == null ? null : error.getCause();
            if (c != null) cause = c.getClass().getSimpleName()
                    + (c.getMessage() == null ? "" : ": " + c.getMessage());
        } catch (Exception ignored) {}
        if (cause.length() > 140) cause = cause.substring(0, 140);
        return "Vidnest · " + (vidnestCandidateIsProxy ? "proxy" : "احتياطي") + " · " + host
                + "\n" + code + (cause.isEmpty() ? "" : "\n" + cause)
                + (vidnestProbeReport.isEmpty() ? "" : "\nالفحص: " + vidnestProbeReport);
    }

    /**
     * 322.3.85: fetch the playlist the way the working Termux test did, then two
     * alternatives. Returns {VidnestPlan or null, report}. Runs off the UI thread.
     */
    private Object[] probeVidnestPlaylist(String url, String browserUa, String referer, String origin) {
        VidnestPlan[] plans = new VidnestPlan[] {
                new VidnestPlan("بلا Referer", browserUa, false),
                new VidnestPlan("عميل بسيط", null, false),
                new VidnestPlan("بالترويسات", browserUa, true)
        };
        StringBuilder report = new StringBuilder();
        for (VidnestPlan plan : plans) {
            if (closed) break;
            String line = probeOnce(url, plan, referer, origin);
            if (line == null) {
                report.append("✓ ").append(plan.name);
                return new Object[] { plan, report.toString() };
            }
            if (report.length() > 0) report.append('\n');
            report.append("✗ ").append(plan.name).append(": ").append(line);
        }
        return new Object[] { null, report.toString() };
    }

    /** null when the body starts with #EXTM3U; otherwise a short description. */
    static String probeOnce(String url, VidnestPlan plan, String referer, String origin) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            // Short timeouts: three tries must fit inside the 15 s startup phase.
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "*/*");
            if (plan.userAgent != null && !plan.userAgent.isEmpty()) c.setRequestProperty("User-Agent", plan.userAgent);
            if (plan.sendReferer) {
                if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
                if (origin != null && !origin.isEmpty()) c.setRequestProperty("Origin", origin);
            }
            int code = c.getResponseCode();
            java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] buf = new byte[1024];
            int n = 0;
            if (in != null) {
                int r;
                while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                try { in.close(); } catch (Exception ignored) {}
            }
            String body = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            String trimmed = body.replace("\uFEFF", "").trim();
            if (code >= 200 && code < 300 && trimmed.startsWith("#EXTM3U")) return null;
            String type = c.getContentType();
            String enc = c.getContentEncoding();
            String head = trimmed.replaceAll("\\s+", " ");
            if (head.length() > 70) head = head.substring(0, 70);
            return "HTTP " + code
                    + (type == null ? "" : " · " + type)
                    + (enc == null ? "" : " · " + enc)
                    + (head.isEmpty() ? " · (فارغ)" : " · " + head);
        } catch (Exception e) {
            String m = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            return m.length() > 90 ? m.substring(0, 90) : m;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** Same outcome paths as an ExoPlayer error, with the probe report for the owner. */
    private void vidnestProbeFailed() {
        failureDetail = ownerMode ? ("Vidnest · فحص الرابط فشل\n" + vidnestProbeReport) : "";
        if (!interactiveSource && subscriberStartupAttempt < 2) {
            retrySubscriberStartup();
        } else if (!interactiveSource) {
            subscriberStartupGeneration++;
            subscriberStartupPhase = 0;
            stopSubscriberStageAnimation();
            cleanupPlayerForRetry();
            destroyProbe();
            removeDirectCover();
            showStage("تعذّر تشغيل الفيديو. حاول مرة أخرى.");
            if (ownerMode) showDecisionBar(false);
        } else {
            message("تعذّر تشغيل البث مباشرة. أغلق وأعد المحاولة.");
            showDecisionBar(false);
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

        // 322.3.85: probe the Vidnest playlist first (background thread), then
        // start ExoPlayer with the request style the server answered correctly.
        if (vidnestMode && vidnestCandidateIsProxy && vidnestPlan == null) {
            final String url = candidate;
            final String ua = userAgent, ref = referer, org = origin;
            final int gen = subscriberStartupGeneration;
            new Thread(() -> {
                final Object[] result = probeVidnestPlaylist(url, ua, ref, org);
                handler.post(() -> {
                    if (closed || gen != subscriberStartupGeneration || !url.equals(candidate)) return;
                    vidnestProbeReport = (String) result[1];
                    if (result[0] == null) {
                        vidnestProbeFailed();
                    } else {
                        vidnestPlan = (VidnestPlan) result[0];
                        startExoPlayer(ua, ref, org);
                    }
                });
            }, "vidnest-probe").start();
            return;
        }
        startExoPlayer(userAgent, referer, origin);
    }

    private void startExoPlayer(String userAgent, String referer, String origin) {
        if (candidate == null || closed) return;

        // 322.3.84/85: the Vidnest /proxy link carries its own upstream headers.
        // ExoPlayer uses the request style chosen by the probe (no Origin/Cookie).
        final boolean minimalHeaders = vidnestMode && vidnestCandidateIsProxy;
        if (minimalHeaders && vidnestPlan != null) {
            userAgent = vidnestPlan.userAgent;
            if (!vidnestPlan.sendReferer) referer = "";
        }
        Map<String,String> headers = new HashMap<>();
        if (referer != null && !referer.isEmpty()) headers.put("Referer", referer);
        if (!origin.isEmpty() && !minimalHeaders) headers.put("Origin", origin);

        DefaultHttpDataSource.Factory http =
                new DefaultHttpDataSource.Factory()
                        .setUserAgent(userAgent)
                        .setAllowCrossProtocolRedirects(vidnestMode)
                        .setDefaultRequestProperties(headers);

        ResolvingDataSource.Factory data =
                new ResolvingDataSource.Factory(http, spec -> {
                    Map<String,String> scoped = new HashMap<>(spec.httpRequestHeaders);
                    if (minimalHeaders) return spec;
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
                    fadeOutDirectCover();
                    if (ownerMode && !decisionShown) {
                        decisionShown = true;
                        showDecisionBar(true);
                    } else {
                        showTransientValue("تم تشغيل الفيديو", 700);
                    }
                } else if (state == Player.STATE_BUFFERING) {
                    if (interactiveSource) showStage("جارٍ تحميل الفيديو…");
                    else showSubscriberStage("جارٍ تشغيل الفيديو");
                } else if (state == Player.STATE_ENDED) {
                    clearResumePosition();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                final String vidnestDiag = (vidnestMode && ownerMode) ? vidnestDiagnostic(error) : "";
                failureDetail = vidnestDiag;
                if (!interactiveSource && subscriberStartupAttempt < 2) {
                    retrySubscriberStartup();
                } else if (!interactiveSource) {
                    subscriberStartupGeneration++;
                    subscriberStartupPhase = 0;
                    stopSubscriberStageAnimation();
                    cleanupPlayerForRetry();
                    destroyProbe();
                    removeDirectCover();
                    showStage(vidnestDiag.isEmpty() ? "تعذّر تشغيل الفيديو. حاول مرة أخرى."
                            : "تعذّر تشغيل الفيديو.\n" + vidnestDiag);
                    if (ownerMode) showDecisionBar(false);
                } else {
                    message(vidnestDiag.isEmpty() ? "تعذّر تشغيل البث مباشرة. أغلق وأعد المحاولة."
                            : "تعذّر تشغيل البث.\n" + vidnestDiag);
                    showDecisionBar(false);
                }
            }
        });

        MediaItem item = new MediaItem.Builder()
                .setUri(candidate)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();

        // 322.3.87: Vidnest plays a clean master given as a data: URI.
        androidx.media3.datasource.DataSource.Factory sourceFactory =
                vidnestMode ? new DefaultDataSource.Factory(activity, data) : data;
        player.setMediaSource(new HlsMediaSource.Factory(sourceFactory).createMediaSource(item));
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

    private TextView addCompactQuick(String text, Runnable action) {
        TextView v = chip(text, 13, action);
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setPadding(dp(5), 0, dp(5), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(50), dp(36));
        lp.setMargins(dp(2), 0, dp(2), 0);
        quickRow.addView(v, lp);
        return v;
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
            TextView item = listRow(chip(labels.get(i), 15, () -> {
                if (player == null) return;
                androidx.media3.common.TrackSelectionParameters.Builder b =
                        player.getTrackSelectionParameters().buildUpon()
                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
                if (choices.get(which) != null) b.setOverrideForType(choices.get(which));
                player.setTrackSelectionParameters(b.build());
                hidePanel();
            }));
            if (isQualityChoiceActive(choices.get(i))) markSelected(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
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

        TextView off = listRow(chip("بدون ترجمة", 15, () -> {
            selectedSubtitle = -1;
            resetSegment();
            captions = false;
            cues = new JSONArray();
            currentSubtitleText = "";
            applySubtitleText("");
            subtitle.setVisibility(View.GONE);
            hidePanel();
        }));
        if (selectedSubtitle < 0 || !captions) markSelected(off);
        LinearLayout.LayoutParams offLp = new LinearLayout.LayoutParams(-1, -2);
        offLp.setMargins(0, dp(2), 0, dp(2));
        list.addView(off, offLp);

        for (int i = 0; i < catalog.length(); i++) {
            JSONObject o = catalog.optJSONObject(i);
            String name = o == null
                    ? "ترجمة SubHub"
                    : o.optString("name", "ترجمة SubHub");
            if (ownerMode && o != null && o.optBoolean("pinned", false)) name = "📌 " + name;
            final int index = i;
            TextView item = listRow(chip(name, 15, () -> {
                selectedSubtitle = index;
                resetSegment();
                captions = true;
                cues = new JSONArray();
                currentSubtitleText = "";
                applySubtitleText("");
                listener.subtitleRequested(index);
                hidePanel();
            }));
            if (captions && selectedSubtitle == index) markSelected(item);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(2), 0, dp(2));
            list.addView(item, lp);
        }

        // 322.3.82: owner-only — pin the currently selected subtitle to this server.
        if (ownerMode && captions && selectedSubtitle >= 0 && selectedSubtitle < catalog.length()) {
            JSONObject current = catalog.optJSONObject(selectedSubtitle);
            boolean alreadyPinned = current != null && current.optBoolean("pinned", false);
            final int pinIndex = selectedSubtitle;
            TextView pin = listRow(chip(
                    alreadyPinned ? "📌  إلغاء تثبيت هذه الترجمة" : "📌  تثبيت هذه الترجمة لهذا السيرفر",
                    14, () -> {
                        listener.pinRequested(pinIndex);
                        showTransientValue("جارٍ التثبيت…", 900);
                        hidePanel();
                    }));
            pin.setBackground(round(0xff3a2f12, 0xfff7bc3d, 2, 14));
            pin.setTextColor(Color.WHITE);
            LinearLayout.LayoutParams pinLp = new LinearLayout.LayoutParams(-1, -2);
            pinLp.setMargins(0, dp(12), 0, dp(2));
            list.addView(pin, pinLp);
        }

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
    }

    // 322.3.82: keep the list's 📌 marker in step with what the owner just pinned.
    public void setPinned(int index, boolean pinned) {
        if (closed) return;
        try {
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject o = catalog.optJSONObject(i);
                if (o != null) o.put("pinned", pinned && i == index);
            }
        } catch (Exception ignored) {}
    }

    public void selectDefault(int index) {
        if (index >= 0 && index < catalog.length()) {
            selectedSubtitle = index;
            resetSegment();
            captions = true;
            listener.subtitleRequested(index);
        }
    }

    private void resetSegment() {
        segmentMode = false;
        segmentBucket = Long.MIN_VALUE;
        segmentAnswered = Long.MIN_VALUE;
        segmentAskedAt = 0L;
    }

    // 322.3.92: the selected subtitle is exclusive/limited — switch to pieces.
    public void enableSegmentMode(int index, String notice) {
        if (closed || selectedSubtitle != index) return;
        resetSegment();
        segmentMode = true;
        cues = new JSONArray();
        if (notice != null && !notice.trim().isEmpty()) showTransientValue(notice.trim(), 4500);
    }

    public void setSegmentCues(int index, long bucket, JSONArray value, String notice) {
        if (closed || !segmentMode || selectedSubtitle != index) return;
        if (notice != null && !notice.trim().isEmpty()) showTransientValue(notice.trim(), 5000);
        if (bucket != segmentBucket) return; // stale piece: the clock moved on
        cues = value == null ? new JSONArray() : value;
        segmentAnswered = bucket;
    }

    private void requestSegment(double time) {
        if (!segmentMode || selectedSubtitle < 0) return;
        double t = Math.max(0d, time);
        long bucket = (long) Math.floor(t / 4.0) * 4L;
        long now = System.currentTimeMillis();
        boolean moved = bucket != segmentBucket;
        boolean retry = segmentAnswered != segmentBucket && now - segmentAskedAt > 5000L;
        if (!moved && !retry) return;
        segmentBucket = bucket;
        segmentAskedAt = now;
        try { listener.segmentRequested(selectedSubtitle, t); } catch (Exception ignored) {}
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
        subtitleOffsetMs = Math.max(-600000L, Math.min(600000L, subtitleOffsetMs + deltaMs));
        double seconds = subtitleOffsetMs / 1000.0;
        String sign = seconds > 0 ? "+" : "";
        showTransientValue("مزامنة " + sign + String.format(Locale.US, "%.1f", seconds) + " ث", 850);
    }

    private void requestSave() {
        if (!ownerMode) return;
        listener.saveRequested(subtitleOffsetMs);
        showTransientValue("جارٍ الحفظ…", 900);
    }

    public void showSaveResult(boolean ok, String text) {
        if (closed) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) value = ok ? "تم الحفظ" : "تعذّر الحفظ";
        if (ok) hideDecisionBar();
        showTransientValue(value, ok ? 1600 : 2200);
    }

    public void setSubtitleOffsetMs(long value) {
        subtitleOffsetMs = Math.max(-600000L, Math.min(600000L, value));
    }

    // 322.3.95: ten clear steps (10%..100%). Saved odd values from older
    // builds (5, 15, 35...) snap onto the 10-step ladder on the next press.
    static final int SUBTITLE_BG_STEP = 10;

    private void adjustSubtitleBackground(int delta) {
        int stepped = (int) Math.round(subtitleBackgroundOpacity / (double) SUBTITLE_BG_STEP)
                * SUBTITLE_BG_STEP;
        subtitleBackgroundOpacity = Math.max(0, Math.min(100, stepped + delta));
        prefs.edit()
                .putInt("background_opacity", subtitleBackgroundOpacity)
                .putBoolean("background", subtitleBackgroundOpacity > 0)
                .apply();
        applySubtitleText(currentSubtitleText);
        showTransientValue("خلفية " + toArabicDigits(subtitleBackgroundOpacity) + "٪", 750);
    }

    // Light at the start, then each press darkens evenly:
    // 10%≈4% black, 30%≈17%, 50%≈34%, 70%≈53%, 100%≈86% (never a hard black slab).
    static int subtitleBackgroundAlphaFor(int opacity) {
        if (opacity <= 0) return 0;
        double level = Math.min(100, opacity) / 100.0;
        int alpha = (int) Math.round(220.0 * Math.pow(level, 1.35));
        return Math.max(8, Math.min(220, alpha));
    }

    private int subtitleBackgroundAlpha() {
        return subtitleBackgroundAlphaFor(subtitleBackgroundOpacity);
    }

    private void applySubtitleText(String text) {
        currentSubtitleText = text == null ? "" : text;
        subtitle.setTextColor(subtitleColor);
        subtitle.setBackgroundColor(Color.TRANSPARENT);
        // 322.3.96: the pill is drawn by SubtitlePillTextView (true half-circle
        // ends, never clipped, no text-shadow doubling).
        subtitle.setPillColor(Color.argb(subtitleBackgroundAlpha(), 0, 0, 0));
        subtitle.setText(currentSubtitleText);
    }

    private String resizeModeButtonLabel() {
        if (resizeMode == 1) return "↔";
        if (resizeMode == 2) return "100%";
        return "▭";
    }

    private void refreshResizeModeButton() {
        if (resizeModeButton != null) resizeModeButton.setText(resizeModeButtonLabel());
    }

    private void cycleResizeMode() {
        resizeMode = (resizeMode + 1) % 3;
        prefs.edit().putInt("resize_mode", resizeMode).apply();
        applyResizeMode();
        refreshResizeModeButton();
        if (resizeMode == 0) showTransientValue("▭  ملاءمة", 800);
        else if (resizeMode == 1) showTransientValue("↔  تمديد", 800);
        else showTransientValue("100%  ملء الشاشة", 800);
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
                // Use ExoPlayer's content clock so subtitle time stays tied to the
                // movie itself even if the stream exposes ad/timeline periods.
                double time = (player.getContentPosition() - subtitleOffsetMs) / 1000.0;
                if (segmentMode) requestSegment(time);
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
        removeDirectCover();

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
