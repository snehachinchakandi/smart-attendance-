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
import com.example.facedetectionapp.db.AttendanceEntity;
import com.example.facedetectionapp.db.StudentEntity;
import com.example.facedetectionapp.ml.FaceMatcher;
import com.example.facedetectionapp.ml.InsightFaceRecognizer;
import com.example.facedetectionapp.repository.AttendanceRepository;
import com.example.facedetectionapp.ui.overlay.FaceOverlayView;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import com.example.facedetectionapp.ui.ClassSessionActivity;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AttendanceActivity extends AppCompatActivity {

    private static final String TAG = "AttendanceActivity";
    private static final int CAM_RC = 102;

    private PreviewView     previewView;
    private FaceOverlayView overlayView;
    private Button          btnMark;
    private TextView        tvStatus, tvResult, tvConfidence;
    private ProgressBar     progressBar;
    private View            resultCard;

    private InsightFaceRecognizer  insightFaceRecognizer;
    private FaceDetector         faceDetector;
    private ExecutorService      analysisExecutor;
    private AttendanceRepository repository;

    private List<StudentEntity> enrolled = null;

    // Class session this attendance belongs to (set from Intent)
    private String classSessionId  = null;
    private String classLabel      = "Attendance";  // shown in top bar

    // Flag: when true, next frame with a face triggers attendance
    private volatile boolean wantCapture  = false;
    private volatile boolean faceDetected = false;
    private boolean          isProcessing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attendance);

        previewView  = findViewById(R.id.previewView);
        overlayView  = findViewById(R.id.overlayView);
        btnMark      = findViewById(R.id.btnMarkAttendance);
        tvStatus     = findViewById(R.id.tvStatus);
        tvResult     = findViewById(R.id.tvResult);
        tvConfidence = findViewById(R.id.tvConfidence);
        progressBar  = findViewById(R.id.progressBar);
        resultCard   = findViewById(R.id.resultCard);

        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);

        // Read class session from Intent (launched from ClassSessionActivity)
        classSessionId = getIntent().getStringExtra(ClassSessionActivity.EXTRA_CLASS_ID);
        classLabel     = getIntent().getStringExtra(ClassSessionActivity.EXTRA_CLASS_NAME);
        if (classLabel == null) classLabel = "Attendance";

        // Show class name in status bar
        updateStatus(classLabel, Color.WHITE);

        repository = new AttendanceRepository(this);
        Executors.newSingleThreadExecutor().execute(() ->
                insightFaceRecognizer = new InsightFaceRecognizer(getAssets()));
        analysisExecutor = Executors.newSingleThreadExecutor();

        faceDetector = FaceDetection.getClient(
            new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.15f)
                .build());

        btnMark.setOnClickListener(v -> onMarkClicked());

        loadStudents();
        checkPermission();
    }

    private void loadStudents() {
        repository.getAllStudents(list -> {
            enrolled = list;
            if (list.isEmpty()) {
                updateStatus("No students registered. Register first.", Color.RED);
                btnMark.setEnabled(false);
            } else {
                Log.d(TAG, "Loaded " + list.size() + " students");
            }
        });
    }

    // ── Button click (main thread) ────────────────────────────────────────────
    private void onMarkClicked() {
        if (isProcessing) return;
        if (enrolled == null || enrolled.isEmpty()) {
            Toast.makeText(this, "No registered students!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!faceDetected) {
            Toast.makeText(this, "No face detected – look at the camera.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        isProcessing = true;
        wantCapture  = true;
        btnMark.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.GONE);
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
    public void onRequestPermissionsResult(int rc,@NonNull String[] p,@NonNull int[] r){
        super.onRequestPermissionsResult(rc,p,r);
        if (rc==CAM_RC && r.length>0 && r[0]==PackageManager.PERMISSION_GRANTED) startCamera();
        else { Toast.makeText(this,"Camera required",Toast.LENGTH_LONG).show(); finish(); }
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
            } catch (ExecutionException | InterruptedException e) { Log.e(TAG, "Camera", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Analysis (background thread) ─────────────────────────────────────────
    @SuppressLint("UnsafeOptInUsageError")
    private void analyzeImage(ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null) { imageProxy.close(); return; }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        int imgW     = imageProxy.getWidth();
        int imgH     = imageProxy.getHeight();

        // KEY FIX: convert YUV→Bitmap here (background thread, imageProxy open)
        boolean doCapture = wantCapture;
        Bitmap  rawFrame  = doCapture ? yuvToBitmap(imageProxy) : null;

        InputImage inputImage = InputImage.fromMediaImage(mediaImage, rotation);

        faceDetector.process(inputImage)
            .addOnSuccessListener(faces -> {
                if (faces.isEmpty()) {
                    faceDetected = false;
                    overlayView.clearFaces();
                    if (!isProcessing)
                        updateStatus("Position your face in the frame", Color.WHITE);
                    if (doCapture) {
                        wantCapture  = false;
                        isProcessing = false;
                        runOnUiThread(() -> {
                            btnMark.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        });
                        updateStatus("Face lost – try again", Color.RED);
                    }
                } else {
                    Face largest  = getLargestFace(faces);
                    Rect bounds   = largest.getBoundingBox();
                    Rect faceRect = new Rect(
                            Math.max(bounds.left, 0),   Math.max(bounds.top, 0),
                            Math.min(bounds.right, imgW), Math.min(bounds.bottom, imgH));
                    faceDetected = true;
                    overlayView.setFace(mapToView(faceRect, rotation, imgW, imgH), true);
                    if (!doCapture)
                        updateStatus("Face detected – tap Mark Attendance", 0xFF4CAF50);

                    if (doCapture && rawFrame != null) {
                        wantCapture = false;
                        Bitmap face = cropAndOrient(rawFrame, faceRect, rotation);
                        updateStatus("Identifying…", Color.YELLOW);
                        Executors.newSingleThreadExecutor().execute(() -> identify(face));
                    }
                }
            })
            .addOnFailureListener(e -> Log.e(TAG, "Detection error", e))
            .addOnCompleteListener(t -> imageProxy.close());
    }

    // ── Identify (background thread) ─────────────────────────────────────────
    private void identify(Bitmap face) {
        try {
            if (insightFaceRecognizer == null || !insightFaceRecognizer.isReady())
                throw new Exception("ArcFace not ready — please wait a moment");
            float[] probe = insightFaceRecognizer.getEmbedding(face);
            if (probe == null) throw new Exception("Embedding failed");
            FaceMatcher.MatchResult match = FaceMatcher.findBestMatch(probe, enrolled);
            runOnUiThread(() -> showResult(match));
        } catch (Exception e) {
            Log.e(TAG, "identify error", e);
            new Handler(Looper.getMainLooper()).post(() -> {
                isProcessing = false;
                btnMark.setEnabled(true);
                progressBar.setVisibility(View.GONE);
                updateStatus("Error: " + e.getMessage(), Color.RED);
            });
        }
    }

    // ── Show result (main thread) ─────────────────────────────────────────────
    private void showResult(FaceMatcher.MatchResult match) {
        progressBar.setVisibility(View.GONE);
        isProcessing = false;
        btnMark.setEnabled(true);
        resultCard.setVisibility(View.VISIBLE);

        String today = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());
        String now   = new SimpleDateFormat("HH:mm:ss",   Locale.getDefault()).format(new Date());
        tvConfidence.setText(String.format(Locale.getDefault(),
                "Confidence: %.1f%%", match.similarity * 100f));

        if (!match.isRecognized) {
            tvResult.setText("⚠ Person Not Registered");
            tvResult.setTextColor(0xFFFF5722);
            updateStatus("Unknown – please register first.", Color.RED);
            repository.insertAttendance(new AttendanceEntity(
                    null, "UNKNOWN", today, now,
                    System.currentTimeMillis(), "UNKNOWN", match.similarity,
                    classSessionId, classLabel), id -> {});
            return;
        }

        StudentEntity s = match.student;

        // Duplicate check: per class session (not just per day)
        if (classSessionId != null) {
            repository.hasAttendanceForClass(s.studentId, classSessionId, alreadyMarked -> {
                if (alreadyMarked) {
                    tvResult.setText("ℹ Already Marked\n" + s.name);
                    tvResult.setTextColor(0xFFFF9800);
                    updateStatus(s.name + " already marked for this class.", Color.YELLOW);
                } else {
                    saveAttendance(s, today, now, match.similarity);
                }
            });
        } else {
            // No class session – fall back to saving without class
            saveAttendance(s, today, now, match.similarity);
        }
    }

    private void saveAttendance(StudentEntity s, String date, String time, float conf) {
        repository.insertAttendance(new AttendanceEntity(
                s.studentId, s.name, date, time,
                System.currentTimeMillis(), "PRESENT", conf,
                classSessionId, classLabel), id -> {
            tvResult.setText("✓ Marked Present!\n" + s.name
                    + "\nRoll: " + s.rollNumber + "  Class: " + s.className);
            tvResult.setTextColor(0xFF4CAF50);
            updateStatus("✓ " + s.name + " PRESENT", 0xFF4CAF50);
            Toast.makeText(this, "Attendance: " + s.name, Toast.LENGTH_LONG).show();
        });
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
        float sx = (float)vw/iW, sy = (float)vh/iH;
        return new Rect(vw-(int)(r.right*sx),(int)(r.top*sy),
                        vw-(int)(r.left*sx), (int)(r.bottom*sy));
    }

    private void updateStatus(String msg, int color) {
        runOnUiThread(() -> { tvStatus.setText(msg); tvStatus.setTextColor(color); });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        analysisExecutor.shutdown();
        if (faceDetector != null) faceDetector.close();
        if (insightFaceRecognizer != null) insightFaceRecognizer.close();
    }
}
