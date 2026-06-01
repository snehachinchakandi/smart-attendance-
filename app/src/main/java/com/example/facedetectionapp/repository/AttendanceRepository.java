package com.example.facedetectionapp.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.facedetectionapp.db.AppDatabase;
import com.example.facedetectionapp.db.AttendanceDao;
import com.example.facedetectionapp.db.AttendanceEntity;
import com.example.facedetectionapp.db.ClassSessionDao;
import com.example.facedetectionapp.db.ClassSessionEntity;
import com.example.facedetectionapp.db.StudentDao;
import com.example.facedetectionapp.db.StudentEntity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AttendanceRepository {

    private final StudentDao      studentDao;
    private final AttendanceDao   attendanceDao;
    private final ClassSessionDao classSessionDao;
    private final ExecutorService executor    = Executors.newFixedThreadPool(3);
    private final Handler         mainHandler = new Handler(Looper.getMainLooper());

    public interface Callback<T> { void onResult(T result); }

    public AttendanceRepository(Context context) {
        AppDatabase db   = AppDatabase.getInstance(context);
        studentDao       = db.studentDao();
        attendanceDao    = db.attendanceDao();
        classSessionDao  = db.classSessionDao();
    }

    // ── Students ──────────────────────────────────────────────────────────────

    public void insertStudent(StudentEntity s, Callback<Boolean> cb) {
        executor.execute(() -> { studentDao.insertStudent(s);
            mainHandler.post(() -> cb.onResult(true)); });
    }

    public void getAllStudents(Callback<List<StudentEntity>> cb) {
        executor.execute(() -> { List<StudentEntity> l = studentDao.getAllStudents();
            mainHandler.post(() -> cb.onResult(l)); });
    }

    public void getStudentCount(Callback<Integer> cb) {
        executor.execute(() -> { int c = studentDao.getStudentCount();
            mainHandler.post(() -> cb.onResult(c)); });
    }

    public void deleteStudent(String studentId, Callback<Boolean> cb) {
        executor.execute(() -> {
            studentDao.deleteStudent(studentId);
            mainHandler.post(() -> cb.onResult(true));
        });
    }

    // ── Attendance ────────────────────────────────────────────────────────────

    public void insertAttendance(AttendanceEntity a, Callback<Long> cb) {
        executor.execute(() -> { long id = attendanceDao.insertAttendance(a);
            mainHandler.post(() -> cb.onResult(id)); });
    }

    public void getAllAttendance(Callback<List<AttendanceEntity>> cb) {
        executor.execute(() -> { List<AttendanceEntity> l = attendanceDao.getAllAttendance();
            mainHandler.post(() -> cb.onResult(l)); });
    }

    public void getAttendanceByClass(String classId, Callback<List<AttendanceEntity>> cb) {
        executor.execute(() -> { List<AttendanceEntity> l = attendanceDao.getAttendanceByClass(classId);
            mainHandler.post(() -> cb.onResult(l)); });
    }

    /** Check if a student was already marked for a specific class session */
    public void hasAttendanceForClass(String studentId, String classId, Callback<Boolean> cb) {
        executor.execute(() -> { int n = attendanceDao.hasAttendanceForClass(studentId, classId);
            mainHandler.post(() -> cb.onResult(n > 0)); });
    }

    public void getPresentCountByDate(String date, Callback<Integer> cb) {
        executor.execute(() -> { int c = attendanceDao.getPresentCountByDate(date);
            mainHandler.post(() -> cb.onResult(c)); });
    }

    // ── Class Sessions ────────────────────────────────────────────────────────

    public void insertClass(ClassSessionEntity cls, Callback<Boolean> cb) {
        executor.execute(() -> { classSessionDao.insertClass(cls);
            mainHandler.post(() -> cb.onResult(true)); });
    }

    public void getAllClasses(Callback<List<ClassSessionEntity>> cb) {
        executor.execute(() -> { List<ClassSessionEntity> l = classSessionDao.getAllClasses();
            mainHandler.post(() -> cb.onResult(l)); });
    }

    public void getPresentCountForClass(String classId, Callback<Integer> cb) {
        executor.execute(() -> { int c = classSessionDao.getPresentCountForClass(classId);
            mainHandler.post(() -> cb.onResult(c)); });
    }

    public void updateClassStatus(String classId, String status, Callback<Boolean> cb) {
        executor.execute(() -> { classSessionDao.updateStatus(classId, status);
            mainHandler.post(() -> cb.onResult(true)); });
    }

    public void deleteClass(String classId, Callback<Boolean> cb) {
        executor.execute(() -> {
            classSessionDao.deleteClass(classId);
            attendanceDao.deleteAttendanceByClass(classId);
            mainHandler.post(() -> cb.onResult(true));
        });
    }
}
