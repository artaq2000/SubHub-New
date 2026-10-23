package com.artaq.subhub;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Publishes a verified local download into the user's visible Download/SubHub folder. */
public final class OwnerDownloadPublisher {
    private OwnerDownloadPublisher() {}

    public static Uri publish(Context context, File source, String displayName, String mime) throws IOException {
        if (Build.VERSION.SDK_INT < 29)
            throw new IOException("Visible Downloads publishing requires Android 10 or newer");
        if (!source.isFile() || source.length() == 0)
            throw new IOException("Cannot publish an empty or missing file");
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
        values.put(MediaStore.Downloads.MIME_TYPE, mime);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SubHub");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Unable to create Downloads entry");
        boolean committed = false;
        try {
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Unable to write Downloads entry");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.Downloads.IS_PENDING, 0);
            if (resolver.update(uri, ready, null, null) != 1)
                throw new IOException("Unable to publish Downloads entry");
            committed = true;
            return uri;
        } finally {
            if (!committed) resolver.delete(uri, null, null);
        }
    }
}
