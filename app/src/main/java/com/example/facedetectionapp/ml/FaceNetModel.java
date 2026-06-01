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
 * Wraps the FaceNet TFLite model to produce 128-dimensional face embeddings.
 *
 * Preprocessing:
 *   - Resize to 160×160
 *   - Normalize pixels: (pixel - 127.5) / 128.0  (FaceNet standard)
 *
 * The model outputs a 1×128 float array. We L2-normalize it so that
 * cosine similarity is simply the dot product.
 */
public class FaceNetModel {

    private static final String TAG        = "FaceNetModel";
    private static final String MODEL_FILE = "facenet.tflite";
    private static final int    INPUT_SIZE = 160;
    private static final int    EMBED_DIM  = 128;

    private Interpreter interpreter;
    private boolean isReady = false;

    public FaceNetModel(AssetManager assetManager) {
        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            interpreter = new Interpreter(loadModelFile(assetManager), options);
            isReady = true;
            Log.d(TAG, "FaceNet model loaded successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load FaceNet model: " + e.getMessage(), e);
        }
    }

    public boolean isReady() {
        return isReady;
    }

    /**
     * Extract a 128-d L2-normalized embedding from a face crop.
     * Input bitmap may be any size; it will be resized to 160×160.
     */
    public float[] getEmbedding(Bitmap bitmap) {
        if (!isReady || interpreter == null) return null;

        // 1. Resize
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true);

        // 2. Build ByteBuffer: 1 × 160 × 160 × 3 × float (4 bytes)
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                1 * INPUT_SIZE * INPUT_SIZE * 3 * 4);
        inputBuffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[INPUT_SIZE * INPUT_SIZE];
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE);

        for (int pixel : pixels) {
            // FaceNet normalization: (value - 127.5) / 128.0
            inputBuffer.putFloat((((pixel >> 16) & 0xFF) - 127.5f) / 128.0f); // R
            inputBuffer.putFloat((((pixel >>  8) & 0xFF) - 127.5f) / 128.0f); // G
            inputBuffer.putFloat((((pixel       ) & 0xFF) - 127.5f) / 128.0f); // B
        }

        // 3. Run inference
        float[][] output = new float[1][EMBED_DIM];
        interpreter.run(inputBuffer, output);

        // 4. L2-normalize the embedding
        return l2Normalize(output[0]);
    }

    /**
     * Cosine similarity between two L2-normalized embeddings.
     * Range: [-1, 1]. Threshold ≥ 0.55 is a good "same person" cutoff.
     */
    public static float cosineSimilarity(float[] emb1, float[] emb2) {
        if (emb1 == null || emb2 == null || emb1.length != emb2.length) return -1f;
        float dot = 0f;
        for (int i = 0; i < emb1.length; i++) {
            dot += emb1[i] * emb2[i];
        }
        // Since both are already L2-normalized, dot product == cosine similarity
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
        AssetFileDescriptor fileDescriptor = assetManager.openFd(MODEL_FILE);
        FileInputStream     inputStream    = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel         fileChannel    = inputStream.getChannel();
        long startOffset    = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}
