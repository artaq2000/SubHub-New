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
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
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
        // 322.3.103: owner pins only the subtitle timing for this film (or episode)
        // on this card — the saved server and the pinned subtitle stay untouched.
        void syncPinRequested(long subtitleOffsetMs);
        // 322.3.109: a subtitle file from this device (everyone: visitor,
        // subscriber, owner). It stays on this device for this film/episode.
        void localSubtitlePickRequested();
        void localSubtitleRemoved();
        void localSubtitleOffsetSaved(long subtitleOffsetMs);
        // 322.3.110: «مشاهدة تلقائية» — this server did not play, try the next one
        // (no message in between), or it plays (remember it for next time).
        void autoFailed(String reason);
        void autoSucceeded();
        // 322.3.111: where the viewer is now (every few seconds, on pause/close
        // and at the end), so the site can show «أكمل · 17:20» / «✓ تمت المشاهدة».
        void progress(long positionMs, long durationMs);
        // 322.3.112: «الحلقة التالية» — countdown ended or «▶ شغّل الآن».
        void nextEpisode();
        // 322.3.114: an episode picked in «حلقات» (menu ⋮).
        void episodeChosen(int season, int episode);
        // 322.3.120: fbox under several names — the page did not open on this name
        // (ok=false), or it opened on this name after another one failed (ok=true).
        default void fboxHost(String host, boolean ok) {}
        // 322.3.123: the site's own page said it moved («X has a new home: Y»): fromHost → toHost.
        default void siteMoved(String fromHost, String toHost) {}
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
    // 322.3.120: not final — an fbox page that does not open on its name moves to
    // the next name (the same page path on another name of the same site).
    private String source;
    private String allowedHost;
    private final String resumeKey;
    private final String preferredServerKey;
    private final String preferredServerLabel;
    private final boolean interactiveSource;
    private final boolean autoServer;
    private final boolean ownerMode;
    private final boolean openChooser;
    // 322.3.79: saved server player URL (yellow card). When set, automatic
    // playback opens it directly instead of the Moviesmod server list.
    // 322.3.113: not final — when the saved player link does not play, the same
    // server is tried once more through its Moviesmod page (embedFallbackUsed).
    private String directEmbedUrl;
    private boolean embedFallbackUsed = false;
    // 322.3.113: Moviesmod «Just a quick check» page — shown to the viewer, who
    // presses «Continue» once (it lasts a day); the search waits, then goes on.
    private boolean gateShown = false;
    // 322.3.124: a page of the probe is loading (between its start and its finish). While
    // one loads, nothing of it is ever shown to the viewer during the check.
    private boolean probeLoading = false;
    // 322.3.125: once the site's check is passed the player starts again from the start —
    // as when the viewer closes it and presses ▶ again, which is what plays (going on in
    // the same page after the check did not). Once per player. The saved player link is
    // taken back for it (a failed try of it clears directEmbedUrl).
    private final String originalDirectEmbedUrl;
    private boolean gateRestarted = false;
    private int gateRestartGeneration = 0;
    // 322.3.125: looks at a check that is verifying by itself (nothing to press) before its
    // page is shown all the same.
    private int gateLooks = 0;
    // The words of the site's check («Just a quick check» / «Continue to Moviesmod»…).
    private static final String GATE_WORDS =
            "just a quick check|continue to moviesmod|real person,? not a bot|verify you are human|checking your browser";
    // 322.3.84: teal Vidnest card. Vidnest pages request two stream families:
    // a clean self-contained "/proxy?url=...m3u8&headers=..." link (https all the
    // way, headers embedded) and a "/hls/<base64>" chain that goes through a plain
    // http IP and fails in ExoPlayer. In this mode only the /proxy link is used
    // (the other is a late fallback), the provider page is never allowed to load
    // the held link itself, and ExoPlayer starts at once.
    private final boolean vidnestMode;
    // 322.3.98: open-server cards (open1/open2). No built-in list of server
    // names: every server button on the Moviesmod page counts, and the owner
    // may tap one directly on the page instead of using the chooser.
    private final boolean openServers;
    // 322.3.106: verify-first open cards (open3/open4). While the owner chooses,
    // a tapped server plays in the Moviesmod page's own player — nothing is
    // captured, so an empty or broken server never ends in a black screen.
    // «💾 حفظ هذا السيرفر» saves the last server tapped; playback afterwards
    // (owner test and viewers) is the normal capture path with subtitles.
    private final boolean verifyFirst;
    // 322.3.112: our own episode files in the same black screen («شاشة واحدة»).
    //   OWN_FILE: a direct video link (R2 / mp4 / m3u8) — no page, plays at once.
    //   OWN_PAGE: our player page (worker / StreamHG) opened hidden; its video
    //             link is taken like a server's.
    static final int OWN_NONE = 0, OWN_FILE = 1, OWN_PAGE = 2;
    private final int ownKind;
    private String ownReferer = "";
    private String verifyLabel = "";
    private String verifyEmbedUrl = "";
    private boolean verifySaving = false;
    private TextView verifySaveButton;
    private LinearLayout verifyBar;
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
    // 322.3.126: over every start for the viewer — the provider page is never shown.
    private FrameLayout directCover;
    private TextView directCoverStage;
    // 322.3.126: our own «▶ اضغط هنا لتشغيل الفيديو» on that screen; it passes the
    // press to the hidden page instead of showing the page and its ads.
    private TextView coverTapButton;
    private int coverTapGeneration = 0;
    // 322.3.126: captured links are looked at one by one (CapturedLinkCheck) before one plays.
    private java.util.concurrent.ExecutorService linkCheckExec;
    private final java.util.concurrent.atomic.AtomicInteger linkCheckGen = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.HashSet<String> linkChecked = new java.util.HashSet<>();
    private int linkChecksRunning = 0;
    private int linkSkipped = 0;
    // 322.3.127: video files (MKV / MP4) taken from the page — played as a file, not a playlist.
    private final java.util.HashSet<String> capturedFiles = new java.util.HashSet<>();
    private final ArrayList<android.animation.Animator> coverAnimators = new ArrayList<>();
    private int directRevealGeneration = 0;
    private boolean chooserAutoRequested = false;
    // 322.3.118: fbox with no saved page for this film — the owner starts from
    // their search; the film's title is typed into their search box for him.
    private String searchText = "";
    private String searchedUrl = "";
    private int searchRuns = 0;
    // 322.3.121: the owner adds a server from fbox on the name he chose — shown on
    // the page, and named in a message that stays when its page does not open
    // (blocked / not found / the site's server down / no answer). He changes the
    // name himself in «✏️ الأسماء»; nothing moves to another name here.
    private String fboxOwnerName = "";
    private TextView fboxNameTag;
    private String fboxPageProblem = "";
    // 322.3.123: «the site moved from X to Y» — shown to the owner on the new page.
    private String ownerMoveNote = "";
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
    // 322.3.109: the subtitle file added from this device. It is not part of
    // SubHub's catalog: selectedSubtitle == LOCAL_SUBTITLE while it is shown.
    static final int LOCAL_SUBTITLE = -2;
    private String localSubtitleName = "";
    private JSONArray localSubtitleCues = null;
    private long localSubtitleOffsetMs = 0L;        // timing saved for the device file
    private long catalogSubtitleOffsetMs = 0L;      // SubHub's timing, kept while the file is shown
    private int defaultCatalogIndex = -1;
    private boolean resumeAfterLocalPick = false;
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
    // 322.3.108: when a saved server does not play, the screen stays black and
    // shows one clear way out (another server / button / player). Only the
    // owner sees why it failed.
    private String firstFailureReason = "";
    private LinearLayout failurePanel;
    // 322.3.110: one attempt of the automatic server search (index of total).
    private int autoIndex = -1;
    private int autoTotal = 0;
    private boolean autoMore = false;   // another server (or the site player) comes after this one
    private boolean autoReported = false;
    // 322.3.112: smart waiting instead of 8 s for every step (EpisodeTiming): a
    // page that does not open at all is skipped at once; a server that is alive
    // gets time to answer, and a server known to be slow gets twice its usual time.
    private long autoExpectMs = 0L;
    // 322.3.112: «الحلقة التالية» card — 10 s after the last subtitle line (the
    // credits), or 40 s before the end when there is no usable subtitle.
    static final int NEXT_COUNTDOWN_S = EpisodeTiming.NEXT_COUNTDOWN_S;
    private String nextLabel = "";          // empty = no next episode
    private boolean nextAuto = true;        // the owner may switch the countdown off
    private boolean creditsReached = false; // the episode counts as watched
    private boolean nextShown = false;
    private boolean nextCancelled = false;
    private int nextGeneration = 0;
    private LinearLayout nextCard;
    private TextView nextCount;
    private JSONArray creditsCuesRef = null;
    private long creditsLastCueMs = -1L;
    // 322.3.108: a subtitle that never arrives is reported, not left silent.
    private int subtitleAskSeq = 0;
    private int subtitleAnsweredSeq = 0;
    private String subscriberStageBase = "";
    private int subscriberStageDots = 0;
    private int subscriberStageAnimationGeneration = 0;
    // 322.3.120: fbox under several names (fbox.gd, vumoo.gd…, the owner's order).
    // When the page does not open on this name — no answer, «not found», the site's
    // server down, or a page without the server grid (blocked / ads) — the same
    // server opens on the next name. The viewer gets no message until every name
    // has failed. A page that shows the site's server grid has opened: a server
    // missing from it is not the name's fault, so no other name is tried then.
    private final ArrayList<String> fboxNextHosts = new ArrayList<>();
    private final ArrayList<String> fboxTriedHosts = new ArrayList<>();
    private boolean fboxSwitched = false;
    private boolean fboxOkReported = false;
    private int fboxGridSize = 0;
    private String fboxReasons = "";
    private static final int FBOX_GRID_ALIVE = 10;   // fbox lists about 50 servers
    // 322.3.123: the same names system for both families of sites — "fbox" (fbox,
    // FlixFrame, DopeBox…: /watch/movie-…) and "mm" (Moviesmod, StreamBoppo, StreamDude…:
    // /movie/27205) — only the words shown differ.
    private String siteFamily = "";
    // 322.3.123: page scripts from the app (assets): one reads «X has a new home: Y» on the
    // site's own page (site_move.js), one closes the site's own pop-ups over the server
    // buttons with their «Later» / «✕» (popup_close.js).
    private String siteMoveScript = "";
    private String popupCloseScript = "";
    private final java.util.HashSet<String> movesSeen = new java.util.HashSet<>();
    private int pageScriptsGeneration = 0;
    // 322.3.123: the owner's «⚙️» for this server — how many times the play button is
    // pressed, and how long between the presses. -1 = the usual way.
    private int tapRuleTaps = -1;
    private long tapRuleGapMs = 2000L;
    // 322.3.123: a page of the site that shows no server at all after this long (a dead
    // name, an empty page) gives way to the next name at once (before: 18 s).
    private static final long NO_GRID_WAIT_MS = 8000L;
    private int gridWaitGeneration = 0;
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
                ownerMode, openChooser, directEmbedUrl, false, false, listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl,
                              boolean vidnestMode, Listener listener) {
        this(activity, parent, source, catalog, resumeKey, allowedHost,
                preferredServerKey, preferredServerLabel, interactiveSource,
                ownerMode, openChooser, directEmbedUrl, vidnestMode, false, listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl,
                              boolean vidnestMode, boolean openServers, Listener listener) {
        this(activity, parent, source, catalog, resumeKey, allowedHost,
                preferredServerKey, preferredServerLabel, interactiveSource,
                ownerMode, openChooser, directEmbedUrl, vidnestMode, openServers, false, listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl,
                              boolean vidnestMode, boolean openServers, boolean verifyFirst,
                              Listener listener) {
        this(activity, parent, source, catalog, resumeKey, allowedHost,
                preferredServerKey, preferredServerLabel, interactiveSource,
                ownerMode, openChooser, directEmbedUrl, vidnestMode, openServers, verifyFirst,
                OWN_NONE, listener);
    }

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, String resumeKey, String allowedHost,
                              String preferredServerKey, String preferredServerLabel,
                              boolean interactiveSource, boolean ownerMode,
                              boolean openChooser, String directEmbedUrl,
                              boolean vidnestMode, boolean openServers, boolean verifyFirst,
                              int ownKind, Listener listener) {
        this.ownKind = (ownKind == OWN_FILE || ownKind == OWN_PAGE) && !interactiveSource ? ownKind : OWN_NONE;
        this.vidnestMode = vidnestMode;
        this.verifyFirst = verifyFirst && openServers && !vidnestMode;
        this.openServers = openServers && !vidnestMode;
        this.activity = activity;
        this.directEmbedUrl = (directEmbedUrl == null || interactiveSource) ? "" : directEmbedUrl.trim();
        this.originalDirectEmbedUrl = this.directEmbedUrl;
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
        // 322.3.115: the player's gear (speed / audio) lives here now.
        addCompactQuick("⚙", this::openSettings);
        if (this.ownerMode) addCompactQuick("حفظ", this::requestSave);
        addCompactQuick("A−", () -> adjustSubtitleSize(-2));
        addCompactQuick("A+", () -> adjustSubtitleSize(2));
        addCompactQuick("↑", () -> adjustSubtitlePosition(4));
        addCompactQuick("↓", () -> adjustSubtitlePosition(-4));
        // 322.3.103: one ⏱ button opens the sync window (replaces −.5 / +.5).
        TextView syncQuick = addCompactQuick("⏱ توقيت", this::openSyncPanel);
        LinearLayout.LayoutParams syncQuickLp = (LinearLayout.LayoutParams) syncQuick.getLayoutParams();
        syncQuickLp.width = dp(78);
        syncQuick.setLayoutParams(syncQuickLp);
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
            // 322.3.114: «الحلقة التالية» follows the phone turning.
            if (nextShown) handler.post(this::nextCardOrientationCheck);
        });
        root.setOnSystemUiVisibilityChangeListener(v -> {
            if (!closed) handler.postDelayed(this::enterImmersive, 220);
        });

        buildDecisionBar();
        // 322.3.112: a direct file starts after the activity has set the resume
        // point and the search step (next loop turn); everything else captures.
        if (this.ownKind == OWN_FILE) handler.post(this::beginOwnFile);
        else beginCapture();
        handler.post(tick);
    }

    // 322.3.112: the page our own files are opened from (Referer).
    public void setOwnReferer(String referer) {
        ownReferer = referer == null ? "" : referer.trim();
    }

    private void beginOwnFile() {
        if (closed || playing) return;
        if (subscriberStartupAttempt == 0) subscriberStartupAttempt = 1;
        candidate = source;
        candidateHeaders = new HashMap<>();
        if (!ownReferer.isEmpty()) candidateHeaders.put("Referer", ownReferer);
        startStream();
    }

    // Our player pages hand the video over through these paths.
    static boolean isOwnWorkerMedia(Uri u) {
        if (u == null || u.getHost() == null || !"https".equalsIgnoreCase(u.getScheme())) return false;
        String h = u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
        return h.endsWith(".workers.dev") && (path.startsWith("/hls") || path.startsWith("/stream"));
    }

    static boolean looksLikeHls(String url) {
        if (url == null) return false;
        String v = url.toLowerCase(Locale.ROOT);
        if (v.contains(".m3u8") || v.contains("mpegurl")) return true;
        try {
            String path = Uri.parse(url).getPath();
            return path != null && path.toLowerCase(Locale.ROOT).startsWith("/hls");
        } catch (Exception ignored) { return false; }
    }

    // OWN_PAGE: a page that plays a plain file (no playlist) shows it in its
    // <video>; read it from there.
    private String ownPageMediaScript() {
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{if(window.__subhubOwnMedia)return;window.__subhubOwnMedia=1;"
                + "var t=" + bridgeToken + ",n=0;var iv=setInterval(function(){n++;try{"
                + "var vs=document.querySelectorAll('video');for(var i=0;i<vs.length;i++){"
                + "var u=String(vs[i].currentSrc||vs[i].src||'');"
                + "if(/^https:\\/\\//i.test(u)&&window.SubHubSourceChoice&&window.SubHubSourceChoice.mediaFound){"
                + "window.SubHubSourceChoice.mediaFound(t,u);clearInterval(iv);return;}}"
                + "var v=vs[0];if(v&&v.paused){try{v.muted=true;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(_){}}"
                + "}catch(_){}if(n>60)clearInterval(iv);},500);}catch(_){}})();";
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

    // 322.3.108: owner-only wording of why the SubHub player could not play.
    static String playbackFailureReason(PlaybackException e) {
        try {
            Throwable c = e;
            while (c != null) {
                if (c instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    int code = ((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) c).responseCode;
                    return "السيرفر رفض البث خارج صفحته (HTTP " + code + ")";
                }
                if (c instanceof androidx.media3.datasource.HttpDataSource.HttpDataSourceException) {
                    return "تعذّر الوصول إلى البث (" + e.getErrorCodeName() + ")";
                }
                if (c instanceof androidx.media3.common.ParserException) {
                    return "رابط البث ليس فيديو صالحاً (" + e.getErrorCodeName() + ")";
                }
                c = c.getCause();
            }
            return e.getErrorCodeName();
        } catch (Exception ignored) {
            return "خطأ غير معروف";
        }
    }

    // 322.3.111: the site knows a later stop for this episode (maybe watched on
    // another server or in the site player): continue from there.
    public void setResumeFromMs(long ms) {
        if (closed || ms < 5000L) return;
        if (ms > pendingResumeMs + 3000L) pendingResumeMs = ms;
    }

    // 322.3.111: where the viewer is now, for the site's episode list.
    private long lastProgressReported = -1L;
    private void reportProgress(long positionMs, long durationMs) {
        if (durationMs <= 0L || positionMs < 0L) return;
        // 322.3.112: from the credits on, the episode stays «✓ تمت المشاهدة».
        if (creditsReached) positionMs = Math.max(positionMs, durationMs);
        if (Math.abs(positionMs - lastProgressReported) < 1000L) return;
        lastProgressReported = positionMs;
        try { listener.progress(positionMs, durationMs); } catch (Exception ignored) {}
    }

    // 322.3.112: the episode that comes after this one («الحلقة ٤» or
    // «الموسم ٢ · الحلقة ١»); auto=false shows the card without a countdown.
    public void setNextEpisode(String label, boolean auto) {
        if (closed || interactiveSource) return;
        String v = label == null ? "" : label.trim();
        nextLabel = v.length() > 60 ? v.substring(0, 60) : v;
        nextAuto = auto;
        updateBottomRow();
    }

    // End of the last dialogue line of the subtitle on screen (video time), -1 if
    // unknown. 322.3.114: the translator's credit at the very end and a lone line
    // after a long silence are not dialogue (EpisodeTiming.lastDialogueEndMs).
    // Exclusive subtitles arrive in 4-second pieces: their end is not known.
    private long creditsCacheOffset = Long.MIN_VALUE, creditsCacheDuration = -1L;
    private long lastSubtitleLineMs(long durationMs) {
        if (segmentMode || cues == null) return -1L;
        if (cues != creditsCuesRef || subtitleOffsetMs != creditsCacheOffset || durationMs != creditsCacheDuration) {
            creditsCuesRef = cues;
            creditsCacheOffset = subtitleOffsetMs;
            creditsCacheDuration = durationMs;
            int n = cues.length();
            double[] st = new double[n], en = new double[n];
            String[] tx = new String[n];
            for (int i = 0; i < n; i++) {
                JSONObject c = cues.optJSONObject(i);
                st[i] = c == null ? 0d : c.optDouble("start", 0d);
                en[i] = c == null ? 0d : c.optDouble("end", 0d);
                tx[i] = c == null ? "" : c.optString("text", "");
            }
            creditsLastCueMs = EpisodeTiming.lastDialogueEndMs(st, en, tx, subtitleOffsetMs, durationMs);
        }
        return creditsLastCueMs;
    }

    private void checkCredits() {
        if (closed || interactiveSource || player == null || !playbackReady) return;
        long duration = player.getDuration();
        if (duration <= 0L || duration == C.TIME_UNSET) return;
        long position = Math.max(0L, player.getContentPosition());
        long at = EpisodeTiming.creditsAtMs(duration, lastSubtitleLineMs(duration));
        if (position >= at) {
            if (!creditsReached) {
                creditsReached = true;
                reportProgress(duration, duration);
                clearResumePosition();
            }
            if (!nextLabel.isEmpty() && !nextShown && !nextCancelled) showNextCard();
        } else if (position < at - 30000L) {
            // Went back well before the credits: the card may come again.
            if (nextShown) hideNextCard();
            nextShown = false;
            nextCancelled = false;
        }
    }

    // 322.3.114: portrait — the card above the controls at the right (as before);
    // landscape — one thin strip at the very bottom, under the progress line.
    private boolean nextCardLandscape = false;
    private int nextLeft = 0;

    private void buildNextCard(boolean landscape) {
        if (nextCard != null) {
            ViewGroup parent = (ViewGroup) nextCard.getParent();
            if (parent != null) parent.removeView(nextCard);
            nextCard = null;
        }
        nextCardLandscape = landscape;
        nextCard = new LinearLayout(activity);
        nextCard.setBackground(round(0xee0b1725, 0xccF7BC3D, 1, landscape ? 14 : 18));
        nextCard.setElevation(dp(28));
        nextCard.setClickable(true);
        TextView title = new TextView(activity);
        title.setTag("next-title");
        title.setTextColor(Color.WHITE);
        title.setTextSize(landscape ? 14 : 16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextDirection(View.TEXT_DIRECTION_RTL);
        title.setSingleLine(landscape);
        nextCount = new TextView(activity);
        nextCount.setTextColor(Color.rgb(184, 197, 214));
        nextCount.setTextSize(13);
        nextCount.setTextDirection(View.TEXT_DIRECTION_RTL);
        nextCount.setSingleLine(true);
        TextView play = chip("▶ شغّل الآن", landscape ? 13 : 14, this::goNextEpisode);
        play.setBackground(round(0xff12301d, 0xff22c55e, 1, 14));
        TextView cancel = chip("✕ إلغاء", landscape ? 13 : 14, this::cancelNextEpisode);
        FrameLayout.LayoutParams lp;
        if (landscape) {
            nextCard.setOrientation(LinearLayout.HORIZONTAL);
            nextCard.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            nextCard.setGravity(Gravity.CENTER_VERTICAL);
            nextCard.setPadding(dp(14), dp(3), dp(6), dp(3));
            title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            nextCount.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            nextCount.setPadding(dp(10), 0, dp(10), 0);
            nextCard.addView(title, new LinearLayout.LayoutParams(-2, -1));
            nextCard.addView(nextCount, new LinearLayout.LayoutParams(-2, -1));
            nextCard.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(140), dp(36));
            a.setMargins(dp(3), 0, dp(3), 0);
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(dp(96), dp(36));
            b.setMargins(dp(3), 0, dp(3), 0);
            nextCard.addView(play, a);
            nextCard.addView(cancel, b);
            lp = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
            lp.leftMargin = dp(12);
            lp.rightMargin = dp(12);
            lp.bottomMargin = dp(4);
        } else {
            nextCard.setOrientation(LinearLayout.VERTICAL);
            nextCard.setPadding(dp(14), dp(10), dp(14), dp(12));
            title.setGravity(Gravity.RIGHT);
            nextCount.setGravity(Gravity.RIGHT);
            nextCount.setPadding(0, dp(2), 0, dp(8));
            nextCard.addView(title, new LinearLayout.LayoutParams(-1, -2));
            nextCard.addView(nextCount, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(0, dp(42), 1.3f);
            a.setMargins(dp(3), 0, dp(3), 0);
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(42), 1f);
            b.setMargins(dp(3), 0, dp(3), 0);
            row.addView(play, a);
            row.addView(cancel, b);
            nextCard.addView(row, new LinearLayout.LayoutParams(-1, -2));
            lp = new FrameLayout.LayoutParams(dp(290), -2, Gravity.BOTTOM | Gravity.RIGHT);
            lp.bottomMargin = dp(96);
            lp.rightMargin = dp(18);
        }
        title.setText("الحلقة التالية: " + nextLabel);
        root.addView(nextCard, lp);
    }

    private void refreshNextCount() {
        if (nextCount == null) return;
        nextCount.setText(nextAuto ? "تبدأ بعد " + toArabicDigits(Math.max(0, nextLeft)) : "التشغيل التلقائي متوقف");
    }

    // The phone turned while the card is on screen: the other layout, same countdown.
    private void nextCardOrientationCheck() {
        if (closed || !nextShown || nextCard == null || nextCard.getVisibility() != View.VISIBLE) return;
        boolean landscape = root.getWidth() > root.getHeight();
        if (landscape == nextCardLandscape) return;
        buildNextCard(landscape);
        refreshNextCount();
        nextCard.bringToFront();
        updateBottomRow();
    }

    private void showNextCard() {
        if (closed || root == null) return;
        nextShown = true;
        boolean landscape = root.getWidth() > root.getHeight();
        if (nextCard == null || landscape != nextCardLandscape) buildNextCard(landscape);
        View t = nextCard.findViewWithTag("next-title");
        if (t instanceof TextView) ((TextView) t).setText("الحلقة التالية: " + nextLabel);
        nextCard.setVisibility(View.VISIBLE);
        nextCard.bringToFront();
        updateBottomRow();
        final int gen = ++nextGeneration;
        nextLeft = NEXT_COUNTDOWN_S;
        refreshNextCount();
        if (!nextAuto) return;
        handler.postDelayed(() -> countdownNext(gen, NEXT_COUNTDOWN_S - 1), 1000L);
    }

    private void countdownNext(int gen, int left) {
        if (closed || gen != nextGeneration || nextCard == null) return;
        if (left <= 0) { goNextEpisode(); return; }
        nextLeft = left;
        refreshNextCount();
        handler.postDelayed(() -> countdownNext(gen, left - 1), 1000L);
    }

    private void hideNextCard() {
        nextGeneration++;
        if (nextCard != null) nextCard.setVisibility(View.GONE);
        updateBottomRow();
    }

    private void cancelNextEpisode() {
        nextCancelled = true;
        hideNextCard();
    }

    private void goNextEpisode() {
        if (closed || nextLabel.isEmpty()) return;
        nextCancelled = true;   // once
        hideNextCard();
        try { listener.nextEpisode(); } catch (Exception ignored) {}
    }

    // 322.3.115: «☰ الحلقات» and «⏭ التالية» under the progress line (with the
    // player's controls; the time sits between them, ⚙ moved to the ⋮ strip).
    // «☰ الحلقات» opens the episodes as cards above the line, swiped sideways.
    // Entries {s, e, t, st, cur, ok} or {h}.
    private JSONArray episodeItems = null;
    private TextView bottomNext, bottomEpisodes;
    private boolean controllerShown = false;
    private LinearLayout episodePicker;
    private int pickerSeason = -1;

    public void setEpisodes(JSONArray items) {
        if (closed || interactiveSource || items == null || items.length() < 2) return;
        episodeItems = items;
        updateBottomRow();
    }

    // Our controls on the player's own bar: the gear leaves (⚙ is in ⋮ now), the
    // time moves to the middle, and our two buttons follow the bar in and out.
    private void customizeControls() {
        if (playerView == null) return;
        try {
            int gear = activity.getResources().getIdentifier("exo_settings", "id", activity.getPackageName());
            View g = gear == 0 ? null : playerView.findViewById(gear);
            if (g != null) g.setVisibility(View.GONE);
        } catch (Exception ignored) {}
        try {
            int time = activity.getResources().getIdentifier("exo_time", "id", activity.getPackageName());
            View t = time == 0 ? null : playerView.findViewById(time);
            if (t != null && t.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) t.getLayoutParams();
                lp.gravity = Gravity.CENTER;
                t.setLayoutParams(lp);
            }
        } catch (Exception ignored) {}
        try {
            playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
                controllerShown = visibility == View.VISIBLE;
                updateBottomRow();
            });
        } catch (Exception ignored) {}
    }

    private void ensureBottomRow() {
        if (bottomNext != null || root == null) return;
        bottomNext = chip("⏭ التالية", 13, this::goNextEpisode);
        bottomNext.setBackground(round(0xcc0b1725, 0x884E718B, 1, 16));
        bottomNext.setVisibility(View.GONE);
        FrameLayout.LayoutParams a = new FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM | Gravity.LEFT);
        a.leftMargin = dp(12);
        a.bottomMargin = dp(12);
        root.addView(bottomNext, a);
        bottomEpisodes = chip("☰ الحلقات", 13, this::openEpisodePicker);
        bottomEpisodes.setBackground(round(0xcc0b1725, 0x884E718B, 1, 16));
        bottomEpisodes.setVisibility(View.GONE);
        FrameLayout.LayoutParams b = new FrameLayout.LayoutParams(-2, dp(36), Gravity.BOTTOM | Gravity.RIGHT);
        b.rightMargin = dp(12);
        b.bottomMargin = dp(12);
        root.addView(bottomEpisodes, b);
    }

    private void updateBottomRow() {
        if (closed || root == null || interactiveSource) return;
        boolean hasNext = !nextLabel.isEmpty(), hasList = episodeItems != null;
        if (!hasNext && !hasList) return;
        ensureBottomRow();
        // In landscape «الحلقة التالية» takes this same place at the credits.
        boolean cardHere = nextShown && nextCard != null && nextCard.getVisibility() == View.VISIBLE && nextCardLandscape;
        boolean pickerOpen = episodePicker != null && episodePicker.getVisibility() == View.VISIBLE;
        boolean show = controllerShown && playbackReady && !cardHere;
        bottomNext.setVisibility(show && hasNext ? View.VISIBLE : View.GONE);
        bottomEpisodes.setVisibility(show && hasList && !pickerOpen ? View.VISIBLE : View.GONE);
        if (bottomNext.getVisibility() == View.VISIBLE) bottomNext.bringToFront();
        if (bottomEpisodes.getVisibility() == View.VISIBLE) bottomEpisodes.bringToFront();
    }

    private void openEpisodePicker() {
        if (episodeItems == null || closed) return;
        collapseMenu();
        int cur = -1, first = -1;
        for (int i = 0; i < episodeItems.length(); i++) {
            JSONObject it = episodeItems.optJSONObject(i);
            if (it == null || !it.optString("h", "").isEmpty()) continue;
            int sn = it.optInt("s", 0);
            if (sn <= 0) continue;
            if (first < 0) first = sn;
            if (it.optBoolean("cur", false)) cur = sn;
        }
        if (first < 0) return;
        if (pickerSeason <= 0) pickerSeason = cur > 0 ? cur : first;
        buildEpisodePicker();
    }

    private void closeEpisodePicker() {
        if (episodePicker != null) {
            ViewGroup parent = (ViewGroup) episodePicker.getParent();
            if (parent != null) parent.removeView(episodePicker);
            episodePicker = null;
        }
        updateBottomRow();
    }

    private void buildEpisodePicker() {
        if (closed || root == null || episodeItems == null) return;
        if (episodePicker != null) {
            ViewGroup parent = (ViewGroup) episodePicker.getParent();
            if (parent != null) parent.removeView(episodePicker);
        }
        java.util.LinkedHashMap<Integer, ArrayList<JSONObject>> bySeason = new java.util.LinkedHashMap<>();
        for (int i = 0; i < episodeItems.length(); i++) {
            JSONObject it = episodeItems.optJSONObject(i);
            if (it == null || !it.optString("h", "").isEmpty()) continue;
            int sn = it.optInt("s", 0), en = it.optInt("e", 0);
            if (sn <= 0 || en <= 0) continue;
            ArrayList<JSONObject> list = bySeason.get(sn);
            if (list == null) { list = new ArrayList<>(); bySeason.put(sn, list); }
            list.add(it);
        }
        if (!bySeason.containsKey(pickerSeason)) pickerSeason = bySeason.keySet().iterator().next();

        episodePicker = new LinearLayout(activity);
        episodePicker.setOrientation(LinearLayout.VERTICAL);
        episodePicker.setPadding(dp(10), dp(6), dp(10), dp(10));
        episodePicker.setBackground(round(0xf00b1725, 0x663D6B8E, 1, 16));
        episodePicker.setElevation(dp(30));
        episodePicker.setClickable(true);

        LinearLayout head = new LinearLayout(activity);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("الحلقات");
        title.setTextColor(GOLD);
        title.setTextSize(15);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), 0, dp(10), 0);
        head.addView(title, new LinearLayout.LayoutParams(-2, dp(38)));
        title.setGravity(Gravity.CENTER_VERTICAL);
        if (bySeason.size() > 1) {
            for (Integer key : bySeason.keySet()) {
                final int k = key;
                TextView sc = chip("الموسم " + toArabicDigits(k), 13, () -> { pickerSeason = k; buildEpisodePicker(); });
                if (k == pickerSeason) sc.setBackground(round(0xff1d4d33, 0xff4ade80, 2, 14));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(34));
                lp.setMargins(dp(3), 0, dp(3), 0);
                head.addView(sc, lp);
            }
        }
        head.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView close = chip("✕", 16, this::closeEpisodePicker);
        head.addView(close, new LinearLayout.LayoutParams(dp(44), dp(36)));
        episodePicker.addView(head, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setPadding(0, dp(6), 0, 0);
        View current = null;
        for (JSONObject it : bySeason.get(pickerSeason)) {
            final int sn = it.optInt("s", 0), en = it.optInt("e", 0);
            final boolean cur = it.optBoolean("cur", false), ok = it.optBoolean("ok", true);
            String st = it.optString("st", "");
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER);
            card.setPadding(dp(8), dp(6), dp(8), dp(6));
            card.setBackground(cur ? round(0xff1d4d33, 0xff4ade80, 2, 12) : round(PANEL_2, BORDER, 1, 12));
            TextView t1 = new TextView(activity);
            t1.setText("الحلقة " + toArabicDigits(en));
            t1.setTextColor(Color.WHITE);
            t1.setTextSize(14);
            t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            t1.setGravity(Gravity.CENTER);
            card.addView(t1, new LinearLayout.LayoutParams(-1, -2));
            String full = it.optString("t", "");
            int dash = full.indexOf(" — ");
            if (dash > 0 && dash + 3 < full.length()) {
                TextView t3 = new TextView(activity);
                t3.setText(full.substring(dash + 3));
                t3.setTextColor(Color.rgb(184, 197, 214));
                t3.setTextSize(11);
                t3.setSingleLine(true);
                t3.setEllipsize(android.text.TextUtils.TruncateAt.END);
                t3.setGravity(Gravity.CENTER);
                card.addView(t3, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView t2 = new TextView(activity);
            t2.setText(cur ? "▶ الآن" : "done".equals(st) ? "✓ تمت" : "progress".equals(st) ? "● أكمل" : (ok ? "" : "قريباً"));
            t2.setTextColor(cur ? Color.rgb(134, 239, 172) : Color.rgb(184, 197, 214));
            t2.setTextSize(12);
            t2.setGravity(Gravity.CENTER);
            card.addView(t2, new LinearLayout.LayoutParams(-1, -2));
            if (!ok && !cur) card.setAlpha(0.45f);
            card.setOnClickListener(v -> {
                enterImmersive();
                if (cur || !ok) { if (cur) closeEpisodePicker(); return; }
                closeEpisodePicker();
                try { listener.episodeChosen(sn, en); } catch (Exception ignored) {}
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(128), dp(78));
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(card, lp);
            if (cur) current = card;
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        episodePicker.addView(scroll, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        lp.bottomMargin = dp(72);   // above the progress line
        root.addView(episodePicker, lp);
        episodePicker.bringToFront();
        menuButton.bringToFront();
        updateBottomRow();
        final View target = current;
        if (target != null) {
            scroll.post(() -> scroll.scrollTo(Math.max(0, target.getLeft() - (scroll.getWidth() - target.getWidth()) / 2), 0));
        }
    }

    // 322.3.115: ⚙ in the ⋮ strip (the player's own gear left its bar):
    // playback speed and, when the video has several, the audio track.
    private void openSettings() {
        if (player == null) {
            message("الإعدادات تظهر بعد تشغيل الفيديو.");
            return;
        }
        panelTitle.setText("الإعدادات");
        panelBody.removeAllViews();
        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(4), dp(4), dp(4), dp(12));

        list.addView(settingsHeader("السرعة"), new LinearLayout.LayoutParams(-1, -2));
        LinearLayout speeds = new LinearLayout(activity);
        speeds.setOrientation(LinearLayout.HORIZONTAL);
        speeds.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        float now = player.getPlaybackParameters().speed;
        float[] values = {0.75f, 1f, 1.25f, 1.5f, 2f};
        String[] names = {"٠٫٧٥×", "عادي", "١٫٢٥×", "١٫٥×", "٢×"};
        for (int i = 0; i < values.length; i++) {
            final float v = values[i];
            TextView c = chip(names[i], 13, () -> {
                if (player != null) player.setPlaybackSpeed(v);
                openSettings();
            });
            if (Math.abs(now - v) < 0.01f) c.setBackground(round(0xff1d4d33, 0xff4ade80, 2, 14));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            speeds.addView(c, lp);
        }
        list.addView(speeds, new LinearLayout.LayoutParams(-1, -2));

        list.addView(settingsHeader("الصوت"), new LinearLayout.LayoutParams(-1, -2));
        int audio = 0;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(i);
                audio++;
                String name = f.label != null && !f.label.trim().isEmpty() ? f.label.trim()
                        : (f.language != null && !f.language.isEmpty()
                            ? new Locale(f.language).getDisplayLanguage(new Locale("ar")) : "الصوت " + toArabicDigits(audio));
                final TrackSelectionOverride o = new TrackSelectionOverride(g.getMediaTrackGroup(), i);
                TextView row = listRow(chip(name, 15, () -> {
                    if (player == null) return;
                    player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                            .setOverrideForType(o).build());
                    hidePanel();
                }));
                if (g.isTrackSelected(i)) markSelected(row);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, dp(2), 0, dp(2));
                list.addView(row, lp);
            }
        }
        if (audio == 0) {
            TextView none = settingsHeader("صوت واحد لهذا الفيديو");
            none.setTextColor(Color.rgb(184, 197, 214));
            none.setTypeface(android.graphics.Typeface.DEFAULT);
            list.addView(none, new LinearLayout.LayoutParams(-1, -2));
        }
        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
    }

    private TextView settingsHeader(String text) {
        TextView h = new TextView(activity);
        h.setText(text);
        h.setTextColor(GOLD);
        h.setTextSize(14);
        h.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        h.setGravity(Gravity.RIGHT);
        h.setTextDirection(View.TEXT_DIRECTION_RTL);
        h.setPadding(dp(8), dp(8), dp(8), dp(4));
        return h;
    }

    // 322.3.110: this player is one attempt of «مشاهدة تلقائية».
    public void setAutoAttempt(int index, int total, boolean more) {
        setAutoAttempt(index, total, more, 0L);
    }

    // 322.3.112: + how long this server usually takes to start (0 = unknown).
    public void setAutoAttempt(int index, int total, boolean more, long expectMs) {
        if (closed || interactiveSource || index < 0 || total <= 0) return;
        autoIndex = index;
        autoTotal = total;
        autoMore = more;
        autoReported = false;
        autoExpectMs = Math.max(0L, Math.min(120000L, expectMs));
        if (!subscriberStageBase.isEmpty()) showSubscriberStage(subscriberStageBase);
        // The step already running gets the new limit too.
        if (more && subscriberStartupPhase > 0 && !playbackReady) {
            subscriberStartupDeadlineMs = SystemClock.elapsedRealtime()
                    + EpisodeTiming.autoPhaseLimitMs(subscriberStartupPhase, autoExpectMs);
        }
    }

    private boolean autoActive() {
        return autoIndex >= 0 && !interactiveSource;
    }

    // 322.3.123: "fbox" or "mm" (Moviesmod family) — the words and the reports.
    public void setSiteFamily(String family) {
        siteFamily = "mm".equals(family) || "fbox".equals(family) ? family : "";
    }

    // 322.3.123: the move-notice reader and the pop-up closer (assets of the app).
    public void setPageScripts(String moveScript, String popupScript) {
        siteMoveScript = moveScript == null ? "" : moveScript;
        popupCloseScript = popupScript == null ? "" : popupScript;
    }

    // 322.3.123: the owner's «⚙️» for this server: taps on play (0–6) and the time
    // between them. A negative count keeps the usual way.
    public void setTapRule(int taps, long gapMs) {
        if (taps < 0) {
            tapRuleTaps = -1;
            return;
        }
        tapRuleTaps = Math.min(6, taps);
        tapRuleGapMs = Math.max(500L, Math.min(6000L, gapMs));
    }

    private String siteIcon() {
        return "mm".equals(siteFamily) ? "🎬" : "📦";
    }

    private String siteWord() {
        return "mm".equals(siteFamily) ? "Moviesmod" : "fbox";
    }

    // 322.3.120: the other fbox names, in order (the page chose this one first).
    public void setFboxFallbackHosts(java.util.List<String> hosts) {
        fboxNextHosts.clear();
        fboxTriedHosts.clear();
        if (closed || interactiveSource || hosts == null || allowedHost.isEmpty() || ownKind != OWN_NONE) return;
        for (String raw : hosts) {
            String h = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            if (h.isEmpty() || h.length() > 80 || h.equals(allowedHost) || fboxNextHosts.contains(h)
                    || !h.matches("([a-z0-9-]+\\.)+[a-z]{2,12}")) continue;
            fboxNextHosts.add(h);
            if (fboxNextHosts.size() >= 11) break;
        }
        if (fboxNextHosts.isEmpty()) return;
        fboxTriedHosts.add(allowedHost);
        // The owner sees which name is tried from the first step on.
        if (ownerMode && !subscriberStageBase.isEmpty()) showSubscriberStage(subscriberStageBase);
    }

    // The fbox page itself is still opening on this name (not a server opened from it).
    private boolean fboxPageStep() {
        return !fboxTriedHosts.isEmpty() && !interactiveSource && !closed && !playing && !playbackReady
                && directEmbedUrl.isEmpty() && !gateShown
                && subscriberStartupPhase >= 1 && subscriberStartupPhase <= 2;
    }

    private boolean isFboxPageRequest(Uri u) {
        if (fboxTriedHosts.isEmpty() || u == null || u.getHost() == null) return false;
        String h = u.getHost().toLowerCase(Locale.ROOT);
        return h.equals(allowedHost) || h.endsWith("." + allowedHost);
    }

    private void fboxNote(String host, String reason) {
        String r = reason == null || reason.trim().isEmpty() ? "لم تفتح الصفحة" : reason.trim();
        // Starts with an Arabic word so every line reads right to left.
        if (fboxReasons.length() < 1500) fboxReasons += (fboxReasons.isEmpty() ? "" : "\n") + "• على " + host + ": " + r;
    }

    // The same server on the next name. False when there is none (or it is not
    // the name's fault) — then the usual steps go on.
    private boolean tryNextFboxHost(String reason) {
        if (!fboxPageStep() || fboxGridSize >= FBOX_GRID_ALIVE || fboxNextHosts.isEmpty()) return false;
        final String failed = allowedHost;
        final String next = fboxNextHosts.get(0);
        String moved;
        try {
            Uri u = Uri.parse(source);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) return false;
            moved = u.buildUpon().authority(next).build().toString();
        } catch (Exception e) {
            return false;
        }
        fboxNextHosts.remove(0);
        fboxNote(failed, reason);
        source = moved;
        allowedHost = next;
        fboxTriedHosts.add(next);
        fboxSwitched = true;
        try { listener.fboxHost(failed, false); } catch (Exception ignored) {}
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        cleanupPlayerForRetry();
        destroyProbe();
        removeDirectCover();
        firstFailureReason = "";
        fboxGridSize = 0;
        discoveredServerPageUrl = "";
        showSubscriberStage(ownerMode ? "جارٍ التجربة على اسم آخر" : "جارٍ إعادة المحاولة");
        handler.postDelayed(() -> {
            if (closed || interactiveSource || playbackReady) return;
            beginCapture();
        }, 300L);
        return true;
    }

    // The page did not open at all on this name (no answer, «not found», the site's
    // server down): the next name at once, without waiting for the time limit.
    private boolean fboxPageFailed(WebView v, String reason) {
        if (closed || v != probe || !fboxPageStep() || fboxGridSize >= FBOX_GRID_ALIVE) return false;
        firstFailureReason = reason == null ? "" : reason;
        if (tryNextFboxHost(firstFailureReason)) return true;
        if (!fboxSwitched) return false;
        retrySubscriberStartup();   // every name was tried: the message
        return true;
    }

    // How many servers this name's page lists — the site's own page shows its grid.
    private void noteServerGrid(String json) {
        if (fboxTriedHosts.isEmpty() || interactiveSource || closed || probe == null
                || json == null || json.length() > 30000) return;
        try {
            JSONArray arr = new JSONArray(json);
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < arr.length() && i < 300; i++) {
                String name = normalizeServerLabel(arr.optString(i));
                if (isProviderServerLabel(name)) seen.add(name.toLowerCase(Locale.ROOT));
            }
            if (seen.size() > fboxGridSize) fboxGridSize = seen.size();
            if (fboxGridSize >= FBOX_GRID_ALIVE) fboxNameWorks();
        } catch (Exception ignored) {}
    }

    // This name's page opened: the site uses it first from now on (and forgets a
    // failure it may remember for it). Once per player.
    private void fboxNameWorks() {
        if (fboxTriedHosts.isEmpty() || fboxOkReported || interactiveSource || closed) return;
        fboxOkReported = true;
        try { listener.fboxHost(allowedHost, true); } catch (Exception ignored) {}
    }

    // 322.3.123: the site's own page says it moved («X has a new home: Y», «Use the New
    // X? Click here»). Only the address the notice names is taken — nothing on the page is
    // pressed. The viewer goes to Y at once (the same page path), before the other names;
    // the owner's page goes there too, and he is told so (he adopts Y for everyone with
    // «🔁 استبدله» in the names box).
    private void siteMoved(String rawHost) {
        if (closed || probe == null || playing || playbackReady || rawHost == null) return;
        String to = rawHost.trim().toLowerCase(Locale.ROOT);
        if (to.startsWith("www.")) to = to.substring(4);
        if (to.isEmpty() || to.length() > 80 || !to.matches("([a-z0-9-]+\\.)+[a-z]{2,12}")) return;
        final String from = allowedHost;
        if (from.isEmpty() || to.equals(from) || to.endsWith("." + from) || !movesSeen.add(from + ">" + to)) return;
        try { listener.siteMoved(from, to); } catch (Exception ignored) {}
        if (!interactiveSource) {
            fboxNextHosts.remove(to);
            fboxNextHosts.add(0, to);
            if (fboxTriedHosts.isEmpty()) fboxTriedHosts.add(from);
            tryNextFboxHost("انتقل إلى " + to);
            return;
        }
        // The owner: the film page he is on, on the new name (the start page otherwise).
        String moved;
        try {
            Uri base = Uri.parse(source);
            String cu = probe.getUrl();
            if (cu != null) {
                Uri cur = Uri.parse(cu);
                String ch = cur.getHost() == null ? "" : cur.getHost().toLowerCase(Locale.ROOT);
                String cp = cur.getPath() == null ? "" : cur.getPath();
                if ("https".equalsIgnoreCase(cur.getScheme()) && (ch.equals(from) || ch.endsWith("." + from))
                        && !"/".equals(cp) && NameCheckRules.validPath(cp)) base = cur;
            }
            if (!"https".equalsIgnoreCase(base.getScheme())) return;
            moved = base.buildUpon().authority(to).build().toString();
        } catch (Exception e) {
            return;
        }
        source = moved;
        allowedHost = to;
        discoveredServerPageUrl = "";
        fboxPageProblem = "";
        if (!fboxOwnerName.isEmpty()) setFboxOwnerName(to);
        ownerMoveNote = "↪️ انتقل الموقع من «" + from + "» إلى «" + to + "» وفتحته لك.\n"
                + "ليعتمده المشتركون: «🔁 استبدله» في «✏️ الأسماء» (بعد «🩺 فحص»).";
        verifyStageGeneration++;
        showStage(ownerMoveNote);
        try { probe.loadUrl(moved); } catch (Exception ignored) {}
    }

    // 322.3.123: on a page of the site (never the server's own player): close the site's
    // pop-ups over the server buttons and read a move notice — now, and a few times more
    // while the page builds itself.
    private void injectPageScripts(final WebView v) {
        if (siteMoveScript.isEmpty() && popupCloseScript.isEmpty()) return;
        final int g = ++pageScriptsGeneration;
        final long[] delays = {0L, 1500L, 3500L, 7000L, 12000L};
        for (long d : delays) {
            handler.postDelayed(() -> {
                if (closed || v != probe || g != pageScriptsGeneration || playing) return;
                runPageScripts(v);
            }, d);
        }
    }

    private void runPageScripts(WebView v) {
        StringBuilder js = new StringBuilder();
        if (!popupCloseScript.isEmpty()) {
            js.append(popupCloseScript)
                    .append("\n;try{window.__subhubPopupCloseV3123&&window.__subhubPopupCloseV3123();}catch(_){}\n");
        }
        if (!siteMoveScript.isEmpty()) {
            js.append(siteMoveScript)
                    .append("\n;(function(){try{var h=window.__subhubSiteMoveV3123&&window.__subhubSiteMoveV3123();")
                    .append("if(h&&window.SubHubSourceChoice&&window.SubHubSourceChoice.moved)window.SubHubSourceChoice.moved(")
                    .append(JSONObject.quote(sourceChoiceToken)).append(",h);}catch(_){}})();");
        }
        try { v.evaluateJavascript(js.toString(), null); } catch (Exception ignored) {}
    }

    // 322.3.123: a viewer's page of the site that shows no server at all after a few
    // seconds — a dead name, an empty page — gives way to the next name at once (before,
    // the 18-second step limit). A check page («Just a quick check») never counts.
    private void scheduleGridWait(final WebView v) {
        if (interactiveSource || fboxNextHosts.isEmpty()) return;
        final int g = ++gridWaitGeneration;
        handler.postDelayed(() -> {
            if (closed || v != probe || g != gridWaitGeneration || gateShown || playing || playbackReady) return;
            if (!fboxPageStep() || fboxGridSize > 0 || fboxNextHosts.isEmpty()) return;
            tryNextFboxHost("الصفحة بلا سيرفرات");
        }, NO_GRID_WAIT_MS);
    }

    private String autoLine(int index) {
        return autoTotal > 1 && index < autoTotal
                ? "السيرفر " + toArabicDigits(index + 1) + " من " + toArabicDigits(autoTotal) : "";
    }

    // 322.3.112: the probe page itself failed to open.
    private void autoPageFailed(WebView v, String reason) {
        if (closed || v != probe || playing || playbackReady || interactiveSource) return;
        if (!(autoActive() && autoMore)) return;
        if (firstFailureReason.isEmpty()) firstFailureReason = reason;
        autoFail(reason);
    }

    // The server did not play: hand over to the next one quietly. False when this
    // was the last one — then the normal failure message shows.
    // 322.3.113: the saved player link did not play → the same server again
    // through its Moviesmod page (once). Not for our own pages (no Moviesmod page).
    private boolean tryEmbedFallback(String reason) {
        if (embedFallbackUsed || directEmbedUrl.isEmpty() || ownKind != OWN_NONE
                || interactiveSource || closed || playbackReady || source == null || source.isEmpty()) return false;
        embedFallbackUsed = true;
        directEmbedUrl = "";
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        cleanupPlayerForRetry();
        destroyProbe();
        removeDirectCover();
        firstFailureReason = "";
        handler.postDelayed(() -> {
            if (closed || interactiveSource || playbackReady) return;
            beginCapture();
        }, 300L);
        return true;
    }

    // 322.3.113: the Moviesmod check page («Just a quick check» / «Continue to
    // Moviesmod»). Reports gate(true) when it is on screen, gate(false) when not.
    private String gateCheckScript() {
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{var t=" + bridgeToken + ",n=0;"
                + "var re=/" + GATE_WORDS + "/i;"
                + "var iv=setInterval(function(){n++;var x='';try{x=String((document.body&&document.body.innerText)||'').slice(0,4000);}catch(_){}"
                + "if(re.test(x)){clearInterval(iv);if(window.SubHubSourceChoice&&window.SubHubSourceChoice.gate)window.SubHubSourceChoice.gate(t,true);return;}"
                + "if(n>=7){clearInterval(iv);if(window.SubHubSourceChoice&&window.SubHubSourceChoice.gate)window.SubHubSourceChoice.gate(t,false);}"
                + "},400);}catch(_){}})();";
    }

    // 322.3.116: the check is no longer the whole Moviesmod page. Our own small
    // card says what it is; the viewer presses «✓ تحقّق الآن» and that press is
    // passed to the page's «Continue» button (the page itself stays hidden, and so
    // does the page that opens after it). Only if the button cannot be found or
    // the press does not pass, the page itself is shown (as in 322.3.113).
    private boolean gatePageVisible = false;
    private LinearLayout gateCard;
    private TextView gateCardStatus, gateCardButton;
    private int gateTapGeneration = 0;

    private void showGate() {
        if (gateShown || closed || interactiveSource || probe == null || playing) return;
        gateShown = true;
        gatePageVisible = false;
        // The search waits for the viewer (no time limit runs meanwhile).
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        removeDirectCover();
        status.setVisibility(View.GONE);
        probe.setAlpha(0.0f);
        if (gateCard == null) {
            gateCard = new LinearLayout(activity);
            gateCard.setOrientation(LinearLayout.VERTICAL);
            gateCard.setGravity(Gravity.CENTER_HORIZONTAL);
            gateCard.setPadding(dp(18), dp(16), dp(18), dp(16));
            gateCard.setBackground(round(0xf00b1725, 0xccF7BC3D, 1, 18));
            gateCard.setElevation(dp(32));
            gateCard.setClickable(true);
            // Only the check itself — no site name.
            TextView title = new TextView(activity);
            title.setText("تحقّق من أنك لست روبوتاً");
            title.setTextColor(Color.WHITE);
            title.setTextSize(18);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            title.setTextDirection(View.TEXT_DIRECTION_RTL);
            title.setPadding(dp(38), dp(4), dp(38), dp(14));
            // 322.3.122: «✕» closes the card — it can never stay stuck on screen.
            FrameLayout gateHead = new FrameLayout(activity);
            gateHead.addView(title, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
            TextView gateClose = chip("✕", 15, this::gateClosed);
            gateClose.setPadding(0, 0, 0, 0);
            gateHead.addView(gateClose, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.RIGHT));
            gateCard.addView(gateHead, new LinearLayout.LayoutParams(-1, -2));
            gateCardButton = chip("✓ تحقّق الآن", 16, this::gatePressed);
            gateCardButton.setBackground(round(0xff12301d, 0xff22c55e, 1, 14));
            gateCard.addView(gateCardButton, new LinearLayout.LayoutParams(-1, dp(48)));
            gateCardStatus = new TextView(activity);
            gateCardStatus.setTextColor(Color.rgb(184, 197, 214));
            gateCardStatus.setTextSize(13);
            gateCardStatus.setGravity(Gravity.CENTER);
            gateCardStatus.setPadding(0, dp(8), 0, 0);
            gateCardStatus.setVisibility(View.GONE);
            gateCard.addView(gateCardStatus, new LinearLayout.LayoutParams(-1, -2));
            root.addView(gateCard, new FrameLayout.LayoutParams(dp(320), -2, Gravity.CENTER));
        }
        gateCardButton.setEnabled(true);
        gateCardButton.setAlpha(1f);
        gateCardButton.setVisibility(View.VISIBLE);
        gateCardStatus.setTextColor(Color.rgb(184, 197, 214));
        gateCardStatus.setVisibility(View.GONE);
        gateCard.setVisibility(View.VISIBLE);
        gateCard.bringToFront();
        menuButton.bringToFront();
        gateLooks = 0;
    }

    // The viewer's own press → the page's «Continue» button.
    private void gatePressed() {
        if (!gateShown || closed || probe == null) return;
        gateLooks = 0;
        gateCardButton.setEnabled(false);
        gateCardButton.setAlpha(0.6f);
        gateCardStatus.setText("جارٍ التحقق…");
        gateCardStatus.setVisibility(View.VISIBLE);
        final int gen = ++gateTapGeneration;
        try { probe.evaluateJavascript(gateButtonScript(), null); } catch (Exception ignored) {}
        // 322.3.124: the site may take its check away in this same page (no new page
        // loads, so no new report would come) — the page is watched from now on.
        try { probe.evaluateJavascript(gateWatchScript(), null); } catch (Exception ignored) {}
        // Still on the check after a few seconds: show the check itself (only if it is
        // really still there — see showGatePage).
        handler.postDelayed(() -> {
            if (gen == gateTapGeneration && gateShown && !closed) showGatePage();
        }, 7000L);
    }

    private String gateButtonScript() {
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{var t=" + bridgeToken + ";var S=window.SubHubSourceChoice;if(!S||!S.gateTarget)return;"
                + "var els=[].slice.call(document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button]'));"
                + "var b=null;for(var i=0;i<els.length;i++){var x=String(els[i].innerText||els[i].value||els[i].textContent||'').trim();"
                + "if(/continue to moviesmod/i.test(x)){b=els[i];break;}if(!b&&/^(continue|verify|proceed|i'?m human|i am human)/i.test(x))b=els[i];}"
                + "if(!b){S.gateTarget(t,-1,-1);return;}"
                + "try{b.scrollIntoView({block:'center',inline:'center'});}catch(_){}"
                + "setTimeout(function(){try{var r=b.getBoundingClientRect();var w=window.innerWidth||document.documentElement.clientWidth,h=window.innerHeight||document.documentElement.clientHeight;"
                + "if(!r.width||!r.height||!w||!h){S.gateTarget(t,-1,-1);return;}"
                + "S.gateTarget(t,(r.left+r.width/2)/w,(r.top+r.height/2)/h);}catch(_){S.gateTarget(t,-1,-1);}},350);"
                + "}catch(_){}})();";
    }

    // Fallback: if our press did not pass, the check itself — only their check
    // box, centred on black (the rest of their page hidden), for the viewer to
    // press by hand.
    // 322.3.124: only while the check is really on the page. Before, this came 7 s
    // after the press whatever the page showed by then: when the check had already
    // gone (the site takes it away in the same page, or its next page was slow), the
    // whole film page with its ads was shown to the viewer — and the search stood
    // still behind it. Now a page without the check goes on hidden, with our screen.
    // 322.3.125: and only when there is something in it to press (their «Continue», or a
    // Cloudflare box): while the site checks by itself («Verifying…» — the white page the
    // viewer saw for a few seconds), our card stays and the page is looked at again; after
    // about 16 s more it is shown all the same, should it need a hand.
    private void showGatePage() {
        if (!gateShown || closed || probe == null || playing) return;
        final int gen = ++gateTapGeneration;
        final WebView pv = probe;
        try {
            pv.evaluateJavascript(gateStateScript(), value -> {
                if (closed || !gateShown || playing || probe != pv || gen != gateTapGeneration) return;
                String state = value == null ? "" : value.replace("\"", "").trim();
                if ("none".equals(state)) {
                    gateGone();
                    return;
                }
                if ("wait".equals(state) && gateLooks < 4) {
                    gateLooks++;
                    final int g2 = ++gateTapGeneration;
                    handler.postDelayed(() -> {
                        if (g2 == gateTapGeneration && gateShown && !closed) showGatePage();
                    }, 4000L);
                    return;
                }
                revealGateBox(pv);
            });
        } catch (Exception e) {
            gateGone();
        }
    }

    // 322.3.125: «none» (the check is gone), «press» (their check with something to press:
    // a «Continue / Verify» button that can be pressed, or a Cloudflare box) or «wait» (their
    // check verifying by itself).
    private String gateStateScript() {
        return "(function(){" + gatePresentFn() + "if(!__subhubGateOn())return 'none';"
                + "try{if(document.querySelector('iframe[src*=\"challenges.cloudflare.com\"],#challenge-stage,.cf-turnstile'))return 'press';"
                + "var els=[].slice.call(document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button]'));"
                + "for(var i=0;i<els.length;i++){var e=els[i],x=String(e.innerText||e.value||e.textContent||'').trim(),r=e.getBoundingClientRect();"
                + "if(r.width>0&&r.height>0&&!e.disabled&&e.getAttribute('aria-disabled')!=='true'"
                + "&&(/continue to moviesmod/i.test(x)||/^(continue|verify|proceed|i'?m human|i am human)/i.test(x)))return 'press';}"
                + "}catch(_){}return 'wait';})();";
    }

    // Their check box on screen (the rest of their page hidden). Never a page that is
    // still loading: that page's own report shows the box when it is the check again.
    private void revealGateBox(final WebView pv) {
        if (!gateShown || closed || probe != pv || playing) return;
        gateTapGeneration++;
        gatePageVisible = true;
        pv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        try { pv.evaluateJavascript(gateOnlyScript(), null); } catch (Exception ignored) {}
        // The viewer may pass it by hand in this same page: watched.
        try { pv.evaluateJavascript(gateWatchScript(), null); } catch (Exception ignored) {}
        handler.postDelayed(() -> {
            if (closed || !gateShown || !gatePageVisible || probe != pv || probeLoading) return;
            if (gateCard != null) gateCard.setVisibility(View.GONE);
            pv.setAlpha(1.0f);
            pv.bringToFront();
            menuButton.bringToFront();
        }, 250L);
    }

    // 322.3.124: a new page starts while their check box is on screen (the viewer passed
    // it, or it reloads): hidden at once — the film page never shows behind the check —
    // and our card says «جارٍ التحقق…» until the new page says whether the check is
    // still there. Should no word come, the page is looked at once more.
    private void gateBetweenPages(WebView v) {
        v.setAlpha(0.0f);
        if (gateCard != null) {
            gateCardButton.setEnabled(false);
            gateCardButton.setAlpha(0.6f);
            gateCardStatus.setText("جارٍ التحقق…");
            gateCardStatus.setVisibility(View.VISIBLE);
            gateCard.setVisibility(View.VISIBLE);
            gateCard.bringToFront();
            menuButton.bringToFront();
        }
        final int gen = ++gateTapGeneration;
        handler.postDelayed(() -> {
            if (gen == gateTapGeneration && gateShown && !closed) showGatePage();
        }, 12000L);
    }

    // 322.3.124: the check is gone (the page says so, or it was not there when we
    // looked): our own screen again, and the search goes on from the step it reached.
    // 322.3.125: the first time, the player starts again from the start instead.
    private void gateGone() {
        if (!gateShown || closed) return;
        if (!gateRestarted && !playing && !interactiveSource) {
            restartAfterCheck();
            return;
        }
        gateResume();
    }

    // The search goes on from the step it reached (also after «✕» on the card).
    private void gateResume() {
        if (!gateShown || closed) return;
        hideGate();
        ensureViewerCover();   // 322.3.126: the search goes on behind our screen
        startSubscriberStartupTimer(Math.max(2, subscriberStartupPhase));
        // The page behind the check gets its own few seconds to show its servers (the
        // wait counted from the check page could end just as the viewer passed it).
        if (probe != null) scheduleGridWait(probe);
    }

    // 322.3.125: «✓ تم التحقق — جارٍ التشغيل من جديد…» on our card for a moment, then the
    // player from the start, as when it was opened: the saved player link first (now with
    // the check passed), all its tries again. The page of the check and its scripts go at
    // once, so nothing they still say can change the new start.
    private void restartAfterCheck() {
        gateRestarted = true;
        gateShown = false;
        gatePageVisible = false;
        gateTapGeneration++;
        final int rg = ++gateRestartGeneration;
        subscriberStartupGeneration++;          // no time limit of the old try runs on
        stopSubscriberStageAnimation();
        cleanupPlayerForRetry();
        destroyProbe();
        removeDirectCover();
        status.setVisibility(View.GONE);
        if (gateCard != null) {
            gateCardButton.setVisibility(View.GONE);
            gateCardStatus.setText("✓ تم التحقق — جارٍ التشغيل من جديد…");
            gateCardStatus.setTextColor(Color.rgb(134, 239, 172));
            gateCardStatus.setVisibility(View.VISIBLE);
            gateCard.setVisibility(View.VISIBLE);
            gateCard.bringToFront();
            menuButton.bringToFront();
        }
        handler.postDelayed(() -> {
            if (closed || rg != gateRestartGeneration) return;
            if (gateCard != null && !gateShown) gateCard.setVisibility(View.GONE);
            if (playing || playbackReady || probe != null) return;
            cleanupPlayerForRetry();             // anything the old page said meanwhile
            directEmbedUrl = originalDirectEmbedUrl;
            embedFallbackUsed = false;
            subscriberStartupAttempt = 0;
            subscriberStartupPhase = 0;
            firstFailureReason = "";
            fboxGridSize = 0;
            discoveredServerPageUrl = "";
            directKickRounds = 0;
            beginCapture();
        }, 1100L);
    }

    // 322.3.124: is the site's check on this page? Its words in the page — or, while our
    // «only the box» style hides the rest of their page, the box itself still there with
    // its words, its button or its «Verifying…» (their page taking the box away, or
    // hiding it, = the check is passed).
    private static String gatePresentFn() {
        return "var __subhubGateOn=function(){try{var re=/" + GATE_WORDS + "/i,g=document.querySelector('[data-subhub-gate]');"
                + "if(g&&g.isConnected){var r=g.getBoundingClientRect(),tx=String(g.innerText||g.textContent||'');"
                + "if(r.width>0&&r.height>0&&(re.test(tx)||/continu|verif|proceed|human|robot|captcha|check|wait/i.test(tx)))return true;}"
                + "var x=String((document.body&&document.body.innerText)||'').slice(0,4000);return re.test(x);}catch(_){return false;}};";
    }

    // 322.3.124: «gone» the moment the check leaves this page (twice in a row not seen,
    // so a page redrawing itself is not taken for it). Two minutes at most.
    private String gateWatchScript() {
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{var t=" + bridgeToken + ",n=0,miss=0;" + gatePresentFn()
                + "if(window.__subhubGateWatch)clearInterval(window.__subhubGateWatch);"
                + "window.__subhubGateWatch=setInterval(function(){n++;"
                + "if(__subhubGateOn()){miss=0;}else if(++miss>=2){clearInterval(window.__subhubGateWatch);window.__subhubGateWatch=0;"
                + "var S=window.SubHubSourceChoice;if(S&&S.gate)S.gate(t,false);return;}"
                + "if(n>=300){clearInterval(window.__subhubGateWatch);window.__subhubGateWatch=0;}"
                + "},400);}catch(_){}})();";
    }

    // 322.3.124: our «only the box» style and the watch go when the check does, so the
    // page (hidden from the viewer) is whole again for the server to be pressed in it.
    private String gateClearScript() {
        return "(function(){try{var s=document.getElementById('__subhubGateOnly');if(s&&s.parentNode)s.parentNode.removeChild(s);"
                + "if(window.__subhubGateWatch){clearInterval(window.__subhubGateWatch);window.__subhubGateWatch=0;}"
                + "[].slice.call(document.querySelectorAll('[data-subhub-gate]')).forEach(function(e){e.removeAttribute('data-subhub-gate');});"
                + "}catch(_){}})();";
    }

    // Their page with everything hidden except the box around «Continue».
    private String gateOnlyScript() {
        return "(function(){try{"
                + "var els=[].slice.call(document.querySelectorAll('button,a,[role=button],input[type=submit],input[type=button]'));"
                + "var b=null;for(var i=0;i<els.length;i++){var x=String(els[i].innerText||els[i].value||els[i].textContent||'').trim();"
                + "if(/continue to moviesmod/i.test(x)){b=els[i];break;}if(!b&&/^(continue|verify|proceed|i'?m human|i am human)/i.test(x))b=els[i];}"
                + "if(!b)return;var bh=b.getBoundingClientRect().height||40,card=null,n=b;"
                + "for(var k=0;k<7&&n&&n.parentElement&&n.parentElement!==document.body;k++){n=n.parentElement;"
                + "var r=n.getBoundingClientRect(),cs=getComputedStyle(n);"
                + "var boxed=parseFloat(cs.borderTopLeftRadius)>6||cs.boxShadow!=='none'||(cs.backgroundColor&&cs.backgroundColor!=='rgba(0, 0, 0, 0)'&&cs.backgroundColor!=='transparent');"
                + "if(boxed&&r.width<window.innerWidth*0.98&&r.height>bh*2){card=n;break;}}"
                + "if(!card)card=b.parentElement||b;card.setAttribute('data-subhub-gate','1');"
                + "var st=document.getElementById('__subhubGateOnly')||document.createElement('style');st.id='__subhubGateOnly';"
                + "st.textContent='html,body{background:#000 !important;}body *{visibility:hidden !important;}"
                + "[data-subhub-gate],[data-subhub-gate] *{visibility:visible !important;}"
                + "[data-subhub-gate]{position:fixed !important;left:50% !important;top:50% !important;transform:translate(-50%,-50%) !important;"
                + "width:min(420px,92vw) !important;box-sizing:border-box !important;max-height:94vh !important;overflow:auto !important;margin:0 !important;z-index:2147483647 !important;}';"
                + "(document.head||document.documentElement).appendChild(st);"
                + "}catch(_){}})();";
    }

    // 322.3.122: the video came — the check is over, whatever the page said last.
    // Before, a «gone» report that arrived after playback started was ignored (and the
    // page is closed when the video starts, so none came after): the card stayed on
    // «جارٍ التحقق…» over the playing film with no way to close it, and the stuck flag
    // also kept the start-up time limit from ever giving its message.
    private void gateDone() {
        if (!gateShown) return;
        gateShown = false;
        gatePageVisible = false;
        gateTapGeneration++;
        if (gateCard != null) gateCard.setVisibility(View.GONE);
    }

    // 322.3.122: «✕» on the check card. A film already playing: only the card goes.
    // Still on the check: the card goes and the search goes on (if the check really
    // stays, the usual message to try another server follows).
    private void gateClosed() {
        if (!gateShown || closed) return;
        if (playing) {
            gateDone();
            return;
        }
        gateResume();
    }

    private void hideGate() {
        if (!gateShown || closed) return;
        gateShown = false;
        gatePageVisible = false;
        gateTapGeneration++;
        if (gateCard != null) gateCard.setVisibility(View.GONE);
        if (probe != null) {
            try { probe.evaluateJavascript(gateClearScript(), null); } catch (Exception ignored) {}
            probe.setAlpha(0.0f);
            subscriberProviderVisible = false;
            root.post(this::applySubscriberProbePreviewLayout);
        }
        status.setVisibility(View.VISIBLE);
        status.bringToFront();
        menuButton.bringToFront();
    }

    private boolean autoFail(String reason) {
        if (!autoActive() || autoReported || !autoMore || closed) return false;
        // 322.3.113: the saved player link first, then this server's Moviesmod page.
        if (tryEmbedFallback(reason)) return true;
        autoReported = true;
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        cleanupPlayerForRetry();
        destroyProbe();
        removeDirectCover();
        String next = autoLine(autoIndex + 1);
        status.setText("جارٍ تجهيز المشاهدة…" + (next.isEmpty() ? "" : "\n" + next));
        status.setVisibility(View.VISIBLE);
        status.bringToFront();
        try { listener.autoFailed(reason == null ? "" : reason); } catch (Exception ignored) {}
        return true;
    }

    // 322.3.108: final failure of a saved server. The screen stays black; the
    // viewer gets one message and a button back to the film page. The owner
    // also gets the reason (the viewer never sees it).
    private void showServerFailure(String reason) {
        if (closed || root == null) return;
        stopSubscriberStageAnimation();
        status.setVisibility(View.GONE);
        if (failurePanel != null) {
            ViewGroup parent = (ViewGroup) failurePanel.getParent();
            if (parent != null) parent.removeView(failurePanel);
            failurePanel = null;
        }
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));
        box.setBackground(round(0xee0b1725, 0xccF7BC3D, 1, 18));
        box.setElevation(dp(28));
        box.setClickable(true);

        TextView text = new TextView(activity);
        text.setTextColor(Color.WHITE);
        text.setTextSize(15);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0f, 1.25f);
        text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        text.setText("تعذّر تشغيل هذا السيرفر.\n"
                + "أغلق المشغّل وأعد المحاولة، وإن لم يعمل\n"
                + "اختر سيرفراً آخر أو زراً آخر أو مشغّلاً آخر.");
        text.setPadding(0, 0, 0, dp(12));
        box.addView(text, new LinearLayout.LayoutParams(-1, -2));

        TextView back = chip("↩ اختر سيرفراً آخر", 15, this::close);
        back.setBackground(round(0xff12301d, 0xff22c55e, 1, 14));
        box.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(320), -2, Gravity.CENTER);
        root.addView(box, lp);
        failurePanel = box;
        box.bringToFront();
        menuButton.bringToFront();
        if (ownerMode) {
            failureDetail = "سبب الفشل: " + (reason == null || reason.trim().isEmpty() ? "غير معروف" : reason.trim())
                    + "\n(المشترك لا يرى السبب — يرى الرسالة والزر فقط.)";
            showDecisionBar(false);
        }
    }

    // 322.3.108: the subtitle did not load (error, or no answer).
    private void showSubtitleFailure(String ownerNote) {
        if (closed) return;
        String text = "تعذّر تحميل الترجمة هنا.\nاختر ترجمة أخرى من CC، أو جرّب زراً آخر أو مشغّلاً آخر.";
        if (ownerMode && ownerNote != null && !ownerNote.trim().isEmpty()) text += "\n(" + ownerNote.trim() + ")";
        showTransientValue(text, 7000L);
    }

    private void watchSubtitleAnswer(int index) {
        final int seq = ++subtitleAskSeq;
        handler.postDelayed(() -> checkSubtitleAnswer(seq, index), 25000L);
    }

    private void checkSubtitleAnswer(int seq, int index) {
        if (closed || seq != subtitleAskSeq || subtitleAnsweredSeq >= seq || selectedSubtitle != index) return;
        if (!playbackReady) {
            // Judge it only once the video plays.
            handler.postDelayed(() -> checkSubtitleAnswer(seq, index), 3000L);
            return;
        }
        subtitleAnsweredSeq = seq;
        showSubtitleFailure("لم يصل ملف الترجمة خلال ٢٥ ثانية");
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
        if (autoActive()) {
            // 322.3.110: «جارٍ تجهيز المشاهدة… السيرفر ٢ من ٥» — no errors in between.
            String line = autoLine(autoIndex);
            base = base.trim();
            int cut = base.indexOf('\n');
            if (cut >= 0) base = base.substring(0, cut);
            if (!line.isEmpty()) base = base + "\n" + line;
        } else if (ownerMode && fboxTriedHosts.size() + fboxNextHosts.size() > 1) {
            // 322.3.120: the owner sees which fbox name is tried («📦 الاسم ٢ من ٤: vumoo.gd»).
            base = base.trim();
            int cut = base.indexOf('\n');
            if (cut >= 0) base = base.substring(0, cut);
            base = base + "\n" + siteIcon() + " الاسم " + toArabicDigits(fboxTriedHosts.size()) + " من "
                    + toArabicDigits(fboxTriedHosts.size() + fboxNextHosts.size()) + ": " + allowedHost;
        }
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
        int nl = subscriberStageBase.indexOf('\n');
        status.setText(nl < 0 ? subscriberStageBase + "  " + dots
                : subscriberStageBase.substring(0, nl) + "  " + dots + subscriberStageBase.substring(nl));
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
        // 322.3.110: during the automatic search a server that makes no progress
        // gives way to the next one. 322.3.112: the limit depends on the step.
        if (autoActive() && autoMore) timeoutMs = EpisodeTiming.autoPhaseLimitMs(phase, autoExpectMs);
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
        // 322.3.126: a captured link is being looked at — its answer comes first.
        if (linkChecksRunning > 0) {
            subscriberStartupDeadlineMs = SystemClock.elapsedRealtime() + 3000L;
            handler.postDelayed(() -> subscriberStartupTick(generation), 1000L);
            return;
        }

        if (firstFailureReason.isEmpty()) {
            firstFailureReason = "انتهت المهلة عند «" + startupStageText(subscriberStartupPhase) + "»";
        }
        if (gateShown) return;
        // 322.3.113: the saved player link did not answer → its Moviesmod page.
        if (tryEmbedFallback(firstFailureReason)) return;
        // 322.3.108: asking to tap only makes sense when the server's player is
        // visible; a hidden page means a black screen — retry, then the message.
        if (subscriberStartupPhase <= 4 && probe != null && subscriberProviderVisible && !(autoActive() && autoMore)) {
            subscriberStartupGeneration++;
            final int tapGeneration = subscriberStartupGeneration;
            stopSubscriberStageAnimation();
            // 322.3.126: the page is never shown (no «اضغط داخل شاشة الفيديو» on their
            // player with its ads) — our own button passes the press to it instead.
            showCoverTapButton();
            handler.postDelayed(() -> {
                if (closed || interactiveSource || playbackReady || tapGeneration != subscriberStartupGeneration) return;
                retrySubscriberStartup();
            }, 45000L);
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
            saveBandwidth();   // 322.3.127
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        playing = false;
        playbackReady = false;
        controllerShown = false;   // 322.3.115: the bottom row leaves with the old player
        updateBottomRow();
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
        // 322.3.126: links still being looked at belong to the old try.
        linkCheckGen.incrementAndGet();
        linkChecked.clear();
        linkChecksRunning = 0;
        capturedFiles.clear();   // 322.3.127
        hideLowerHint();         // 322.3.127
    }

    private void retrySubscriberStartup() {
        if (closed || interactiveSource || playbackReady) return;
        subscriberStartupGeneration++;
        stopSubscriberStageAnimation();
        // 322.3.120: the fbox page did not open on this name → the same server on the next name.
        if (tryNextFboxHost(firstFailureReason)) return;
        // 322.3.110: in the automatic search each server gets one try.
        if (autoActive() && autoMore && autoFail(firstFailureReason)) return;
        // 322.3.113: the saved player link first, then this server's Moviesmod page.
        if (tryEmbedFallback(firstFailureReason)) return;
        // 322.3.120: the page opened on no fbox name — the message now (no second round).
        if (fboxSwitched && fboxPageStep() && fboxGridSize < FBOX_GRID_ALIVE) {
            final String last = allowedHost;
            fboxNote(last, firstFailureReason);
            try { listener.fboxHost(last, false); } catch (Exception ignored) {}
            cleanupPlayerForRetry();
            destroyProbe();
            removeDirectCover();
            subscriberStartupPhase = 0;
            showServerFailure("لم تفتح صفحة السيرفرات على أي اسم من أسماء " + siteWord() + ":\n" + fboxReasons);
            return;
        }
        if (subscriberStartupAttempt >= 2) {
            cleanupPlayerForRetry();
            destroyProbe();
            removeDirectCover();
            subscriberStartupPhase = 0;
            showServerFailure(firstFailureReason.isEmpty() ? "تعذّر الاتصال بالسيرفر مرتين" : firstFailureReason);
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
                || lower.equals("settings")
                // 322.3.123: a share row or a pop-up button is never a server.
                || lower.matches("whatsapp|telegram|facebook|twitter|x|reddit|pinterest|email|share|copy link|copy|more|more…|more\\.\\.\\.|join|later"));
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
        // 322.3.125: a word from a page already gone (the player is starting again) changes nothing.
        if (!interactiveSource && probe == null) return;
        selectedProviderLabel = label;
        pendingServerKey = key;
        pendingServerLabel = label;
        pendingServerPickedAt = SystemClock.elapsedRealtime();
        if (interactiveSource && verifyFirst) {
            verifyLabel = label;
            updateVerifySaveButton();
            showVerifyStage("السيرفر " + label + " — إن اشتغل الفيلم فعلاً اضغط «💾 حفظ هذا السيرفر».");
        } else if (interactiveSource) {
            showStage("تم اختيار السيرفر " + label + "…");
        } else {
            // 322.3.124: a server pressed in the page means the check is passed (no server
            // is pressed under it) — its card goes now, not with the page's next report.
            // 322.3.125: the first time, the player starts again from the start instead.
            if (gateShown && !gateRestarted) {
                gateGone();
                return;
            }
            if (gateShown) hideGate();
            startSubscriberStartupTimer(3);
            fboxNameWorks();   // 322.3.120: the server was found on this name's page
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
        if (verifyFirst) { showVerifyBar(); return; }
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

    // 322.3.106: verify cards keep their buttons in the app bar at the top, so
    // nothing covers the server buttons of the page.
    private void showVerifyBar() {
        if (verifyBar != null || closed) return;
        verifyBar = new LinearLayout(activity);
        verifyBar.setOrientation(LinearLayout.HORIZONTAL);
        verifyBar.setGravity(Gravity.CENTER_VERTICAL);
        manualServerButton = chip("جلب السيرفرات", 13, this::requestServerList);
        manualServerButton.setBackground(round(0xee102a40, 0xff2f93c7, 1, 14));
        verifySaveButton = chip("💾 حفظ هذا السيرفر", 13, this::saveVerifiedServer);
        LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(-2, dp(42));
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(-2, dp(42));
        b.leftMargin = dp(8);
        verifyBar.addView(manualServerButton, a);
        verifyBar.addView(verifySaveButton, b);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(42), Gravity.TOP | Gravity.END);
        lp.topMargin = dp(9);
        lp.rightMargin = dp(10);
        verifyBar.setElevation(dp(28));
        root.addView(verifyBar, lp);
        verifyBar.bringToFront();
        updateVerifySaveButton();
    }

    private void updateVerifySaveButton() {
        if (verifySaveButton == null) return;
        boolean ready = !verifyLabel.isEmpty() && !verifySaving;
        verifySaveButton.setText(verifySaving ? "جارٍ الحفظ…"
                : verifyLabel.isEmpty() ? "💾 حفظ هذا السيرفر" : "💾 حفظ: " + verifyLabel);
        verifySaveButton.setAlpha(ready ? 1.0f : 0.5f);
        verifySaveButton.setBackground(ready
                ? round(0xee10351f, 0xff22c55e, 1, 14)
                : round(PANEL_2, BORDER, 1, 14));
    }

    private void saveVerifiedServer() {
        if (closed || !verifyFirst || !interactiveSource || verifySaving) return;
        if (verifyLabel.isEmpty()) {
            showVerifyStage("اضغط سيرفراً في الصفحة أولاً، وتأكّد أن الفيلم يشتغل، ثم احفظه.");
            return;
        }
        String key = serverKey(verifyLabel);
        if (key.isEmpty()) return;
        verifySaving = true;
        updateVerifySaveButton();
        showStage("جارٍ حفظ السيرفر " + verifyLabel + "…");
        // The page the owner is on is the server page (fallback when the page
        // did not report itself yet).
        if (discoveredServerPageUrl == null || discoveredServerPageUrl.isEmpty()) {
            try { if (probe != null) recordServerPage(probe.getUrl()); } catch (Exception ignored) {}
        }
        listener.serverSelected(key, verifyLabel, discoveredServerPageUrl, verifyEmbedUrl);
        listener.saveRequested(offsetForSubHubSave());
    }

    // The hint hides itself after a few seconds so it does not cover the page.
    private int verifyStageGeneration = 0;
    private void showVerifyStage(String text) {
        showStage(text);
        final int g = ++verifyStageGeneration;
        handler.postDelayed(() -> {
            if (g == verifyStageGeneration && !closed && !playing) status.setVisibility(View.GONE);
        }, 6000L);
    }

    // 322.3.106: while the owner chooses on a verify card, only a finger moves
    // the page. Some Moviesmod pages (or the player inside them) keep pulling
    // the view back up to the video; any scroll that does not follow a touch is
    // undone at once.
    private String scrollGuardScript() {
        return "(function(){try{if(window.__subhubScrollGuardV3106)return;window.__subhubScrollGuardV3106=1;"
                + "var touching=false,lastTouch=0,lastUser=0,keepX=scrollX,keepY=scrollY;"
                + "var raw=window.scrollTo;"
                + "function t(){lastTouch=Date.now();}"
                + "addEventListener('touchstart',function(){touching=true;t();},{capture:true,passive:true});"
                + "addEventListener('touchmove',t,{capture:true,passive:true});"
                + "addEventListener('touchend',function(){touching=false;t();},{capture:true,passive:true});"
                + "addEventListener('touchcancel',function(){touching=false;t();},{capture:true,passive:true});"
                + "addEventListener('scroll',function(){var now=Date.now();"
                + "if(touching||now-lastTouch<900||now-lastUser<160){lastUser=now;keepX=scrollX;keepY=scrollY;return;}"
                + "if(Math.abs(scrollY-keepY)>3||Math.abs(scrollX-keepX)>3){try{raw.call(window,keepX,keepY);}catch(_){}}"
                + "},{capture:true,passive:true});"
                + "var noop=function(){};"
                + "try{Element.prototype.scrollIntoView=function(){};}catch(_){}"
                + "try{window.scrollTo=function(){if(touching||Date.now()-lastTouch<900)return raw.apply(window,arguments);};}catch(_){}"
                + "try{window.scroll=window.scrollTo;window.scrollBy=noop;}catch(_){}"
                + "try{var f=HTMLElement.prototype.focus;HTMLElement.prototype.focus=function(o){try{return f.call(this,Object.assign({},o||{},{preventScroll:true}));}catch(_){return f.call(this);}};}catch(_){}"
                + "}catch(_){}})();";
    }

    private void selectProviderServer(String rawLabel) {
        String label = normalizeServerLabel(rawLabel);
        if (probe == null || closed || playing || !isProviderServerLabel(label)) return;
        selectedProviderLabel = label;
        // 322.3.106: on a verify card the page stays visible and usable.
        if (manualServerButton != null && !verifyFirst) manualServerButton.setVisibility(View.GONE);
        probe.setAlpha(interactiveSource && !verifyFirst ? 0.02f : 1.0f);
        // 322.3.123: on a verify card «💾 حفظ» names the server only when the page took
        // it (the page reports the press). Before, it was named at once — also when the
        // page's button was covered and the page kept playing its own server.
        final boolean verifyCard = interactiveSource && verifyFirst;
        if (!verifyCard) recordProviderChoice(label);
        showStage("جارٍ فتح السيرفر " + label + "…");
        if (verifyCard && probe != null) runPageScripts(probe);   // the site's pop-ups first
        final String wanted = label;
        String js = "window.__subhubSelectProvider && window.__subhubSelectProvider("
                + JSONObject.quote(label) + ")";
        try {
            probe.evaluateJavascript(js, value -> {
                if (closed || !verifyCard || "true".equals(value)) return;
                showVerifyStage("ما لقيت «" + wanted + "» في الصفحة — اضغطه بيدك.");
            });
        } catch (Exception ignored) {}
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
            // 322.3.125: that press ended the site's check — the player starts again from
            // the start (this page is gone).
            if (probe == null || closed) return;
        }
        if (verifyFirst && interactiveSource) {
            // 322.3.106: remember the server's player URL only. The page keeps
            // playing it in its own player; nothing is opened or captured.
            try {
                Uri u = Uri.parse(rawUrl == null ? "" : rawUrl.trim());
                String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
                if ("https".equalsIgnoreCase(u.getScheme()) && !host.isEmpty()
                        && !host.equals(allowedHost) && !host.endsWith("." + allowedHost)) {
                    verifyEmbedUrl = u.toString();
                }
            } catch (Exception ignored) {}
            return;
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
        // 322.3.112: OWN_PAGE — the video link found in the page's <video>.
        @JavascriptInterface
        public void mediaFound(String token, String rawUrl) {
            if (!sourceChoiceToken.equals(token) || ownKind != OWN_PAGE || rawUrl == null || rawUrl.length() > 4000) return;
            final String url = rawUrl.trim();
            handler.post(() -> {
                if (closed || playing || probe == null || candidate != null) return;
                Map<String,String> h = new HashMap<>();
                if (!resolvedProviderUrl.isEmpty()) h.put("Referer", resolvedProviderUrl);
                capture(url, h);
            });
        }

        @JavascriptInterface
        public void serverPage(String token, String rawUrl) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> recordServerPage(rawUrl));
        }

        // 322.3.123: the page says the site moved («X has a new home: Y») — Y only.
        @JavascriptInterface
        public void moved(String token, String rawHost) {
            if (!sourceChoiceToken.equals(token) || rawHost == null || rawHost.length() > 100) return;
            handler.post(() -> siteMoved(rawHost));
        }

        @JavascriptInterface
        public void servers(String token, String json) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> {
                noteServerGrid(json);   // 322.3.120: this fbox name's page has opened
                showServerChooser(json);
            });
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

        // 322.3.113: Moviesmod's check page is (or is no longer) on screen.
        @JavascriptInterface
        public void gate(String token, boolean present) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> {
                if (closed || interactiveSource) return;
                // 322.3.122: playing already — a «gone» report still takes the card away.
                if (playing) {
                    if (!present) gateDone();
                    return;
                }
                if (present) {
                    if (!gateShown) showGate();
                    // the page reloaded with the check: only the box again
                    else if (gatePageVisible && probe != null) revealGateBox(probe);
                }
                // The page behind the check is open now: go on from the step it reached.
                else gateGone();
            });
        }

        // 322.3.116: where the page's «Continue» button is (after the viewer pressed ours).
        @JavascriptInterface
        public void gateTarget(String token, double normalizedX, double normalizedY) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> {
                if (closed || !gateShown || gatePageVisible) return;
                if (normalizedX < 0 || normalizedY < 0) { showGatePage(); return; }
                dispatchProviderTap(normalizedX, normalizedY);
            });
        }

        @JavascriptInterface
        public void playTarget(String token, double normalizedX, double normalizedY) {
            if (!sourceChoiceToken.equals(token)) return;
            handler.post(() -> dispatchProviderTap(normalizedX, normalizedY));
        }
    }

    public void setSearchText(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        searchText = t.length() > 80 ? t.substring(0, 80) : t;
    }

    // 322.3.121: the fbox name the owner chose (this page is opened on it).
    public void setFboxOwnerName(String host) {
        if (closed || !interactiveSource || host == null) return;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.length() > 80 || !h.matches("([a-z0-9-]+\\.)+[a-z]{2,12}")) return;
        fboxOwnerName = h;
        // The whole hint and message show here (a fixed height cut them after two lines).
        ViewGroup.LayoutParams slp = status.getLayoutParams();
        if (slp != null) {
            slp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            status.setLayoutParams(slp);
        }
        status.setMinHeight(dp(48));
        status.setPadding(dp(16), dp(6), dp(16), dp(6));
        if (fboxNameTag == null) {
            fboxNameTag = new TextView(activity);
            fboxNameTag.setTextColor(Color.WHITE);
            fboxNameTag.setTextSize(12);
            fboxNameTag.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            fboxNameTag.setTextDirection(View.TEXT_DIRECTION_LTR);
            fboxNameTag.setPadding(dp(10), dp(4), dp(10), dp(4));
            fboxNameTag.setBackground(round(0xd90b1725, 0xccF7BC3D, 1, 12));
            fboxNameTag.setElevation(dp(27));
            // Only a label: touches go on to the page under it.
            fboxNameTag.setClickable(false);
            fboxNameTag.setFocusable(false);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.LEFT);
            lp.leftMargin = dp(8);
            lp.bottomMargin = dp(8);
            root.addView(fboxNameTag, lp);
        }
        fboxNameTag.setText(siteIcon() + " " + h);
        fboxNameTag.setVisibility(View.VISIBLE);
        fboxNameTag.bringToFront();
    }

    private boolean isFboxOwnerPage(Uri u) {
        if (!interactiveSource || fboxOwnerName.isEmpty() || u == null || u.getHost() == null) return false;
        String h = u.getHost().toLowerCase(Locale.ROOT);
        return h.equals(fboxOwnerName) || h.endsWith("." + fboxOwnerName);
    }

    // The chosen name's page did not open: the reason stays on screen (the hint
    // that hides itself is not shown over it).
    private void fboxOwnerProblem(WebView v, String text) {
        if (closed || v != probe || !interactiveSource) return;
        fboxPageProblem = text;
        verifyStageGeneration++;
        showStage(text);
    }

    // 322.3.121: on fbox sites the search that WORKS is the 🔍 at the top of the
    // page (it opens a «Search» window whose box shows the films as you type); the
    // big box in the page does not. So the 🔍 is pressed and the title typed in the
    // box that appears; only without that 🔍 the box in the page is used. Old text
    // in the box is always replaced by this film's title. Nothing else is pressed:
    // the owner picks the right film himself.
    private String searchFillScript() {
        String q = JSONObject.quote(searchText);
        return "(function(){try{var q=" + q + ",n=0,clicked=false,before=[];"
                + "function vis(e){try{var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>6&&r.height>6&&r.bottom>0&&r.right>0"
                + "&&r.top<(innerHeight||9999)&&s.display!=='none'&&s.visibility!=='hidden';}catch(_){return false;}}"
                + "function attrs(e){var s='';try{for(var i=0;i<e.attributes.length;i++)s+=' '+e.attributes[i].name+'='+e.attributes[i].value;}catch(_){}return s.toLowerCase();}"
                + "function boxes(){return [].slice.call(document.querySelectorAll('input')).filter(function(e){var t=(e.type||'text').toLowerCase();"
                + "return (t==='text'||t==='search')&&!e.disabled&&!e.readOnly&&vis(e);});}"
                + "function searchy(e){return /search|keyword|query|title|imdb|بحث/.test(attrs(e));}"
                // the 🔍 in their header: small, near the top, «search» in itself or in its icon
                + "function opener(){var best=null,bt=1e9,br=-1,lim=Math.min(170,(innerHeight||800)*0.3);"
                + "[].slice.call(document.querySelectorAll('button,a,[role=button],[onclick],label,span,i,svg,div')).forEach(function(e){"
                + "if(!vis(e)||(e.querySelector&&e.querySelector('input')))return;var r=e.getBoundingClientRect();"
                + "if(r.top<0||r.top>lim||r.width>90||r.height>90)return;"
                + "var s=attrs(e);try{[].slice.call(e.querySelectorAll('*')).slice(0,12).forEach(function(c){s+=attrs(c);});}catch(_){}"
                + "if(!/search|magnif|loupe/.test(s)||/submit|type=search/.test(s))return;"
                + "if(r.top<bt-2||(Math.abs(r.top-bt)<=2&&r.right>br)){bt=r.top;br=r.right;best=e;}});"
                + "if(!best)return null;return best.closest('button,a,[role=button],[onclick]')||best;}"
                + "function press(e){['mousedown','mouseup','click'].forEach(function(k){try{e.dispatchEvent(new MouseEvent(k,{bubbles:true,cancelable:true,view:window}));}catch(_){}});}"
                + "function fill(i){try{i.focus();}catch(_){}try{var d=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value');d.set.call(i,'');d.set.call(i,q);}catch(_){i.value=q;}"
                + "try{i.dispatchEvent(new InputEvent('input',{bubbles:true,data:q,inputType:'insertText'}));}catch(_){try{i.dispatchEvent(new Event('input',{bubbles:true}));}catch(__){}}"
                + "try{i.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}"
                + "['keydown','keyup'].forEach(function(k){try{i.dispatchEvent(new KeyboardEvent(k,{bubbles:true,key:'e',code:'KeyE'}));}catch(_){}});}"
                + "var onSearchPage=/search/i.test(location.pathname||'');"
                + "(function t(){n++;"
                + "if(!clicked&&!onSearchPage){var o=opener();if(o){clicked=true;before=boxes();press(o);setTimeout(t,350);return;}}"
                + "if(clicked){var now=boxes(),a=document.activeElement,pick=null;"
                + "if(a&&a.tagName==='INPUT'&&now.indexOf(a)>=0&&before.indexOf(a)<0)pick=a;"
                + "if(!pick)pick=now.filter(function(e){return before.indexOf(e)<0;})[0]||null;"
                + "if(pick){fill(pick);return;}"
                + "if(n<16){setTimeout(t,300);return;}}"
                // no 🔍 (or no box came up after it): the search box in the page
                + "var b=boxes().filter(searchy)[0]||null;if(b){fill(b);return;}"
                + "if(n<40)setTimeout(t,400);"
                + "})();"
                + "}catch(_){}})();";
    }

    private String providerPickerScript() {
        String wanted = JSONObject.quote(normalizeServerLabel(preferredServerLabel));
        String bridgeToken = JSONObject.quote(sourceChoiceToken);
        return "(function(){try{"
                + "var bridgeToken=" + bridgeToken + ",wanted=" + wanted
                + ",openMode=" + (openServers ? "true" : "false")
                + ",ownerTap=" + (openServers && interactiveSource ? "true" : "false") + ";"
                + "function clean(v){return String(v||'').replace(/[⭐★☆]/g,'').replace(/\\s+/g,' ').trim();}"
                + "function norm(v){return clean(v).toLowerCase();}"
                + "function bad(t){t=norm(t);return !t||/^(watch now|play|play now|home|movies|select server|trailer|download|settings|view details)$/.test(t)"
                // 322.3.123: share buttons and pop-up buttons are never servers (a moved site's
                // page has no grid, and its share row was listed as «servers»).
                + "||/^(whatsapp|telegram|facebook|twitter|x|reddit|pinterest|email|share|copy link|copy|more|more…|more\\.\\.\\.|join|later)$/.test(t);}"
                + "function known(t){t=norm(t);return /^(vidsrc\\.mov|vidsrc\\.fyi|vidrock|vidnest|vidking|vidlink|vidfast|vidup|videasy|111movies|2embed|multiembed|superflix|peachify)$/.test(t);}"
                + "var SEL='button,a,[role=button],[data-server],[data-src],[data-url],[data-embed]';"
                + "function label(e){return clean(e.innerText||e.textContent||e.getAttribute('data-server')||'');}"
                + "function legacyEls(){var all=[].slice.call(document.querySelectorAll(SEL));"
                + "return all.filter(function(e){var t=label(e);if(bad(t)||t.length>48)return false;"
                + "if(e.hasAttribute('data-server'))return true;"
                + "if((e.hasAttribute('data-src')||e.hasAttribute('data-url')||e.hasAttribute('data-embed'))&&known(t))return true;"
                + "return known(t);});}"
                // 322.3.98 open mode: the server grid is found by STRUCTURE, not by name.
                // Any known server marks its container; every short button in that
                // container is a server too (new names like «VIP (No ADS)», MappleTV…).
                // With no known name at all, the largest group of sibling buttons wins.
                + "function serverEls(){var base=legacyEls();if(!openMode)return base;"
                + "var all=[].slice.call(document.querySelectorAll(SEL)).filter(function(e){var t=label(e);return t.length>=2&&t.length<=48&&!bad(t);});"
                + "var boxes=[];base.forEach(function(e){var b=e.parentElement;if(!b)return;"
                + "var n=all.filter(function(x){return b.contains(x);}).length;if(n<3&&b.parentElement)b=b.parentElement;"
                + "if(boxes.indexOf(b)<0)boxes.push(b);});"
                + "if(!boxes.length){var best=null,bn=0,seen=[];all.forEach(function(x){var p=x.parentElement;if(!p||seen.indexOf(p)>=0)return;seen.push(p);"
                + "var n=all.filter(function(y){return y.parentElement===p;}).length;if(n>bn){bn=n;best=p;}});if(best&&bn>=4)boxes.push(best);}"
                + "var out=[];all.forEach(function(x){if(out.indexOf(x)<0&&boxes.some(function(b){return b.contains(x);}))out.push(x);});"
                + "base.forEach(function(x){if(out.indexOf(x)<0)out.push(x);});return out;}"
                + "function sendList(){try{var names=[],seen={};serverEls().forEach(function(e){var t=clean(e.innerText||e.textContent||e.getAttribute('data-server')||'');var k=norm(t);if(k&&!seen[k]){seen[k]=1;names.push(t);}});"
                + "if(names.length&&window.SubHubSourceChoice&&window.SubHubSourceChoice.serverPage)window.SubHubSourceChoice.serverPage(bridgeToken,String(location.href||''));"
                + "if(window.SubHubSourceChoice)window.SubHubSourceChoice.servers(bridgeToken,JSON.stringify(names));}catch(_){}}"
                + "function point(e){var r=e.getBoundingClientRect(),vw=Math.max(1,innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,innerHeight||document.documentElement.clientHeight||1);return [Math.max(.02,Math.min(.98,(r.left+r.width/2)/vw)),Math.max(.02,Math.min(.98,(r.top+r.height/2)/vh))];}"
                + "function uncovered(e){try{var r=e.getBoundingClientRect(),x=Math.max(1,Math.min((innerWidth||9999)-1,r.left+r.width/2)),y=Math.max(1,Math.min((innerHeight||9999)-1,r.top+r.height/2)),top=document.elementFromPoint(x,y);return !!top&&(top===e||e.contains(top)||top.contains(e));}catch(_){return false;}}"
                + "function direct(e){var vals=[e.getAttribute('data-src'),e.getAttribute('data-url'),e.getAttribute('data-embed'),e.getAttribute('href')];"
                + "for(var i=0;i<vals.length;i++){var v=String(vals[i]||'').trim();if(/^https:\\/\\//i.test(v)&&v.indexOf(location.host)<0)return v;}return '';}"
                + "function reportFrame(label){try{var frames=[].slice.call(document.querySelectorAll('iframe[src]'));for(var i=0;i<frames.length;i++){var u=String(frames[i].src||'');if(/^https:\\/\\//i.test(u)&&u.indexOf(location.host)<0){window.SubHubSourceChoice.resolved(bridgeToken,label,u);return true;}}}catch(_){}return false;}"
                // 322.3.123: the server is pressed INSIDE the page (its own click), so an ad or a
                // pop-up floating over the buttons no longer stops the choice (before: a covered
                // button was not pressed at all). A finger tap is only the fallback, and only on
                // a button nothing covers. A shown button wins over a hidden copy of it.
                + "function shown(e){try{var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';}catch(_){return false;}}"
                + "function activate(label){try{var want=norm(label),els=serverEls(),hits=els.filter(function(e){return norm(e.innerText||e.textContent||e.getAttribute('data-server')||'')===want;});"
                + "if(!hits.length)return false;var hit=hits.find(shown)||hits[0];var lab=clean(hit.innerText||hit.textContent||hit.getAttribute('data-server')||label);"
                + "if(window.SubHubSourceChoice)window.SubHubSourceChoice.picked(bridgeToken,lab);"
                + "var d=direct(hit);if(d){window.SubHubSourceChoice.resolved(bridgeToken,lab,d);return true;}"
                + "var obs=new MutationObserver(function(){if(reportFrame(lab)){try{obs.disconnect();}catch(_){}}});"
                + "try{obs.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});}catch(_){}"
                + "try{hit.click();}catch(_){}"
                + "setTimeout(function(){if(reportFrame(lab)||!uncovered(hit))return;var p=point(hit);if(window.SubHubSourceChoice&&window.SubHubSourceChoice.autoPick)window.SubHubSourceChoice.autoPick(bridgeToken,lab,p[0],p[1]);},180);"
                + "for(var n=1;n<=20;n++)setTimeout(function(){reportFrame(lab);},n*300);return true;}catch(_){return false;}}"
                // 322.3.98 open mode, owner only: a tap on ANY server button of the page
                // (Web Video Caster style) is recorded like a choice from the list, so
                // it can be saved. Capture phase: the page's own handler still runs.
                + "if(ownerTap&&!window.__subhubOpenTapV3298){window.__subhubOpenTapV3298=1;"
                + "document.addEventListener('click',function(ev){try{var t=ev.target&&ev.target.closest?ev.target.closest(SEL):null;"
                + "if(!t||serverEls().indexOf(t)<0)return;var lab=label(t);if(!lab||!window.SubHubSourceChoice)return;"
                + "if(window.SubHubSourceChoice.serverPage)window.SubHubSourceChoice.serverPage(bridgeToken,String(location.href||''));"
                + "window.SubHubSourceChoice.picked(bridgeToken,lab);"
                + "var d=direct(t);if(d){window.SubHubSourceChoice.resolved(bridgeToken,lab,d);return;}"
                + "for(var n=1;n<=20;n++)setTimeout(function(){reportFrame(lab);},n*300);}catch(_){}},true);}"
                + "window.__subhubSelectProvider=activate;"
                + "window.__subhubRequestServerList=function(){sendList();var n=0,t=setInterval(function(){n++;sendList();if(n>=8)clearInterval(t);},350);};"
                // 322.3.124: the site's check on the page — no server is pressed under it (the
                // site ignores that press, and the search used to stop there for good). The
                // waiting uses up no tries. The check coming and going is reported, so a check
                // that shows late still gets its card, and one passed in the same page lets the
                // search go on at once.
                + gatePresentFn()
                + "var gateWas=false;function gateSeen(){var on=__subhubGateOn();if(on!==gateWas){gateWas=on;"
                + "try{var S=window.SubHubSourceChoice;if(S&&S.gate)S.gate(bridgeToken,on);}catch(_){}}return on;}"
                + "if(!gateSeen())sendList();"
                + "if(wanted){var tries=0;if(window.__subhubPickLoop)clearInterval(window.__subhubPickLoop);"
                + "window.__subhubPickLoop=setInterval(function(){if(gateSeen())return;tries++;sendList();"
                + "if(activate(wanted)||tries>=240){clearInterval(window.__subhubPickLoop);window.__subhubPickLoop=0;}},500);}"
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
        // 322.3.123: the owner's «⚙️» for this server — exactly so many taps on play, this
        // far apart (0 = never tap: the video starts by itself and a tap would pause it).
        // Without it, the usual way: a tap now and then for about 8 seconds.
        String schedule = tapRuleTaps < 0
                ? "kick();setTimeout(kick,120);setTimeout(kick,320);setTimeout(kick,650);"
                + "var n=0,t=setInterval(function(){n++;kick();if(n>=18)clearInterval(t);},420);"
                : "play();setTimeout(play,400);setTimeout(play,1200);"
                + "for(var i=0;i<" + tapRuleTaps + ";i++)setTimeout(function(){play();tap();},700+i*" + tapRuleGapMs + ");";
        return "(function(){try{"
                + "if(window.__subhubProviderKickV3265)return;window.__subhubProviderKickV3265=true;"
                + "var bridgeToken=" + bridgeToken + ";"
                + "function vis(e){if(!e)return false;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>25&&r.height>20&&r.bottom>0&&r.right>0&&r.top<(innerHeight||99999)&&s.display!=='none'&&s.visibility!=='hidden';}"
                + "function point(e){var r=e.getBoundingClientRect(),vw=Math.max(1,innerWidth||document.documentElement.clientWidth||1),vh=Math.max(1,innerHeight||document.documentElement.clientHeight||1);return [Math.max(.02,Math.min(.98,(r.left+r.width/2)/vw)),Math.max(.02,Math.min(.98,(r.top+r.height/2)/vh))];}"
                + "function play(){try{var vids=[].slice.call(document.querySelectorAll('video')).filter(vis);vids.forEach(function(v){try{v.muted=false;var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(_){}});}catch(_){}}"
                + "function tap(){try{var els=[].slice.call(document.querySelectorAll('button,a,[role=button],[class*=play],[id*=play]')).filter(vis);"
                + "var b=els.find(function(e){var t=String(e.innerText||e.textContent||e.getAttribute('aria-label')||e.title||'').trim().toLowerCase();return /(^|\\s)(play|watch|start|continue|resume)(\\s|$)/.test(t)||/(play|triangle)/.test(String(e.className||'').toLowerCase());});"
                + "if(!b){var frames=[].slice.call(document.querySelectorAll('iframe,[class*=player],[id*=player]')).filter(function(e){if(!vis(e))return false;var r=e.getBoundingClientRect();return r.width>160&&r.height>90;});b=frames[0];}"
                + "if(b&&window.SubHubSourceChoice&&window.SubHubSourceChoice.playTarget){var p=point(b);window.SubHubSourceChoice.playTarget(bridgeToken,p[0],p[1]);}"
                + "}catch(_){}}"
                + "function kick(){play();tap();}"
                + schedule
                + "}catch(_){}})();";
    }

    private String resumePrefKey() {
        if (resumeKey.isEmpty()) return "";
        return "resume_" + resumeKey.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private void clearResumePosition() {
        String key = resumePrefKey();
        if (!key.isEmpty()) prefs.edit().remove(key).remove(key + "_at").apply();
        pendingResumeMs = 0L;
    }

    // 322.3.127: films and episodes share one place across servers («title_…»). The
    // first time after the update, the place this server/card had saved is kept.
    public void useLegacyResume(String legacyKey) {
        if (closed || legacyKey == null || legacyKey.trim().isEmpty()) return;
        String now = resumePrefKey();
        if (now.isEmpty() || prefs.contains(now)) return;
        String old = "resume_" + legacyKey.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        long v = Math.max(0L, prefs.getLong(old, 0L));
        if (v >= 5000L && v > pendingResumeMs) pendingResumeMs = v;
    }

    // 322.3.127: a film — the site page's place for it (its own servers and ours write
    // it) with the time it was saved: the latest stop wins. Without a time on our side
    // (nothing saved since the update) the further place is kept.
    public void setTitleResume(long ms, long atMillis) {
        if (closed || ms < 5000L) return;
        String key = resumePrefKey();
        long savedAt = key.isEmpty() ? 0L : prefs.getLong(key + "_at", 0L);
        if (savedAt <= 0L) {
            if (ms > pendingResumeMs) pendingResumeMs = ms;
            return;
        }
        if (atMillis > savedAt + 2000L) pendingResumeMs = ms;
    }

    private void persistResume(boolean force) {
        if (resumeKey.isEmpty() || player == null) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastResumePersistAt < 5000L) return;
        lastResumePersistAt = now;

        long position = Math.max(0L, player.getContentPosition());
        long duration = player.getDuration();
        if (playbackReady) reportProgress(position, duration);
        if (creditsReached) {   // 322.3.112: watched — the next time starts from the beginning
            clearResumePosition();
            return;
        }
        if (player.getPlaybackState() == Player.STATE_ENDED
                || (duration > 0L && position >= Math.max(0L, duration - 45000L))) {
            clearResumePosition();
            return;
        }
        if (position < 5000L) return;
        String key = resumePrefKey();
        // 322.3.127: + when (the latest stop wins between servers and the site).
        if (!key.isEmpty()) prefs.edit().putLong(key, position).putLong(key + "_at", System.currentTimeMillis()).apply();
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
        if (interactiveSource || probe == null || root == null || closed || gateShown) return;
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
        // 322.3.126: drawn only while our screen is over it — never shown to the viewer.
        probe.setAlpha(subscriberProviderVisible && directCover != null ? 1.0f : 0.0f);
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
        if (interactiveSource && verifyFirst) {
            showVerifyStage("اضغط أي سيرفر في الصفحة — يشتغل هنا في مشغّل الموقع. إن اشتغل الفيلم فعلاً اضغط «💾 حفظ هذا السيرفر».");
        } else if (interactiveSource) {
            showStage(openServers
                    ? "ادخل إلى الفيلم وأغلق الإعلانات، ثم اضغط أي سيرفر في الصفحة — أو «جلب السيرفرات»."
                    : "ادخل يدوياً إلى الفيلم وأغلق الإعلانات، ثم اضغط «جلب السيرفرات».");
        } else {
            if (subscriberStartupAttempt == 0) subscriberStartupAttempt = 1;
            if (subscriberStartupAttempt > 1) showSubscriberStage("جارٍ إعادة المحاولة");
            directKickRounds = 0;   // 322.3.126: each try gets its own taps
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
        // 322.3.127: the hidden page never makes a sound behind our screen — every
        // video and audio in every frame of it is muted (not while the owner tries a
        // server on the page itself).
        if (!interactiveSource) muteHiddenPage(probe);
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
                probeLoading = false;
                // 322.3.127: a WebView without document-start scripts — the top page at least.
                if (!interactiveSource && !muteInAllFrames) {
                    try { v.evaluateJavascript(MUTE_SCRIPT, null); } catch (Exception ignored) {}
                }
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
                        if (ownKind == OWN_PAGE) {
                            try { v.evaluateJavascript(ownPageMediaScript(), null); } catch (Exception ignored) {}
                        }
                        handler.postDelayed(() -> {
                            if (closed || probe == null || v != probe) return;
                            // 322.3.126: the provider page is drawn (its player needs it) but
                            // only under our screen — the viewer never sees it or its ads.
                            ensureViewerCover();
                            subscriberProviderVisible = true;
                            applySubscriberProbePreviewLayout();
                            try { v.evaluateJavascript(providerPlayScript(), null); } catch (Exception ignored) {}
                            scheduleDirectKick(v);
                        }, 180L);
                    }
                } else {
                    if (interactiveSource && verifyFirst) {
                        // 322.3.121: the chosen fbox name's page did not open — its reason stays.
                        if (!fboxPageProblem.isEmpty()) {
                            verifyStageGeneration++;
                            showStage(fboxPageProblem);
                        } else if (!ownerMoveNote.isEmpty()) {
                            // 322.3.123: the site moved — said once, on its new page.
                            verifyStageGeneration++;
                            showStage(ownerMoveNote);
                            ownerMoveNote = "";
                        } else if (verifyLabel.isEmpty()) {
                            showVerifyStage("اضغط أي سيرفر في الصفحة — يشتغل هنا في مشغّل الموقع. إن اشتغل الفيلم فعلاً اضغط «💾 حفظ هذا السيرفر».");
                        }
                        // 322.3.106: the page cannot pull the view back to its player.
                        try { v.evaluateJavascript(scrollGuardScript(), null); } catch (Exception ignored) {}
                    } else if (interactiveSource) {
                        showStage(openServers
                    ? "ادخل إلى الفيلم وأغلق الإعلانات، ثم اضغط أي سيرفر في الصفحة — أو «جلب السيرفرات»."
                    : "ادخل يدوياً إلى الفيلم وأغلق الإعلانات، ثم اضغط «جلب السيرفرات».");
                    } else {
                        startSubscriberStartupTimer(2);
                    }
                    try { v.evaluateJavascript(providerPickerScript(), null); } catch (Exception ignored) {}
                    // 322.3.123: a move notice / the site's pop-ups; a page with no server → the next name.
                    injectPageScripts(v);
                    scheduleGridWait(v);
                    // 322.3.118: fbox search for this film's title (owner, no saved page yet).
                    // Only on their front page (also again after their «Continue» check), never on a
                    // results or film page, so what the owner types himself is left alone.
                    // 322.3.121: also on their search page (when the 🔍 is a link), never on an
                    // error page of the chosen name.
                    if (interactiveSource && !searchText.isEmpty() && url != null && searchRuns < 3 && fboxPageProblem.isEmpty()) {
                        String path = "";
                        try { path = Uri.parse(url).getPath(); } catch (Exception ignored) {}
                        if (path == null || path.isEmpty() || "/".equals(path) || "/home".equals(path)
                                || path.toLowerCase(Locale.ROOT).startsWith("/search")) {
                            searchRuns++;
                            searchedUrl = url;
                            showVerifyStage("ابحث عن «" + searchText + "» واختر الفيلم نفسه، ثم اضغط السيرفر — احفظ من صفحة هذا الفيلم فقط.");
                            try { v.evaluateJavascript(searchFillScript(), null); } catch (Exception ignored) {}
                        }
                    }
                    // 322.3.113: Moviesmod's check page is shown to the viewer, not pressed by us.
                    // 322.3.116: a page that opens while the check is up stays hidden until
                    // the check says it is gone (no flash of the Moviesmod page).
                    if (!interactiveSource) {
                        if (gateShown && gatePageVisible) v.setAlpha(0.0f);
                        try { v.evaluateJavascript(gateCheckScript(), null); } catch (Exception ignored) {}
                    }
                    if (openChooser && !chooserAutoRequested && !playing) {
                        chooserAutoRequested = true;
                        handler.postDelayed(() -> {
                            if (!closed && !playing && probe == v && !serverChooserShown) requestServerList();
                        }, 1200L);
                    }
                }
            }

            // 322.3.112: during the automatic search a page that cannot open at all
            // (no network answer, or «not found») gives way to the next server at
            // once. 403/5xx are not judged: protection pages answer like that and
            // then open by themselves.
            @Override public void onReceivedError(WebView v, WebResourceRequest r,
                                                  android.webkit.WebResourceError e) {
                if (closed || v != probe || r == null || !r.isForMainFrame() || e == null) return;
                // Only "no answer at all"; a load cut short by the next page is not a failure.
                int code = e.getErrorCode();
                if (code != ERROR_HOST_LOOKUP && code != ERROR_CONNECT && code != ERROR_TIMEOUT
                        && code != ERROR_FAILED_SSL_HANDSHAKE) return;
                String why = "الصفحة لم تفتح";
                try { if (e != null && e.getDescription() != null) why += " (" + e.getDescription() + ")"; }
                catch (Exception ignored) {}
                final String reason = why;
                // 322.3.121: the owner's chosen fbox name does not answer — he is told by name.
                if (isFboxOwnerPage(r.getUrl())) {
                    final String text = "⚠️ «" + fboxOwnerName + "» لا يردّ — اختر اسماً آخر من «✏️ الأسماء».";
                    handler.post(() -> fboxOwnerProblem(v, text));
                    return;
                }
                // 322.3.120: the fbox page itself (not a server opened from it) → the next name at once.
                final boolean fboxPage = isFboxPageRequest(r.getUrl());
                handler.post(() -> {
                    if (fboxPage && fboxPageFailed(v, reason)) return;
                    autoPageFailed(v, reason);
                });
            }

            // 322.3.121: a new page — any reason shown for the one before is gone.
            @Override public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (v != probe) return;
                fboxPageProblem = "";
                probeLoading = true;
                // 322.3.124: their check box is on screen and a new page starts — hidden at once.
                if (!closed && !interactiveSource && gateShown && gatePageVisible) gateBetweenPages(v);
            }

            @Override public void onReceivedHttpError(WebView v, WebResourceRequest r,
                                                      WebResourceResponse resp) {
                if (closed || v != probe || r == null || resp == null || !r.isForMainFrame()) return;
                int code = resp.getStatusCode();
                // 322.3.121: the owner's chosen fbox name is blocked (451), «not found» or
                // the site's server is down — he is told by name, and changes it himself.
                if (isFboxOwnerPage(r.getUrl()) && (code == 404 || code == 410 || code == 451
                        || code == 521 || code == 522 || code == 523 || code == 525 || code == 526 || code == 530)) {
                    final String text = code == 451
                            ? "⚠️ «" + fboxOwnerName + "» محجوب عندك (451) — اختر اسماً آخر من «✏️ الأسماء»."
                            : code >= 500
                            ? "⚠️ خادم «" + fboxOwnerName + "» متوقف (" + code + ") — اختر اسماً آخر من «✏️ الأسماء»."
                            : "⚠️ الصفحة غير موجودة على «" + fboxOwnerName + "» (" + code + ") — جرّب اسماً آخر من «✏️ الأسماء».";
                    handler.post(() -> fboxOwnerProblem(v, text));
                    return;
                }
                // 322.3.120: an fbox name that answers «not found / gone», «blocked» (451)
                // or Cloudflare's «the site's server is down» (521–523, 525, 526, 530) →
                // the next name at once. Never 403/503: check pages answer like that.
                if (isFboxPageRequest(r.getUrl()) && (code == 404 || code == 410 || code == 451
                        || code == 521 || code == 522 || code == 523 || code == 525 || code == 526 || code == 530)) {
                    final String why = code == 451 ? "الصفحة محجوبة (451)"
                            : code >= 500 ? "خادم الموقع متوقف (" + code + ")"
                            : "الصفحة غير موجودة (" + code + ")";
                    handler.post(() -> {
                        if (fboxPageFailed(v, why)) return;
                        if (code == 404 || code == 410) autoPageFailed(v, why);
                    });
                    return;
                }
                if (code != 404 && code != 410) return;
                handler.post(() -> autoPageFailed(v, "الصفحة غير موجودة (" + code + ")"));
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
                // 322.3.127: a video FILE the page's player reads (Vidnest on fbox: a 1.48 GB
                // MKV) is taken too, not only playlists; it is looked at before it plays.
                boolean videoFile = !vidnestMode && ownKind == OWN_NONE
                        && !url.toLowerCase(Locale.ROOT).contains(".m3u8")
                        && CapturedLinkCheck.isVideoFileRequest(url, r.getRequestHeaders());
                if ("https".equals(r.getUrl().getScheme())
                        && (url.toLowerCase(Locale.ROOT).contains(".m3u8")
                            || (ownKind == OWN_PAGE && isOwnWorkerMedia(r.getUrl()))
                            || videoFile)
                        && ((!interactiveSource && autoServer) || !selectedProviderLabel.isEmpty())
                        // 322.3.106: no capture while the owner verifies on the page.
                        && !(verifyFirst && interactiveSource)) {
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
                // 322.3.126: our screen from the first step (the servers page, the server,
                // its player) until the video plays — the viewer sees none of their pages.
                showDirectCover();
                root.post(this::applySubscriberProbePreviewLayout);
                startSubscriberStartupTimer(1);
            }
        }

        handler.postDelayed(() -> {
            if (interactiveSource && !verifyFirst && !closed && !playing && !vidnestListShown && !vidnestEvaluating) {
                message("لم يُلتقط بث بعد. اختر سيرفراً آخر أو أعد المحاولة.");
                if (!selectedProviderLabel.isEmpty()) showDecisionBar(false);
            }
        }, 45000);
    }

    // 322.3.79: these players often need the play button pressed two or three
    // times (the first taps hit an invisible ad layer). Keep tapping the play
    // target every few seconds until the stream is captured.
    private void scheduleDirectKick(WebView v) {
        // 322.3.126: every viewer start (not only the saved player link) — the page is
        // hidden, so these taps (and our button after them) are the only ones it gets.
        if (interactiveSource) return;
        if (tapRuleTaps >= 0) {   // 322.3.123: the owner's «⚙️» taps are the whole plan
            if (directKickRounds == 0) {
                directKickRounds = 3;
                scheduleDirectReveal(v);
            }
            return;
        }
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
    // 322.3.126: the provider player is no longer shown — our own button
    // («▶ اضغط هنا لتشغيل الفيديو») on our screen passes the viewer's press to it.
    // The time limit keeps running: no press, and the next try / message comes.
    private void scheduleDirectReveal(WebView v) {
        directRevealGeneration++;
        final int generation = directRevealGeneration;
        handler.postDelayed(() -> {
            if (generation != directRevealGeneration || closed || playing
                    || candidate != null || probe == null || v != probe) return;
            if (directCover == null || linkChecksRunning > 0) return;
            if (autoActive() && autoMore) return;   // 322.3.110: the next server comes instead
            showCoverTapButton();
        }, 8000L);
    }

    // 322.3.81: animated SubHub loading screen. Letters rise in one by one,
    // the name then pulses softly, three gold dots bounce, and the real stage
    // text sits under them (no separate box) until the video starts.
    private void showDirectCover() {
        if (interactiveSource || closed || playbackReady) return;   // 322.3.126: every viewer start
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

        // 322.3.126: shown only when the hidden page needs a press (see showCoverTapButton).
        coverTapButton = chip("▶ اضغط هنا لتشغيل الفيديو", 16, this::coverTapPressed);
        coverTapButton.setBackground(round(0xff12301d, 0xff22c55e, 1, 14));
        coverTapButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams tapLp = new LinearLayout.LayoutParams(dp(270), dp(48));
        tapLp.topMargin = dp(18);
        column.addView(coverTapButton, tapLp);

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
        coverTapButton = null;
        coverTapGeneration++;
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
        coverTapButton = null;
        coverTapGeneration++;
        directRevealGeneration++;
        cover.setClickable(false);
        cover.animate().alpha(0f).setDuration(380L).withEndAction(() -> {
            stopCoverAnimators();
            ViewGroup parent = (ViewGroup) cover.getParent();
            if (parent != null) parent.removeView(cover);
        }).start();
    }

    // 322.3.126: the viewer's start always runs behind our screen (it can be gone after
    // their check card, or a page that loaded while it was away).
    private void ensureViewerCover() {
        if (interactiveSource || closed || playing || playbackReady || gateShown) return;
        if (directCover == null) showDirectCover();
    }

    // 322.3.126: the hidden page needs a press — our button, never their page.
    private void showCoverTapButton() {
        if (closed || playing || interactiveSource || probe == null) return;
        ensureViewerCover();
        if (coverTapButton == null) return;
        setDirectCoverStage("السيرفر يحتاج ضغطة لتشغيل الفيديو");
        coverTapButton.setVisibility(View.VISIBLE);
    }

    // The viewer's press → the hidden page: its own play button (found in the page),
    // then the middle of its player. A fresh time for this step; our button again
    // after a few seconds if still nothing came.
    private void coverTapPressed() {
        if (closed || playing || probe == null || candidate != null) return;
        if (coverTapButton != null) coverTapButton.setVisibility(View.GONE);
        final int gen = ++coverTapGeneration;
        final WebView pv = probe;
        directKickRounds = 0;
        try {
            pv.evaluateJavascript("window.__subhubProviderKickV3265=false;" + providerPlayScript(), null);
        } catch (Exception ignored) {}
        dispatchProviderTap(0.5d, 0.5d);
        startSubscriberStartupTimer(4);
        handler.postDelayed(() -> {
            if (gen != coverTapGeneration || closed || playing || candidate != null
                    || probe != pv || linkChecksRunning > 0) return;
            if (autoActive() && autoMore) return;
            showCoverTapButton();
        }, 9000L);
    }

    // 322.3.126: every m3u8 the page asks for is looked at before it plays — one at a
    // time, in the page's order. A short complete clip (an ad, the site's intro) is
    // skipped and the capture goes on; anything that cannot be judged plays as before.
    private void capture(String url, Map<String,String> headers) {
        if (closed || playing) return;
        if (url != null && url.toLowerCase(Locale.ROOT).contains(".m3u8")) {
            checkCapturedLink(url, headers);
            return;
        }
        // 322.3.127: a video file — its head says its length (or its size): an ad is skipped.
        if (!vidnestMode && ownKind == OWN_NONE && CapturedLinkCheck.isVideoFileRequest(url, headers)) {
            checkCapturedFile(url, headers);
            return;
        }
        acceptCapture(url, headers);
    }

    // 322.3.127: the same queue as playlists. The page reads a file in many byte
    // ranges — each link is looked at once.
    private void checkCapturedFile(final String url, Map<String,String> headers) {
        if (!linkChecked.add(url)) return;   // each link once
        if (linkCheckExec == null) linkCheckExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        final int gen = linkCheckGen.get();
        final Map<String,String> h = headers == null ? new HashMap<>() : new HashMap<>(headers);
        linkChecksRunning++;
        try {
            linkCheckExec.execute(() -> {
                if (gen != linkCheckGen.get()) return;   // the try is over (no post: counter reset)
                String v;
                try {
                    v = CapturedLinkCheck.fileVerdict(url, CapturedLinkCheck.httpHead(h,
                            u -> CookieManager.getInstance().getCookie(u)));
                } catch (Throwable t) {
                    v = "";
                }
                final String verdict = v;
                handler.post(() -> {
                    if (gen != linkCheckGen.get()) return;
                    linkChecksRunning = Math.max(0, linkChecksRunning - 1);
                    if (closed || playing) return;
                    if (verdict.isEmpty()) {
                        capturedFiles.add(url);
                        acceptCapture(url, h);
                        return;
                    }
                    linkSkipped++;
                    if (ownerMode || interactiveSource) showTransientValue("⏭ " + verdict, 1800);
                });
            });
        } catch (Exception e) {   // no thread for it: straight to the player
            linkChecksRunning = Math.max(0, linkChecksRunning - 1);
            capturedFiles.add(url);
            acceptCapture(url, h);
        }
    }

    private void checkCapturedLink(final String url, Map<String,String> headers) {
        if (!linkChecked.add(url)) return;   // each link once
        if (linkCheckExec == null) linkCheckExec = java.util.concurrent.Executors.newSingleThreadExecutor();
        final int gen = linkCheckGen.get();
        final Map<String,String> h = headers == null ? new HashMap<>() : new HashMap<>(headers);
        linkChecksRunning++;
        try {
            linkCheckExec.execute(() -> {
                if (gen != linkCheckGen.get()) return;   // the try is over (no post: counter reset)
                String v;
                try {
                    v = CapturedLinkCheck.verdict(url, CapturedLinkCheck.http(h,
                            u -> CookieManager.getInstance().getCookie(u)));
                } catch (Throwable t) {
                    v = "";
                }
                final String verdict = v;
                handler.post(() -> {
                    if (gen != linkCheckGen.get()) return;
                    linkChecksRunning = Math.max(0, linkChecksRunning - 1);
                    if (closed || playing) return;
                    if (verdict.isEmpty()) {
                        acceptCapture(url, h);
                        return;
                    }
                    linkSkipped++;
                    if (ownerMode || interactiveSource) showTransientValue("⏭ " + verdict, 1800);
                });
            });
        } catch (Exception e) {   // no thread for it: as before, straight to the player
            linkChecksRunning = Math.max(0, linkChecksRunning - 1);
            acceptCapture(url, h);
        }
    }

    private void acceptCapture(String url, Map<String,String> headers) {
        if (closed || playing) return;
        if (manualServerButton != null) manualServerButton.setVisibility(View.GONE);
        boolean first = candidate == null;
        // 322.3.127: a file link may end with «/» (no last segment).
        String last = url == null ? null : Uri.parse(url).getLastPathSegment();
        if (first || (last != null && last.toLowerCase(Locale.ROOT).contains("master")
                && !capturedFiles.contains(candidate))) {
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

    // 322.3.127: the quality — every film starts at 720 (480 when there is no 720), the
    // automatic choice stays at or under it, one quality down when it stalls (with a
    // quick line saying where the trouble most likely is), back up when calm. A quality
    // the viewer raised by hand is never lowered by itself: when it stalls the same way,
    // a small card offers «⬇ خفّض إلى …» (StallGuard, a second one for his pick).
    private final StallGuard stallGuard = new StallGuard();
    private final StallGuard manualGuard = new StallGuard();
    private boolean manualQuality = false;
    private long stallBeganAt = 0L;
    private long lastSeekAt = 0L;
    private int stallGeneration = 0;
    private long lastCalmCheckAt = 0L;
    private LinearLayout lowerHint;

    private void noteStall() {
        if (player == null || closed || !playbackReady || stallBeganAt > 0L) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastSeekAt < 4000L) return;   // the viewer moved in the film
        stallBeganAt = now;
        final int gen = ++stallGeneration;
        final boolean byHand = manualQuality;
        final StallGuard g = byHand ? manualGuard : stallGuard;
        stallVerdict(g.onStall(now, playingHeight(), videoHeights()), byHand);
        handler.postDelayed(() -> {
            if (closed || player == null || gen != stallGeneration || stallBeganAt == 0L || manualQuality != byHand) return;
            stallVerdict(g.onLongStall(SystemClock.elapsedRealtime(), playingHeight(), videoHeights()), byHand);
        }, StallGuard.LONG_STALL_MS);
    }

    // A lower quality is due: «تلقائي» takes it with a quick line; a hand pick is offered it.
    private void stallVerdict(int lower, boolean byHand) {
        if (lower <= 0 || player == null) return;
        String why = BandwidthMemory.slowReason(
                bandwidthMeasured && bandwidthMeter != null ? bandwidthMeter.getBitrateEstimate() : 0L,
                bandwidthBestKey.isEmpty() ? 0L : prefs.getLong(bandwidthBestKey, 0L));
        if (byHand) {
            showLowerHint(lower, why);
            return;
        }
        setVideoCap(lower);
        showTransientValue("⬇ " + lower + " — " + why, 2600);
    }

    private void showLowerHint(final int lower, String why) {
        hideLowerHint();
        if (root == null || closed) return;
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(round(0xee0b1725, 0xccF7BC3D, 1, 14));
        box.setElevation(dp(26));
        TextView t = new TextView(activity);
        t.setText("الفيديو يعلّق — " + why);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        box.addView(t, new LinearLayout.LayoutParams(-2, -2));
        TextView b = chip("⬇ خفّض إلى " + lower, 13, () -> {
            hideLowerHint();
            pickHeight(lower);
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2);
        blp.topMargin = dp(8);
        box.addView(b, blp);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        p.setMargins(dp(16), dp(16), dp(16), dp(76));
        root.addView(box, p);
        lowerHint = box;
        final LinearLayout mine = box;
        handler.postDelayed(() -> { if (lowerHint == mine) hideLowerHint(); }, 12000L);
    }

    private void hideLowerHint() {
        if (lowerHint == null) return;
        ViewGroup parent = (ViewGroup) lowerHint.getParent();
        if (parent != null) parent.removeView(lowerHint);
        lowerHint = null;
    }

    // The viewer took the offer: that height, still his own choice.
    private void pickHeight(int height) {
        if (player == null || closed) return;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i) || g.getTrackFormat(i).height != height) continue;
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .setOverrideForType(new TrackSelectionOverride(g.getMediaTrackGroup(), i))
                        .clearVideoSizeConstraints()
                        .build());
                manualQuality = true;
                manualGuard.begin(height, SystemClock.elapsedRealtime());
                stallBeganAt = 0L;
                stallGeneration++;
                showTransientValue("⬇ " + height, 1200);
                return;
            }
        }
    }

    // Every few seconds of smooth playback: back up one quality after calm minutes.
    private void calmCheck() {
        if (player == null || closed || manualQuality || stallBeganAt > 0L || !player.isPlaying()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastCalmCheckAt < 5000L) return;
        lastCalmCheckAt = now;
        int up = stallGuard.onCalm(now, videoHeights());
        if (up > 0) setVideoCap(up);
    }

    private void setVideoCap(int cap) {
        if (player == null || cap <= 0) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setMaxVideoSize(Integer.MAX_VALUE, cap).build());
    }

    private int playingHeight() {
        androidx.media3.common.Format f = player == null ? null : player.getVideoFormat();
        return f == null || f.height <= 0 ? 0 : f.height;
    }

    /** The qualities (heights) this stream has. */
    private int[] videoHeights() {
        if (player == null) return new int[0];
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                int h = g.getTrackFormat(i).height;
                if (h > 0) set.add(h);
            }
        }
        int[] out = new int[set.size()];
        int k = 0;
        for (Integer h : set) out[k++] = h;
        return out;
    }

    // 322.3.127: the speed the automatic quality starts from (BandwidthMemory).
    private DefaultBandwidthMeter bandwidthMeter;
    private String bandwidthKey = "";
    private String bandwidthBestKey = "";   // the best speed lately here (for «where is the trouble»)
    private boolean bandwidthMeasured = false;

    private void saveBandwidth() {
        if (bandwidthMeter == null || bandwidthKey.isEmpty()) return;
        long v = BandwidthMemory.toSave(bandwidthMeter.getBitrateEstimate(), bandwidthMeasured);
        if (v > 0) {
            prefs.edit()
                    .putLong(bandwidthKey, v)
                    .putLong(bandwidthBestKey, BandwidthMemory.newBest(prefs.getLong(bandwidthBestKey, 0L), v))
                    .apply();
        }
        bandwidthMeasured = false;
    }

    /** "wifi" (Wi-Fi / cable), "cell" (mobile data) or "other". */
    private String networkKind() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    activity.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "other";
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c == null) return "other";
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) return "wifi";
            if (c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return "cell";
        } catch (Exception ignored) {}
        return "other";
    }

    // 322.3.127: every <video>/<audio> of the hidden page (and of each frame in it) is
    // muted and stays muted: «muted» can only be set to true, play() mutes first, and
    // a media that starts or turns its sound on is muted again. The page still loads
    // and plays — we only take its link.
    static final String MUTE_SCRIPT = "(function(){try{if(window.__subhubMuteV3127)return;window.__subhubMuteV3127=1;"
            + "var P=HTMLMediaElement.prototype,d=Object.getOwnPropertyDescriptor(P,'muted');"
            + "var on=function(m){try{if(d&&d.set)d.set.call(m,true);else m.muted=true;}catch(e){}};"
            + "var is=function(m){try{return d&&d.get?d.get.call(m):m.muted;}catch(e){return true;}};"
            + "if(d&&d.set&&d.get){Object.defineProperty(P,'muted',{configurable:true,enumerable:d.enumerable,"
            + "get:d.get,set:function(){d.set.call(this,true);}});}"
            + "var pl=P.play;P.play=function(){on(this);return pl.apply(this,arguments);};"
            + "var hush=function(e){var t=e&&e.target;if(t instanceof HTMLMediaElement&&!is(t))on(t);};"
            + "['play','playing','volumechange','loadeddata'].forEach(function(n){document.addEventListener(n,hush,true);});"
            + "}catch(e){}})();";
    private boolean muteInAllFrames = false;

    private void muteHiddenPage(WebView v) {
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(v, MUTE_SCRIPT, java.util.Collections.singleton("*"));
                muteInAllFrames = true;
            }
        } catch (Exception ignored) {
            muteInAllFrames = false;
        }
    }

    private void destroyProbe() {
        probeLoading = false;
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
        if (autoFail("Vidnest · فحص الرابط فشل")) return;
        if (!interactiveSource && subscriberStartupAttempt < 2) {
            retrySubscriberStartup();
        } else if (!interactiveSource) {
            subscriberStartupGeneration++;
            subscriberStartupPhase = 0;
            stopSubscriberStageAnimation();
            cleanupPlayerForRetry();
            destroyProbe();
            removeDirectCover();
            showServerFailure("Vidnest · فحص الرابط فشل\n" + vidnestProbeReport);
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
        gateDone();   // 322.3.122: the video came, so the check passed — its card goes
        linkCheckGen.incrementAndGet();   // 322.3.126: the rest of the queue is not needed
        linkChecksRunning = 0;
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

        // 322.3.127: «تلقائي» starts from the speed this device measured last time on
        // this kind of connection (about 720p the first time), then follows the real
        // speed while playing, as before.
        String network = networkKind();
        bandwidthKey = BandwidthMemory.key(network);
        bandwidthBestKey = BandwidthMemory.bestKey(network);
        bandwidthMeasured = false;
        bandwidthMeter = new DefaultBandwidthMeter.Builder(activity)
                .setInitialBitrateEstimate(BandwidthMemory.initial(prefs.getLong(bandwidthKey, 0L)))
                .build();
        bandwidthMeter.addEventListener(handler, (elapsedMs, bytes, bitrate) -> bandwidthMeasured = true);
        // 322.3.127: loads further ahead (2–2.5 minutes; less at the highest qualities,
        // where memory limits it) and goes up a quality more carefully (60 % of the
        // measured speed, 15 s loaded first) — servers that are slow on one connection.
        DefaultTrackSelector selector = new DefaultTrackSelector(activity,
                new AdaptiveTrackSelection.Factory(15_000, 25_000, 25_000, 0.6f));
        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(120_000, 150_000, 2_500, 5_000)
                .build();
        player = new ExoPlayer.Builder(activity)
                .setBandwidthMeter(bandwidthMeter)
                .setTrackSelector(selector)
                .setLoadControl(loadControl)
                .build();
        // 322.3.127: every film starts at 720 (480 when there is no 720) and the automatic
        // choice stays at or under it; StallGuard lowers it when it stalls.
        manualQuality = false;
        stallBeganAt = 0L;
        stallGeneration++;
        hideLowerHint();
        stallGuard.begin(StallGuard.START_HEIGHT, SystemClock.elapsedRealtime());
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxVideoSize(Integer.MAX_VALUE, stallGuard.cap())
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
        customizeControls();

        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    // 322.3.127: a stall (if any) is over.
                    stallBeganAt = 0L;
                    stallGeneration++;
                    playbackReady = true;
                    updateBottomRow();   // 322.3.115
                    if (autoActive() && !autoReported) {
                        autoReported = true;
                        try { listener.autoSucceeded(); } catch (Exception ignored) {}
                    }
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
                    noteStall();   // 322.3.127
                    if (interactiveSource) showStage("جارٍ تحميل الفيديو…");
                    else showSubscriberStage("جارٍ تشغيل الفيديو");
                } else if (state == Player.STATE_ENDED) {
                    if (player != null) reportProgress(Math.max(0L, player.getDuration()), player.getDuration());
                    clearResumePosition();
                    // 322.3.112: the end came before the card (short credits): go on.
                    if (!nextLabel.isEmpty() && nextAuto && !nextCancelled && !interactiveSource) goNextEpisode();
                }
            }

            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition, int reason) {
                // 322.3.127: a seek loads for a moment — that is not a stall.
                if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAt = SystemClock.elapsedRealtime();
            }

            @Override public void onPlayerError(PlaybackException error) {
                final String vidnestDiag = (vidnestMode && ownerMode) ? vidnestDiagnostic(error) : "";
                failureDetail = vidnestDiag;
                final String reason = vidnestDiag.isEmpty() ? playbackFailureReason(error) : vidnestDiag;
                if (firstFailureReason.isEmpty()) firstFailureReason = reason;
                if (autoFail(reason)) return;   // 322.3.110: next server, quietly
                if (!interactiveSource && subscriberStartupAttempt < 2) {
                    retrySubscriberStartup();
                } else if (!interactiveSource) {
                    subscriberStartupGeneration++;
                    subscriberStartupPhase = 0;
                    stopSubscriberStageAnimation();
                    cleanupPlayerForRetry();
                    destroyProbe();
                    removeDirectCover();
                    showServerFailure(reason);
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
        // 322.3.112: our own plain files (R2 / mp4) are not playlists.
        // 322.3.127: nor a video file taken from the page (MKV / MP4).
        if ((ownKind != OWN_NONE || capturedFiles.contains(candidate)) && !looksLikeHls(candidate)) {
            player.setMediaSource(new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(sourceFactory)
                    .createMediaSource(MediaItem.fromUri(candidate)));
        } else {
            player.setMediaSource(new HlsMediaSource.Factory(sourceFactory).createMediaSource(item));
        }
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
        syncPanelOpen = false;
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
        ArrayList<Integer> heights = new ArrayList<>();   // 322.3.127
        // 322.3.127: «تلقائي» = 720 and under (lower when the server stalls).
        labels.add(String.format(new Locale("ar"), "تلقائي — حتى %d", StallGuard.START_HEIGHT));
        choices.add(null);
        heights.add(0);

        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                androidx.media3.common.Format f = g.getTrackFormat(i);
                labels.add(f.height > 0
                        ? String.format(new Locale("ar"), "%d بكسل", f.height)
                        : "جودة المصدر");
                choices.add(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
                heights.add(Math.max(0, f.height));
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
                hideLowerHint();
                stallBeganAt = 0L;
                stallGeneration++;
                if (choices.get(which) != null) {
                    b.setOverrideForType(choices.get(which));
                    // 322.3.127: the viewer's own quality — no cap and never lowered by
                    // itself (a stall only offers «⬇ خفّض»). Not kept for the next film.
                    b.clearVideoSizeConstraints();
                    manualQuality = true;
                    manualGuard.begin(Math.max(0, heights.get(which)), SystemClock.elapsedRealtime());
                } else {
                    // 322.3.127: «تلقائي» again — 720 and under, and the stall rule.
                    manualQuality = false;
                    stallGuard.begin(StallGuard.START_HEIGHT, SystemClock.elapsedRealtime());
                    b.setMaxVideoSize(Integer.MAX_VALUE, stallGuard.cap());
                }
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
            leaveLocalSubtitle();
            selectedSubtitle = -1;
            resetSegment();
            captions = false;
            cues = new JSONArray();
            currentSubtitleText = "";
            applySubtitleText("");
            subtitle.setVisibility(View.GONE);
            hidePanel();
        }));
        if ((selectedSubtitle < 0 && selectedSubtitle != LOCAL_SUBTITLE) || !captions) markSelected(off);
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
                leaveLocalSubtitle();
                selectedSubtitle = index;
                resetSegment();
                captions = true;
                cues = new JSONArray();
                currentSubtitleText = "";
                applySubtitleText("");
                listener.subtitleRequested(index);
                watchSubtitleAnswer(index);
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

        addLocalSubtitleSection(list);

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        showPanel(true);
    }

    // ---------------------------------------------------------------------
    // 322.3.109: «📁 إضافة ترجمة من الجهاز» — for everyone. The file is read
    // and kept on this device only (never uploaded); 📌 pins are hidden while
    // it is shown, the ⏱ timing tool works and saves on this device.
    // ---------------------------------------------------------------------
    private void addLocalSubtitleSection(LinearLayout list) {
        TextView head = new TextView(activity);
        head.setText("──────  ترجمة من جهازك  ──────");
        head.setTextColor(0xff8aa0b2);
        head.setTextSize(12);
        head.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
        headLp.setMargins(0, dp(16), 0, dp(6));
        list.addView(head, headLp);

        if (hasLocalSubtitle()) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView item = listRow(chip("📁  " + localSubtitleName + "  (من الجهاز)", 14, () -> {
                selectLocalSubtitle();
                hidePanel();
            }));
            item.setGravity(Gravity.CENTER);
            item.setTextDirection(View.TEXT_DIRECTION_RTL);
            if (captions && selectedSubtitle == LOCAL_SUBTITLE) markSelected(item);
            TextView remove = chip("✕", 16, () -> {
                removeLocalSubtitle();
                chooseSubtitle();
            });
            remove.setBackground(round(0xff3b1414, 0xffef4444, 1, 14));
            remove.setContentDescription("حذف ترجمة الجهاز");
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams rmLp = new LinearLayout.LayoutParams(dp(46), dp(46));
            rmLp.setMargins(dp(6), 0, 0, 0);
            row.addView(remove, rmLp);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.setMargins(0, dp(2), 0, dp(4));
            list.addView(row, rowLp);
        }

        TextView add = listRow(chip(
                (hasLocalSubtitle() ? "📁  تغيير ملف الترجمة من الجهاز" : "📁  إضافة ترجمة من الجهاز")
                        + "\nSRT · VTT · ASS",
                14, this::pickLocalSubtitle));
        add.setBackground(round(0xff0f2a44, 0xff38bdf8, 2, 14));
        add.setTextColor(Color.WHITE);
        add.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, -2);
        addLp.setMargins(0, dp(2), 0, dp(2));
        list.addView(add, addLp);
    }

    private boolean hasLocalSubtitle() {
        return localSubtitleCues != null && localSubtitleCues.length() > 0;
    }

    private boolean localSubtitleActive() {
        return selectedSubtitle == LOCAL_SUBTITLE && hasLocalSubtitle();
    }

    private void pickLocalSubtitle() {
        hidePanel();
        resumeAfterLocalPick = player != null && player.isPlaying();
        showTransientValue("اختر ملف الترجمة (SRT أو VTT أو ASS)…", 1600);
        try { listener.localSubtitlePickRequested(); } catch (Exception ignored) {}
    }

    // The picker was closed without a file, or the file could not be used.
    public void localSubtitlePickFailed(String message) {
        if (closed) return;
        if (message != null && !message.trim().isEmpty()) showTransientValue(message.trim(), 4500);
        resumeAfterLocalPickIfNeeded();
    }

    private void resumeAfterLocalPickIfNeeded() {
        if (resumeAfterLocalPick && player != null) player.play();
        resumeAfterLocalPick = false;
    }

    /** A device file is ready (just picked, or saved earlier for this film). */
    public void setLocalSubtitle(String name, JSONArray value, long offsetMs, boolean announce) {
        if (closed || value == null || value.length() == 0) return;
        localSubtitleName = name == null || name.trim().isEmpty() ? "ترجمة من الجهاز" : name.trim();
        localSubtitleCues = value;
        localSubtitleOffsetMs = clampSync(offsetMs);
        boolean wasLocal = selectedSubtitle == LOCAL_SUBTITLE;
        if (wasLocal) {
            cues = localSubtitleCues;
            subtitleOffsetMs = localSubtitleOffsetMs;
        } else {
            selectLocalSubtitle();
        }
        if (announce) {
            showTransientValue("✓ أُضيفت الترجمة: " + localSubtitleName
                    + "\n" + toArabicDigits(value.length()) + " سطراً — على جهازك فقط", 3200);
        } else {
            // Saved earlier for this film/episode on this device.
            showTransientValue("📁 ترجمة من جهازك: " + localSubtitleName + "\nلتغييرها أو حذفها: زر CC", 2600);
        }
        resumeAfterLocalPickIfNeeded();
    }

    private void selectLocalSubtitle() {
        if (!hasLocalSubtitle()) return;
        if (selectedSubtitle != LOCAL_SUBTITLE) catalogSubtitleOffsetMs = subtitleOffsetMs;
        selectedSubtitle = LOCAL_SUBTITLE;
        resetSegment();
        captions = true;
        subtitleAnsweredSeq = subtitleAskSeq;     // nothing to wait for from SubHub
        cues = localSubtitleCues;
        subtitleOffsetMs = localSubtitleOffsetMs;
        currentSubtitleText = "";
        applySubtitleText("");
    }

    // Switching from the device file back to SubHub: SubHub's timing returns.
    private void leaveLocalSubtitle() {
        if (selectedSubtitle != LOCAL_SUBTITLE) return;
        subtitleOffsetMs = catalogSubtitleOffsetMs;
        selectedSubtitle = -1;
    }

    private void removeLocalSubtitle() {
        boolean wasActive = selectedSubtitle == LOCAL_SUBTITLE;
        localSubtitleCues = null;
        localSubtitleName = "";
        localSubtitleOffsetMs = 0L;
        try { listener.localSubtitleRemoved(); } catch (Exception ignored) {}
        if (wasActive) {
            leaveLocalSubtitle();
            cues = new JSONArray();
            currentSubtitleText = "";
            applySubtitleText("");
            if (defaultCatalogIndex >= 0 && defaultCatalogIndex < catalog.length()) {
                selectDefault(defaultCatalogIndex);
            } else {
                captions = false;
                subtitle.setVisibility(View.GONE);
            }
        }
        showTransientValue("حُذفت ترجمة الجهاز", 1300);
    }

    // Owner «حفظ»: the device file's timing never goes to SubHub.
    private long offsetForSubHubSave() {
        return selectedSubtitle == LOCAL_SUBTITLE ? catalogSubtitleOffsetMs : subtitleOffsetMs;
    }

    /** SubHub's default subtitle, kept for when the device file is removed. */
    public void rememberDefaultIndex(int index) {
        defaultCatalogIndex = index;
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
        defaultCatalogIndex = index;
        if (index >= 0 && index < catalog.length()) {
            selectedSubtitle = index;
            resetSegment();
            captions = true;
            listener.subtitleRequested(index);
            watchSubtitleAnswer(index);
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
        subtitleAnsweredSeq = subtitleAskSeq;
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
        subtitleAnsweredSeq = subtitleAskSeq;
        if (error != null && !error.isEmpty()) {
            // 322.3.108: say what to do next, and long enough to read it.
            showSubtitleFailure(error);
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

    private void requestSave() {
        if (!ownerMode) return;
        listener.saveRequested(offsetForSubHubSave());
        showTransientValue("جارٍ الحفظ…", 900);
    }

    public void showSaveResult(boolean ok, String text) {
        if (closed) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) value = ok ? "تم الحفظ" : "تعذّر الحفظ";
        if (verifyFirst && interactiveSource && !playing) {
            // 322.3.106: saved from the verify page → close it; the card reopens
            // the saved server in the SubHub player at once (subtitles + timing).
            verifySaving = false;
            updateVerifySaveButton();
            if (ok) {
                showStage(value + " — جارٍ تشغيله في مشغّل التطبيق…");
                handler.postDelayed(() -> { if (!closed) close(); }, 900L);
            } else {
                showVerifyStage(value);
            }
            return;
        }
        if (ok) hideDecisionBar();
        showTransientValue(value, ok ? 1600 : 2200);
    }

    public void setSubtitleOffsetMs(long value) {
        if (selectedSubtitle == LOCAL_SUBTITLE) {
            // 322.3.109: SubHub's timing is kept aside while the device file is shown.
            catalogSubtitleOffsetMs = clampSync(value);
            syncBaselineMs = catalogSubtitleOffsetMs;
            return;
        }
        subtitleOffsetMs = clampSync(value);
        syncBaselineMs = subtitleOffsetMs;
    }

    // ---------------------------------------------------------------------
    // 322.3.103: ⏱ sync window. The viewer pauses the film the moment the
    // actor speaks the line shown here and taps 🎯 — the app computes the
    // offset itself, so nobody has to guess whether to add or subtract.
    // Scope: this film (or this episode) on this card only.
    //   owner      → «📌 تثبيت التوقيت» saves it in SubHub for everyone;
    //   any viewer → «💾 حفظ على جهازي» keeps it on this device, or closes
    //                without saving (the change lasts for this viewing only).
    // Positive offset = the subtitle appears later than in the file.
    // ---------------------------------------------------------------------
    private boolean syncPanelOpen = false;
    private String syncScope = "";
    private long syncBaselineMs = 0L;
    private double syncRefStartSec = Double.NaN;
    private TextView syncClockView;
    private TextView syncStateView;
    private TextView syncLineView;
    private TextView syncLineTimeView;
    private TextView syncTapButton;
    private TextView syncPlayButton;
    private TextView syncSaveButton;
    private TextView syncRevertButton;

    static long clampSync(long value) {
        return Math.max(-600000L, Math.min(600000L, value));
    }

    // Called right after setSubtitleOffsetMs: a timing this viewer saved on
    // this device for this film/episode on this card wins over SubHub's.
    public void setSyncScope(String scope) {
        syncScope = scope == null ? "" : scope.trim();
        if (syncScope.length() > 140) syncScope = syncScope.substring(0, 140);
        if (hasLocalSync()) subtitleOffsetMs = clampSync(prefs.getLong(syncPrefKey(), 0L));
    }

    private String syncPrefKey() {
        return syncScope.isEmpty() ? "" : "sync_offset_" + syncScope;
    }

    private boolean hasLocalSync() {
        String key = syncPrefKey();
        return !key.isEmpty() && prefs.contains(key);
    }

    static String syncClockText(long ms) {
        long safe = Math.max(0L, ms);
        long s = safe / 1000L;
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        String base = h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
        return base + "." + ((safe % 1000L) / 100L);
    }

    static String syncSecondsText(long ms) {
        return String.format(Locale.US, "%.1f", Math.abs(ms) / 1000.0);
    }

    static String syncDescribe(long offsetMs) {
        if (Math.abs(offsetMs) < 50L) return "✓ بلا تعديل — توقيت ملف الترجمة الأصلي";
        return offsetMs > 0
                ? "الترجمة مؤخَّرة " + syncSecondsText(offsetMs) + " ث عن الملف"
                : "الترجمة مقدَّمة " + syncSecondsText(offsetMs) + " ث عن الملف";
    }

    // Offset that makes a line which starts at cueStartSec in the file
    // appear exactly at the current film position.
    static long syncOffsetFor(long positionMs, double cueStartSec) {
        return clampSync(positionMs - Math.round(cueStartSec * 1000.0));
    }

    private double cueStartAt(int i) {
        JSONObject c = cues.optJSONObject(i);
        if (c == null) return Double.NaN;
        double v = c.optDouble("start", Double.NaN);
        return Double.isInfinite(v) ? Double.NaN : v;
    }

    private int[] syncSortedCues() {
        ArrayList<Integer> idx = new ArrayList<>();
        for (int i = 0; i < cues.length(); i++) if (!Double.isNaN(cueStartAt(i))) idx.add(i);
        java.util.Collections.sort(idx, (a, b) -> Double.compare(cueStartAt(a), cueStartAt(b)));
        int[] out = new int[idx.size()];
        for (int i = 0; i < out.length; i++) out[i] = idx.get(i);
        return out;
    }

    // Position (inside the sorted list) of the line nearest to targetSec.
    private int syncNearest(int[] order, double targetSec) {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < order.length; i++) {
            double gap = Math.abs(cueStartAt(order[i]) - targetSec);
            if (gap < bestGap) { bestGap = gap; best = i; }
        }
        return best;
    }

    private long syncPositionMs() {
        return player == null ? 0L : Math.max(0L, player.getContentPosition());
    }

    private boolean syncHasLines() {
        return captions && (selectedSubtitle >= 0 || selectedSubtitle == LOCAL_SUBTITLE) && cues.length() > 0;
    }

    // The reference line: the one chosen with السابق/التالي, otherwise the
    // line nearest to where the film is now (in file time).
    private JSONObject syncReferenceCue() {
        if (!syncHasLines()) return null;
        int[] order = syncSortedCues();
        if (order.length == 0) return null;
        double target = Double.isNaN(syncRefStartSec)
                ? (syncPositionMs() - subtitleOffsetMs) / 1000.0
                : syncRefStartSec;
        int at = syncNearest(order, target);
        return at < 0 ? null : cues.optJSONObject(order[at]);
    }

    private void syncStep(int direction) {
        if (!syncHasLines()) return;
        int[] order = syncSortedCues();
        if (order.length == 0) return;
        double target = Double.isNaN(syncRefStartSec)
                ? (syncPositionMs() - subtitleOffsetMs) / 1000.0
                : syncRefStartSec;
        int at = syncNearest(order, target);
        int next = Math.max(0, Math.min(order.length - 1, at + direction));
        syncRefStartSec = cueStartAt(order[next]);
        updateSyncPanelTexts();
    }

    private void syncTapNow() {
        if (player == null) {
            showTransientValue("انتظر حتى يبدأ الفيديو", 1300);
            return;
        }
        JSONObject cue = syncReferenceCue();
        if (cue == null) {
            showTransientValue("اختر ترجمة من CC أولاً", 1500);
            return;
        }
        setSyncOffset(syncOffsetFor(syncPositionMs(), cue.optDouble("start", 0d)));
        syncRefStartSec = cue.optDouble("start", Double.NaN);
        showTransientValue("تم ✓ " + syncDescribe(subtitleOffsetMs), 1600);
    }

    private void setSyncOffset(long value) {
        subtitleOffsetMs = clampSync(value);
        updateSyncPanelTexts();
    }

    private void syncTogglePlay() {
        if (player == null) return;
        if (player.isPlaying()) player.pause(); else player.play();
        updateSyncPanelTexts();
    }

    private void syncSaveLocal() {
        if (localSubtitleActive()) {
            // 322.3.109: the device file keeps its own timing, on this device.
            localSubtitleOffsetMs = subtitleOffsetMs;
            try { listener.localSubtitleOffsetSaved(localSubtitleOffsetMs); } catch (Exception ignored) {}
            showTransientValue("💾 حُفظ توقيت ملفك على جهازك", 1500);
            hidePanel();
            return;
        }
        String key = syncPrefKey();
        if (key.isEmpty()) {
            showTransientValue("تعذّر الحفظ لهذا الفيلم", 1500);
            return;
        }
        prefs.edit().putLong(key, subtitleOffsetMs).apply();
        showTransientValue("💾 حُفظ على جهازك لهذا الفيلم", 1500);
        hidePanel();
    }

    private void syncRevert() {
        if (localSubtitleActive()) {
            syncRefStartSec = Double.NaN;
            setSyncOffset(0L);
            localSubtitleOffsetMs = 0L;
            try { listener.localSubtitleOffsetSaved(0L); } catch (Exception ignored) {}
            showTransientValue("رجعنا لتوقيت ملفك الأصلي", 1300);
            return;
        }
        String key = syncPrefKey();
        if (!ownerMode && !key.isEmpty()) prefs.edit().remove(key).apply();
        syncRefStartSec = Double.NaN;
        setSyncOffset(syncBaselineMs);
        showTransientValue(ownerMode ? "رجعنا للتوقيت المثبّت" : "رجعنا لتوقيت SubHub", 1300);
    }

    private void syncPinForEveryone() {
        if (localSubtitleActive()) { syncSaveLocal(); return; }
        if (!ownerMode) return;
        listener.syncPinRequested(subtitleOffsetMs);
        showTransientValue("جارٍ تثبيت التوقيت…", 1000);
    }

    // Result of «📌 تثبيت التوقيت» from SubHub (does not touch the save bar).
    public void showSyncPinResult(boolean ok, String text, long pinnedMs) {
        if (closed) return;
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) value = ok ? "تم تثبيت التوقيت ✓" : "تعذّر تثبيت التوقيت";
        if (ok) {
            syncBaselineMs = clampSync(pinnedMs);
            updateSyncPanelTexts();
        }
        showTransientValue(value, ok ? 1700 : 2200);
    }

    private TextView syncText(float sizeSp, int color) {
        TextView v = new TextView(activity);
        v.setTextColor(color);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    private LinearLayout syncRow() {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return row;
    }

    private TextView addSyncRowChip(LinearLayout row, String text, Runnable action) {
        TextView v = chip(text, 13, action);
        v.setPadding(dp(3), 0, dp(3), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(v, lp);
        return v;
    }

    private void addSyncBlock(LinearLayout list, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(topDp), 0, 0);
        list.addView(v, lp);
    }

    private void openSyncPanel() {
        hideColorStrip();
        syncRefStartSec = Double.NaN;
        panelTitle.setText("⏱ توقيت الترجمة");
        panelBody.removeAllViews();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(2), dp(6), dp(14));

        syncClockView = syncText(15, Color.WHITE);
        addSyncBlock(list, syncClockView, 0);

        syncStateView = syncText(14, GOLD);
        addSyncBlock(list, syncStateView, 4);

        TextView hint = syncText(12, 0xffaecbe0);
        hint.setText("أوقف الفيديو لحظة ينطق الممثل السطر الظاهر هنا، ثم اضغط 🎯");
        addSyncBlock(list, hint, 8);

        LinearLayout lineBox = new LinearLayout(activity);
        lineBox.setOrientation(LinearLayout.VERTICAL);
        lineBox.setPadding(dp(10), dp(8), dp(10), dp(8));
        lineBox.setBackground(round(PANEL_2, BORDER, 1, 12));
        syncLineView = syncText(16, Color.WHITE);
        syncLineView.setMaxLines(4);
        lineBox.addView(syncLineView, new LinearLayout.LayoutParams(-1, -2));
        syncLineTimeView = syncText(12, 0xff9fb6c8);
        lineBox.addView(syncLineTimeView, new LinearLayout.LayoutParams(-1, -2));
        addSyncBlock(list, lineBox, 6);

        LinearLayout nav = syncRow();
        addSyncRowChip(nav, "→ السطر السابق", () -> syncStep(-1));
        addSyncRowChip(nav, "السطر التالي ←", () -> syncStep(1));
        addSyncBlock(list, nav, 6);

        // Play/pause lives inside the window: in portrait it sits over the
        // video's own controls, and the pause must land on the spoken word.
        LinearLayout playRow = syncRow();
        addSyncRowChip(playRow, "⏪ رجوع ٣ ث", () -> {
            if (player != null) player.seekTo(Math.max(0L, player.getCurrentPosition() - 3000L));
        });
        syncPlayButton = addSyncRowChip(playRow, "⏸ إيقاف", this::syncTogglePlay);
        addSyncBlock(list, playRow, 8);

        syncTapButton = listRow(chip("🎯  الممثل نطق هذا السطر الآن", 15, this::syncTapNow));
        syncTapButton.setBackground(round(0xff3a2f12, GOLD, 2, 14));
        addSyncBlock(list, syncTapButton, 8);

        TextView fineLabel = syncText(12, 0xffaecbe0);
        fineLabel.setText("ضبط يدوي — أخّر: تظهر الترجمة بعد · قدّم: تظهر قبل");
        addSyncBlock(list, fineLabel, 12);

        LinearLayout fine = syncRow();
        addSyncRowChip(fine, "أخّر ١ ث", () -> setSyncOffset(subtitleOffsetMs + 1000L));
        addSyncRowChip(fine, "أخّر ٠٫١", () -> setSyncOffset(subtitleOffsetMs + 100L));
        addSyncRowChip(fine, "قدّم ٠٫١", () -> setSyncOffset(subtitleOffsetMs - 100L));
        addSyncRowChip(fine, "قدّم ١ ث", () -> setSyncOffset(subtitleOffsetMs - 1000L));
        addSyncBlock(list, fine, 4);

        if (ownerMode && !localSubtitleActive()) {
            syncSaveButton = listRow(chip("📌  تثبيت هذا التوقيت لهذا الفيلم — للجميع", 14,
                    this::syncPinForEveryone));
            syncSaveButton.setBackground(round(0xff3a2f12, GOLD, 2, 14));
            addSyncBlock(list, syncSaveButton, 14);
        } else {
            syncSaveButton = listRow(chip("💾  حفظ على جهازي لهذا الفيلم", 14, this::syncSaveLocal));
            syncSaveButton.setBackground(round(0xff1d4d33, 0xff4ade80, 2, 14));
            addSyncBlock(list, syncSaveButton, 14);

            TextView noSave = listRow(chip("إغلاق بدون حفظ", 14, this::hidePanel));
            addSyncBlock(list, noSave, 6);

            TextView noSaveHint = syncText(11, 0xff8aa0b2);
            noSaveHint.setText("بدون حفظ: يبقى التعديل لهذه المشاهدة فقط");
            addSyncBlock(list, noSaveHint, 2);
        }

        syncRevertButton = listRow(chip(localSubtitleActive() ? "↺  رجوع لتوقيت ملفك الأصلي"
                : ownerMode ? "↺  رجوع للتوقيت المثبّت" : "↺  رجوع لتوقيت SubHub",
                13, this::syncRevert));
        addSyncBlock(list, syncRevertButton, 8);

        scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
        panelBody.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        showPanel(true);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        boolean landscape = root.getWidth() > root.getHeight();
        // Portrait: stay in the black band above the video so the picture and
        // the subtitle under it remain visible while timing.
        lp.height = landscape
                ? Math.max(dp(220), root.getHeight() - dp(84))
                : Math.min(dp(470), Math.max(dp(250), (int) (root.getHeight() * 0.40f) - dp(72)));
        panel.setLayoutParams(lp);

        syncPanelOpen = true;
        updateSyncPanelTexts();
        handler.removeCallbacks(syncRefresh);
        handler.postDelayed(syncRefresh, 250);
    }

    private final Runnable syncRefresh = new Runnable() {
        @Override public void run() {
            if (closed || !syncPanelOpen) return;
            updateSyncPanelTexts();
            handler.postDelayed(this, 250);
        }
    };

    private void updateSyncPanelTexts() {
        if (!syncPanelOpen || syncClockView == null) return;
        syncClockView.setText("⏱ وقت الفيديو الآن: " + syncClockText(syncPositionMs()));
        syncPlayButton.setText(player != null && player.isPlaying() ? "⏸ إيقاف" : "▶ تشغيل");
        syncStateView.setText(syncDescribe(subtitleOffsetMs));
        syncStateView.setTextColor(Math.abs(subtitleOffsetMs) < 50L ? 0xff4ade80 : GOLD);

        JSONObject cue = syncReferenceCue();
        if (cue == null) {
            syncLineView.setText(player == null ? "انتظر حتى يبدأ الفيديو"
                    : "اختر ترجمة من زر CC أولاً");
            syncLineTimeView.setText("");
            syncTapButton.setAlpha(0.45f);
        } else {
            String t = cue.optString("text", "").trim();
            syncLineView.setText(t.isEmpty() ? "…" : t);
            double start = cue.optDouble("start", 0d);
            long shown = Math.round(start * 1000.0) + subtitleOffsetMs;
            syncLineTimeView.setText("وقته في الملف " + syncClockText(Math.round(start * 1000.0))
                    + "  ·  يظهر الآن عند " + syncClockText(shown));
            syncTapButton.setAlpha(1f);
        }

        boolean changed = Math.abs(subtitleOffsetMs - syncBaselineMs) >= 50L;
        if (localSubtitleActive()) {
            boolean savedHere = subtitleOffsetMs == localSubtitleOffsetMs;
            syncSaveButton.setText(savedHere
                    ? "✓  محفوظ على جهازك لهذا الملف"
                    : "💾  حفظ توقيت ملفك على جهازي");
            syncRevertButton.setVisibility(Math.abs(subtitleOffsetMs) >= 50L ? View.VISIBLE : View.GONE);
        } else if (ownerMode) {
            syncSaveButton.setText(changed
                    ? "📌  تثبيت هذا التوقيت لهذا الفيلم — للجميع"
                    : "✓  هذا هو التوقيت المثبّت للجميع");
            syncRevertButton.setVisibility(changed ? View.VISIBLE : View.GONE);
        } else {
            boolean savedHere = hasLocalSync()
                    && prefs.getLong(syncPrefKey(), Long.MIN_VALUE) == subtitleOffsetMs;
            syncSaveButton.setText(savedHere
                    ? "✓  محفوظ على جهازك لهذا الفيلم"
                    : "💾  حفظ على جهازي لهذا الفيلم");
            syncRevertButton.setVisibility(changed || hasLocalSync() ? View.VISIBLE : View.GONE);
        }
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
            checkCredits();
            persistResume(false);
            calmCheck();   // 322.3.127
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
        if (linkCheckExec != null) {   // 322.3.126
            linkCheckExec.shutdownNow();
            linkCheckExec = null;
        }

        persistResume(true);
        saveBandwidth();   // 322.3.127
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
