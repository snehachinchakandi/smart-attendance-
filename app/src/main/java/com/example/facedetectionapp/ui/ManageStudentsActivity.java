package com.example.facedetectionapp.ui;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.db.StudentEntity;
import com.example.facedetectionapp.repository.AttendanceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ManageStudentsActivity extends AppCompatActivity {

    private EditText etSearch;
    private RecyclerView recyclerStudents;
    private View emptyStateView;

    private AttendanceRepository repository;
    private StudentsAdapter adapter;
    private List<StudentEntity> allStudentsList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_students);

        etSearch = findViewById(R.id.etSearch);
        recyclerStudents = findViewById(R.id.recyclerStudents);
        emptyStateView = findViewById(R.id.emptyStateView);

        repository = new AttendanceRepository(this);

        // Setup Recycler
        recyclerStudents.setLayoutManager(new LinearLayoutManager(this));
        adapter = new StudentsAdapter();
        recyclerStudents.setAdapter(adapter);

        // Listeners
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterStudents(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // Load data
        loadStudents();
    }

    private void loadStudents() {
        repository.getAllStudents(list -> {
            allStudentsList.clear();
            allStudentsList.addAll(list);
            filterStudents(etSearch.getText().toString());
        });
    }

    private void filterStudents(String query) {
        String cleanQuery = query.trim().toLowerCase(Locale.getDefault());
        List<StudentEntity> filtered = new ArrayList<>();

        for (StudentEntity student : allStudentsList) {
            boolean matchesName = student.name != null && student.name.toLowerCase(Locale.getDefault()).contains(cleanQuery);
            boolean matchesRoll = student.rollNumber != null && student.rollNumber.toLowerCase(Locale.getDefault()).contains(cleanQuery);
            if (matchesName || matchesRoll) {
                filtered.add(student);
            }
        }

        adapter.setData(filtered);

        if (filtered.isEmpty()) {
            emptyStateView.setVisibility(View.VISIBLE);
            recyclerStudents.setVisibility(View.GONE);
        } else {
            emptyStateView.setVisibility(View.GONE);
            recyclerStudents.setVisibility(View.VISIBLE);
        }
    }

    private void confirmDelete(StudentEntity student) {
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Delete Student Details")
                .setMessage("Are you sure you want to delete all registration details and face embeddings for \"" + student.name + "\"?\n\nThis action cannot be undone.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    repository.deleteStudent(student.studentId, success -> {
                        if (success) {
                            Toast.makeText(this, student.name + " deleted successfully.", Toast.LENGTH_SHORT).show();
                            loadStudents(); // reload database list
                        } else {
                            Toast.makeText(this, "Failed to delete student.", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── ADAPTER ──────────────────────────────────────────────────────────────

    private class StudentsAdapter extends RecyclerView.Adapter<StudentsAdapter.VH> {

        private final List<StudentEntity> items = new ArrayList<>();

        public void setData(List<StudentEntity> data) {
            items.clear();
            items.addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_manage_student, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            StudentEntity student = items.get(pos);
            h.tvName.setText(student.name);
            h.tvRoll.setText("Roll No: " + (student.rollNumber != null ? student.rollNumber : "N/A"));
            h.tvClass.setText("Class/Section: " + (student.className != null ? student.className : "N/A"));
            h.tvRegistered.setText("Registered: " + (student.registeredAt != null ? student.registeredAt : "N/A"));

            h.btnDelete.setOnClickListener(v -> confirmDelete(student));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvRoll, tvClass, tvRegistered;
            ImageButton btnDelete;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvStudentName);
                tvRoll = v.findViewById(R.id.tvStudentRoll);
                tvClass = v.findViewById(R.id.tvStudentClass);
                tvRegistered = v.findViewById(R.id.tvRegisteredDate);
                btnDelete = v.findViewById(R.id.btnDeleteStudent);
            }
        }
    }
}
