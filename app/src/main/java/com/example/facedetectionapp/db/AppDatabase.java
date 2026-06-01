package com.example.facedetectionapp.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
    entities  = {StudentEntity.class, AttendanceEntity.class, ClassSessionEntity.class},
    version   = 6,
    exportSchema = false
)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract StudentDao      studentDao();
    public abstract AttendanceDao   attendanceDao();
    public abstract ClassSessionDao classSessionDao();

    /**
     * Migrate v2 → v3:
     *  - Create class_sessions table
     *  - Add class_session_id + class_name columns to attendance
     * Existing student & attendance rows are preserved.
     */
    static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `class_sessions` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    "`class_id` TEXT NOT NULL," +
                    "`class_name` TEXT NOT NULL," +
                    "`subject` TEXT," +
                    "`date` TEXT NOT NULL," +
                    "`time` TEXT NOT NULL," +
                    "`created_at` INTEGER NOT NULL," +
                    "`status` TEXT NOT NULL DEFAULT 'ACTIVE')");

            db.execSQL("ALTER TABLE attendance ADD COLUMN `class_session_id` TEXT");
            db.execSQL("ALTER TABLE attendance ADD COLUMN `class_name` TEXT");
        }
    };

    /**
     * Migrate v3 → v4:
     *  - Drop and recreate students table to clear stale 128-d FaceNet embeddings.
     *  - ArcFace produces 512-d embeddings: all students MUST be re-registered.
     *  - Attendance and session records are preserved.
     */
    static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS `students`");
            db.execSQL("CREATE TABLE IF NOT EXISTS `students` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    "`student_id` TEXT NOT NULL," +
                    "`name` TEXT NOT NULL," +
                    "`roll_number` TEXT," +
                    "`class_name` TEXT," +
                    "`embedding` TEXT," +
                    "`photo_path` TEXT," +
                    "`registered_at` TEXT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_students_student_id` ON `students` (`student_id`)");
        }
    };

    /**
     * Migrate v4 → v5:
     *  - Drop and recreate students table to clear corrupted 512-d ArcFace embeddings.
     *  - Reverting back to FaceNet 128-d embeddings to fix Android 16KB support/size issues.
     */
    static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS `students`");
            db.execSQL("CREATE TABLE IF NOT EXISTS `students` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    "`student_id` TEXT NOT NULL," +
                    "`name` TEXT NOT NULL," +
                    "`roll_number` TEXT," +
                    "`class_name` TEXT," +
                    "`embedding` TEXT," +
                    "`photo_path` TEXT," +
                    "`registered_at` TEXT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_students_student_id` ON `students` (`student_id`)");
        }
    };

    /**
     * Migrate v5 → v6:
     *  - Drop and recreate students table to clear FaceNet embeddings.
     *  - Switched to MobileFaceNet TFLite model (~192-d embeddings).
     */
    static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS `students`");
            db.execSQL("CREATE TABLE IF NOT EXISTS `students` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                    "`student_id` TEXT NOT NULL," +
                    "`name` TEXT NOT NULL," +
                    "`roll_number` TEXT," +
                    "`class_name` TEXT," +
                    "`embedding` TEXT," +
                    "`photo_path` TEXT," +
                    "`registered_at` TEXT)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_students_student_id` ON `students` (`student_id`)");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "attendance_system.db"
                    )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build();
                }
            }
        }
        return INSTANCE;
    }
}
