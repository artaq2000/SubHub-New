package com.artaq.subhub;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * JavaScript bridge used only by the live SubHub page.
 *
 * The web page supplies the Firebase ID token and logical destination. Android
 * supplies the selected content:// Uri captured by BackgroundMainActivity.
 * The actual bytes are uploaded by R2UploadService, so WebView may be paused
 * while the user watches YouTube/TikTok or turns the screen off.
 */
public final class R2NativeBridge {
    static final String PREFS = BackgroundMainActivity.R2_PREFS;
    static final String KEY_STATE = "upload_state";
    static final long CHUNK_SIZE = 50L * 1024L * 1024L;

    private final Activity activity;
    private final SharedPreferences prefs;

    R2NativeBridge(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    @JavascriptInterface
    public boolean isAvailable() {
        return true;
    }

    @JavascriptInterface
    public synchronized String startUpload(
            String idToken,
            String folder,
            String filename,
            double sizeDouble,
            String mode,
            String contextJson
    ) {
        try {
            if (idToken == null || idToken.length() < 20 || idToken.length() > 20000) {
                return "ERR:تعذّر الحصول على جلسة المالك";
            }

            String uri = prefs.getString(BackgroundMainActivity.KEY_SELECTED_URI, "");
            String pickedName = prefs.getString(BackgroundMainActivity.KEY_SELECTED_NAME, "");
            long pickedSize = prefs.getLong(BackgroundMainActivity.KEY_SELECTED_SIZE, -1L);
            long selectedAt = prefs.getLong(BackgroundMainActivity.KEY_SELECTED_AT, 0L);

            if (uri == null || uri.isEmpty()) {
                return "ERR:لم يعثر التطبيق على الملف الذي تم اختياره";
            }
            if (selectedAt > 0L && System.currentTimeMillis() - selectedAt > 30L * 60L * 1000L) {
                return "ERR:اختر الملف مرة أخرى ثم ابدأ الرفع";
            }

            long size = Math.max(0L, Math.round(sizeDouble));
            if (size <= 0L && pickedSize > 0L) size = pickedSize;
            if (size <= 0L) return "ERR:تعذّر معرفة حجم الملف";

            if (pickedSize > 0L && Math.abs(pickedSize - size) > 4096L) {
                return "ERR:الملف المختار لا يطابق ملف الرفع";
            }

            String currentRaw = prefs.getString(KEY_STATE, "");
            if (currentRaw != null && !currentRaw.isEmpty()) {
                try {
                    JSONObject current = new JSONObject(currentRaw);
                    String status = current.optString("status", "");
                    boolean active = "queued".equals(status)
                            || "uploading".equals(status)
                            || "waiting".equals(status)
                            || "completing".equals(status)
                            || "auth_required".equals(status);
                    if (active) {
                        if (uri.equals(current.optString("uri"))
                                && size == current.optLong("size", -1L)) {
                            String jobId = current.optString("jobId", "");
                            refreshAuthInternal(current, idToken);
                            startService(R2UploadService.ACTION_RESUME, jobId);
                            return jobId;
                        }
                        return "ERR:يوجد رفع آخر جارٍ حالياً";
                    }
                } catch (Exception ignored) {}
            }

            String jobId = UUID.randomUUID().toString();
            JSONObject state = new JSONObject();
            state.put("jobId", jobId);
            state.put("status", "queued");
            state.put("message", "جاري تجهيز الرفع في الخلفية…");
            state.put("uri", uri);
            state.put("filename", clean(filename, pickedName));
            state.put("folder", cleanFolder(folder));
            state.put("size", size);
            state.put("mode", mode == null ? "quick" : mode);
            state.put("context", contextJson == null ? "{}" : contextJson);
            state.put("token", idToken);
            state.put("chunkSize", CHUNK_SIZE);
            state.put("parts", new JSONArray());
            state.put("bytesDone", 0L);
            state.put("percent", 0);
            state.put("createdAt", System.currentTimeMillis());
            state.put("updatedAt", System.currentTimeMillis());

            prefs.edit().putString(KEY_STATE, state.toString()).commit();
            startService(R2UploadService.ACTION_START, jobId);
            return jobId;
        } catch (Exception e) {
            return "ERR:تعذّر بدء الرفع في الخلفية";
        }
    }

    @JavascriptInterface
    public synchronized String getState(String jobId) {
        return publicState(jobId);
    }

    @JavascriptInterface
    public synchronized String getCurrentState() {
        return publicState("");
    }

    @JavascriptInterface
    public synchronized void refreshAuth(String jobId, String idToken) {
        if (idToken == null || idToken.length() < 20 || idToken.length() > 20000) return;
        try {
            String raw = prefs.getString(KEY_STATE, "");
            if (raw == null || raw.isEmpty()) return;
            JSONObject state = new JSONObject(raw);
            if (jobId != null && !jobId.isEmpty()
                    && !jobId.equals(state.optString("jobId", ""))) return;
            refreshAuthInternal(state, idToken);
            String status = state.optString("status", "");
            if ("auth_required".equals(status)) {
                state.put("status", "queued");
                state.put("message", "تم تجديد الجلسة — استكمال الرفع…");
                state.put("updatedAt", System.currentTimeMillis());
                prefs.edit().putString(KEY_STATE, state.toString()).commit();
            }
            startService(R2UploadService.ACTION_RESUME, state.optString("jobId", ""));
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public synchronized void cancelUpload(String jobId) {
        try {
            String raw = prefs.getString(KEY_STATE, "");
            if (raw == null || raw.isEmpty()) return;
            JSONObject state = new JSONObject(raw);
            if (!jobId.equals(state.optString("jobId", ""))) return;
            state.put("cancelRequested", true);
            state.put("updatedAt", System.currentTimeMillis());
            prefs.edit().putString(KEY_STATE, state.toString()).commit();
            startService(R2UploadService.ACTION_CANCEL, jobId);
        } catch (Exception ignored) {}
    }

    private void refreshAuthInternal(JSONObject state, String idToken) throws Exception {
        state.put("token", idToken);
        state.put("updatedAt", System.currentTimeMillis());
        prefs.edit().putString(KEY_STATE, state.toString()).commit();
    }

    private String publicState(String jobId) {
        try {
            String raw = prefs.getString(KEY_STATE, "");
            if (raw == null || raw.isEmpty()) return "{\"status\":\"none\"}";
            JSONObject state = new JSONObject(raw);
            if (jobId != null && !jobId.isEmpty()
                    && !jobId.equals(state.optString("jobId", ""))) {
                return "{\"status\":\"none\"}";
            }
            state.remove("token");
            state.remove("uri");
            state.remove("uploadId");
            return state.toString();
        } catch (Exception e) {
            return "{\"status\":\"none\"}";
        }
    }

    private void startService(String action, String jobId) {
        Intent i = new Intent(activity, R2UploadService.class);
        i.setAction(action);
        i.putExtra("jobId", jobId == null ? "" : jobId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(i);
        } else {
            activity.startService(i);
        }
    }

    private static String clean(String requested, String fallback) {
        String x = requested == null ? "" : requested.trim();
        if (x.isEmpty()) x = fallback == null ? "" : fallback.trim();
        if (x.isEmpty()) x = "video.mp4";
        return x.length() > 240 ? x.substring(0, 240) : x;
    }

    private static String cleanFolder(String folder) {
        if (folder == null) return "";
        String x = folder.trim();
        while (x.startsWith("/")) x = x.substring(1);
        while (x.endsWith("/")) x = x.substring(0, x.length() - 1);
        return x.length() > 240 ? x.substring(0, 240) : x;
    }
}
