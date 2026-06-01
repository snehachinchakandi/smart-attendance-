package com.example.facedetectionapp.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface AttendanceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertAttendance(AttendanceEntity attendance);

    @Query("SELECT * FROM attendance ORDER BY timestamp_millis DESC")
    List<AttendanceEntity> getAllAttendance();

    @Query("SELECT * FROM attendance WHERE class_session_id = :classId ORDER BY timestamp_millis DESC")
    List<AttendanceEntity> getAttendanceByClass(String classId);

    @Query("SELECT * FROM attendance WHERE date = :date ORDER BY timestamp_millis DESC")
    List<AttendanceEntity> getAttendanceByDate(String date);

    @Query("SELECT COUNT(*) FROM attendance WHERE student_id = :studentId " +
           "AND class_session_id = :classId AND status = 'PRESENT'")
    int hasAttendanceForClass(String studentId, String classId);

    @Query("SELECT COUNT(*) FROM attendance WHERE date = :date AND status = 'PRESENT'")
    int getPresentCountByDate(String date);

    @Query("DELETE FROM attendance WHERE class_session_id = :classId")
    void deleteAttendanceByClass(String classId);

    @Query("DELETE FROM attendance")
    void deleteAllAttendance();
}
