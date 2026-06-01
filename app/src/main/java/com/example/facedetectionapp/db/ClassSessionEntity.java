package com.example.facedetectionapp.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.annotation.NonNull;

/**
 * Represents a scheduled class session.
 * e.g. "Mathematics – Section A" created on 28-05-2025 at 09:30
 */
@Entity(tableName = "class_sessions")
public class ClassSessionEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    @ColumnInfo(name = "class_id")
    public String classId;           // e.g. "CLS-1716880000000"

    @NonNull
    @ColumnInfo(name = "class_name")
    public String className;         // e.g. "Mathematics"

    @ColumnInfo(name = "subject")
    public String subject;           // e.g. "Algebra" (optional)

    @NonNull
    @ColumnInfo(name = "date")
    public String date;              // "dd-MM-yyyy"

    @NonNull
    @ColumnInfo(name = "time")
    public String time;              // "HH:mm"

    @ColumnInfo(name = "created_at")
    public long createdAt;           // millis for sorting

    @ColumnInfo(name = "status")
    public String status;            // "ACTIVE" | "CLOSED"

    public ClassSessionEntity() {}

    public ClassSessionEntity(@NonNull String classId, @NonNull String className,
                               String subject, @NonNull String date,
                               @NonNull String time, long createdAt, String status) {
        this.classId   = classId;
        this.className = className;
        this.subject   = subject;
        this.date      = date;
        this.time      = time;
        this.createdAt = createdAt;
        this.status    = status;
    }
}
