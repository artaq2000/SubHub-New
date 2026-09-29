package com.artaq.subhub;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.WebView;

import java.lang.reflect.Field;

/**
 * 322.3.48 launcher activity.
 *
 * Keeps MainActivity untouched except for the version stamp, while adding the
 * native R2 background-upload bridge. The existing WebView file chooser still
 * returns the selected File to the site; this subclass also remembers the
 * matching content:// Uri so the foreground service can read it after SubHub
 * goes to the background.
 */
public class BackgroundMainActivity extends MainActivity {
    private static final int FILE_CHOOSER_REQUEST = 2207;
    static final String R2_PREFS = "subhub_r2_background_v348";
    static final String KEY_SELECTED_URI = "selected_uri";
    static final String KEY_SELECTED_NAME = "selected_name";
    static final String KEY_SELECTED_SIZE = "selected_size";
    static final String KEY_SELECTED_AT = "selected_at";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installR2Bridge();
    }

    private void installR2Bridge() {
        try {
            Field f = MainActivity.class.getDeclaredField("webView");
            f.setAccessible(true);
            WebView w = (WebView) f.get(this);
            if (w != null) {
                w.addJavascriptInterface(new R2NativeBridge(this), "SubHubR2Bridge");
            }
        } catch (Exception ignored) {
            // The normal app remains usable even if the optional R2 bridge fails.
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        /*
         * Save the Uri BEFORE MainActivity forwards the result to WebView.
         * qlStartUpload()/seriesStartUpload() can therefore call the native
         * bridge immediately and it will already know which document was picked.
         */
        if (requestCode == FILE_CHOOSER_REQUEST && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) rememberSelectedDocument(uri, data.getFlags());
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void rememberSelectedDocument(Uri uri, int resultFlags) {
        try {
            int takeFlags = resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (takeFlags != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException ignored) {
                    // ACTION_GET_CONTENT grants are normally task-scoped. The
                    // foreground service keeps the process/task alive while uploading.
                }
            }

            String name = "";
            long size = -1L;
            Cursor c = null;
            try {
                c = getContentResolver().query(
                        uri,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null
                );
                if (c != null && c.moveToFirst()) {
                    int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int si = c.getColumnIndex(OpenableColumns.SIZE);
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
                }
            } finally {
                if (c != null) c.close();
            }

            getSharedPreferences(R2_PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SELECTED_URI, uri.toString())
                    .putString(KEY_SELECTED_NAME, name == null ? "" : name)
                    .putLong(KEY_SELECTED_SIZE, size)
                    .putLong(KEY_SELECTED_AT, System.currentTimeMillis())
                    .apply();
        } catch (Exception ignored) {}
    }
}
