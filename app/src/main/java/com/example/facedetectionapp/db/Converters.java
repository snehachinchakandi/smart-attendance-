package com.example.facedetectionapp.db;

import androidx.room.TypeConverter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;

/**
 * Converts float[] to/from JSON String so Room can persist face embeddings.
 */
public class Converters {

    private static final Gson gson = new Gson();

    @TypeConverter
    public static String fromFloatArray(float[] value) {
        if (value == null) return null;
        Type type = new TypeToken<float[]>() {}.getType();
        return gson.toJson(value, type);
    }

    @TypeConverter
    public static float[] toFloatArray(String value) {
        if (value == null) return null;
        Type type = new TypeToken<float[]>() {}.getType();
        return gson.fromJson(value, type);
    }
}
