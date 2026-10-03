package com.artaq.subhub;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/**
 * Renders the real subtitle view on the emulator and checks the pixels:
 * both ends must be true half circles (not clipped flat), the fill must be
 * exactly the chosen opacity (no shadow doubling), and the ten opacity steps
 * must go from light to dark evenly.
 */
@RunWith(AndroidJUnit4.class)
public class SubtitlePillTest {
    private static final String LINE = "سيقتله شخص آخر إن لم أفعل.";
    private static final String TWO = "السيد (ساكاموتو) الذي كنت أعرفه\nقد اختفى منذ زمن طويل.";

    private Bitmap render(String text, int pillAlpha, int maxWidthPx) {
        AtomicReference<Bitmap> out = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SubtitlePillTextView v = new SubtitlePillTextView(c);
            v.setTextColor(0xFFFFD84A);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            v.setGravity(Gravity.CENTER);
            v.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
            v.setShadowLayer(1.25f * c.getResources().getDisplayMetrics().density, 0, 0, Color.BLACK);
            v.setLineSpacing(0, 1.04f);
            v.setMaxWidth(maxWidthPx);
            v.setPillColor(Color.argb(pillAlpha, 0, 0, 0));
            v.setText(text);
            v.measure(View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
            Bitmap b = Bitmap.createBitmap(v.getMeasuredWidth(), v.getMeasuredHeight(),
                    Bitmap.Config.ARGB_8888);
            b.eraseColor(Color.TRANSPARENT);
            v.draw(new Canvas(b));
            out.set(b);
        });
        return out.get();
    }

    private static boolean filled(Bitmap b, int x, int y) {
        return Color.alpha(b.getPixel(x, y)) > 60;
    }

    private static int leftmost(Bitmap b, int y) {
        for (int x = 0; x < b.getWidth(); x++) if (filled(b, x, y)) return x;
        return -1;
    }

    private static int rightmost(Bitmap b, int y) {
        for (int x = b.getWidth() - 1; x >= 0; x--) if (filled(b, x, y)) return x;
        return -1;
    }

    private static int[] box(Bitmap b) {
        int x0 = b.getWidth(), y0 = b.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < b.getHeight(); y++)
            for (int x = 0; x < b.getWidth(); x++)
                if (filled(b, x, y)) {
                    x0 = Math.min(x0, x); x1 = Math.max(x1, x);
                    y0 = Math.min(y0, y); y1 = Math.max(y1, y);
                }
        return new int[]{x0, y0, x1, y1};
    }

    @Test
    public void bothEndsAreTrueHalfCircles() {
        Bitmap b = render(LINE, 200, 1600);
        int[] r = box(b);
        assertTrue("pill missing", r[2] > r[0] && r[3] > r[1]);
        // Never touches the view edge: nothing clipped.
        assertTrue("left touches edge " + r[0], r[0] >= 2);
        assertTrue("right touches edge " + r[2], r[2] <= b.getWidth() - 3);
        assertTrue("top touches edge " + r[1], r[1] >= 2);
        assertTrue("bottom touches edge " + r[3], r[3] <= b.getHeight() - 3);

        float h = r[3] - r[1] + 1;
        float radius = h / 2f;
        float cy = r[1] + radius - 0.5f;
        float[] fractions = {0f, 0.5f, 0.8f, -0.5f, -0.8f};
        for (float f : fractions) {
            int y = Math.round(cy + f * radius);
            double inset = radius - Math.sqrt(radius * radius - (f * radius) * (f * radius));
            int expectLeft = (int) Math.round(r[0] + inset);
            int expectRight = (int) Math.round(r[2] - inset);
            int tol = Math.max(3, Math.round(radius * 0.08f));
            assertEquals("left arc at " + f, expectLeft, leftmost(b, y), tol);
            assertEquals("right arc at " + f, expectRight, rightmost(b, y), tol);
        }
        // A flat (clipped) end would keep the same edge at 80% height.
        assertTrue("left end is flat", leftmost(b, Math.round(cy + 0.8f * radius)) - r[0] > radius * 0.25f);
        assertTrue("right end is flat", r[2] - rightmost(b, Math.round(cy + 0.8f * radius)) > radius * 0.25f);
    }

    @Test
    public void fillIsExactlyTheChosenOpacityAndStepsGoLightToDark() {
        int previous = -1;
        for (int step = 10; step <= 100; step += 10) {
            int alpha = DirectStreamPlayer.subtitleBackgroundAlphaFor(step);
            Bitmap b = render(LINE, alpha, 1600);
            // Measure inside the pill, beside the text, on the middle row.
            int y = b.getHeight() / 2;
            int x = -1;
            for (int i = 0; i < b.getWidth(); i++) if (Color.alpha(b.getPixel(i, y)) > 0) { x = i; break; }
            assertTrue("no pill at step " + step, x >= 0);
            int got = Color.alpha(b.getPixel(x + 6, y));
            assertEquals("fill alpha at " + step + "% (shadow doubling?)", alpha, got, 3);
            assertTrue("not darker at " + step + "%", got > previous);
            previous = got;
        }
        assertTrue("10% must be light", DirectStreamPlayer.subtitleBackgroundAlphaFor(10) <= 16);
        assertTrue("100% must not be solid black", DirectStreamPlayer.subtitleBackgroundAlphaFor(100) <= 225);
    }

    @Test
    public void twoLinesNeverOverlapIntoDarkerStripe() {
        int alpha = 120;
        Bitmap b = render(TWO, alpha, 700);
        int[] r = box(b);
        int maxAlpha = 0;
        // Scan the column just inside the wider pill's left end across all rows.
        int x = r[0] + Math.max(4, (r[3] - r[1]) / 8);
        for (int y = r[1]; y <= r[3]; y++) maxAlpha = Math.max(maxAlpha, Color.alpha(b.getPixel(x, y)));
        assertTrue("overlap made a darker stripe: " + maxAlpha, maxAlpha <= alpha + 3);
    }

    @Test
    public void noPillWhenOpacityIsZero() {
        Bitmap b = render(LINE, 0, 1600);
        int y = b.getHeight() / 2;
        assertEquals(0, Color.alpha(b.getPixel(2, y)));
        assertEquals(0, Color.alpha(b.getPixel(b.getWidth() - 3, y)));
    }
}
