package com.example.facedetectionapp.ml;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * InsightFace SCRFD face detector (det_500m.onnx from buffalo_sc model pack).
 *
 * Model specs:
 *   Input  : [1, 3, 640, 640] float32, normalized as (pixel - 127.5) / 128.0
 *   Outputs: 9 tensors (3 strides × {score, bbox, kps})
 *   Strides: 8, 16, 32
 *
 * Usage:
 *   List<FaceBox> boxes = detector.detect(bitmap, 0.5f);
 */
public class InsightFaceDetector {

    private static final String TAG        = "InsightFaceDetector";
    private static final String MODEL_FILE = "det_500m.onnx";

    /** Detection input resolution. SCRFD-500m is optimized for 640×640, but 960×960 detects much smaller/distant faces. */
    public static final int DET_SIZE = 960;

    /** Confidence threshold for accepting a face. */
    private static final float DEFAULT_CONFIDENCE = 0.45f;

    /** IoU threshold for NMS. */
    private static final float NMS_THRESHOLD = 0.4f;

    /** Anchor counts per position at each stride (buffalo_sc det_500m has 2). */
    private static final int[] STRIDES      = {8, 16, 32};
    private static final int   NUM_ANCHORS  = 2;   // anchors per cell

    // Normalization constants (same as InsightFace Python)
    private static final float MEAN = 127.5f;
    private static final float STD  = 128.0f;

    private OrtEnvironment ortEnv;
    private OrtSession    ortSession;
    private boolean       isReady = false;

