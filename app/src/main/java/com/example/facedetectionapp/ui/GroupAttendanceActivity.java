package com.example.facedetectionapp.ui;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.db.AttendanceEntity;
import com.example.facedetectionapp.db.StudentEntity;
import com.example.facedetectionapp.ml.FaceAligner;
import com.example.facedetectionapp.ml.FaceMatcher;
import com.example.facedetectionapp.ml.InsightFaceDetector;
import com.example.facedetectionapp.ml.InsightFaceRecognizer;
import com.example.facedetectionapp.repository.AttendanceRepository;
import com.example.facedetectionapp.ml.FaceServerClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import java.util.ArrayList;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class GroupAttendanceActivity extends AppCompatActivity {

    private static final String TAG = "GroupAttendanceActivity";
    private static final int REQ_CAMERA_PERMISSION = 201;
    private static final int REQ_TAKE_PHOTO  = 101;
    private static final int REQ_CHOOSE_GALLERY = 102;

    // Group photo ArcFace threshold — lenient to handle compression / distance
    private static final float GROUP_THRESHOLD = 0.30f;

    private TextView    tvClassDetails, tvStatDetected, tvStatPresent, tvStatUnknown;
    private View        placeholderLayout, listContainer, resultsActionRow;
    private CardView    cardStats;
    private ImageView   ivGroupImage;
    private ProgressBar progressBar;
    private Button      btnCamera, btnGallery, btnSave, btnExport;
    private RecyclerView recyclerDetected;

    // ── InsightFace models ────────────────────────────────────────────────────
    private InsightFaceDetector   detector;
    private InsightFaceRecognizer recognizer;
    private AttendanceRepository  repository;

    private List<StudentEntity> enrolledStudents = new ArrayList<>();
    private DetectedStudentsAdapter detectedStudentsAdapter;

    private String classSessionId = null;
    private String classLabel     = "Class Session";
    private Uri    tempPhotoUri;
    private File   tempPhotoFile;

    // ── DetectedFaceItem ─────────────────────────────────────────────────────
    public static class DetectedFaceItem {
        public final RectF  bbox;
        public final Bitmap faceCrop;
        public final String name, rollNumber;
        public final float  confidence;
        public final boolean isRecognized;
        public final StudentEntity student;

        public DetectedFaceItem(RectF bbox, Bitmap faceCrop, String name,
                                String rollNumber, float confidence,
                                boolean isRecognized, StudentEntity student) {
            this.bbox = bbox; this.faceCrop = faceCrop; this.name = name;
            this.rollNumber = rollNumber; this.confidence = confidence;
            this.isRecognized = isRecognized; this.student = student;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_group_attendance);

        tvClassDetails   = findViewById(R.id.tvClassDetails);
        tvStatDetected   = findViewById(R.id.tvStatDetected);
        tvStatPresent    = findViewById(R.id.tvStatPresent);
        tvStatUnknown    = findViewById(R.id.tvStatUnknown);
        placeholderLayout = findViewById(R.id.placeholderLayout);
        listContainer    = findViewById(R.id.listContainer);
        resultsActionRow = findViewById(R.id.resultsActionRow);
        cardStats        = findViewById(R.id.cardStats);
        ivGroupImage     = findViewById(R.id.ivGroupImage);
        progressBar      = findViewById(R.id.progressBar);
        btnCamera        = findViewById(R.id.btnCamera);
        btnGallery       = findViewById(R.id.btnGallery);
        btnSave          = findViewById(R.id.btnSave);
        btnExport        = findViewById(R.id.btnExport);
        recyclerDetected = findViewById(R.id.recyclerDetectedStudents);

        classSessionId = getIntent().getStringExtra(ClassSessionActivity.EXTRA_CLASS_ID);
        classLabel     = getIntent().getStringExtra(ClassSessionActivity.EXTRA_CLASS_NAME);
        if (classLabel == null) classLabel = "Group Attendance";
        tvClassDetails.setText(classLabel);

        repository = new AttendanceRepository(this);

        // Load InsightFace models on background thread to avoid ANR
        Executors.newSingleThreadExecutor().execute(() -> {
            detector   = new InsightFaceDetector(getAssets());
            recognizer = new InsightFaceRecognizer(getAssets());
            runOnUiThread(() -> {
                if (!detector.isReady() || !recognizer.isReady()) {
                    Toast.makeText(this,
                        "InsightFace models failed to load. " +
                        "Ensure det_500m.onnx and w600k_mbf.onnx are in assets/.",
                        Toast.LENGTH_LONG).show();
                }
            });
        });

        recyclerDetected.setLayoutManager(new LinearLayoutManager(this));
        detectedStudentsAdapter = new DetectedStudentsAdapter();
        recyclerDetected.setAdapter(detectedStudentsAdapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnServerSettings).setOnClickListener(v -> showServerSettingsDialog());
        btnCamera.setOnClickListener(v -> checkCameraAndCapture());
        btnGallery.setOnClickListener(v -> chooseFromGallery());
        btnSave.setOnClickListener(v -> saveAttendanceRecords());
        btnExport.setOnClickListener(v -> exportAndShareReport());

        loadStudents();
    }

    private void loadStudents() {
        repository.getAllStudents(list -> {
            enrolledStudents = list;
            if (list.isEmpty())
                Toast.makeText(this, "Warning: No registered students!", Toast.LENGTH_LONG).show();
            else
                Log.d(TAG, "Enrolled students: " + list.size());
        });
    }

    // ── Image capture ─────────────────────────────────────────────────────────

    private void checkCameraAndCapture() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) takePhoto();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int rc, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(rc, p, r);
        if (rc == REQ_CAMERA_PERMISSION && r.length > 0
                && r[0] == PackageManager.PERMISSION_GRANTED) takePhoto();
        else Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show();
    }

    private void takePhoto() {
        try {
            tempPhotoFile = File.createTempFile("group_", ".jpg", getCacheDir());
            tempPhotoUri  = FileProvider.getUriForFile(this,
                    getPackageName() + ".provider", tempPhotoFile);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, tempPhotoUri);
            startActivityForResult(intent, REQ_TAKE_PHOTO);
        } catch (Exception e) {
            Toast.makeText(this, "Camera error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void chooseFromGallery() {
        startActivityForResult(new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI), REQ_CHOOSE_GALLERY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;
        Uri uri = (requestCode == REQ_TAKE_PHOTO) ? tempPhotoUri
                : (data != null ? data.getData() : null);
        if (uri != null) processSelectedImage(uri);
    }

    // ── Bitmap loading ────────────────────────────────────────────────────────

    private void processSelectedImage(Uri uri) {
        progressBar.setVisibility(View.VISIBLE);
        placeholderLayout.setVisibility(View.GONE);
        ivGroupImage.setVisibility(View.GONE);
        cardStats.setVisibility(View.GONE);
        listContainer.setVisibility(View.GONE);
        resultsActionRow.setVisibility(View.GONE);
        btnSave.setEnabled(true);
        btnSave.setText("Save Attendance");

        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap bmp = loadScaledBitmap(uri);
            if (bmp == null) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    placeholderLayout.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Failed to load image.", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            runInsightFacePipeline(bmp);
        });
    }

    private Bitmap loadScaledBitmap(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, opts);
            if (in != null) in.close();

            int scale = 1;
            while (opts.outWidth / scale > 4096 || opts.outHeight / scale > 4096) scale *= 2;

            BitmapFactory.Options scaleOpts = new BitmapFactory.Options();
            scaleOpts.inSampleSize = scale;
            in = getContentResolver().openInputStream(uri);
            Bitmap bmp = BitmapFactory.decodeStream(in, null, scaleOpts);
            if (in != null) in.close();
            return rotateIfRequired(bmp, uri);
        } catch (Exception e) {
            Log.e(TAG, "loadScaledBitmap", e);
            return null;
        }
    }

    private Bitmap rotateIfRequired(Bitmap bmp, Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            ExifInterface exif = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && in != null)
                exif = new ExifInterface(in);
            if (in != null) in.close();
            if (exif == null) return bmp;
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            int deg = 0;
            if (ori == ExifInterface.ORIENTATION_ROTATE_90)  deg = 90;
            if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg == 0) return bmp;
            Matrix m = new Matrix(); m.postRotate(deg);
            Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            bmp.recycle();
            return rot;
        } catch (Exception e) { return bmp; }
    }

    // ── SharedPreferences & Settings ─────────────────────────────────────────

    private String getServerIp() {
        return getSharedPreferences("app_settings", MODE_PRIVATE).getString("server_ip", "");
    }

    private void saveServerIp(String ip) {
        getSharedPreferences("app_settings", MODE_PRIVATE).edit().putString("server_ip", ip).apply();
    }

    private void showServerSettingsDialog() {
        EditText input = new EditText(this);
        input.setHint("e.g. 192.168.1.100");
        input.setText(getServerIp());

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        container.setPadding(pad, pad/2, pad, pad/2);
        container.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("Hybrid Server IP Settings")
                .setMessage("Enter the IP address of your local Python server (port 8000). Leave empty to use strictly on-device processing.")
                .setView(container)
                .setPositiveButton("Save", (dialog, which) -> {
                    String ip = input.getText().toString().trim();
                    saveServerIp(ip);
                    Toast.makeText(this, "Server IP Saved!", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── InsightFace pipeline ──────────────────────────────────────────────────

    private void runInsightFacePipeline(Bitmap original) {
        String serverIp = getServerIp();
        if (!serverIp.isEmpty()) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.VISIBLE);
                Toast.makeText(this, "Contacting Hybrid Server...", Toast.LENGTH_SHORT).show();
            });

            FaceServerClient.recognizeGroup(serverIp, original, new FaceServerClient.RecognizeCallback() {
                @Override
                public void onSuccess(FaceServerClient.RecognizeResponse response) {
                    processServerRecognitionResponse(original, response);
                }

                @Override
                public void onFailure(Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(GroupAttendanceActivity.this,
                                "Server unreachable. Falling back to On-Device Offline Engine!",
                                Toast.LENGTH_LONG).show();
                        runInsightFacePipelineOffline(original);
                    });
                }
            });
        } else {
            runInsightFacePipelineOffline(original);
        }
    }

    private void processServerRecognitionResponse(Bitmap original, FaceServerClient.RecognizeResponse response) {
        List<DetectedFaceItem> items = new ArrayList<>();
        int presentCount = 0, unknownCount = 0;

        for (FaceServerClient.MatchItem match : response.matches) {
            if (match.bbox == null || match.bbox.size() < 4) continue;
            RectF bbox = new RectF(match.bbox.get(0), match.bbox.get(1), match.bbox.get(2), match.bbox.get(3));

            Bitmap displayCrop = FaceAligner.cropBox(original, bbox);

            if (match.is_recognized) {
                presentCount++;
                StudentEntity s = null;
                for (StudentEntity enrolled : enrolledStudents) {
                    if (enrolled.studentId.equals(match.student_id)) {
                        s = enrolled;
                        break;
                    }
                }
                if (s == null) {
                    s = new StudentEntity(match.student_id, match.name, match.roll_number, match.class_name, new float[512], null, "");
                }

                items.add(new DetectedFaceItem(bbox, displayCrop,
                        match.name, match.roll_number,
                        (float) match.similarity, true, s));
            } else {
                unknownCount++;
                items.add(new DetectedFaceItem(bbox, displayCrop,
                        "Unknown Face", "Not Registered",
                        (float) match.similarity, false, null));
            }
        }

        Bitmap annotated = drawBoundingBoxes(original, items);

        final int present = presentCount, unknown = unknownCount;
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            placeholderLayout.setVisibility(View.GONE);
            listContainer.setVisibility(View.VISIBLE);
            resultsActionRow.setVisibility(View.VISIBLE);
            cardStats.setVisibility(View.VISIBLE);
            ivGroupImage.setImageBitmap(annotated);
            ivGroupImage.setVisibility(View.VISIBLE);
            tvStatDetected.setText(String.valueOf(items.size()));
            tvStatPresent.setText(String.valueOf(present));
            tvStatUnknown.setText(String.valueOf(unknown));
            detectedStudentsAdapter.setData(items);
            Toast.makeText(this,
                    "Hybrid Server: Identified " + present + " of " + items.size() + " faces.",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void runInsightFacePipelineOffline(Bitmap original) {
        if (detector == null || !detector.isReady()
                || recognizer == null || !recognizer.isReady()) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                placeholderLayout.setVisibility(View.VISIBLE);
                Toast.makeText(this,
                        "InsightFace models not ready yet. " +
                        "Please ensure det_500m.onnx and w600k_mbf.onnx are in assets/.",
                        Toast.LENGTH_LONG).show();
            });
            return;
        }

        // 1. SCRFD detection
        List<InsightFaceDetector.FaceBox> faces = detector.detect(original, 0.30f);
        Log.d(TAG, "SCRFD detected " + faces.size() + " faces");

        if (faces.isEmpty()) {
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                placeholderLayout.setVisibility(View.VISIBLE);
                Toast.makeText(this,
                        "No faces detected. Ensure the photo is clear and well-lit.",
                        Toast.LENGTH_LONG).show();
            });
            return;
        }

        // 2. ArcFace recognition for each detected face
        List<DetectedFaceItem> items = new ArrayList<>();
        int presentCount = 0, unknownCount = 0;

        for (InsightFaceDetector.FaceBox faceBox : faces) {
            // Use padded bounding-box crop for ArcFace input (reliable, no warp bug)
            Bitmap aligned = FaceAligner.cropBox(original, faceBox.bbox);
            if (aligned == null) continue;

            float[] embedding = recognizer.getEmbedding(aligned);
            if (embedding == null) { aligned.recycle(); continue; }

            FaceMatcher.MatchResult match =
                    FaceMatcher.findBestMatch(embedding, enrolledStudents, GROUP_THRESHOLD);

            // Display crop: simple padded box crop for the thumbnail
            Bitmap displayCrop = FaceAligner.cropBox(original, faceBox.bbox);

            if (match.isRecognized) {
                presentCount++;
                items.add(new DetectedFaceItem(faceBox.bbox, displayCrop,
                        match.student.name, match.student.rollNumber,
                        match.similarity, true, match.student));
            } else {
                unknownCount++;
                items.add(new DetectedFaceItem(faceBox.bbox, displayCrop,
                        "Unknown Face", "Not Registered",
                        match.similarity, false, null));
            }
            aligned.recycle();
        }

        // 3. Annotate image
        Bitmap annotated = drawBoundingBoxes(original, items);

        final int present = presentCount, unknown = unknownCount;
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            placeholderLayout.setVisibility(View.GONE);
            listContainer.setVisibility(View.VISIBLE);
            resultsActionRow.setVisibility(View.VISIBLE);
            cardStats.setVisibility(View.VISIBLE);
            ivGroupImage.setImageBitmap(annotated);
            ivGroupImage.setVisibility(View.VISIBLE);
            tvStatDetected.setText(String.valueOf(items.size()));
            tvStatPresent.setText(String.valueOf(present));
            tvStatUnknown.setText(String.valueOf(unknown));
            detectedStudentsAdapter.setData(items);
            Toast.makeText(this,
                    "InsightFace: Identified " + present + " of " + items.size() + " faces.",
                    Toast.LENGTH_LONG).show();
        });
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private Bitmap drawBoundingBoxes(Bitmap original, List<DetectedFaceItem> items) {
        Bitmap out = original.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(out);

        float strokeW = Math.max(4f, out.getWidth() / 200f);
        float textSz  = Math.max(18f, out.getWidth() / 64f);
        float cornerL = strokeW * 6f;

        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(strokeW);

        Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(strokeW * 1.8f);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTextSize(textSz);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setColor(Color.WHITE);

        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);

        for (DetectedFaceItem face : items) {
            RectF rf   = face.bbox;
            Rect  r    = new Rect((int)rf.left,(int)rf.top,(int)rf.right,(int)rf.bottom);
            int   color = face.isRecognized ? 0xFF4CAF50 : 0xFFFF5722;

            boxPaint.setColor(color); boxPaint.setAlpha(80);
            canvas.drawRect(rf, boxPaint);

            cornerPaint.setColor(color);
            int l = r.left, t = r.top, right = r.right, b = r.bottom;
            canvas.drawLine(l, t, l + cornerL, t, cornerPaint);
            canvas.drawLine(l, t, l, t + cornerL, cornerPaint);
            canvas.drawLine(right, t, right - cornerL, t, cornerPaint);
            canvas.drawLine(right, t, right, t + cornerL, cornerPaint);
            canvas.drawLine(l, b, l + cornerL, b, cornerPaint);
            canvas.drawLine(l, b, l, b - cornerL, cornerPaint);
            canvas.drawLine(right, b, right - cornerL, b, cornerPaint);
            canvas.drawLine(right, b, right, b - cornerL, cornerPaint);

            String label  = face.isRecognized ? face.name : "Unknown";
            float  textW  = textPaint.measureText(label);
            float  textH  = textPaint.getTextSize();
            bgPaint.setColor(color);
            canvas.drawRoundRect(new RectF(l - strokeW/2f, t - textH - strokeW*2f,
                    l + textW + strokeW*2f, t), strokeW, strokeW, bgPaint);
            canvas.drawText(label, l + strokeW, t - strokeW * 1.5f, textPaint);
        }
        return out;
    }

    // ── Save / Export ─────────────────────────────────────────────────────────

    private void saveAttendanceRecords() {
        List<DetectedFaceItem> items = detectedStudentsAdapter.getItems();
        List<DetectedFaceItem> presentItems = new ArrayList<>();
        for (DetectedFaceItem it : items)
            if (it.isRecognized && it.student != null) presentItems.add(it);

        if (presentItems.isEmpty()) {
            Toast.makeText(this, "No recognized students to save.", Toast.LENGTH_LONG).show();
            return;
        }

        btnSave.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        String dateStr = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());
        String timeStr = new SimpleDateFormat("HH:mm:ss",   Locale.getDefault()).format(new Date());
        final int total = presentItems.size();
        AtomicInteger done = new AtomicInteger(0);

        for (DetectedFaceItem item : presentItems) {
            StudentEntity s = item.student;
            repository.insertAttendance(new AttendanceEntity(
                    s.studentId, s.name, dateStr, timeStr,
                    System.currentTimeMillis(), "PRESENT", item.confidence,
                    classSessionId, classLabel), id -> {
                if (done.incrementAndGet() == total) {
                    runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        btnSave.setText("Saved ✓");
                        Toast.makeText(this, "✓ Saved " + total + " students!", Toast.LENGTH_LONG).show();
                    });
                }
            });
        }
    }

    private void exportAndShareReport() {
        List<DetectedFaceItem> items = detectedStudentsAdapter.getItems();
        if (items.isEmpty()) {
            Toast.makeText(this, "No data to export.", Toast.LENGTH_SHORT).show();
            return;
        }
        String date = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());
        String time = new SimpleDateFormat("HH:mm:ss",   Locale.getDefault()).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("===== BATCH ATTENDANCE REPORT =====\n");
        sb.append("Class   : ").append(classLabel).append("\n");
        sb.append("Date    : ").append(date).append("\n");
        sb.append("Time    : ").append(time).append("\n");
        sb.append("Faces   : ").append(items.size()).append("\n");
        int presentCnt = 0;
        for (DetectedFaceItem it : items) if (it.isRecognized) presentCnt++;
        sb.append("Present : ").append(presentCnt).append("\n");
        sb.append("Unknown : ").append(items.size() - presentCnt).append("\n\n");
        sb.append("PRESENT STUDENTS:\n");
        int n = 1;
        for (DetectedFaceItem it : items) {
            if (it.isRecognized)
                sb.append(String.format(Locale.getDefault(), "%02d. %s | Roll: %s\n",
                        n++, it.name, it.rollNumber));
        }
        sb.append("\nGenerated by InsightFace Attendance System\n");

        String fname = "Attendance_" + classLabel.replaceAll("[^a-zA-Z0-9]", "_")
                + "_" + date + ".txt";
        try {
            File f = new File(getCacheDir(), fname);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(sb.toString().getBytes());
            fos.close();
            Uri shareUri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            saveToDownloads(fname, sb.toString());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, shareUri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Export Report"));
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveToDownloads(String fileName, String content) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os != null) { os.write(content.getBytes()); os.close(); }
                }
            } else {
                File f = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), fileName);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(content.getBytes()); fos.close();
            }
        } catch (Exception e) { Log.e(TAG, "saveToDownloads", e); }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector   != null) detector.close();
        if (recognizer != null) recognizer.close();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private static class DetectedStudentsAdapter
            extends RecyclerView.Adapter<DetectedStudentsAdapter.VH> {

        private final List<DetectedFaceItem> items = new ArrayList<>();

        void setData(List<DetectedFaceItem> data) {
            items.clear(); items.addAll(data); notifyDataSetChanged();
        }
        List<DetectedFaceItem> getItems() { return items; }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_detected_face, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            DetectedFaceItem it = items.get(pos);
            h.ivFace.setImageBitmap(it.faceCrop);
            h.tvName.setText(it.name);
            h.tvRoll.setText(it.rollNumber);
            if (it.student != null && it.student.className != null) {
                h.tvClass.setText(it.student.className);
                h.tvClass.setVisibility(View.VISIBLE);
            } else {
                h.tvClass.setVisibility(View.GONE);
            }
            h.tvConfidence.setText(String.format(Locale.getDefault(),
                    "%.1f%%", it.confidence * 100f));
            if (it.isRecognized) {
                h.tvStatusBadge.setText("PRESENT");
                h.tvStatusBadge.setBackgroundColor(0xFF4CAF50);
                h.tvConfidence.setTextColor(0xFF4CAF50);
            } else {
                h.tvStatusBadge.setText("UNKNOWN");
                h.tvStatusBadge.setBackgroundColor(0xFFFF5722);
                h.tvConfidence.setTextColor(0xFFFF5722);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            ImageView ivFace;
            TextView  tvName, tvRoll, tvClass, tvConfidence, tvStatusBadge;
            VH(View v) {
                super(v);
                ivFace        = v.findViewById(R.id.ivFaceCrop);
                tvName        = v.findViewById(R.id.tvStudentName);
                tvRoll        = v.findViewById(R.id.tvStudentRoll);
                tvClass       = v.findViewById(R.id.tvStudentClass);
                tvConfidence  = v.findViewById(R.id.tvConfidence);
                tvStatusBadge = v.findViewById(R.id.tvStatusBadge);
            }
        }
    }
}
