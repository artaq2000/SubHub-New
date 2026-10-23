package com.artaq.subhub;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.net.URI;
import java.util.List;

/**
 * Native owner download test dialog. Caller MUST enforce owner authorization
 * before invoking show(). Not exposed as a JavascriptInterface or exported Activity.
 * Only finite, unencrypted, single-track HLS MPEG-TS is currently supported.
 */
public final class OwnerVideoDownloadDialog {
    private OwnerVideoDownloadDialog() {}

    public static void show(Activity activity, String candidateUrl) {
        Handler main = new Handler(Looper.getMainLooper());
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        EditText source = new EditText(activity);
        source.setSingleLine(true);
        source.setHint("رابط M3U8");
        source.setText(candidateUrl == null ? "" : candidateUrl);
        layout.addView(source);

        final boolean directMp4 = candidateUrl != null && candidateUrl.toLowerCase(java.util.Locale.ROOT).split("\\?")[0].endsWith(".mp4");
        Spinner quality = new Spinner(activity);
        layout.addView(quality);
        Spinner connections = new Spinner(activity);
        String[] counts = {"1", "2", "4", "8", "12", "16"};
        connections.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item, counts));
        connections.setSelection(3);
        layout.addView(connections);

        TextView status = new TextView(activity);
        status.setText("أدخل رابط M3U8 ثم اضغط فحص");
        layout.addView(status);

        final List<HlsPlaylistInspector.Quality>[] options = new List[1];
        final boolean[] busy = {false};
        AlertDialog dialog = new AlertDialog.Builder(activity)
            .setTitle("فحص الفيديو — تجربة المالك")
            .setView(layout)
            .setNegativeButton("إغلاق", (d, which) -> {})
            .setNeutralButton("فحص", null)
            .setPositiveButton("تحميل", null)
            .create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (busy[0]) return;
                busy[0] = true;
                options[0] = null;
                if (directMp4) {
                    status.setText("رابط MP4 مباشر: اختر عدد الاتصالات ثم اضغط تحميل. الجودة هي جودة الملف الأصلي.");
                    busy[0] = false;
                    return;
                }
                status.setText("جاري فحص الجودات...");
                String url = source.getText().toString().trim();
                new Thread(() -> {
                    try {
                        List<HlsPlaylistInspector.Quality> found = OwnerVideoDownload.inspect(url);
                        if (found.isEmpty()) throw new IllegalArgumentException("لم تُكتشف جودات مدعومة");
                        String[] labels = new String[found.size()];
                        for (int i = 0; i < found.size(); i++) {
                            HlsPlaylistInspector.Quality item = found.get(i);
                            labels[i] = item.height > 0 ? item.height + "p" : "جودة " + (i + 1);
                        }
                        main.post(() -> {
                            if (!dialog.isShowing()) return;
                            options[0] = found;
                            quality.setAdapter(new ArrayAdapter<>(activity,
                                android.R.layout.simple_spinner_dropdown_item, labels));
                            status.setText("اختر الجودة ثم اضغط تحميل");
                            busy[0] = false;
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            if (!dialog.isShowing()) return;
                            status.setText("فشل الفحص: " + e.getMessage());
                            busy[0] = false;
                        });
                    }
                }, "SubHub-owner-inspect").start();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (busy[0] || (!directMp4 && (options[0] == null || quality.getSelectedItemPosition() < 0))) return;
                int selected = directMp4 ? 0 : quality.getSelectedItemPosition();
                URI uri = directMp4 ? URI.create(source.getText().toString().trim()) : options[0].get(selected).url;
                int workers = Integer.parseInt(counts[connections.getSelectedItemPosition()]);
                File folder = activity.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES);
                if (folder == null) {
                    status.setText("تعذر الوصول إلى مجلد الفيديو");
                    return;
                }
                File destination = new File(folder, "subhub-owner-" + System.currentTimeMillis() + (directMp4 ? ".mp4" : ".ts"));
                busy[0] = true;
                status.setText("جاري التحميل...");
                new Thread(() -> {
                    try {
                        File result;
                        if (directMp4) {
                            SegmentedVideoDownloader.download(uri.toString(), destination, workers,
                                (done, total) -> main.post(() -> {
                                    if (dialog.isShowing()) status.setText("تحميل MP4: " + (100 * done / Math.max(1, total)) + "%");
                                }));
                            result = destination;
                        } else {
                            result = OwnerVideoDownload.downloadHlsTransportStream(
                                uri, destination, workers, (stage, done, total) ->
                                    main.post(() -> {
                                        if (dialog.isShowing()) status.setText(stage + ": " + done + "/" + total);
                                    }));
                        }
                        main.post(() -> {
                            if (!dialog.isShowing()) return;
                            busy[0] = false;
                            status.setText("تم حفظ ملف TS: " + result.getAbsolutePath());
                            Toast.makeText(activity, "اكتمل تحميل الفيديو", Toast.LENGTH_LONG).show();
                        });
                    } catch (Exception e) {
                        main.post(() -> {
                            if (!dialog.isShowing()) return;
                            busy[0] = false;
                            status.setText("فشل التحميل: " + e.getMessage());
                        });
                    }
                }, "SubHub-owner-download").start();
            });
        });
        dialog.show();
    }
}
