package com.example.facedetectionapp.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.db.StudentEntity;
import com.example.facedetectionapp.ml.InsightFaceRecognizer;
import com.example.facedetectionapp.repository.AttendanceRepository;
import com.example.facedetectionapp.ml.FaceServerClient;
import com.example.facedetectionapp.ui.overlay.FaceOverlayView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";
    private static final int CAM_RC = 101;

    private PreviewView previewView;
    private FaceOverlayView overlayView;
    private EditText etName, etRoll, etClass;
    private Button btnCapture;
    private TextView tvStatus;
    private ImageView ivCaptured;
    private ProgressBar progressBar;

    private InsightFaceRecognizer insightFaceRecognizer;
    private FaceDetector faceDetector;
    private ExecutorService analysisExecutor;
    private AttendanceRepository repository;

    // Flag: when true, the next frame that has a face will be captured
    private volatile boolean wantCapture    = false;
    private volatile boolean faceDetected   = false;
    private boolean          isRegistering  = false;

    // Pending registration info (set on main thread, read on background)
    private String pendingName, pendingRoll, pendingCls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.overlayView);
        etName      = findViewById(R.id.etName);
        etRoll      = findViewById(R.id.etRoll);
        etClass     = findViewById(R.id.etClass);
        btnCapture  = findViewById(R.id.btnCapture);
        tvStatus    = findViewById(R.id.tvStatus);
        ivCaptured  = findViewById(R.id.ivCaptured);
        progressBar = findViewById(R.id.progressBar);

        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        repository = new AttendanceRepository(this);
        analysisExecutor = Executors.newSingleThreadExecutor();
        // Load ArcFace recognizer on background thread
        Executors.newSingleThreadExecutor().execute(() ->
                insightFaceRecognizer = new InsightFaceRecognizer(getAssets()));

        faceDetector = FaceDetection.getClient(
            new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.15f)
                .build());

        btnCapture.setOnClickListener(v -> onCaptureClicked());
        checkPermission();
    }

    // ── Button click (main thread) ────────────────────────────────────────────
    private void onCaptureClicked() {
        if (isRegistering) return;

        pendingName = etName.getText().toString().trim();
        pendingRoll = etRoll.getText().toString().trim();
        pendingCls  = etClass.getText().toString().trim();

        if (pendingName.isEmpty()) { etName.setError("Name required"); return; }
        if (pendingRoll.isEmpty()) { etRoll.setError("Roll required"); return; }
        if (!faceDetected) {
            Toast.makeText(this, "No face detected yet – position face in frame",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        isRegistering = true;
        wantCapture   = true;   // signal analyzeImage to capture next frame
        btnCapture.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        updateStatus("Hold still…", Color.YELLOW);
    }

    // ── Camera ────────────────────────────────────────────────────────────────
    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAM_RC);
    }

    @Override
    public void onRequestPermissionsResult(int rc,@NonNull String[] p,@NonNull int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (rc == CAM_RC && r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else { Toast.makeText(this,"Camera permission required",Toast.LENGTH_LONG).show(); finish(); }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider cp = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis ia = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                ia.setAnalyzer(analysisExecutor, this::analyzeImage);
                cp.unbindAll();
                cp.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, ia);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera error", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Analysis (runs on analysisExecutor background thread) ─────────────────
    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) { imageProxy.close(); return; }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int imgW     = imageProxy.getWidth();
        int imgH     = imageProxy.getHeight();

        // ── KEY FIX: convert YUV→Bitmap HERE (imageProxy still open, background thread)
        //    Only do this when a capture has been requested, to save CPU.
        boolean doCapture = wantCapture;
        Bitmap  rawFrame  = doCapture ? yuvToBitmap(imageProxy) : null;

        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);

        faceDetector.process(inputImage)
            .addOnSuccessListener(faces -> {
                if (faces.isEmpty()) {
                    faceDetected = false;
                    overlayView.clearFaces();
                    updateStatus("Position your face in the frame", Color.WHITE);
                    if (doCapture) {
                        // Face disappeared just as user tapped – reset and tell them
                        wantCapture   = false;
                        isRegistering = false;
                        runOnUiThread(() -> {
                            btnCapture.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        });
                        updateStatus("Face lost – please try again", Color.RED);
                    }
                } else {
                    Face largest  = getLargestFace(faces);
                    Rect bounds   = largest.getBoundingBox();
                    Rect faceRect = new Rect(
                            Math.max(bounds.left,   0), Math.max(bounds.top,    0),
                            Math.min(bounds.right, imgW), Math.min(bounds.bottom, imgH));
                    faceDetected = true;
                    overlayView.setFace(mapToView(faceRect, rotation, imgW, imgH), true);

                    if (!doCapture)
                        updateStatus("✓ Face detected – tap Capture to register", 0xFF4CAF50);

                    if (doCapture && rawFrame != null) {
                        wantCapture = false;
                        Bitmap face = cropAndOrient(rawFrame, faceRect, rotation);
                        updateStatus("Extracting features…", Color.YELLOW);
                        // Run embedding + save on background thread
                        Executors.newSingleThreadExecutor().execute(
                                () -> saveStudent(face));
                    }
                }
            })
            .addOnFailureListener(e -> Log.e(TAG, "Detection failed", e))
            .addOnCompleteListener(t -> imageProxy.close());
    }

    // ── Save student (background thread) ─────────────────────────────────────
    private void saveStudent(Bitmap faceBitmap) {
        try {
            if (insightFaceRecognizer == null || !insightFaceRecognizer.isReady())
                throw new Exception("ArcFace model not ready yet — please wait a moment and try again");
            float[] embedding = insightFaceRecognizer.getEmbedding(faceBitmap);
            if (embedding == null) throw new Exception("Embedding extraction failed");

            String id  = "STU-" + System.currentTimeMillis();
            String ts  = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.getDefault()).format(new Date());
            StudentEntity s = new StudentEntity(
                    id, pendingName, pendingRoll,
                    pendingCls.isEmpty() ? "N/A" : pendingCls,
                    embedding, null, ts);

            repository.insertStudent(s, ok -> {
                progressBar.setVisibility(View.GONE);
                isRegistering = false;
                btnCapture.setEnabled(true);
                if (ok) {
                    ivCaptured.setImageBitmap(faceBitmap);
                    updateStatus("✓ " + pendingName + " registered successfully!", 0xFF4CAF50);
                    Toast.makeText(this, "Registered: " + pendingName, Toast.LENGTH_LONG).show();
                    etName.setText(""); etRoll.setText(""); etClass.setText("");

                    // Upload to Hybrid Server if configured
                    String serverIp = getSharedPreferences("app_settings", MODE_PRIVATE).getString("server_ip", "");
                    if (!serverIp.isEmpty()) {
                        FaceServerClient.registerStudent(serverIp, id, pendingName, pendingRoll, pendingCls, faceBitmap, new FaceServerClient.RegisterCallback() {
                            @Override
                            public void onSuccess(String message) {
                                runOnUiThread(() -> Toast.makeText(RegisterActivity.this, "Synced with Hybrid Server!", Toast.LENGTH_SHORT).show());
                            }

                            @Override
                            public void onFailure(Exception e) {
                                runOnUiThread(() -> Toast.makeText(RegisterActivity.this, "Server sync failed (saved locally): " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        });
                    }
                } else {
                    updateStatus("DB save failed. Try again.", Color.RED);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "saveStudent error", e);
            new Handler(Looper.getMainLooper()).post(() -> {
                progressBar.setVisibility(View.GONE);
                isRegistering = false;
                btnCapture.setEnabled(true);
                updateStatus("Error: " + e.getMessage(), Color.RED);
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    @SuppressLint("UnsafeOptInUsageError")
    private Bitmap yuvToBitmap(ImageProxy proxy) {
        try {
            return proxy.toBitmap();
        } catch (Exception e) {
            Log.e(TAG, "yuvToBitmap error: " + e.getMessage(), e);
            return null;
        }
    }

    private Bitmap rotateBitmap(Bitmap source, float angle) {
        if (angle == 0 || source == null) return source;
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        source.recycle();
        return rotated;
    }

    private Bitmap cropAndOrient(Bitmap raw, Rect face, int rotation) {
        if (raw == null) return null;
        
        // 1. Rotate the raw landscape bitmap upright to portrait first
        Bitmap upright = rotateBitmap(raw, rotation);
        if (upright == null) return null;
        
        // 2. Crop the face from the upright bitmap using ML Kit's upright portrait coordinates
        int cx = face.centerX(), cy = face.centerY();
        int sz = (int)(Math.max(face.width(), face.height()) * 1.4f);
        int l  = Math.max(0, cx - sz/2), t = Math.max(0, cy - sz/2);
        int r  = Math.min(upright.getWidth(), cx + sz/2);
        int b  = Math.min(upright.getHeight(), cy + sz/2);
        
        if (r-l <= 0 || b-t <= 0) return upright;
        Bitmap crop = Bitmap.createBitmap(upright, l, t, r-l, b-t);
        upright.recycle();
        
        return crop;
    }

    private Face getLargestFace(List<Face> faces) {
        Face best = faces.get(0);
        for (Face f : faces)
            if (f.getBoundingBox().width() > best.getBoundingBox().width()) best = f;
        return best;
    }

    private Rect mapToView(Rect r, int rot, int iw, int ih) {
        int vw = overlayView.getWidth(), vh = overlayView.getHeight();
        if (vw == 0 || vh == 0) return r;
        int iW = rot % 180 == 0 ? iw : ih;
        int iH = rot % 180 == 0 ? ih : iw;
        float sx = (float) vw / iW, sy = (float) vh / iH;
        return new Rect(vw-(int)(r.right*sx), (int)(r.top*sy),
                        vw-(int)(r.left*sx),  (int)(r.bottom*sy));
    }

    private void updateStatus(String msg, int color) {
        runOnUiThread(() -> { tvStatus.setText(msg); tvStatus.setTextColor(color); });
    }

    public void onBackClick(View v) { finish(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
        if (insightFaceRecognizer != null) insightFaceRecognizer.close();
    }
}
