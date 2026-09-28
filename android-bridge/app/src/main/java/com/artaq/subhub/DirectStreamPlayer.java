package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
 * Independent direct-stream player.
 *
 * Important: this owns its own subtitle state, panel and preferences. It does
 * not read or mutate R2's subPanel/_subSettings. We copy the UX, not the state.
 */
@androidx.media3.common.util.UnstableApi
public final class DirectStreamPlayer {
    public interface Listener { void closed(); void subtitleRequested(int index); }

    private static final String PREFS = "subhub_direct_stream_settings_v1";
    private static final int GOLD = Color.rgb(244,184,63);
    private static final int PANEL = Color.rgb(8,27,43);
    private static final int PANEL_2 = Color.rgb(12,39,59);
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = Color.rgb(173,190,207);

    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final StyledSubtitleView subtitle;
    private final TextView status;
    private final LinearLayout toolbar;
    private final JSONArray catalog;
    private final String source;
    private final SharedPreferences prefs;

    private WebView probe;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean closed, playing, captions = true;
    private int selectedSubtitle = -1;
    private JSONArray cues = new JSONArray();
    private String candidate;
    private Map<String,String> candidateHeaders;
    private AlertDialog qualityDialog;

    // Direct-stream-only settings. Never shared with R2.
    private long subtitleOffsetMs;
    private int subtitlePosition;
    private int subtitleSizeSp;
    private int subtitleStrokePx;
    private boolean subtitleBackground;
    private int subtitleColor;
    private int resizeModeIndex;

