package com.example.facedetectionapp.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.db.ClassSessionEntity;
import com.example.facedetectionapp.repository.AttendanceRepository;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Shows all class sessions and lets the user:
 *  - Create a new class (name + optional subject → date/time auto-set to now)
 *  - Tap a class to open the attendance camera for that class
 *  - Long-press a class to delete it
 */
public class ClassSessionActivity extends AppCompatActivity {

    public static final String EXTRA_CLASS_ID   = "class_id";
    public static final String EXTRA_CLASS_NAME = "class_name";

    private RecyclerView         recyclerView;
    private TextView             tvEmpty;
    private FloatingActionButton fab;
    private AttendanceRepository repository;
    private ClassAdapter         adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_class_session);

        recyclerView = findViewById(R.id.recyclerClasses);
        tvEmpty      = findViewById(R.id.tvEmpty);
        fab          = findViewById(R.id.fabAddClass);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        repository = new AttendanceRepository(this);
        adapter    = new ClassAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        fab.setOnClickListener(v -> showAddClassDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadClasses();
    }

    private void loadClasses() {
        repository.getAllClasses(list -> {
            adapter.setData(list);
            tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
            recyclerView.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    private void showAddClassDialog() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_add_class, null);
        EditText etName    = dialogView.findViewById(R.id.etClassName);
        EditText etSubject = dialogView.findViewById(R.id.etSubject);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Create New Class")
                .setView(dialogView)
                .setPositiveButton("Create", (dlg, w) -> {
                    String name    = etName.getText().toString().trim();
                    String subject = etSubject.getText().toString().trim();
                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, "Class name is required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    createClass(name, subject);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createClass(String name, String subject) {
        String date = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(new Date());
        String time = new SimpleDateFormat("HH:mm",      Locale.getDefault()).format(new Date());
        String id   = "CLS-" + System.currentTimeMillis();

        ClassSessionEntity cls = new ClassSessionEntity(
                id, name, subject.isEmpty() ? null : subject,
                date, time, System.currentTimeMillis(), "ACTIVE");

        repository.insertClass(cls, ok -> {
            if (ok) {
                Toast.makeText(this, "Class created: " + name, Toast.LENGTH_SHORT).show();
                loadClasses();
            }
        });
    }

    private void openAttendance(ClassSessionEntity cls) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_attendance_mode, null);
        AlertDialog dialog = new AlertDialog.Builder(this, R.style.DialogTheme)
                .setView(dialogView)
                .create();

        View cardLive = dialogView.findViewById(R.id.cardLiveCamera);
        View cardGroup = dialogView.findViewById(R.id.cardGroupPhoto);
        TextView tvClassName = dialogView.findViewById(R.id.tvDialogClassName);

        tvClassName.setText(cls.className + (cls.subject != null ? " – " + cls.subject : ""));

        cardLive.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, AttendanceActivity.class);
            intent.putExtra(EXTRA_CLASS_ID,   cls.classId);
            intent.putExtra(EXTRA_CLASS_NAME, cls.className
                    + (cls.subject != null ? " – " + cls.subject : "")
                    + "  (" + cls.date + " " + cls.time + ")");
            startActivity(intent);
        });

        cardGroup.setOnClickListener(v -> {
            dialog.dismiss();
            Intent intent = new Intent(this, GroupAttendanceActivity.class);
            intent.putExtra(EXTRA_CLASS_ID,   cls.classId);
            intent.putExtra(EXTRA_CLASS_NAME, cls.className
                    + (cls.subject != null ? " – " + cls.subject : "")
                    + "  (" + cls.date + " " + cls.time + ")");
            startActivity(intent);
        });

        dialog.show();
    }

    private void confirmDelete(ClassSessionEntity cls) {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Delete Class?")
                .setMessage("Delete \"" + cls.className + "\" and all its attendance records?")
                .setPositiveButton("Delete", (d, w) ->
                        repository.deleteClass(cls.classId, ok -> {
                            Toast.makeText(this, "Class deleted", Toast.LENGTH_SHORT).show();
                            loadClasses();
                        }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    class ClassAdapter extends RecyclerView.Adapter<ClassAdapter.VH> {

        private final List<ClassSessionEntity> items = new ArrayList<>();

        void setData(List<ClassSessionEntity> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_class_session, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            ClassSessionEntity cls = items.get(pos);
            h.tvName.setText(cls.className);
            h.tvSubject.setText(cls.subject != null ? cls.subject : "No subject");
            h.tvDateTime.setText(cls.date + "  " + cls.time);

            // Load present count for this class
            repository.getPresentCountForClass(cls.classId, count ->
                    h.tvCount.setText(count + " present"));

            boolean active = "ACTIVE".equals(cls.status);
            h.tvStatus.setText(active ? "ACTIVE" : "CLOSED");
            h.tvStatus.setBackgroundColor(active ? 0xFF4CAF50 : 0xFF9E9E9E);

            h.itemView.setOnClickListener(v -> openAttendance(cls));
            h.itemView.setOnLongClickListener(v -> { confirmDelete(cls); return true; });
        }

        @Override public int getItemCount() { return items.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvSubject, tvDateTime, tvCount, tvStatus;
            VH(View v) {
                super(v);
                tvName     = v.findViewById(R.id.tvClassName);
                tvSubject  = v.findViewById(R.id.tvSubject);
                tvDateTime = v.findViewById(R.id.tvDateTime);
                tvCount    = v.findViewById(R.id.tvCount);
                tvStatus   = v.findViewById(R.id.tvStatus);
            }
        }
    }
}