    public InsightFaceDetector(AssetManager assetManager) {
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(4);
            try { opts.addNnapi(); } catch (Exception ignored) { /* NNAPI optional */ }

            InputStream is = assetManager.open(MODEL_FILE);
            byte[] modelBytes = readAllBytes(is);
            is.close();

            ortSession = ortEnv.createSession(modelBytes, opts);
            isReady = true;
            Log.d(TAG, "InsightFace SCRFD detector loaded (det_500m.onnx)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load SCRFD detector: " + e.getMessage(), e);
        }
    }

    public boolean isReady() { return isReady; }

    /**
     * Detect all faces in bitmap. Returns list of {@link FaceBox} with bounding boxes
     * and 5 facial keypoints (left eye, right eye, nose, left mouth, right mouth),
     * all in original image coordinates.
     */
    public List<FaceBox> detect(Bitmap bitmap, float confidenceThreshold) {
        if (!isReady || ortSession == null || bitmap == null) return Collections.emptyList();

        float confThresh = confidenceThreshold > 0 ? confidenceThreshold : DEFAULT_CONFIDENCE;

        try {
            int origW = bitmap.getWidth();
            int origH = bitmap.getHeight();

            // ── 1. Letterbox-resize to DET_SIZE × DET_SIZE ────────────────────
            float[] letterboxParams = new float[3]; // scaleX, scaleY, padX, padY
            // We keep aspect ratio and pad with zeros (as InsightFace does)
            float imRatio   = (float) origH / origW;
            float modelRatio = 1.0f; // DET_SIZE / DET_SIZE == 1
            int newH, newW;
            if (imRatio > modelRatio) {
                newH = DET_SIZE;
                newW = (int) (newH / imRatio);
            } else {
                newW = DET_SIZE;
                newH = (int) (newW * imRatio);
            }
            float detScale = (float) newH / origH; // same as (float)newW / origW

            Bitmap resized  = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
            Bitmap detInput = Bitmap.createBitmap(DET_SIZE, DET_SIZE, Bitmap.Config.ARGB_8888);
            // Paste resized into top-left of blank DET_SIZE×DET_SIZE canvas
            android.graphics.Canvas c = new android.graphics.Canvas(detInput);
            c.drawBitmap(resized, 0, 0, null);
            resized.recycle();

            // ── 2. Build NCHW float tensor ──────────────────────────────────
            float[] inputData = bitmapToNchw(detInput);
            detInput.recycle();

            long[] shape = {1, 3, DET_SIZE, DET_SIZE};
            OnnxTensor inputTensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(inputData), shape);

            // ── 3. Run inference ───────────────────────────────────────────
            Map<String, OnnxTensor> inputs = new HashMap<>();
            String inputName = ortSession.getInputNames().iterator().next();
            inputs.put(inputName, inputTensor);

            OrtSession.Result result = ortSession.run(inputs);
            inputTensor.close();

            // ── 4. Decode SCRFD outputs ────────────────────────────────────
            // Outputs are ordered: score8, score16, score32,
            //                      bbox8,  bbox16,  bbox32,
            //                      kps8,   kps16,   kps32
            List<float[]> scoresList = new ArrayList<>();
            List<float[]> bboxList   = new ArrayList<>();
            List<float[]> kpsList    = new ArrayList<>();

            String[] outNames = ortSession.getOutputNames().toArray(new String[0]);
            int fmc = STRIDES.length; // 3

            for (int i = 0; i < fmc; i++) {
                float[] scores = flattenOutput(result, i);            // score
                float[] bboxes = flattenOutput(result, i + fmc);      // bbox
                float[] kps    = flattenOutput(result, i + fmc * 2);  // kps

                int stride  = STRIDES[i];
                int gridH   = DET_SIZE / stride;
                int gridW   = DET_SIZE / stride;
                int nAnchors = gridH * gridW * NUM_ANCHORS;

                // Generate anchor centers
                float[][] centers = generateAnchorCenters(gridH, gridW, stride);

                // Decode boxes
                for (int j = 0; j < nAnchors; j++) {
                    float score = scores[j];
                    if (score < confThresh) continue;

                    float cx = centers[j][0];
                    float cy = centers[j][1];

                    // bbox in [left, top, right, bottom] relative to anchor center
                    float x1 = (cx - bboxes[j * 4    ] * stride) / detScale;
                    float y1 = (cy - bboxes[j * 4 + 1] * stride) / detScale;
                    float x2 = (cx + bboxes[j * 4 + 2] * stride) / detScale;
                    float y2 = (cy + bboxes[j * 4 + 3] * stride) / detScale;

                    // Clamp to original image bounds
                    x1 = Math.max(0, Math.min(x1, origW));
                    y1 = Math.max(0, Math.min(y1, origH));
                    x2 = Math.max(0, Math.min(x2, origW));
                    y2 = Math.max(0, Math.min(y2, origH));

                    // Decode 5 keypoints (10 values: x0,y0,x1,y1,...x4,y4)
                    float[][] kpsPoints = new float[5][2];
                    for (int k = 0; k < 5; k++) {
                        kpsPoints[k][0] = (cx + kps[j * 10 + k * 2    ] * stride) / detScale;
                        kpsPoints[k][1] = (cy + kps[j * 10 + k * 2 + 1] * stride) / detScale;
                        kpsPoints[k][0] = Math.max(0, Math.min(kpsPoints[k][0], origW));
                        kpsPoints[k][1] = Math.max(0, Math.min(kpsPoints[k][1], origH));
                    }

                    scoresList.add(new float[]{score});
                    bboxList.add(new float[]{x1, y1, x2, y2});
                    kpsList.add(flatten5kps(kpsPoints));
                }
            }
            result.close();

            if (bboxList.isEmpty()) return Collections.emptyList();

            // ── 5. NMS ────────────────────────────────────────────────────
            List<Integer> keepIdx = nms(bboxList, scoresList, NMS_THRESHOLD);

            List<FaceBox> detections = new ArrayList<>();
            for (int idx : keepIdx) {
                float[] b   = bboxList.get(idx);
                float[] kps = kpsList.get(idx);
                float   sc  = scoresList.get(idx)[0];
                detections.add(new FaceBox(
                        new RectF(b[0], b[1], b[2], b[3]), sc, kps));
            }

            return detections;

        } catch (Exception e) {
            Log.e(TAG, "detect error: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private float[] bitmapToNchw(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] data = new float[3 * w * h];
        // NCHW layout: [channel][row][col]
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            int r  = (px >> 16) & 0xFF;
            int g  = (px >>  8) & 0xFF;
            int b  = (px      ) & 0xFF;
            // Normalize and map to RGB layout:
            data[i]              = (r - MEAN) / STD; // R channel
            data[w * h + i]      = (g - MEAN) / STD; // G channel
            data[2 * w * h + i]  = (b - MEAN) / STD; // B channel
        }
        return data;
    }

    private float[] flattenOutput(OrtSession.Result result, int index) throws Exception {
        Object raw = result.get(index).getValue();
        if (raw instanceof float[][]) {
            float[][] r2 = (float[][]) raw;
            float[] flat = new float[r2.length * r2[0].length];
            for (int i = 0; i < r2.length; i++) {
                System.arraycopy(r2[i], 0, flat, i * r2[0].length, r2[0].length);
            }
            return flat;
        } else if (raw instanceof float[]) {
            return (float[]) raw;
        } else {
            // Handle [N, K, D] (batched)
            float[][][] r3 = (float[][][]) raw;
            float[][] r2 = r3[0]; // batch 0
            float[] flat = new float[r2.length * r2[0].length];
            for (int i = 0; i < r2.length; i++) {
                System.arraycopy(r2[i], 0, flat, i * r2[0].length, r2[0].length);
            }
            return flat;
        }
    }

    /**
     * Generate grid anchor centers for a given feature map size.
     * Each cell generates NUM_ANCHORS (=2) anchors at the same center.
     */
    private float[][] generateAnchorCenters(int gridH, int gridW, int stride) {
        int total = gridH * gridW * NUM_ANCHORS;
        float[][] centers = new float[total][2];
        int idx = 0;
        for (int row = 0; row < gridH; row++) {
            for (int col = 0; col < gridW; col++) {
                float cx = col * stride;
                float cy = row * stride;
                for (int a = 0; a < NUM_ANCHORS; a++) {
                    centers[idx][0] = cx;
                    centers[idx][1] = cy;
                    idx++;
                }
            }
        }
        return centers;
    }

    private float[] flatten5kps(float[][] kpsPoints) {
        float[] flat = new float[10];
        for (int k = 0; k < 5; k++) {
            flat[k * 2]     = kpsPoints[k][0];
            flat[k * 2 + 1] = kpsPoints[k][1];
        }
        return flat;
    }

    private List<Integer> nms(List<float[]> bboxes, List<float[]> scores, float iouThresh) {
        int n = bboxes.size();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        // Sort by score descending
        order.sort((a, b) -> Float.compare(scores.get(b)[0], scores.get(a)[0]));

        boolean[] suppressed = new boolean[n];
        List<Integer> keep = new ArrayList<>();

        for (int i = 0; i < order.size(); i++) {
            int idx = order.get(i);
            if (suppressed[idx]) continue;
            keep.add(idx);

            float[] bi = bboxes.get(idx);
            float area_i = (bi[2] - bi[0]) * (bi[3] - bi[1]);

            for (int j = i + 1; j < order.size(); j++) {
                int jdx = order.get(j);
                if (suppressed[jdx]) continue;

                float[] bj = bboxes.get(jdx);
                float interX1 = Math.max(bi[0], bj[0]);
                float interY1 = Math.max(bi[1], bj[1]);
                float interX2 = Math.min(bi[2], bj[2]);
                float interY2 = Math.min(bi[3], bj[3]);

                float interW = Math.max(0, interX2 - interX1);
                float interH = Math.max(0, interY2 - interY1);
                float inter  = interW * interH;

                float area_j = (bj[2] - bj[0]) * (bj[3] - bj[1]);
                float iou    = inter / (area_i + area_j - inter + 1e-6f);

                if (iou > iouThresh) suppressed[jdx] = true;
            }
        }
        return keep;
    }

    private byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int read;
        while ((read = is.read(buf)) != -1) {
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    public void close() {
        try {
            if (ortSession != null) { ortSession.close(); ortSession = null; }
            if (ortEnv    != null) { ortEnv.close();    ortEnv    = null; }
        } catch (Exception ignored) {}
        isReady = false;
    }

    // ── Data Class ────────────────────────────────────────────────────────────

    /**
     * A detected face with its bounding box, confidence score and 5 facial keypoints.
     *
     * Keypoints order (InsightFace convention):
     *   [0] = left eye, [1] = right eye, [2] = nose tip,
     *   [3] = left mouth corner, [4] = right mouth corner
     *
     * kps layout: float[10] = [kp0x, kp0y, kp1x, kp1y, ..., kp4x, kp4y]
     */
    public static class FaceBox {
        public final RectF  bbox;
        public final float  score;
        public final float[] kps;   // 10 values: 5 keypoints × (x, y)

        public FaceBox(RectF bbox, float score, float[] kps) {
            this.bbox  = bbox;
            this.score = score;
            this.kps   = kps;
        }
    }
}
