package com.artaq.subhub;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.ui.AspectRatioFrameLayout;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Video-only backend for Direct Stream -> R2.
 * No controls, subtitles, settings or panels live here. Those remain the
 * existing R2 web UI. This view only paints the captured HLS video underneath.
 */
@androidx.media3.common.util.UnstableApi
public final class DirectR2Playback {
    private final Activity activity;
    private final FrameLayout root;
    private final AspectRatioFrameLayout frame;
    private final TextureView texture;
    private final ExoPlayer player;
    private boolean released;

    public DirectR2Playback(Activity activity, FrameLayout root, String url,
                            Map<String,String> capturedHeaders) {
        this.activity = activity;
        this.root = root;

        frame = new AspectRatioFrameLayout(activity);
        frame.setBackgroundColor(Color.BLACK);
        frame.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        frame.setVisibility(android.view.View.VISIBLE);
        frame.setClickable(false);
        frame.setFocusable(false);

        texture = new TextureView(activity);
        texture.setOpaque(true);
        texture.setClickable(false);
        texture.setFocusable(false);
        frame.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // index 0 => behind the WebView. Web UI stays fully interactive.
        root.addView(frame, 0, new FrameLayout.LayoutParams(1,1));

        Map<String,String> base = capturedHeaders == null
                ? new HashMap<>() : new HashMap<>(capturedHeaders);
        String ua = header(base, "User-Agent", android.webkit.WebSettings.getDefaultUserAgent(activity));

        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(cleanHeaders(base));

        ResolvingDataSource.Factory resolving = new ResolvingDataSource.Factory(http, spec -> {
            Map<String,String> scoped = new HashMap<>(spec.httpRequestHeaders);
            String cookies = CookieManager.getInstance().getCookie(spec.uri.toString());
            if (cookies != null && !cookies.isEmpty()) scoped.put("Cookie", cookies);
            return spec.withRequestHeaders(scoped);
        });

        player = new ExoPlayer.Builder(activity).build();
        player.setVideoTextureView(texture);
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(), true);
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters().buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build());

        player.addListener(new Player.Listener() {
            @Override public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 0 && size.height > 0) {
                    float ratio = (size.width * size.pixelWidthHeightRatio) / (float) size.height;
                    frame.setAspectRatio(ratio);
                }
            }
        });

        MediaItem item = new MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build();
        player.setMediaSource(new HlsMediaSource.Factory(resolving).createMediaSource(item));
        player.prepare();
        player.play();
    }

    private static String header(Map<String,String> in, String name, String fallback) {
        for (Map.Entry<String,String> e : in.entrySet()) {
            if (name.equalsIgnoreCase(e.getKey())) return e.getValue();
        }
        return fallback;
    }

    private static Map<String,String> cleanHeaders(Map<String,String> in) {
        Map<String,String> out = new HashMap<>();
        for (Map.Entry<String,String> e : in.entrySet()) {
            String k = e.getKey();
            if (k == null) continue;
            String low = k.toLowerCase(Locale.ROOT);
            if ("host".equals(low) || "connection".equals(low)
                    || "content-length".equals(low) || "accept-encoding".equals(low)
                    || "cookie".equals(low)) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    public void setBounds(int left, int top, int width, int height) {
        if (released) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        lp.leftMargin = Math.max(0,left);
        lp.topMargin = Math.max(0,top);
        lp.width = Math.max(1,width);
        lp.height = Math.max(1,height);
        frame.setLayoutParams(lp);
    }

    public void setResizeMode(String mode) {
        if (released) return;
        int v = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        if ("fill".equals(mode)) v = AspectRatioFrameLayout.RESIZE_MODE_FILL;
        else if ("zoom".equals(mode) || "cover".equals(mode)) v = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        frame.setResizeMode(v);
    }

    public void play() { if (!released) player.play(); }
    public void pause() { if (!released) player.pause(); }
    public void seekTo(long ms) { if (!released) player.seekTo(Math.max(0,ms)); }
    public void setVolume(float value) { if (!released) player.setVolume(Math.max(0f,Math.min(1f,value))); }

    public JSONObject state() {
        JSONObject o = new JSONObject();
        try {
            o.put("position", released ? 0 : player.getCurrentPosition());
            o.put("duration", released ? 0 : Math.max(0,player.getDuration()));
            o.put("buffered", released ? 0 : player.getBufferedPosition());
            o.put("playing", !released && player.isPlaying());
            o.put("state", released ? Player.STATE_IDLE : player.getPlaybackState());
        } catch (Exception ignored) {}
        return o;
    }

    public void release() {
        if (released) return;
        released = true;
        try { player.clearVideoTextureView(texture); } catch (Exception ignored) {}
        try { player.release(); } catch (Exception ignored) {}
        try {
            ViewGroup p = (ViewGroup) frame.getParent();
            if (p != null) p.removeView(frame);
        } catch (Exception ignored) {}
    }
}
