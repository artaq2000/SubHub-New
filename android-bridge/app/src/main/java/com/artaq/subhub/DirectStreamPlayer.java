package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
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
import java.util.Map;

/** A session owns every resource; never shares a player or clock with R2/VidSrc. */
@androidx.media3.common.util.UnstableApi
public final class DirectStreamPlayer {
    public interface Listener { void closed(); void subtitleRequested(int index); }
    private final Activity activity;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FrameLayout root;
    private final TextView status, subtitle;
    private final LinearLayout toolbar;
    private final JSONArray catalog;
    private final String source;
    private WebView probe;
    private ExoPlayer player;
    private PlayerView playerView;
    private boolean closed, playing, captions = true;
    private int selectedSubtitle = -1;
    private JSONArray cues = new JSONArray();
    private String candidate;
    private Map<String,String> candidateHeaders;
    private long subtitleOffsetMs;
    private int subtitlePosition = 12;
    private AlertDialog dialog;

    public DirectStreamPlayer(Activity activity, FrameLayout parent, String source,
                              JSONArray catalog, Listener listener) {
        this.activity=activity; this.listener=listener; this.source=source; this.catalog=catalog;
        root=new FrameLayout(activity); root.setBackgroundColor(Color.BLACK);
        parent.addView(root,new FrameLayout.LayoutParams(-1,-1));
        root.setClickable(true); root.setFocusableInTouchMode(true); root.requestFocus();
        subtitle=new TextView(activity); subtitle.setTextColor(Color.WHITE); subtitle.setTextSize(22);
        subtitle.setGravity(Gravity.CENTER); subtitle.setShadowLayer(3,1,1,Color.BLACK);
        subtitle.setBackgroundColor(0x88000000); subtitle.setVisibility(View.GONE);
        root.addView(subtitle,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        status=new TextView(activity); status.setTextColor(Color.WHITE); status.setTextSize(16);
        status.setGravity(Gravity.CENTER); status.setBackgroundColor(0xcc101b2a);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(64),Gravity.TOP);sp.topMargin=dp(52);
        root.addView(status,sp);
        toolbar=new LinearLayout(activity); toolbar.setBackgroundColor(0xdd101b2a);
        button("إغلاق",this::close); button("⋮",this::settings);
        button("الجودة",this::quality); button("CC",this::chooseSubtitle);
        root.addView(toolbar,new FrameLayout.LayoutParams(-1,dp(52),Gravity.TOP));
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->positionSubtitle());
        beginCapture();
        handler.post(tick);
    }
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void button(String text,Runnable action){Button b=new Button(activity);b.setText(text);b.setTextSize(13);b.setOnClickListener(v->action.run());toolbar.addView(b,new LinearLayout.LayoutParams(0,-1,1));}
    private void message(String text){status.setText(text);status.setVisibility(View.VISIBLE);}
    private void beginCapture(){
        message("جارٍ التقاط البث… اضغط تشغيل المصدر إذا احتاج ذلك.");
        probe=new WebView(activity);
        WebSettings s=probe.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(true);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(probe,true);
        probe.setWebChromeClient(new WebChromeClient()); // New windows have no destination.
        probe.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                // Keep user navigation inside the requested provider. Nested resource loads still work.
                Uri u=r.getUrl();String host=u.getHost();
                return !"https".equals(u.getScheme()) || (r.isForMainFrame() &&
                    !("vidsrc.to".equals(host) || (host!=null && host.endsWith(".vidsrc.to"))));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String path=r.getUrl().getPath();
                if("https".equals(r.getUrl().getScheme()) && path!=null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".m3u8")){
                    String url=r.getUrl().toString();Map<String,String> headers=new HashMap<>(r.getRequestHeaders());
                    handler.post(()->capture(url,headers));
                }
                return null;
            }
        });
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,-1);p.topMargin=dp(116);
        root.addView(probe,0,p);probe.loadUrl(source);
        handler.postDelayed(()->{if(!closed&&!playing)message("لم يُلتقط بث بعد. اضغط تشغيل المصدر، أو أغلق وأعد المحاولة.");},45000);
    }
    private void capture(String url,Map<String,String> headers){
        if(closed||playing)return;
        boolean first=candidate==null;
        if(first || Uri.parse(url).getLastPathSegment().toLowerCase(java.util.Locale.ROOT).contains("master")){
            candidate=url;candidateHeaders=headers;
        }
        if(first)handler.postDelayed(()->{if(!closed&&!playing)startStream();},1400);
    }
    private void destroyProbe(){if(probe!=null){probe.stopLoading();probe.loadUrl("about:blank");root.removeView(probe);probe.destroy();probe=null;}}
    private String header(String name,String fallback){for(Map.Entry<String,String> e:candidateHeaders.entrySet())if(name.equalsIgnoreCase(e.getKey()))return e.getValue();return fallback;}
    private void startStream(){
        if(candidate==null||closed)return;playing=true;
        String userAgent=header("User-Agent",WebSettings.getDefaultUserAgent(activity));
        String referer=header("Referer",source),origin=header("Origin","");
        destroyProbe(); // Stop provider audio and scripts before starting our player.
        Map<String,String> headers=new HashMap<>();headers.put("Referer",referer);if(!origin.isEmpty())headers.put("Origin",origin);
        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(userAgent).setDefaultRequestProperties(headers);
        ResolvingDataSource.Factory data=new ResolvingDataSource.Factory(http,spec->{
            // Cookies are resolved for each media/key URL, never copied to unrelated hosts.
            Map<String,String> scoped=new HashMap<>(spec.httpRequestHeaders);
            String cookies=CookieManager.getInstance().getCookie(spec.uri.toString());
            if(cookies!=null&&!cookies.isEmpty())scoped.put("Cookie",cookies);
            return spec.withRequestHeaders(scoped);
        });
        player=new ExoPlayer.Builder(activity).build();
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build());
        player.setAudioAttributes(new androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true);
        playerView=new PlayerView(activity);playerView.setPlayer(player);playerView.setKeepScreenOn(true);
        FrameLayout.LayoutParams pp=new FrameLayout.LayoutParams(-1,-1);pp.topMargin=dp(52);root.addView(playerView,0,pp);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY)status.setVisibility(View.GONE);else if(state==Player.STATE_BUFFERING)message("جارٍ تحميل البث…");}
            @Override public void onPlayerError(PlaybackException error){message("تعذّر تشغيل البث مباشرة. قد يتطلب جلسة المصدر؛ أغلق وأعد المحاولة.");}
        });
        MediaItem item=new MediaItem.Builder().setUri(candidate).setMimeType(MimeTypes.APPLICATION_M3U8).build();
        player.setMediaSource(new HlsMediaSource.Factory(data).createMediaSource(item));player.prepare();player.play();
        message("جارٍ تشغيل البث المباشر…");
    }
    private void showDialog(AlertDialog.Builder builder){if(dialog!=null)dialog.dismiss();dialog=builder.create();dialog.show();}
    private void quality(){
        if(player==null){message("الجودات تظهر بعد التقاط البث.");return;}
        ArrayList<String> labels=new ArrayList<>();ArrayList<TrackSelectionOverride> choices=new ArrayList<>();
        labels.add("تلقائي");choices.add(null);
        for(Tracks.Group g:player.getCurrentTracks().getGroups())if(g.getType()==C.TRACK_TYPE_VIDEO)
            for(int i=0;i<g.length;i++)if(g.isTrackSupported(i)){
                androidx.media3.common.Format f=g.getTrackFormat(i);
                labels.add(f.height>0?String.format(new java.util.Locale("ar"),"%d بكسل",f.height):"جودة المصدر");
                choices.add(new TrackSelectionOverride(g.getMediaTrackGroup(),i));
            }
        showDialog(new AlertDialog.Builder(activity).setTitle("الجودة المتاحة في البث").setItems(labels.toArray(new String[0]),(d,which)->{
            if(player==null)return;
            androidx.media3.common.TrackSelectionParameters.Builder b=player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            if(choices.get(which)!=null)b.setOverrideForType(choices.get(which));player.setTrackSelectionParameters(b.build());
        }));
    }
    private void chooseSubtitle(){
        ArrayList<String> names=new ArrayList<>();names.add("بدون ترجمة");
        for(int i=0;i<catalog.length();i++)names.add(catalog.optJSONObject(i).optString("name","ترجمة SubHub"));
        showDialog(new AlertDialog.Builder(activity).setTitle("ترجمة SubHub").setItems(names.toArray(new String[0]),(d,index)->{
            selectedSubtitle=index-1;cues=new JSONArray();subtitle.setText("");
            captions=index>0;if(captions)listener.subtitleRequested(index-1);
        }));
    }
    public void selectDefault(int index){if(index>=0&&index<catalog.length()){selectedSubtitle=index;listener.subtitleRequested(index);}}
    public void setCues(int index,JSONArray value,String error){
        if(closed||selectedSubtitle!=index)return;
        if(error!=null&&!error.isEmpty()){message(error);return;}cues=value;
    }
    private void positionSubtitle(){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)subtitle.getLayoutParams();p.bottomMargin=Math.max(dp(65),root.getHeight()*subtitlePosition/100);subtitle.setLayoutParams(p);}
    private void settings(){
        String[] names={"إظهار / إخفاء الترجمة","حجم الترجمة","موضع الترجمة","تأخير الترجمة نصف ثانية","تقديم الترجمة نصف ثانية","تصفير مزامنة الترجمة","ملاءمة / تمديد / قص"};
        showDialog(new AlertDialog.Builder(activity).setTitle("أدوات المشغّل المباشر").setItems(names,(d,i)->{
            if(i==0)captions=!captions;
            if(i==1)slider("حجم الترجمة",48,22,v->subtitle.setTextSize(12+v));
            if(i==2)slider("موضع الترجمة",75,subtitlePosition,v->{subtitlePosition=v;positionSubtitle();});
            if(i==3)subtitleOffsetMs+=500;if(i==4)subtitleOffsetMs-=500;if(i==5)subtitleOffsetMs=0;
            if(i==6&&playerView!=null)playerView.setResizeMode((playerView.getResizeMode()==0)?3:(playerView.getResizeMode()==3?4:0));
        }));
    }
    private interface Value {void set(int value);}
    private void slider(String title,int max,int initial,Value value){SeekBar s=new SeekBar(activity);s.setMax(max);s.setProgress(initial);s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar v){}public void onStopTrackingTouch(SeekBar v){}public void onProgressChanged(SeekBar v,int p,boolean user){if(user)value.set(p);}});showDialog(new AlertDialog.Builder(activity).setTitle(title).setView(s).setPositiveButton("تم",null));}
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;String text="";
        if(player!=null&&captions){double time=(player.getCurrentPosition()-subtitleOffsetMs)/1000.0;
            for(int i=0;i<cues.length();i++){JSONObject c=cues.optJSONObject(i);if(c!=null&&c.optDouble("start")<=time&&time<c.optDouble("end")){if(!text.isEmpty())text+="\n";text+=c.optString("text");}}
        }
        subtitle.setText(text);subtitle.setVisibility(text.isEmpty()?View.GONE:View.VISIBLE);handler.postDelayed(this,100);
    }};
    public void pause(){if(player!=null)player.pause();}
    public void close(){if(closed)return;closed=true;handler.removeCallbacksAndMessages(null);if(dialog!=null)dialog.dismiss();destroyProbe();if(playerView!=null)playerView.setPlayer(null);if(player!=null){player.release();player=null;}((ViewGroup)root.getParent()).removeView(root);listener.closed();}
}
