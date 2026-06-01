package com.example.facedetectionapp;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.facedetectionapp.ui.DashboardActivity;

/**
 * Legacy entry point – immediately redirects to DashboardActivity.
 * The manifest now sets DashboardActivity as the launcher, so this
 * class is only kept to avoid any residual references.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, DashboardActivity.class));
        finish();
    }
}