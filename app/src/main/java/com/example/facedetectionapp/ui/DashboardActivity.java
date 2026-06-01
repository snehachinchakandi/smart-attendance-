package com.example.facedetectionapp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.facedetectionapp.R;
import com.example.facedetectionapp.repository.AttendanceRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Main dashboard – shows stats and navigation cards.
 */
public class DashboardActivity extends AppCompatActivity {

    private TextView tvDate, tvStudentCount, tvTodayCount;
    private AttendanceRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        repository = new AttendanceRepository(this);

        tvDate         = findViewById(R.id.tvDate);
        tvStudentCount = findViewById(R.id.tvStudentCount);
        tvTodayCount   = findViewById(R.id.tvTodayCount);

        CardView cardRegister   = findViewById(R.id.cardRegister);
        CardView cardAttendance = findViewById(R.id.cardAttendance);
        CardView cardHistory    = findViewById(R.id.cardHistory);
        CardView cardStudentStats = findViewById(R.id.cardStudentStats);

        // Show today's date
        String today = new SimpleDateFormat("EEEE, dd MMM yyyy", Locale.getDefault())
                .format(new Date());
        tvDate.setText(today);

        cardRegister.setOnClickListener(v ->
                startActivity(new Intent(this, RegisterActivity.class)));

        cardAttendance.setOnClickListener(v ->
                startActivity(new Intent(this, ClassSessionActivity.class)));

        cardHistory.setOnClickListener(v ->
                startActivity(new Intent(this, HistoryActivity.class)));

        cardStudentStats.setOnClickListener(v ->
                startActivity(new Intent(this, ManageStudentsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStats();
    }

    private void refreshStats() {
        String todayDate = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
                .format(new Date());

        repository.getStudentCount(count ->
                tvStudentCount.setText(String.valueOf(count)));

        repository.getPresentCountByDate(todayDate, count ->
                tvTodayCount.setText(String.valueOf(count)));
    }
}
