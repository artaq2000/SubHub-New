package com.artaq.subhub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.view.Gravity;
import android.widget.TextView;

/**
 * 322.3.96: subtitle text with a true pill (capsule) background per line.
 *
 * Why not a LineBackgroundSpan (322.3.94/95): TextView clips its canvas to the
 * text area (inside the padding) before drawing spans, so anything a span
 * draws beside the text is cut off flat. Here the pill is drawn in onDraw
 * BEFORE TextView clips, using the real line bounds from the Layout, with its
 * own clean Paint (no text shadow), so both ends are perfect half circles.
 */
public final class SubtitlePillTextView extends TextView {
    /** How far the pill reaches beside the text and above/below the outer lines. */
    public static final int PILL_SIDE_DP = 14;
    public static final int PILL_EDGE_DP = 4;
    /** View padding is always larger than the pill reach, so the view never clips it. */
    public static final int VIEW_PAD_SIDE_DP = PILL_SIDE_DP + 4;
    public static final int VIEW_PAD_EDGE_DP = PILL_EDGE_DP + 3;

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private int pillColor = Color.TRANSPARENT;
    private float pillSide;
    private float pillEdge;

    public SubtitlePillTextView(Context context) {
        super(context);
        float d = context.getResources().getDisplayMetrics().density;
        pillSide = PILL_SIDE_DP * d;
        pillEdge = PILL_EDGE_DP * d;
        pillPaint.setStyle(Paint.Style.FILL);
        pillPaint.setDither(true);
        int side = Math.round(VIEW_PAD_SIDE_DP * d);
        int edge = Math.round(VIEW_PAD_EDGE_DP * d);
        setPadding(side, edge, side, edge);
    }

    public void setPillColor(int color) {
        if (pillColor == color) return;
        pillColor = color;
        invalidate();
    }

    public int getPillColor() {
        return pillColor;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawPills(canvas);
        super.onDraw(canvas);
    }

    private void drawPills(Canvas canvas) {
        if (Color.alpha(pillColor) == 0) return;
        Layout layout = getLayout();
        CharSequence text = getText();
        if (layout == null || text == null || text.length() == 0) return;

        float originX = getCompoundPaddingLeft() - getScrollX();
        float originY = getExtendedPaddingTop() - getScrollY() + verticalOffset(layout);
        int count = layout.getLineCount();
        pillPaint.setColor(pillColor);

        for (int i = 0; i < count; i++) {
            if (isBlank(text, layout.getLineStart(i), layout.getLineEnd(i))) continue;
            float left = layout.getLineLeft(i);
            float right = layout.getLineRight(i);
            if (right - left < 1f) continue;
            // Only the outer edges grow; inner lines meet exactly so two
            // translucent pills never overlap into a darker stripe.
            float top = layout.getLineTop(i) - (i == 0 ? pillEdge : 0f);
            float bottom = layout.getLineBottom(i) + (i == count - 1 ? pillEdge : 0f);
            pillRect.set(originX + left - pillSide, originY + top,
                    originX + right + pillSide, originY + bottom);
            float radius = pillRect.height() / 2f; // exact half circle on both ends
            canvas.drawRoundRect(pillRect, radius, radius, pillPaint);
        }
    }

    private int verticalOffset(Layout layout) {
        int gravity = getGravity() & Gravity.VERTICAL_GRAVITY_MASK;
        if (gravity == Gravity.TOP) return 0;
        int box = getMeasuredHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
        int textHeight = layout.getHeight();
        if (textHeight >= box) return 0;
        return gravity == Gravity.BOTTOM ? box - textHeight : (box - textHeight) / 2;
    }

    private static boolean isBlank(CharSequence text, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(end, text.length()); i++) {
            if (!Character.isWhitespace(text.charAt(i))) return false;
        }
        return true;
    }
}
