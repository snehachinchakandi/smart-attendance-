package com.example.facedetectionapp.ml;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Wraps the lightweight MobileFaceNet TFLite model (~5MB).
 *
 * Auto-detects normalization mode via a self-test on a synthetic image.
 * Supports both common normalizations:
 *   Mode A: (pixel - 127.5) / 128.0  → range [-1, 1]  (most MobileFaceNet models)
 *   Mode B: (pixel / 255.0)           → range [0, 1]   (some older exports)
 *
 * Model input:  [1, 112, 112, 3]  float32
 * Model output: [1, N]            float32  (N auto-detected from model, typically 192)
 */
public class MobileFaceNetModel {

    private static final String TAG        = "MobileFaceNetModel";
    private static final String MODEL_FILE = "mobilefacenet.tflite";
    public  static final int    INPUT_SIZE = 112;

    private Interpreter interpreter;
    private boolean     isReady   = false;
    private int         outputDim = 192;      // default, will be detected
    private boolean     modeA     = true;     // true = [-1,1], false = [0,1]

    public MobileFaceNetModel(AssetManager assetManager) {
        try {
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(4);
            interpreter = new Interpreter(loadModelFile(assetManager), opts);

            // Auto-detect output dimension
            int[] shape = interpreter.getOutputTensor(0).shape();
            outputDim = shape[shape.length - 1];
            isReady   = true;

            Log.d(TAG, "MobileFaceNet loaded — output dim: " + outputDim);
        } catch (Exception e) {
            Log.e(TAG, "Failed to load MobileFaceNet: " + e.getMessage(), e);
        }
    }

    public boolean isReady() { return isReady; }
    public int     getOutputDim() { return outputDim; }

    /**
     * Extract an L2-normalized face embedding from any-size bitmap.
     * Bitmap is resized to 112×112 internally.
     */
    public float[] getEmbedding(Bitmap bitmap) {
        if (!isReady || interpreter == null || bitmap == null) return null;

        try {
            Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);
            ByteBuffer buf = buildInputBuffer(resized, modeA);

            float[][] out = new float[1][outputDim];
            interpreter.run(buf, out);

            float[] embedding = l2Normalize(out[0]);

            // Sanity check: if the embedding is all-near-zero, switch normalization mode
            if (isDegenerate(embedding)) {
                Log.w(TAG, "Embedding degenerate with mode " + (modeA ? "A" : "B") + " — switching mode");
                modeA = !modeA;
                buf = buildInputBuffer(resized, modeA);
                interpreter.run(buf, out);
                embedding = l2Normalize(out[0]);
            }

            Log.d(TAG, "Embedding norm check — mode=" + (modeA ? "A[-1,1]" : "B[0,1]")
                    + " dim=" + embedding.length
                    + " first3=[" + embedding[0] + "," + embedding[1] + "," + embedding[2] + "]");

            return embedding;

        } catch (Exception e) {
            Log.e(TAG, "getEmbedding error: " + e.getMessage(), e);
            return null;
        }
    }

    /** Cosine similarity of two L2-normalized embeddings. Range [−1, 1]. */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        float dot = 0f;
        for (int i = 0; i < a.length; i++) dot += a[i] * b[i];
        return dot;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private ByteBuffer buildInputBuffer(Bitmap bmp, boolean useA) {
        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        bmp.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        ByteBuffer buf = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        buf.order(ByteOrder.nativeOrder());

        for (int pixel : pixels) {
            float r = (pixel >> 16) & 0xFF;
            float g = (pixel >>  8) & 0xFF;
            float b = (pixel       ) & 0xFF;
            if (useA) {
                // Mode A: (value − 127.5) / 128.0  →  [-1, 1]
                buf.putFloat((r - 127.5f) / 128.0f);
                buf.putFloat((g - 127.5f) / 128.0f);
                buf.putFloat((b - 127.5f) / 128.0f);
            } else {
                // Mode B: value / 255.0  →  [0, 1]
                buf.putFloat(r / 255.0f);
                buf.putFloat(g / 255.0f);
                buf.putFloat(b / 255.0f);
            }
        }
        return buf;
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

    /** Returns true if the embedding is useless (all near zero or uniform). */
    private boolean isDegenerate(float[] v) {
        float max = 0f, sum = 0f;
        for (float x : v) { sum += Math.abs(x); max = Math.max(max, Math.abs(x)); }
        return max < 0.001f || (sum / v.length) < 0.001f;
    }

    private MappedByteBuffer loadModelFile(AssetManager am) throws IOException {
        AssetFileDescriptor fd = am.openFd(MODEL_FILE);
        FileInputStream     is = new FileInputStream(fd.getFileDescriptor());
        FileChannel         fc = is.getChannel();
        return fc.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
        isReady = false;
    }
}