    // R2-style independent panel.
    private FrameLayout settingsSheet;
    private LinearLayout settingsContent;
    private final ArrayList<Button> tabButtons = new ArrayList<>();
    private boolean panelOpen;
    private int activeTab;

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, Listener listener) {
        this.activity=activity;
        this.listener=listener;
        this.source=source;
        this.catalog=catalog;
        this.prefs=activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);

        subtitlePosition=prefs.getInt("subtitle_position",12);
        subtitleSizeSp=prefs.getInt("subtitle_size_sp",28);
        subtitleStrokePx=prefs.getInt("subtitle_stroke_px",2);
        subtitleBackground=prefs.getBoolean("subtitle_background",true);
        subtitleColor=prefs.getInt("subtitle_color",Color.WHITE);
        subtitleOffsetMs=prefs.getLong("subtitle_offset_ms",0L);
        resizeModeIndex=prefs.getInt("resize_mode",0);

        root=new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        parent.addView(root,new FrameLayout.LayoutParams(-1,-1));
        root.setClickable(true);
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        subtitle=new StyledSubtitleView(activity);
        subtitle.setTextSize(subtitleSizeSp);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setFillColor(subtitleColor);
        subtitle.setStrokeWidth(subtitleStrokePx);
        subtitle.setPadding(dp(12),dp(5),dp(12),dp(5));
        subtitle.setVisibility(View.GONE);
        applySubtitleBackground();
        root.addView(subtitle,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        installSubtitleDrag();

        status=new TextView(activity);
        status.setTextColor(TEXT);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundColor(0xcc101b2a);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(64),Gravity.TOP);
        sp.topMargin=dp(52);
        root.addView(status,sp);

        toolbar=new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(dp(6),dp(4),dp(6),dp(4));
        toolbar.setBackgroundColor(0xe0101b2a);
        topButton("إغلاق",this::close);
        topButton("⋮",()->openSettings(0));
        topButton("الجودة",this::quality);
        topButton("CC",()->openSettings(0));
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,dp(52),Gravity.TOP));

        buildSettingsSheet();
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            positionSubtitle();
            layoutSettingsSheet();
        });

        beginCapture();
        handler.post(tick);
    }

    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}

    private GradientDrawable rounded(int color,int strokeColor,int strokeDp,int radiusDp){
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if(strokeDp>0)d.setStroke(dp(strokeDp),strokeColor);
        return d;
    }

    private void topButton(String text,Runnable action){
        Button b=new Button(activity);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackground(rounded(Color.rgb(72,72,72),Color.TRANSPARENT,0,8));
        b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1f);
        lp.setMargins(dp(4),0,dp(4),0);
        toolbar.addView(b,lp);
    }

    private TextView title(String value,float size){
        TextView t=new TextView(activity);
        t.setText(value);
        t.setTextColor(TEXT);
        t.setTextSize(size);
        t.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);
        t.setPadding(dp(12),dp(8),dp(12),dp(8));
        return t;
    }

    private Button panelButton(String text,Runnable action){
        Button b=new Button(activity);
        b.setText(text);
        b.setTextColor(TEXT);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(rounded(PANEL_2,Color.rgb(35,79,112),1,14));
        b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));
        lp.setMargins(0,dp(5),0,dp(5));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildSettingsSheet(){
        settingsSheet=new FrameLayout(activity);
        settingsSheet.setVisibility(View.GONE);
        settingsSheet.setBackground(rounded(PANEL,Color.rgb(31,78,111),1,24));
        settingsSheet.setClickable(true);
        settingsSheet.setFocusable(true);
        settingsSheet.setElevation(dp(18));

        LinearLayout outer=new LinearLayout(activity);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(12),dp(10),dp(12),dp(10));
        settingsSheet.addView(outer,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout header=new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button close=panelButton("✕",this::closeSettings);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(48),dp(44));
        close.setLayoutParams(cp);
        header.addView(close);
        TextView h=title("✨ إعدادات الترجمة",20);
        header.addView(h,new LinearLayout.LayoutParams(0,dp(48),1f));
        outer.addView(header,new LinearLayout.LayoutParams(-1,dp(50)));

        LinearLayout tabs=new LinearLayout(activity);
        String[] labels={"الترجمة 💬","المظهر 🎨","الخط ✍️","المزامنة ⏱️"};
        for(int i=0;i<labels.length;i++){
            final int tab=i;
            Button b=new Button(activity);
            b.setText(labels[i]);
            b.setTextColor(TEXT);
            b.setTextSize(12);
            b.setAllCaps(false);
            b.setOnClickListener(v->showTab(tab));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1f);
            lp.setMargins(dp(3),0,dp(3),0);
            tabs.addView(b,lp);
            tabButtons.add(b);
        }
        outer.addView(tabs,new LinearLayout.LayoutParams(-1,dp(50)));

        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(true);
        settingsContent=new LinearLayout(activity);
        settingsContent.setOrientation(LinearLayout.VERTICAL);
        settingsContent.setPadding(dp(8),dp(10),dp(8),dp(20));
        scroll.addView(settingsContent,new ScrollView.LayoutParams(-1,-2));
        outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));

        root.addView(settingsSheet,new FrameLayout.LayoutParams(-1,dp(260),Gravity.BOTTOM));
        showTab(0);
    }

    private void layoutSettingsSheet(){
        if(settingsSheet==null)return;
        int h=root.getHeight(),w=root.getWidth();
        if(h<=0||w<=0)return;
        int sheetH=(int)(h*(w>h?0.36f:0.56f));
        FrameLayout.LayoutParams sp=(FrameLayout.LayoutParams)settingsSheet.getLayoutParams();
        sp.height=Math.max(dp(250),sheetH);
        sp.leftMargin=w>h?dp(18):dp(10);
        sp.rightMargin=w>h?dp(18):dp(10);
        sp.bottomMargin=dp(6);
        settingsSheet.setLayoutParams(sp);

        int reserve=panelOpen?sp.height+sp.bottomMargin+dp(4):0;
        if(playerView!=null){
            FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)playerView.getLayoutParams();
            pp.topMargin=dp(52);
            pp.bottomMargin=reserve;
            playerView.setLayoutParams(pp);
        }
        if(probe!=null){
            FrameLayout.LayoutParams pp=(FrameLayout.LayoutParams)probe.getLayoutParams();
            pp.topMargin=dp(116);
            pp.bottomMargin=reserve;
            probe.setLayoutParams(pp);
        }
        positionSubtitle();
    }

    private void openSettings(int tab){
        panelOpen=true;
        settingsSheet.setVisibility(View.VISIBLE);
        showTab(tab);
        layoutSettingsSheet();
    }

    private void closeSettings(){
        panelOpen=false;
        settingsSheet.setVisibility(View.GONE);
        layoutSettingsSheet();
    }

    private void showTab(int tab){
        activeTab=Math.max(0,Math.min(3,tab));
        for(int i=0;i<tabButtons.size();i++){
            Button b=tabButtons.get(i);
            b.setBackground(rounded(i==activeTab?GOLD:PANEL_2,
                    i==activeTab?GOLD:Color.rgb(35,79,112),1,14));
            b.setTextColor(i==activeTab?Color.BLACK:TEXT);
        }
        settingsContent.removeAllViews();
        if(activeTab==0)buildSubtitleTab();
        else if(activeTab==1)buildAppearanceTab();
        else if(activeTab==2)buildFontTab();
        else buildSyncTab();
    }

    private void buildSubtitleTab(){
        TextView t=title("اختر الترجمة",18);
        settingsContent.addView(t,new LinearLayout.LayoutParams(-1,dp(46)));

        settingsContent.addView(panelButton(
                selectedSubtitle<0?"✓ بدون ترجمة":"بدون ترجمة",
                ()->selectSubtitle(-1)));

        for(int i=0;i<catalog.length();i++){
            JSONObject o=catalog.optJSONObject(i);
            String name=o==null?"ترجمة SubHub":o.optString("name","ترجمة SubHub");
            final int index=i;
            settingsContent.addView(panelButton(
                    (selectedSubtitle==i?"★ ":"")+name,
                    ()->selectSubtitle(index)));
        }

        settingsContent.addView(title("موضع الترجمة",15));
        settingsContent.addView(sliderView(2,75,subtitlePosition,v->{
            subtitlePosition=v;
            prefs.edit().putInt("subtitle_position",v).apply();
            positionSubtitle();
        }));
    }

    private void buildAppearanceTab(){
        settingsContent.addView(title("مظهر الترجمة",18));

        settingsContent.addView(panelButton(
                subtitleBackground?"الخلفية: مفعّلة":"الخلفية: بدون",
                ()->{
                    subtitleBackground=!subtitleBackground;
                    prefs.edit().putBoolean("subtitle_background",subtitleBackground).apply();
                    applySubtitleBackground();
                    showTab(1);
                }));

        settingsContent.addView(panelButton(
                "لون أبيض",
                ()->{subtitleColor=Color.WHITE;applySubtitleColor();showTab(1);}));
        settingsContent.addView(panelButton(
                "لون أصفر",
                ()->{subtitleColor=Color.rgb(255,222,89);applySubtitleColor();showTab(1);}));
        settingsContent.addView(panelButton(
                "لون سماوي",
                ()->{subtitleColor=Color.rgb(113,223,255);applySubtitleColor();showTab(1);}));

        settingsContent.addView(panelButton(
                resizeModeIndex==0?"الشاشة: ملاءمة":(resizeModeIndex==1?"الشاشة: تمديد":"الشاشة: قص"),
                this::cycleResizeMode));
    }

    private void buildFontTab(){
        settingsContent.addView(title("حجم الخط",18));
        settingsContent.addView(sliderView(14,62,subtitleSizeSp,v->{
            subtitleSizeSp=v;
            subtitle.setTextSize(v);
            prefs.edit().putInt("subtitle_size_sp",v).apply();
        }));

        settingsContent.addView(title("سماكة الحد",18));
        settingsContent.addView(sliderView(0,6,subtitleStrokePx,v->{
            subtitleStrokePx=v;
            subtitle.setStrokeWidth(v);
            prefs.edit().putInt("subtitle_stroke_px",v).apply();
        }));
    }

    private void buildSyncTab(){
        TextView current=title(syncText(),18);
        settingsContent.addView(current,new LinearLayout.LayoutParams(-1,dp(52)));

        settingsContent.addView(panelButton("تأخير الترجمة ٠٫٥ ثانية",()->{
            subtitleOffsetMs+=500;
            persistOffset();
            showTab(3);
        }));
        settingsContent.addView(panelButton("تقديم الترجمة ٠٫٥ ثانية",()->{
            subtitleOffsetMs-=500;
            persistOffset();
            showTab(3);
        }));
        settingsContent.addView(panelButton("تصفير مزامنة الترجمة",()->{
            subtitleOffsetMs=0;
            persistOffset();
            showTab(3);
        }));
    }

    private String syncText(){
        if(subtitleOffsetMs==0)return "المزامنة الحالية: ٠٫٠ ثانية";
        return String.format(Locale.US,"المزامنة الحالية: %+.1f ثانية",subtitleOffsetMs/1000.0);
    }

    private void persistOffset(){prefs.edit().putLong("subtitle_offset_ms",subtitleOffsetMs).apply();}

    private interface Value {void set(int value);}
    private View sliderView(int min,int max,int initial,Value value){
        LinearLayout row=new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar s=new SeekBar(activity);
        s.setMax(max-min);
        s.setProgress(Math.max(0,Math.min(max-min,initial-min)));
        TextView n=title(String.valueOf(initial),14);
        n.setGravity(Gravity.CENTER);
        row.addView(s,new LinearLayout.LayoutParams(0,dp(48),1f));
        row.addView(n,new LinearLayout.LayoutParams(dp(70),dp(48)));
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar v){}
            public void onStopTrackingTouch(SeekBar v){}
            public void onProgressChanged(SeekBar v,int p,boolean user){
                if(!user)return;
                int actual=min+p;
                n.setText(String.valueOf(actual));
                value.set(actual);
            }
        });
        return row;
    }

    private void selectSubtitle(int index){
        selectedSubtitle=index;
        cues=new JSONArray();
        subtitle.setText("");
        captions=index>=0;
        if(index>=0)listener.subtitleRequested(index);
        showTab(0);
    }

    private void applySubtitleBackground(){
        subtitle.setBackgroundColor(subtitleBackground?0x88000000:Color.TRANSPARENT);
    }

    private void applySubtitleColor(){
        subtitle.setFillColor(subtitleColor);
        prefs.edit().putInt("subtitle_color",subtitleColor).apply();
    }

    private void cycleResizeMode(){
        resizeModeIndex=(resizeModeIndex+1)%3;
        prefs.edit().putInt("resize_mode",resizeModeIndex).apply();
        applyResizeMode();
        showTab(1);
    }

    private void applyResizeMode(){
        if(playerView==null)return;
        playerView.setResizeMode(resizeModeIndex==0?0:(resizeModeIndex==1?3:4));
    }

    private void installSubtitleDrag(){
        final float[] startY={0};
        final int[] startPos={subtitlePosition};
        subtitle.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                startY[0]=e.getRawY();
                startPos[0]=subtitlePosition;
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
                float dy=startY[0]-e.getRawY();
                int h=Math.max(dp(180),root.getHeight());
                subtitlePosition=Math.max(2,Math.min(75,startPos[0]+Math.round(dy/h*100f)));
                positionSubtitle();
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                prefs.edit().putInt("subtitle_position",subtitlePosition).apply();
                return true;
            }
            return true;
        });
    }

    private void message(String text){
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void beginCapture(){
        message("جارٍ التقاط البث… اضغط تشغيل المصدر إذا احتاج ذلك.");
        probe=new WebView(activity);
        WebSettings s=probe.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(probe,true);
        probe.setWebChromeClient(new WebChromeClient());
        probe.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();
                String host=u.getHost();
                return !"https".equals(u.getScheme()) || (r.isForMainFrame() &&
                    !("vidsrc.to".equals(host) || (host!=null && host.endsWith(".vidsrc.to"))));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String path=r.getUrl().getPath();
                if("https".equals(r.getUrl().getScheme()) && path!=null &&
                   path.toLowerCase(Locale.ROOT).endsWith(".m3u8")){
                    String url=r.getUrl().toString();
                    Map<String,String> headers=new HashMap<>(r.getRequestHeaders());
                    handler.post(()->capture(url,headers));
                }
                return null;
            }
        });
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-1);
        p.topMargin=dp(116);
        root.addView(probe,0,p);
        probe.loadUrl(source);
        handler.postDelayed(()->{
            if(!closed&&!playing)message("لم يُلتقط بث بعد. اضغط تشغيل المصدر، أو أغلق وأعد المحاولة.");
        },45000);
    }

    private void capture(String url,Map<String,String> headers){
        if(closed||playing)return;
        boolean first=candidate==null;
        if(first || Uri.parse(url).getLastPathSegment().toLowerCase(Locale.ROOT).contains("master")){
            candidate=url;
            candidateHeaders=headers;
        }
        if(first)handler.postDelayed(()->{if(!closed&&!playing)startStream();},1400);
    }

    private void destroyProbe(){
        if(probe!=null){
            probe.stopLoading();
            probe.loadUrl("about:blank");
            root.removeView(probe);
            probe.destroy();
            probe=null;
        }
    }

    private String header(String name,String fallback){
        for(Map.Entry<String,String> e:candidateHeaders.entrySet())
            if(name.equalsIgnoreCase(e.getKey()))return e.getValue();
        return fallback;
    }

    private void startStream(){
        if(candidate==null||closed)return;
        playing=true;
        String userAgent=header("User-Agent",WebSettings.getDefaultUserAgent(activity));
        String referer=header("Referer",source);
        String origin=header("Origin","");
        destroyProbe();

        Map<String,String> headers=new HashMap<>();
        headers.put("Referer",referer);
        if(!origin.isEmpty())headers.put("Origin",origin);

        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers);
        ResolvingDataSource.Factory data=new ResolvingDataSource.Factory(http,spec->{
            Map<String,String> scoped=new HashMap<>(spec.httpRequestHeaders);
            String cookies=CookieManager.getInstance().getCookie(spec.uri.toString());
            if(cookies!=null&&!cookies.isEmpty())scoped.put("Cookie",cookies);
            return spec.withRequestHeaders(scoped);
        });

        player=new ExoPlayer.Builder(activity).build();
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true)
                        .build());
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),true);

        playerView=new PlayerView(activity);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-1);
        pp.topMargin=dp(52);
        root.addView(playerView,0,pp);
        applyResizeMode();
        layoutSettingsSheet();

        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY)status.setVisibility(View.GONE);
                else if(state==Player.STATE_BUFFERING)message("جارٍ تحميل البث…");
            }
            @Override public void onPlayerError(PlaybackException error){
                message("تعذّر تشغيل البث مباشرة. قد يتطلب جلسة المصدر؛ أغلق وأعد المحاولة.");
            }
        });

        MediaItem item=new MediaItem.Builder()
                .setUri(candidate)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(item));
        player.prepare();
        player.play();
        message("جارٍ تشغيل البث المباشر…");
    }

    private void quality(){
        if(player==null){
            message("الجودات تظهر بعد التقاط البث.");
            return;
        }
        ArrayList<String> labels=new ArrayList<>();
        ArrayList<TrackSelectionOverride> choices=new ArrayList<>();
        labels.add("تلقائي");
        choices.add(null);

        for(Tracks.Group g:player.getCurrentTracks().getGroups()){
            if(g.getType()!=C.TRACK_TYPE_VIDEO)continue;
            for(int i=0;i<g.length;i++){
                if(!g.isTrackSupported(i))continue;
                androidx.media3.common.Format f=g.getTrackFormat(i);
                labels.add(f.height>0?
                        String.format(new Locale("ar"),"%d بكسل",f.height):
                        "جودة المصدر");
                choices.add(new TrackSelectionOverride(g.getMediaTrackGroup(),i));
            }
        }

        if(qualityDialog!=null)qualityDialog.dismiss();
        qualityDialog=new AlertDialog.Builder(activity)
                .setTitle("الجودة المتاحة في البث")
                .setItems(labels.toArray(new String[0]),(d,which)->{
                    if(player==null)return;
                    androidx.media3.common.TrackSelectionParameters.Builder b=
                            player.getTrackSelectionParameters().buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO);
                    if(choices.get(which)!=null)b.setOverrideForType(choices.get(which));
                    player.setTrackSelectionParameters(b.build());
                })
                .create();
        qualityDialog.show();
    }

    public void selectDefault(int index){
        if(index>=0&&index<catalog.length()){
            selectedSubtitle=index;
            captions=true;
            listener.subtitleRequested(index);
        }
    }

    public void setCues(int index,JSONArray value,String error){
        if(closed||selectedSubtitle!=index)return;
        if(error!=null&&!error.isEmpty()){
            message(error);
            return;
        }
        cues=value;
    }

    private void positionSubtitle(){
        FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)subtitle.getLayoutParams();
        int base=Math.max(dp(65),root.getHeight()*subtitlePosition/100);
        if(panelOpen && settingsSheet!=null)base=Math.max(base,settingsSheet.getLayoutParams().height+dp(12));
        p.bottomMargin=base;
        p.leftMargin=dp(18);
        p.rightMargin=dp(18);
        subtitle.setLayoutParams(p);
    }

    private final Runnable tick=new Runnable(){
        public void run(){
            if(closed)return;
            String text="";
            if(player!=null&&captions){
                double time=(player.getCurrentPosition()-subtitleOffsetMs)/1000.0;
                for(int i=0;i<cues.length();i++){
                    JSONObject c=cues.optJSONObject(i);
                    if(c!=null&&c.optDouble("start")<=time&&time<c.optDouble("end")){
                        if(!text.isEmpty())text+="\n";
                        text+=c.optString("text");
                    }
                }
            }
            subtitle.setText(text);
            subtitle.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);
            handler.postDelayed(this,100);
        }
    };

    public void pause(){if(player!=null)player.pause();}

    public void close(){
        if(closed)return;
        closed=true;
        handler.removeCallbacksAndMessages(null);
        if(qualityDialog!=null)qualityDialog.dismiss();
        destroyProbe();
        if(playerView!=null)playerView.setPlayer(null);
        if(player!=null){
            player.release();
            player=null;
        }
        ((ViewGroup)root.getParent()).removeView(root);
        listener.closed();
    }

    public static class StyledSubtitleView extends TextView {
        private float strokeWidth = 2f;
        private int fillColor = Color.WHITE;

        public StyledSubtitleView(android.content.Context context){super(context);}

        public void setStrokeWidth(float v){strokeWidth=Math.max(0f,v);invalidate();}
        public void setFillColor(int color){fillColor=color;super.setTextColor(color);invalidate();}

        @Override protected void onDraw(Canvas canvas){
            Paint p=getPaint();
            Paint.Style oldStyle=p.getStyle();
            float oldStroke=p.getStrokeWidth();
            int oldColor=getCurrentTextColor();

            if(strokeWidth>0f){
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(strokeWidth*getResources().getDisplayMetrics().density);
                super.setTextColor(Color.BLACK);
                super.onDraw(canvas);
            }

            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(oldStroke);
            super.setTextColor(fillColor);
            super.onDraw(canvas);

            p.setStyle(oldStyle);
            super.setTextColor(oldColor==Color.BLACK?fillColor:oldColor);
        }
    }
}
