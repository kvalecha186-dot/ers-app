package com.ers;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * MainActivity — Bug Fix:
 *  ✅ Replaced deprecated getResources().getColor() with ContextCompat.getColor()
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS         = 200;
    private static final int REQUEST_LOCATION_PERMISSION = 201;

    private TextView         statusText, triggerTypeText;
    private MaterialCardView mainCard, logoCard;
    private MaterialButton   listButton, settingsButton;
    private View             statusIndicator;
    private SwitchMaterial   serviceToggle;
    private SharedPreferences preferences;
    private Vibrator          vibrator;
    private boolean           isServiceRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        if (!preferences.getBoolean("onboardingCompleted", false)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        vibrator        = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        statusText      = findViewById(R.id.statusText);
        triggerTypeText = findViewById(R.id.triggerTypeText);
        mainCard        = findViewById(R.id.mainCard);
        logoCard        = findViewById(R.id.logoCard);
        listButton      = findViewById(R.id.listButton);
        settingsButton  = findViewById(R.id.settingsButton);
        statusIndicator = findViewById(R.id.statusIndicator);
        serviceToggle   = findViewById(R.id.serviceToggle);

        isServiceRunning = preferences.getBoolean("serviceEnabled", false);
        serviceToggle.setChecked(isServiceRunning);

        Animation fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
        fadeIn.setDuration(800);
        mainCard.startAnimation(fadeIn);

        Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
        logoCard.startAnimation(pulse);

        serviceToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            vibrateClick();
            if (isChecked) {
                if (allPermissionsGranted()) {
                    checkBatteryOptimization();
                } else {
                    serviceToggle.setChecked(false);
                    showPermissionDialog();
                }
            } else {
                showStopServiceDialog();
            }
        });

        listButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            vibrateClick();
            startActivity(new Intent(this, RecordingsListActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        settingsButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            vibrateClick();
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });

        requestPermissions();
        updateTriggerDisplay();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTriggerDisplay();
        isServiceRunning = preferences.getBoolean("serviceEnabled", false);
        serviceToggle.setChecked(isServiceRunning);
        updateStatus(isServiceRunning && allPermissionsGranted());
    }

    private void showPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("ERS needs:\n\n"
                        + "• Microphone — record audio\n"
                        + "• Location — capture GPS coordinates\n"
                        + "• Notifications — show press counter\n"
                        + "• SMS — send auto-alert when recording starts\n\n"
                        + "Grant permissions now?")
                .setPositiveButton("Grant", (d, w) -> requestPermissions())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showStopServiceDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Stop Emergency Service?")
                .setMessage("You won't be able to trigger recordings until you turn it back on.")
                .setPositiveButton("Stop",   (d, w) -> stopRecordingService())
                .setNegativeButton("Cancel", (d, w) -> serviceToggle.setChecked(true))
                .show();
    }

    private void checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new AlertDialog.Builder(this)
                        .setTitle("Disable Battery Optimization")
                        .setMessage("ERS must run in the background. Disable battery optimization to ensure it works when your phone is idle.")
                        .setPositiveButton("Open Settings", (d, w) -> {
                            try {
                                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception e) {
                                Toast.makeText(this, "Disable manually in battery settings", Toast.LENGTH_LONG).show();
                            }
                            startRecordingService();
                        })
                        .setNegativeButton("Skip", (d, w) -> startRecordingService())
                        .show();
                return;
            }
        }
        startRecordingService();
    }

    private void updateTriggerDisplay() {
        // Updated to reflect HOLD 2-second trigger (not 5× press)
        String[] labels = {"Hold Vol Down (3s)", "Hold Vol Up (3s)"};
        int idx = preferences.getInt("triggerType", 0);
        if (idx >= labels.length) idx = 0; // safety fallback
        triggerTypeText.setText(labels[idx]);
    }

    private void vibrateClick() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
            else
                vibrator.vibrate(50);
        }
    }

    private boolean allPermissionsGranted() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS
            };
        } else {
            perms = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED)
                return false;
        }
        return true;
    }

    private void requestPermissions() {
        if (!allPermissionsGranted()) {
            String[] perms;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.SEND_SMS   // ← request SMS permission too
                };
            } else {
                perms = new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.SEND_SMS
                };
            }
            ActivityCompat.requestPermissions(this, perms, REQUEST_PERMISSIONS);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                   ContextCompat.checkSelfPermission(this,
                           Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                           != PackageManager.PERMISSION_GRANTED) {
            new AlertDialog.Builder(this)
                    .setTitle("Background Location")
                    .setMessage("ERS needs background location to capture GPS during emergencies.")
                    .setPositiveButton("Grant", (d, w) ->
                            ActivityCompat.requestPermissions(this,
                                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                                    REQUEST_LOCATION_PERMISSION))
                    .setNegativeButton("Skip", null)
                    .show();
        }
    }

    private void startRecordingService() {
        if (allPermissionsGranted()) {
            Intent si = new Intent(this, ERSRecordingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si);
            else startService(si);

            isServiceRunning = true;
            preferences.edit().putBoolean("serviceEnabled", true).apply();
            updateStatus(true);
            Toast.makeText(this, "✅ Emergency Service Started", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecordingService() {
        stopService(new Intent(this, ERSRecordingService.class));
        isServiceRunning = false;
        preferences.edit().putBoolean("serviceEnabled", false).apply();
        updateStatus(false);
        Toast.makeText(this, "⚠️ Emergency Service Stopped", Toast.LENGTH_SHORT).show();
    }

    private void updateStatus(boolean active) {
        if (active) {
            statusText.setText("ACTIVE & MONITORING");
            // ✅ Bug Fix #11: use ContextCompat instead of deprecated getResources().getColor()
            statusText.setTextColor(ContextCompat.getColor(this, R.color.success));
            statusIndicator.setBackgroundResource(R.drawable.status_indicator);
            statusIndicator.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pulse));
        } else {
            statusText.setText("SERVICE DISABLED");
            statusText.setTextColor(ContextCompat.getColor(this, R.color.error));
            statusIndicator.clearAnimation();
            statusIndicator.setBackgroundColor(
                    ContextCompat.getColor(this, android.R.color.darker_gray));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
            @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { allGranted = false; break; }
            }
            if (allGranted) {
                vibrateClick();
                Toast.makeText(this, "✅ Permissions Granted", Toast.LENGTH_SHORT).show();
                if (serviceToggle.isChecked()) checkBatteryOptimization();
            } else {
                serviceToggle.setChecked(false);
                updateStatus(false);
                Toast.makeText(this,
                        "⚠️ Some permissions missing — SMS alert may not work",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isServiceRunning) moveTaskToBack(true);
        else { super.onBackPressed(); finishAffinity(); }
    }
}
