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
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

/**
 * SettingsActivity
 *
 * BUG FIXES applied:
 *   [10] Blank contact fields no longer erase saved contacts — only updates if non-empty
 *   [11] Uses ContextCompat.getColor() instead of deprecated getResources().getColor()
 *
 * NEW FEATURES:
 *   [C]  Max recording duration selector  — 5 / 15 / 30 min / unlimited
 *   [D]  Google Drive backup toggle       — one tap on/off
 *   [SMS] SMS permission row              — shows status, taps to app settings
 */
public class SettingsActivity extends AppCompatActivity {

    private SharedPreferences  prefs;
    private RadioGroup         triggerTypeGroup;
    private RadioGroup         durationGroup;
    private TextInputEditText  emailInput, phoneInput;
    private TextInputEditText  smtpEmailInput, smtpPasswordInput;
    private MaterialButton     saveButton;
    private MaterialButton     driveToggleBtn;

    // Permission rows
    private LinearLayout rowAccessibility, rowMicrophone,
                         rowNotification,  rowBattery,
                         rowLocation,      rowSms;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("ERSSettings", MODE_PRIVATE);

        // ── Bind views ─────────────────────────────────────────────────────
        triggerTypeGroup = findViewById(R.id.triggerTypeGroup);
        durationGroup    = findViewById(R.id.durationGroup);
        emailInput       = findViewById(R.id.emailInput);
        phoneInput       = findViewById(R.id.phoneInput);
        smtpEmailInput   = findViewById(R.id.smtpEmailInput);
        smtpPasswordInput = findViewById(R.id.smtpPasswordInput);
        saveButton       = findViewById(R.id.saveButton);
        driveToggleBtn   = findViewById(R.id.driveToggleButton);

        rowAccessibility = findViewById(R.id.rowAccessibility);
        rowMicrophone    = findViewById(R.id.rowMicrophone);
        rowNotification  = findViewById(R.id.rowNotification);
        rowBattery       = findViewById(R.id.rowBattery);
        rowLocation      = findViewById(R.id.rowLocation);
        rowSms           = findViewById(R.id.rowSms);

        loadSettings();
        bindPermissionRows();
        bindDriveToggle();

        saveButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            saveSettings();
        });

        View backBtn = findViewById(R.id.backButton);
        if (backBtn != null) backBtn.setOnClickListener(v -> onBackPressed());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionStatuses();
        refreshDriveButton();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Permission status helpers
    // ═════════════════════════════════════════════════════════════════════════

    private void refreshPermissionStatuses() {
        setRowStatus(rowAccessibility, isAccessibilityEnabled());
        setRowStatus(rowMicrophone,    isMicGranted());
        setRowStatus(rowNotification,  isNotifGranted());
        setRowStatus(rowBattery,       isBatteryOptDisabled());
        setRowStatus(rowLocation,      isLocationGranted());
        setRowStatus(rowSms,           isSmsGranted());
    }

    private void setRowStatus(LinearLayout row, boolean enabled) {
        if (row == null) return;
        // [11] Fixed: ContextCompat.getColor() instead of deprecated API
        TextView badge = row.findViewWithTag("badgeText");
        if (badge != null) {
            badge.setText(enabled ? "ON" : "OFF");
            badge.setTextColor(ContextCompat.getColor(this,
                    enabled ? R.color.success : R.color.error));
        }
        MaterialButton btn = row.findViewWithTag("actionBtn");
        if (btn != null) btn.setText(enabled ? "✓ Enabled" : "Tap to Enable");
    }

    // ── Permission checks ─────────────────────────────────────────────────────

    private boolean isAccessibilityEnabled() {
        try {
            if (Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 0) return false;
            String flat = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            String comp = getPackageName() + "/" + VolumeButtonAccessibilityService.class.getName();
            TextUtils.SimpleStringSplitter spl = new TextUtils.SimpleStringSplitter(':');
            spl.setString(flat);
            while (spl.hasNext()) { if (spl.next().equalsIgnoreCase(comp)) return true; }
        } catch (Exception ignore) {}
        return false;
    }

    private boolean isMicGranted() {
        return check(Manifest.permission.RECORD_AUDIO);
    }

    private boolean isNotifGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return check(Manifest.permission.POST_NOTIFICATIONS);
        return true;
    }

    private boolean isBatteryOptDisabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }

    private boolean isLocationGranted() {
        return check(Manifest.permission.ACCESS_FINE_LOCATION);
    }

    private boolean isSmsGranted() {
        return check(Manifest.permission.SEND_SMS);
    }

    private boolean check(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ── Row click handlers ────────────────────────────────────────────────────

    private void bindPermissionRows() {

        if (rowAccessibility != null) rowAccessibility.setOnClickListener(v -> {
            if (isAccessibilityEnabled()) { toast("✅ Accessibility already enabled"); return; }
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast("Find 'ERS Emergency Monitor' and turn it ON");
        });

        if (rowMicrophone != null) rowMicrophone.setOnClickListener(v -> {
            if (isMicGranted()) { toast("✅ Microphone already granted"); return; }
            openAppSettings();
            toast("Permissions → Microphone → Allow");
        });

        if (rowNotification != null) rowNotification.setOnClickListener(v -> {
            if (isNotifGranted()) { toast("✅ Notifications already allowed"); return; }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                startActivity(i);
            } else { openAppSettings(); }
            toast("Enable notifications for ERS");
        });

        if (rowBattery != null) rowBattery.setOnClickListener(v -> {
            if (isBatteryOptDisabled()) { toast("✅ Battery optimization already disabled"); return; }
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
            toast("Tap 'Allow' to disable battery optimization");
        });

        if (rowLocation != null) rowLocation.setOnClickListener(v -> {
            if (isLocationGranted()) { toast("✅ Location already granted"); return; }
            openAppSettings();
            toast("Permissions → Location → Allow");
        });

        // SMS permission row (new)
        if (rowSms != null) rowSms.setOnClickListener(v -> {
            if (isSmsGranted()) { toast("✅ SMS permission already granted"); return; }
            openAppSettings();
            toast("Permissions → SMS → Allow\n(needed for instant emergency alerts)");
        });
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  [D] Google Drive backup toggle
    // ═════════════════════════════════════════════════════════════════════════

    private void bindDriveToggle() {
        if (driveToggleBtn == null) return;
        driveToggleBtn.setOnClickListener(v -> {
            boolean cur = prefs.getBoolean("driveBackupEnabled", false);
            prefs.edit().putBoolean("driveBackupEnabled", !cur).apply();
            refreshDriveButton();
            toast(!cur
                ? "☁️ Drive backup ON — recordings will upload after stop"
                : "☁️ Drive backup OFF");
        });
    }

    private void refreshDriveButton() {
        if (driveToggleBtn == null) return;
        boolean on = prefs.getBoolean("driveBackupEnabled", false);
        driveToggleBtn.setText(on ? "☁️  Drive Backup: ON" : "☁️  Drive Backup: OFF");
        driveToggleBtn.setBackgroundTintList(ContextCompat.getColorStateList(this,
                on ? R.color.success : R.color.primary));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Load / Save settings
    // ═════════════════════════════════════════════════════════════════════════

    private void loadSettings() {
        // Load trigger — HOLD mode
        int savedTrigger = prefs.getInt("triggerType", 0);
        if (savedTrigger == 1) triggerTypeGroup.check(R.id.volumeUpRadio);
        else                   triggerTypeGroup.check(R.id.volumeDownRadio); // default

        // Contacts
        emailInput.setText(prefs.getString("email", ""));
        phoneInput.setText(prefs.getString("phone", ""));

        // SMTP auto-email sender config
        smtpEmailInput.setText(prefs.getString("smtpEmail", ""));
        smtpPasswordInput.setText(prefs.getString("smtpPassword", ""));

        // [C] Duration (only if layout has it)
        if (durationGroup != null) {
            switch (prefs.getInt("maxRecordingDuration", 1)) {
                case 0:  durationGroup.check(R.id.dur5min);      break;
                case 2:  durationGroup.check(R.id.dur30min);     break;
                case 3:  durationGroup.check(R.id.durUnlimited); break;
                default: durationGroup.check(R.id.dur15min);     break;
            }
        }
    }

    private void saveSettings() {
        SharedPreferences.Editor ed = prefs.edit();

        // Trigger — HOLD 2 seconds on selected button
        int sel = triggerTypeGroup.getCheckedRadioButtonId();
        if      (sel == R.id.volumeDownRadio) ed.putInt("triggerType", 0); // hold vol down
        else if (sel == R.id.volumeUpRadio)   ed.putInt("triggerType", 1); // hold vol up

        // [10] Contacts — only overwrite if user actually typed something
        String email = emailInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        if (!email.isEmpty()) ed.putString("email", email);
        if (!phone.isEmpty()) ed.putString("phone", phone);
        if (email.isEmpty() && phone.isEmpty()) {
            toast("ℹ️ No contacts entered — existing contacts kept");
        }

        // SMTP — strip spaces (Google shows App Password with spaces)
        String smtpEmail = smtpEmailInput.getText().toString().trim();
        String smtpPwd   = smtpPasswordInput.getText().toString().trim().replaceAll("\\s", "");
        if (!smtpEmail.isEmpty()) ed.putString("smtpEmail",    smtpEmail);
        if (!smtpPwd.isEmpty())   ed.putString("smtpPassword", smtpPwd);

        // [C] Duration
        if (durationGroup != null) {
            int dSel = durationGroup.getCheckedRadioButtonId();
            if      (dSel == R.id.dur5min)      ed.putInt("maxRecordingDuration", 0);
            else if (dSel == R.id.dur15min)     ed.putInt("maxRecordingDuration", 1);
            else if (dSel == R.id.dur30min)     ed.putInt("maxRecordingDuration", 2);
            else if (dSel == R.id.durUnlimited) ed.putInt("maxRecordingDuration", 3);
        }

        ed.apply();
        toast("✅ Settings saved!");
        finish();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }
}
