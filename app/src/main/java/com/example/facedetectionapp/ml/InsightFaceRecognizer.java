package com.example.facedetectionapp.ml;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * InsightFace ArcFace recognizer using w600k_mbf.onnx (from buffalo_sc model pack).
 *
 * Architecture: MobileFaceNet backbone trained with ArcFace loss on WebFace600K.
 *
 * Model specs:
 *   Input  : [1, 3, 112, 112]  float32, normalized as (pixel - 127.5) / 127.5
 *   Output : [1, 512]          float32  (raw embedding, L2-normalize after)
 *
 * After L2-normalization, cosine similarity ≥ 0.28 (group photos, lenient)
 * to ≥ 0.40 (live camera, strict) indicates the same person.
 */
public class InsightFaceRecognizer {

    private static final String TAG        = "InsightFaceRecognizer";
    private static final String MODEL_FILE = "w600k_mbf.onnx";

    /** ArcFace canonical input size. */
    public static final int INPUT_SIZE = 112;

    /** ArcFace normalization (InsightFace python: input_mean=127.5, input_std=127.5). */
    private static final float MEAN = 127.5f;
    private static final float STD  = 127.5f;

    private OrtEnvironment ortEnv;
    private OrtSession    ortSession;
    private boolean       isReady = false;
    private int           embedDim = 512;

    public InsightFaceRecognizer(AssetManager assetManager) {
        try {
            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(4);
            try { opts.addNnapi(); } catch (Exception ignored) { /* NNAPI optional */ }

            InputStream is = assetManager.open(MODEL_FILE);
            byte[] modelBytes = readAllBytes(is);
            is.close();

            ortSession = ortEnv.createSession(modelBytes, opts);

            // w600k_mbf.onnx always outputs [1, 512] — keep default embedDim=512
            // (dynamic shape detection removed for ONNX Runtime API compatibility)
            isReady = true;
            Log.d(TAG, "InsightFace ArcFace recognizer loaded (w600k_mbf.onnx, dim=" + embedDim + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ArcFace recognizer: " + e.getMessage(), e);
        }
    }

    public boolean isReady()   { return isReady;   }
    public int    getEmbedDim(){ return embedDim;   }

    /**
     * Extract a 512-d L2-normalized face embedding from a face crop Bitmap.
     *
     * For best accuracy, the crop should be an aligned face (using the 5 keypoints
     * from InsightFaceDetector). A simple bounding-box crop also works but is slightly
     * less accurate.
     *
     * @param bitmap Face crop (any size — resized to 112×112 internally)
     * @return       L2-normalized float[] of dimension {@link #embedDim}, or null on error
     */
    public float[] getEmbedding(Bitmap bitmap) {
        if (!isReady || ortSession == null || bitmap == null) return null;
        try {
            // 1. Resize to 112×112
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

            // 2. Convert to NCHW float32, normalize: (pixel - 127.5) / 127.5
            float[] inputData = bitmapToNchw(resized);
            resized.recycle();

            // 3. Create OnnxTensor
            long[] shape = {1, 3, INPUT_SIZE, INPUT_SIZE};
            OnnxTensor inputTensor = OnnxTensor.createTensor(
                    ortEnv, FloatBuffer.wrap(inputData), shape);

            // 4. Run inference
            Map<String, OnnxTensor> inputs = new HashMap<>();
            String inputName = ortSession.getInputNames().iterator().next();
            inputs.put(inputName, inputTensor);

            OrtSession.Result result = ortSession.run(inputs);
            inputTensor.close();

            // 5. Extract output
            float[][] rawOut = (float[][]) result.get(0).getValue();
            result.close();

            float[] embedding = rawOut[0]; // [512]

            // 6. L2-normalize
            return l2Normalize(embedding);

        } catch (Exception e) {
            Log.e(TAG, "getEmbedding error: " + e.getMessage(), e);
            return null;
        }
    }

    /** Cosine similarity of two L2-normalized embeddings. Range [-1, 1]. */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        float dot = 0f;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot; // dot of L2-normalized vectors == cosine similarity
    }

    // ── Private Helpers ───────────────────────────────────────────────────────

    private float[] bitmapToNchw(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);

        float[] data = new float[3 * w * h];
        for (int i = 0; i < pixels.length; i++) {
            int px = pixels[i];
            int r  = (px >> 16) & 0xFF;
            int g  = (px >>  8) & 0xFF;
            int b  = (px      ) & 0xFF;
            // InsightFace ArcFace expects RGB input:
            data[i]              = (r - MEAN) / STD;  // R channel
            data[w * h + i]      = (g - MEAN) / STD;  // G channel
            data[2 * w * h + i]  = (b - MEAN) / STD;  // B channel
        }
        return data;
    }

    private float[] l2Normalize(float[] v) {
        float norm = 0f;
        for (float x : v) norm += x * x;
        norm = (float) Math.sqrt(norm);
        if (norm < 1e-10f) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / norm;
        return out;
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
}
