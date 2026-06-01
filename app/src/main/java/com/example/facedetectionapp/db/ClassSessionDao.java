package com.example.facedetectionapp.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ClassSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertClass(ClassSessionEntity session);

    @Query("SELECT * FROM class_sessions ORDER BY created_at DESC")
    List<ClassSessionEntity> getAllClasses();

    @Query("SELECT * FROM class_sessions WHERE class_id = :classId LIMIT 1")
    ClassSessionEntity getClassById(String classId);

    @Query("UPDATE class_sessions SET status = :status WHERE class_id = :classId")
    void updateStatus(String classId, String status);

    @Query("DELETE FROM class_sessions WHERE class_id = :classId")
    void deleteClass(String classId);

    /** Count how many PRESENT records exist for a given class session */
    @Query("SELECT COUNT(*) FROM attendance WHERE class_session_id = :classId AND status = 'PRESENT'")
    int getPresentCountForClass(String classId);
}
