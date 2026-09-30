package com.artaq.subhub;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;
import androidx.webkit.JavaScriptReplyProxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PlaybackLifecycleTest {
    private MainActivity activity;
    private WebView web;
    private Object field(String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity); }
    private String asset(String name) throws Exception {
        try(java.io.InputStream in=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name)) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toString("UTF-8");
        }
    }
    private void main(Runnable r) { InstrumentationRegistry.getInstrumentation().runOnMainSync(r); }
    private String js(String script) throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        main(()->web.evaluateJavascript(script,v->{value.set(v);done.countDown();}));
        assertTrue("JS timeout",done.await(10,TimeUnit.SECONDS));return value.get();
    }
    private JSONObject state() throws Exception {String s=js("window.testState?JSON.stringify(testState()):'{}'");return new JSONObject(new org.json.JSONTokener(s).nextValue().toString());}
    private JSONObject await(boolean paused,int timeout) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;JSONObject p=new JSONObject();
        while(SystemClock.elapsedRealtime()<end){p=state();if(p.optInt("ready")>=1&&!p.optBoolean("pending",true)&&p.optBoolean("paused") == paused)return p;SystemClock.sleep(100);}
        fail("Playback did not reach paused="+paused+": "+p);return p;
    }
    @Test public void nativePushSurvivesReturnStoppedTimersAndLostSession() throws Exception {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            final String home=asset("home54.html"),redirect=asset("redirect54.html"),provider=asset("provider54.html"),top=asset("top54.js");
            scenario.onActivity(a->{
                activity=a;
                try {
                    web=(WebView)field("webView");web.stopLoading();
                    Field scripts=MainActivity.class.getDeclaredField("siteBridgeScript");scripts.setAccessible(true);scripts.set(activity,top);
                    WebViewClient original=web.getWebViewClient();
                    web.setWebViewClient(new WebViewClient(){
                        @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                            String host=r.getUrl().getHost();String body="";
                            if("subhub-at7.pages.dev".equals(host))body=home;
                            if("vidsrc.to".equals(host))body=redirect;
                            if("provider.test".equals(host))body=provider;
                            return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
                        }
                        @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap f){original.onPageStarted(v,u,f);}
                        @Override public void onPageFinished(WebView v,String u){original.onPageFinished(v,u);}
                        @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return original.shouldOverrideUrlLoading(v,r);}
                    });
                    web.loadUrl("https://subhub-at7.pages.dev/native-test54");
                }catch(Exception e){throw new RuntimeException(e);}
            });
            JSONObject first=await(false,25000);
            assertTrue("Redirected shadow video has an active native source",first.getString("source").startsWith("provider.test"));
            SystemClock.sleep(3300);
            assertEquals("Pause icon auto-hides", "hidden",state().getString("center"));
            js("testToggle()");JSONObject paused=await(true,6000);double checkpoint=paused.getDouble("time");assertTrue(checkpoint>2);
            // Stop all page timers. Native push must still reach the real paused media.
            main(()->{try{for(Object value:((Map<?,?>)field("playbackReplies")).values())((JavaScriptReplyProxy)value).postMessage("{\"command\":\"test-stop-timers\"}");}catch(Exception e){throw new RuntimeException(e);}});
            scenario.moveToState(Lifecycle.State.STARTED);scenario.moveToState(Lifecycle.State.RESUMED);
            js("testToggle()");await(false,6000);js("testToggle()");paused=await(true,6000);checkpoint=paused.getDouble("time");
            // Reproduce the user's frozen session: no timers and no live native handle.
            main(()->{try{((Map<?,?>)field("playbackReplies")).clear();}catch(Exception e){throw new RuntimeException(e);}});
            js("testToggle()");JSONObject restored=await(false,25000);
            assertTrue("Reload must preserve the actual stopped time",restored.getDouble("time")>=checkpoint-0.5);
            assertEquals("",restored.getString("status"));
            js("testToggle()");await(true,6000);js("testToggle()");await(false,6000);
            System.out.println("REAL ANDROID WebView: redirected shadow media, native push with timers stopped, background/return, recovery checkpoint, repeat resume and hidden pause icon: PASS");
        }
    }
}
