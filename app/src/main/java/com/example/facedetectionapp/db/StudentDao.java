package com.example.facedetectionapp.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for Students table.
 */
@Dao
public interface StudentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertStudent(StudentEntity student);

    @Query("SELECT * FROM students ORDER BY registered_at DESC")
    List<StudentEntity> getAllStudents();

    @Query("SELECT * FROM students WHERE student_id = :studentId LIMIT 1")
    StudentEntity getStudentById(String studentId);

    @Query("SELECT * FROM students WHERE name LIKE :name LIMIT 1")
    StudentEntity getStudentByName(String name);

    @Query("SELECT COUNT(*) FROM students")
    int getStudentCount();

    @Query("DELETE FROM students WHERE student_id = :studentId")
    void deleteStudent(String studentId);

    @Query("DELETE FROM students")
    void deleteAllStudents();
}
