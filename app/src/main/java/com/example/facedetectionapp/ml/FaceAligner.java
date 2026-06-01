package com.example.facedetectionapp.ml;

import android.graphics.Bitmap;
import android.graphics.Matrix;

/**
 * Aligns a face crop using the 5 landmark keypoints provided by InsightFaceDetector.
 *
 * InsightFace uses a fixed reference template (112×112) for alignment, which ensures
 * that the eyes, nose, and mouth corners are always at the same pixel positions.
 * This dramatically improves ArcFace embedding quality vs. simple bounding-box crops.
 *
 * Reference template (112×112, as used in InsightFace python):
 *   [0] Left  eye:           38.29, 51.70
 *   [1] Right eye:           73.53, 51.50
 *   [2] Nose tip:            56.02, 71.74
 *   [3] Left  mouth corner:  41.55, 92.37
 *   [4] Right mouth corner:  70.72, 92.20
 */
public class FaceAligner {

    private static final int OUT_SIZE = InsightFaceRecognizer.INPUT_SIZE; // 112

    // Reference landmark positions in the 112×112 canonical face space
    private static final float[] REF_X = {38.29f, 73.53f, 56.02f, 41.55f, 70.72f};
    private static final float[] REF_Y = {51.70f, 51.50f, 71.74f, 92.37f, 92.20f};

    /**
     * Align and crop a face to a 112×112 Bitmap using the 5 SCRFD keypoints.
     *
     * @param srcBitmap  The full image
     * @param kps        float[10] = [kp0x,kp0y, kp1x,kp1y, ..., kp4x,kp4y]
     *                   as returned by {@link InsightFaceDetector.FaceBox#kps}
     * @return           Aligned 112×112 face Bitmap, or null on error
     */
    public static Bitmap align(Bitmap srcBitmap, float[] kps) {
        if (srcBitmap == null || kps == null || kps.length < 10) return null;

        // ── Extract detected keypoints ─────────────────────────────────────
        float[] srcX = new float[5];
        float[] srcY = new float[5];
        for (int i = 0; i < 5; i++) {
            srcX[i] = kps[i * 2];
            srcY[i] = kps[i * 2 + 1];
        }

        // ── Estimate similarity transform (scale, rotation, translation) ───
        // We use a least-squares similarity transform (umeyama method, simplified to
        // use only the two eye points for a robust, fast estimate on mobile).
        // Full 5-point version shown below for maximum accuracy.
        float[] transform = estimateSimilarityTransform(srcX, srcY, REF_X, REF_Y);

        // ── Apply affine warp to produce 112×112 aligned face ──────────────
        return applyWarp(srcBitmap, transform);
    }

    /**
     * Fallback: crop a 112×112 face using bounding box only (no keypoints).
     * Less accurate than {@link #align} but works even if kps are null.
     */
    public static Bitmap cropBox(Bitmap srcBitmap, android.graphics.RectF bbox) {
        if (srcBitmap == null || bbox == null) return null;

        int srcW = srcBitmap.getWidth(), srcH = srcBitmap.getHeight();

        // Add 20% padding around the detected box (InsightFace convention)
        float padFrac = 0.20f;
        float w = bbox.width();
        float h = bbox.height();
        float l = Math.max(0, bbox.left   - w * padFrac);
        float t = Math.max(0, bbox.top    - h * padFrac);
        float r = Math.min(srcW, bbox.right  + w * padFrac);
        float b = Math.min(srcH, bbox.bottom + h * padFrac);

        if (r - l <= 0 || b - t <= 0) return null;
        Bitmap cropped = Bitmap.createBitmap(srcBitmap,
                (int) l, (int) t, (int) (r - l), (int) (b - t));
        return Bitmap.createScaledBitmap(cropped, OUT_SIZE, OUT_SIZE, true);
    }

    // ── Private Math ──────────────────────────────────────────────────────────

    /**
     * Estimate 2D similarity transform (scale + rotation + translation) using the
     * normalized least-squares / Procrustes approach (Umeyama algorithm simplified).
     *
     * Returns a float[6] = [a, b, tx, c, d, ty] such that:
     *   [x'] = [a  -b  tx] [x]
     *   [y']   [b   a  ty] [y]
     *                       [1]
     */
    private static float[] estimateSimilarityTransform(
            float[] srcX, float[] srcY, float[] dstX, float[] dstY) {

        int n = srcX.length;
        float meanSrcX = mean(srcX), meanSrcY = mean(srcY);
        float meanDstX = mean(dstX), meanDstY = mean(dstY);

        // Center
        float[] cSrcX = sub(srcX, meanSrcX), cSrcY = sub(srcY, meanSrcY);
        float[] cDstX = sub(dstX, meanDstX), cDstY = sub(dstY, meanDstY);

        float varSrc = 0f;
        for (int i = 0; i < n; i++) varSrc += cSrcX[i] * cSrcX[i] + cSrcY[i] * cSrcY[i];
        varSrc /= n;

        // Cross-covariance
        float cov00 = 0f, cov01 = 0f, cov10 = 0f, cov11 = 0f;
        for (int i = 0; i < n; i++) {
            cov00 += cSrcX[i] * cDstX[i];
            cov01 += cSrcX[i] * cDstY[i];
            cov10 += cSrcY[i] * cDstX[i];
            cov11 += cSrcY[i] * cDstY[i];
        }
        cov00 /= n; cov01 /= n; cov10 /= n; cov11 /= n;

        // a = (cov00 + cov11) / varSrc
        // b = (cov01 - cov10) / varSrc
        float scale = (float) Math.sqrt(cov00 * cov00 + cov01 * cov01 + cov10 * cov10 + cov11 * cov11) / varSrc;
        float a = (cov00 + cov11) / (varSrc + 1e-6f);
        float b = (cov01 - cov10) / (varSrc + 1e-6f);

        float tx = meanDstX - (a * meanSrcX - b * meanSrcY);
        float ty = meanDstY - (b * meanSrcX + a * meanSrcY);

        return new float[]{a, -b, tx, b, a, ty};
    }

    private static Bitmap applyWarp(Bitmap src, float[] t) {
        // t = [a, -b, tx, b, a, ty]
        // canvas.drawBitmap(src, M, paint) maps src pixel at pos P → canvas at M*P.
        // So M must be the FORWARD transform (source → canonical).
        float a    = t[0], b_neg = t[1], tx = t[2];
        float b    = t[3], aa   = t[4], ty = t[5];

        Matrix fwdMatrix = new Matrix();
        fwdMatrix.setValues(new float[]{
            a,    b_neg, tx,
            b,    aa,    ty,
            0f,   0f,    1f
        });

        Bitmap out = Bitmap.createBitmap(OUT_SIZE, OUT_SIZE, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        canvas.drawBitmap(src, fwdMatrix, null);
        return out;
    }

    private static float mean(float[] a) {
        float s = 0; for (float v : a) s += v; return s / a.length;
    }
    private static float[] sub(float[] a, float m) {
        float[] out = new float[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] - m;
        return out;
    }
}
