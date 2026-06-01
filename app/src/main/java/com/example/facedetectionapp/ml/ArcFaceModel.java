package com.example.facedetectionapp.ml;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;

import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Wraps the ArcFace / MobileFaceNet TFLite model to produce 512-dimensional
 * L2-normalized face embeddings, significantly more accurate than FaceNet 128-d.
 *
 * Model: arcface.tflite (mobilesec/arcface-tensorflowlite)
 * Input : 1 × 112 × 112 × 3  float32, normalized to [-1, 1]
 * Output: 1 × 512             float32 (L2-normalized embedding)
 *
 * Similarity: cosine similarity ≥ 0.40 → same person (ArcFace space)
 */
public class ArcFaceModel {

    private static final String TAG        = "ArcFaceModel";
    private static final String MODEL_FILE = "arcface.tflite";
    private static final int    INPUT_SIZE = 112;   // ArcFace canonical input
    public  static final int    EMBED_DIM  = 512;   // ArcFace embedding dimension

    private Interpreter interpreter;
    private boolean isReady = false;

    public ArcFaceModel(AssetManager assetManager) {
        try {
            Interpreter.Options opts = new Interpreter.Options();
            opts.setNumThreads(4);
            interpreter = new Interpreter(loadModelFile(assetManager), opts);
            isReady = true;
            Log.d(TAG, "ArcFace model loaded successfully (512-d embeddings)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ArcFace model: " + e.getMessage(), e);
        }
    }

    public boolean isReady() { return isReady; }

    /**
     * Extract a 512-d L2-normalized embedding from a face crop Bitmap.
     * Input may be any size — it will be resized to 112×112.
     */
    public float[] getEmbedding(Bitmap bitmap) {
        if (!isReady || interpreter == null || bitmap == null) return null;

        // 1. Resize to 112×112
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Build input ByteBuffer: 1 × 112 × 112 × 3 × float (4 bytes)
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            // ArcFace normalization: value / 127.5 - 1.0  →  range [-1, 1]
            inputBuffer.putFloat((((pixel >> 16) & 0xFF) / 127.5f) - 1.0f); // R
            inputBuffer.putFloat((((pixel >>  8) & 0xFF) / 127.5f) - 1.0f); // G
            inputBuffer.putFloat((((pixel       ) & 0xFF) / 127.5f) - 1.0f); // B
        }

        // 3. Run inference
        float[][] output = new float[1][EMBED_DIM];
        interpreter.run(inputBuffer, output);

        // 4. L2-normalize so cosine similarity == dot product
        return l2Normalize(output[0]);
    }

    /**
     * Cosine similarity between two L2-normalized 512-d ArcFace embeddings.
     * Range [-1, 1]. Threshold ≥ 0.40 is a strong "same person" signal in ArcFace space.
     */
    public static float cosineSimilarity(float[] emb1, float[] emb2) {
        if (emb1 == null || emb2 == null || emb1.length != emb2.length) return -1f;
        float dot = 0f;
        for (int i = 0; i < emb1.length; i++) {
            dot += emb1[i] * emb2[i];
        }
        return dot;
    }

    private float[] l2Normalize(float[] vector) {
        float norm = 0f;
        for (float v : vector) norm += v * v;
        norm = (float) Math.sqrt(norm);
        if (norm == 0f) return vector;
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager) throws IOException {
        AssetFileDescriptor fd = assetManager.openFd(MODEL_FILE);
        FileInputStream     is = new FileInputStream(fd.getFileDescriptor());
        FileChannel         fc = is.getChannel();
        return fc.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
        isReady = false;
    }
}
