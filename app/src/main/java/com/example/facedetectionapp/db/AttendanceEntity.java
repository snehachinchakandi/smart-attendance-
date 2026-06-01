package com.example.facedetectionapp.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.annotation.NonNull;

@Entity(tableName = "attendance")
public class AttendanceEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @ColumnInfo(name = "student_id", index = true)
    public String studentId;

    @NonNull
    @ColumnInfo(name = "student_name")
    public String studentName;

    @NonNull
    @ColumnInfo(name = "date")
    public String date;

    @NonNull
    @ColumnInfo(name = "time")
    public String time;

    @ColumnInfo(name = "timestamp_millis")
    public long timestampMillis;

    @ColumnInfo(name = "status")
    public String status;               // "PRESENT" | "UNKNOWN"

    @ColumnInfo(name = "confidence")
    public float confidence;

    /** Links this record to a class session. Null = legacy record (no class). */
    @ColumnInfo(name = "class_session_id", index = true)
    public String classSessionId;

    /** Human-readable class name, denormalised for display. */
    @ColumnInfo(name = "class_name")
    public String className;

    public AttendanceEntity() {}

    public AttendanceEntity(String studentId, @NonNull String studentName,
                             @NonNull String date, @NonNull String time,
                             long timestampMillis, String status, float confidence,
                             String classSessionId, String className) {
        this.studentId      = studentId;
        this.studentName    = studentName;
        this.date           = date;
        this.time           = time;
        this.timestampMillis = timestampMillis;
        this.status         = status;
        this.confidence     = confidence;
        this.classSessionId = classSessionId;
        this.className      = className;
    }
}
