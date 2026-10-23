package com.artaq.subhub;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Debug-only, separate-package test harness. Never included in release builds.
 * Owner authorization will be required before exposing this in production SubHub.
 */
public final class OwnerDownloaderTestActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView message = new TextView(this);
        message.setText("اختبار تحميل HLS\nنسخة تطوير منفصلة عن SubHub. استخدم رابط M3U8 تملك حق تنزيله. يدعم حالياً قوائم TS المكتملة دون تشفير فقط.");
        root.addView(message);
        Button launch = new Button(this);
        launch.setText("فحص الفيديو");
        launch.setOnClickListener(v -> OwnerVideoDownloadDialog.show(this, ""));
        root.addView(launch);
        setContentView(root);
    }
}
