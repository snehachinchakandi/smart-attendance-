package com.example.facedetectionapp.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.db.AttendanceEntity;
import com.example.facedetectionapp.repository.AttendanceRepository;

import java.util.List;

/**
 * Displays full attendance history sorted newest first.
 */
public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView     tvEmpty;
    private AttendanceRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        recyclerView = findViewById(R.id.recyclerView);
        tvEmpty      = findViewById(R.id.tvEmpty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        repository = new AttendanceRepository(this);

        // Back button
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        loadHistory();
    }

    private void loadHistory() {
        repository.getAllAttendance(list -> {
            if (list.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                tvEmpty.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
                recyclerView.setAdapter(new HistoryAdapter(list));
            }
        });
    }

    // ─── ADAPTER ─────────────────────────────────────────────────────────────

    static class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {

        private final List<AttendanceEntity> items;

        HistoryAdapter(List<AttendanceEntity> items) { this.items = items; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_attendance, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            AttendanceEntity e = items.get(position);
            h.tvName.setText(e.studentName);
            h.tvDateTime.setText(e.date + "  " + e.time);
            h.tvConfidence.setText(String.format("%.1f%%", e.confidence * 100f));

            if (e.className != null && !e.className.isEmpty()) {
                h.tvClassName.setText(e.className);
                h.tvClassName.setVisibility(View.VISIBLE);
            } else {
                h.tvClassName.setText("General Attendance");
                h.tvClassName.setVisibility(View.VISIBLE);
            }

            boolean present = "PRESENT".equals(e.status);
            h.tvStatus.setText(present ? "PRESENT" : "UNKNOWN");
            int bg = present ? 0xFF4CAF50 : 0xFFFF5722;
            h.tvStatus.setBackgroundColor(bg);
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvDateTime, tvClassName, tvStatus, tvConfidence;
            VH(View v) {
                super(v);
                tvName       = v.findViewById(R.id.tvName);
                tvDateTime   = v.findViewById(R.id.tvDateTime);
                tvClassName  = v.findViewById(R.id.tvClassName);
                tvStatus     = v.findViewById(R.id.tvStatus);
                tvConfidence = v.findViewById(R.id.tvConfidence);
            }
        }
    }
}
