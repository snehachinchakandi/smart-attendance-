package com.example.facedetectionapp.ml;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FaceServerClient {

    private static final String TAG = "FaceServerClient";
    private static final OkHttpClient client = new OkHttpClient();
    private static final Gson gson = new Gson();

    public interface RegisterCallback {
        void onSuccess(String message);
        void onFailure(Exception e);
    }

    public interface RecognizeCallback {
        void onSuccess(RecognizeResponse response);
        void onFailure(Exception e);
    }

    // ── DATA MODELS FOR JSON PARSING ─────────────────────────────────────────
    public static class RecognizeResponse {
        public String status;
        public int detected_count;
        public List<MatchItem> matches;
    }

    public static class MatchItem {
        public List<Integer> bbox; // [x1, y1, x2, y2]
        public boolean is_recognized;
        public String name;
        public String student_id;
        public String roll_number;
        public String class_name;
        public double similarity;
    }

    // ── PUBLIC API ENDPOINTS ──────────────────────────────────────────────────

    /**
     * Upload student face photo to register on the server.
     */
    public static void registerStudent(
            String serverIp,
            String studentId,
            String name,
            String rollNumber,
            String className,
            Bitmap faceCrop,
            RegisterCallback callback
    ) {
        String url = "http://" + serverIp.trim() + ":8000/register";
        byte[] imgBytes = bitmapToBytes(faceCrop);

        RequestBody fileBody = RequestBody.create(imgBytes, MediaType.parse("image/jpeg"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("student_id", studentId)
                .addFormDataPart("name", name)
                .addFormDataPart("roll_number", rollNumber == null ? "" : rollNumber)
                .addFormDataPart("class_name", className == null ? "" : className)
                .addFormDataPart("file", "face.jpg", fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Registration request failed", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onFailure(new IOException("Server error: " + response.code() + " - " + response.message()));
                    return;
                }
                String body = response.body().string();
                callback.onSuccess(body);
            }
        });
    }

    /**
     * Upload group photo to detect and recognize all faces.
     */
    public static void recognizeGroup(
            String serverIp,
            Bitmap groupImage,
            RecognizeCallback callback
    ) {
        String url = "http://" + serverIp.trim() + ":8000/recognize";
        byte[] imgBytes = bitmapToBytes(groupImage);

        RequestBody fileBody = RequestBody.create(imgBytes, MediaType.parse("image/jpeg"));
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("threshold", "0.30")
                .addFormDataPart("file", "group.jpg", fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Recognition request failed", e);
                callback.onFailure(e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onFailure(new IOException("Server error: " + response.code() + " - " + response.message()));
                    return;
                }
                try {
                    String body = response.body().string();
                    Type type = new TypeToken<RecognizeResponse>() {}.getType();
                    RecognizeResponse res = gson.fromJson(body, type);
                    callback.onSuccess(res);
                } catch (Exception e) {
                    callback.onFailure(e);
                }
            }
        });
    }

    // ── PRIVATE HELPERS ──────────────────────────────────────────────────────

    private static byte[] bitmapToBytes(Bitmap bmp) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, stream);
        return stream.toByteArray();
    }
}
