package com.example.facedetectionapp.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/**
 * Transparent overlay that draws an animated bounding box around a detected face.
 * - Green  = face detected cleanly
 * - Red    = face detected but issues
 * Corner markers give a professional scanner look.
 */
public class FaceOverlayView extends View {

    private final Paint boxPaint    = new Paint();
    private final Paint cornerPaint = new Paint();
    private final Paint labelPaint  = new Paint();

    private Rect    faceRect      = null;
    private boolean isFaceGood    = false;
    private boolean hasFace       = false;

    private static final int CORNER_LEN   = 60;   // px
    private static final int CORNER_WIDTH = 8;

    public FaceOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Semi-transparent box
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(3f);
        boxPaint.setPathEffect(new CornerPathEffect(4));

        // Solid corner brackets
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(CORNER_WIDTH);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        // Label
        labelPaint.setTextSize(36f);
        labelPaint.setAntiAlias(true);
    }

    /** Update the face bounding box (already mapped to view coordinates). */
    public void setFace(Rect rect, boolean good) {
        this.faceRect   = rect;
        this.isFaceGood = good;
        this.hasFace    = true;
        invalidate();
    }

    /** Clear overlay when no face is present. */
    public void clearFaces() {
        this.hasFace  = false;
        this.faceRect = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasFace || faceRect == null) return;

        int color = isFaceGood ? 0xFF4CAF50 : 0xFFFF5722;
        boxPaint.setColor(color & 0x44FFFFFF | 0x44000000);  // transparent version
        cornerPaint.setColor(color);
        labelPaint.setColor(color);

        // Draw translucent box
        boxPaint.setColor((color & 0x00FFFFFF) | 0x44000000);
        canvas.drawRect(faceRect, boxPaint);

        // Draw corner brackets (TL, TR, BL, BR)
        int l = faceRect.left, t = faceRect.top,
            r = faceRect.right, b = faceRect.bottom;

        // Top-left
        canvas.drawLine(l, t, l + CORNER_LEN, t, cornerPaint);
        canvas.drawLine(l, t, l, t + CORNER_LEN, cornerPaint);
        // Top-right
        canvas.drawLine(r, t, r - CORNER_LEN, t, cornerPaint);
        canvas.drawLine(r, t, r, t + CORNER_LEN, cornerPaint);
        // Bottom-left
        canvas.drawLine(l, b, l + CORNER_LEN, b, cornerPaint);
        canvas.drawLine(l, b, l, b - CORNER_LEN, cornerPaint);
        // Bottom-right
        canvas.drawLine(r, b, r - CORNER_LEN, b, cornerPaint);
        canvas.drawLine(r, b, r, b - CORNER_LEN, cornerPaint);

        // Label above box
        String label = isFaceGood ? "Face Detected" : "Adjust Position";
        float textW = labelPaint.measureText(label);
        canvas.drawText(label, l + (faceRect.width() - textW) / 2f, t - 16, labelPaint);
    }
}
