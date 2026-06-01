package com.example.facedetectionapp.ml;

import android.util.Log;

import com.example.facedetectionapp.db.StudentEntity;

import java.util.List;

/**
 * Matches a probe face embedding against all registered student embeddings
 * using MobileFaceNet cosine similarity.
 *
 * Threshold guide (MobileFaceNet L2-normalized cosine space):
 *   ≥ 0.50 → Very strict  (ideal for 1-to-1 verification, controlled lighting)
 *   ≥ 0.40 → Balanced     (recommended for single-camera attendance)
 *   ≥ 0.30 → Lenient      (group photos with compression/distance)
 *   ≥ 0.20 → Very lenient (testing / debugging only)
 */
public class FaceMatcher {

    private static final String TAG       = "FaceMatcher";
    public  static final float  THRESHOLD = 0.40f; // balanced threshold for live attendance

    public static class MatchResult {
        public final StudentEntity student;
        public final float         similarity;
        public final boolean       isRecognized;

        public MatchResult(StudentEntity student, float similarity, boolean isRecognized) {
            this.student      = student;
            this.similarity   = similarity;
            this.isRecognized = isRecognized;
        }
    }

    /** Find best match using default threshold. */
    public static MatchResult findBestMatch(float[] probeEmbedding,
                                            List<StudentEntity> enrolledStudents) {
        return findBestMatch(probeEmbedding, enrolledStudents, THRESHOLD);
    }

    /**
     * Find the best matching student.
     *
     * @param probeEmbedding   L2-normalized MobileFaceNet embedding from the probe face.
     * @param enrolledStudents All registered students from DB.
     * @param threshold        Cosine similarity cutoff.
     */
    public static MatchResult findBestMatch(float[] probeEmbedding,
                                            List<StudentEntity> enrolledStudents,
                                            float threshold) {
        if (probeEmbedding == null || enrolledStudents == null || enrolledStudents.isEmpty()) {
            Log.w(TAG, "findBestMatch: probe is null or no enrolled students.");
            return new MatchResult(null, 0f, false);
        }

        StudentEntity bestStudent    = null;
        float         bestSimilarity = -1f;

        Log.d(TAG, "=== MATCHING PROBE (dim=" + probeEmbedding.length + ") against "
                + enrolledStudents.size() + " students | threshold=" + threshold + " ===");

        for (StudentEntity student : enrolledStudents) {
            if (student.embedding == null) {
                Log.w(TAG, "  Student " + student.name + " has null embedding — skipped");
                continue;
            }

            // Dimension guard: skip stale embeddings from a different model
            if (student.embedding.length != probeEmbedding.length) {
                Log.w(TAG, "  Student " + student.name + " embedding dim mismatch: "
                        + student.embedding.length + " vs probe " + probeEmbedding.length + " — skipped");
                continue;
            }

            float sim = MobileFaceNetModel.cosineSimilarity(probeEmbedding, student.embedding);
            Log.d(TAG, "  [" + student.name + "] sim=" + String.format("%.4f", sim)
                    + (sim >= threshold ? " ✓ MATCH" : " ✗ below threshold"));

            if (sim > bestSimilarity) {
                bestSimilarity = sim;
                bestStudent    = student;
            }
        }

        boolean recognized = bestSimilarity >= threshold;
        Log.d(TAG, ">>> Best: " + (bestStudent != null ? bestStudent.name : "NONE")
                + " | sim=" + String.format("%.4f", bestSimilarity)
                + " | recognized=" + recognized);

        return new MatchResult(
                recognized ? bestStudent : null,
                bestSimilarity,
                recognized
        );
    }
}
