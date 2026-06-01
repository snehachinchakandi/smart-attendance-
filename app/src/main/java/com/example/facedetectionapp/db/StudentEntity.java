package com.example.facedetectionapp.db;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;
import androidx.room.Index;
import androidx.annotation.NonNull;

/**
 * Represents a registered student with their face embedding vector.
 * The embedding is a 128-dimensional float array from FaceNet.
 */
@Entity(
    tableName = "students",
    indices = {@Index(value = {"student_id"}, unique = true)}
)
public class StudentEntity {

    @PrimaryKey(autoGenerate = true)
    public int id;

    @NonNull
    @ColumnInfo(name = "student_id")
    public String studentId;           // e.g. "STU-001"

    @NonNull
    @ColumnInfo(name = "name")
    public String name;

    @ColumnInfo(name = "roll_number")
    public String rollNumber;

    @ColumnInfo(name = "class_name")
    public String className;

    @ColumnInfo(name = "embedding")
    public float[] embedding;          // FaceNet 128-d vector

    @ColumnInfo(name = "photo_path")
    public String photoPath;           // path to saved face photo

    @ColumnInfo(name = "registered_at")
    public String registeredAt;        // ISO timestamp

    public StudentEntity() {}

    public StudentEntity(
            @NonNull String studentId,
            @NonNull String name,
            String rollNumber,
            String className,
            float[] embedding,
            String photoPath,
            String registeredAt) {
        this.studentId   = studentId;
        this.name        = name;
        this.rollNumber  = rollNumber;
        this.className   = className;
        this.embedding   = embedding;
        this.photoPath   = photoPath;
        this.registeredAt = registeredAt;
    }
}
