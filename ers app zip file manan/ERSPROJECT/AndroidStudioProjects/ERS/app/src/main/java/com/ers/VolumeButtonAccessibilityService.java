package com.ers;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * VolumeButtonAccessibilityService
 *
 * TRIGGER CHANGED: 5× multiple taps → HOLD 2 seconds
 * ─────────────────────────────────────────────────────
 * Old: Press volume down 5 times quickly → complex, unreliable in panic
 * New: Press and HOLD volume down for 2 seconds → simple, natural, fast
 *
 * HOW IT WORKS:
 *   • Press volume button → short buzz + "Hold for recording…" toast
 *   • Hold 2 seconds     → double buzz + recording starts / stops
 *   • Release early      → nothing happens (accidental press safe)
 *
 * NOTIFICATION SOUNDS FIXED:
 *   • All channels set to IMPORTANCE_LOW + setSilent(true) + no sound
 *   • No more 3-4 notification beeps when recording starts
 */
public class VolumeButtonAccessibilityService extends AccessibilityService {

    private static final String TAG = "VolumeButtonService";

    // ── HOLD trigger config ───────────────────────────────────────────────────
    private static final long HOLD_DURATION_MS    = 3_000L; // hold for 3 seconds
    private static final long WAKELOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;

    // Notification
    private static final String NOTIF_CHANNEL_ID = "ERS_Hold";
    private static final int    NOTIF_ID         = 99;

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean isRecording  = false;
    private boolean isHolding    = false;
    private int     triggerType  = 0;

    // ── Services ──────────────────────────────────────────────────────────────
    private SharedPreferences     prefs;
    private Vibrator              vibrator;
    private PowerManager          powerManager;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private final Handler         handler      = new Handler(Looper.getMainLooper());
    private NotificationManager   notifManager;
    private Toast                 activeToast;

    // ── Hold runnable — fires after HOLD_DURATION_MS if button still held ─────
    private final Runnable holdTriggerRunnable = () -> {
        if (!isHolding) return; // button was released before 2s — ignore

        boolean sosActive = prefs.getBoolean("sosCountdownActive", false);

        if (sosActive) {
            vibratePattern(new long[]{0, 150, 80, 150, 80, 150});
            showToast("✅ SOS CANCELLED");
            sendToService("ACTION_CANCEL_SOS");
            setRecording(false);

        } else if (!isRecording) {
            wakeScreen();
            vibratePattern(new long[]{0, 200, 100, 200}); // double buzz = START
            setRecording(true);
            showToast("🚨 SOS in 5 seconds…\nHold again to CANCEL");
            sendToService("ACTION_SOS_COUNTDOWN");
            dismissHoldNotif();

        } else {
            vibratePattern(new long[]{0, 300, 100, 300}); // double long buzz = STOP
            setRecording(false);
            showToast("⏹ Recording STOPPED\nSending now…");
            sendToService("ACTION_STOP_RECORDING");
            dismissHoldNotif();
        }

        isHolding = false;
    };

    // ── Progress tick — shows "Hold… 1s / 2s" feedback while user holds ──────
    private int holdProgressSec = 0;
    private final Runnable holdProgressRunnable = new Runnable() {
        @Override public void run() {
            if (!isHolding) return;
            holdProgressSec++;
            String action = isRecording ? "STOP recording" : "START recording";
            if (prefs.getBoolean("sosCountdownActive", false)) action = "CANCEL SOS";
            showToast("Hold… " + holdProgressSec + "s / 3s  →  " + action);
            showHoldProgressNotif(holdProgressSec);
            if (holdProgressSec < (HOLD_DURATION_MS / 1000)) {
                handler.postDelayed(this, 1000);
            }
        }
    };

    // ═════════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        prefs        = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        vibrator     = (Vibrator)            getSystemService(VIBRATOR_SERVICE);
        powerManager = (PowerManager)        getSystemService(POWER_SERVICE);
        notifManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        triggerType  = prefs.getInt("triggerType", 0);
        isRecording  = prefs.getBoolean("isCurrentlyRecording", false);

        prefs.registerOnSharedPreferenceChangeListener((p, key) -> {
            if ("triggerType".equals(key))
                triggerType = p.getInt("triggerType", 0);
            if ("isCurrentlyRecording".equals(key))
                isRecording = p.getBoolean("isCurrentlyRecording", false);
            if ("sosCountdownActive".equals(key) && !p.getBoolean("sosCountdownActive", false)) {
                isRecording = false;
                prefs.edit().putBoolean("isCurrentlyRecording", false).apply();
            }
        });

        initWakeLocks();
        createNotifChannel();
        Log.d(TAG, "✅ VolumeButtonService ready — HOLD " + HOLD_DURATION_MS + "ms to trigger");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        // ERS only filters hardware key events. It does not inspect screen content.
        info.eventTypes          = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType        = AccessibilityServiceInfo.FEEDBACK_ALL_MASK;
        info.notificationTimeout = 0;
        info.packageNames        = null;
        setServiceInfo(info);
        ensureWakeLock();
        Log.d(TAG, "🔗 Service connected");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {}
    @Override public void onInterrupt() { cancelHold(); }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelHold();
        releaseWakeLocks();
        dismissHoldNotif();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Key event — HOLD logic
    // ═════════════════════════════════════════════════════════════════════════

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        try {
            ensureWakeLock();

            int keyCode = event.getKeyCode();
            int action  = event.getAction();

            // Only care about volume buttons
            if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false;

            // Only the configured trigger button
            boolean isDown  = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
            boolean matches = (triggerType == 0 &&  isDown)
                           || (triggerType == 1 && !isDown);
            if (!matches) return false;

            // Service must be enabled
            if (!prefs.getBoolean("serviceEnabled", false)) return false;

            if (action == KeyEvent.ACTION_DOWN && !event.isLongPress()) {
                // ── PRESS DOWN — start hold timer ─────────────────────────
                if (!isHolding) {
                    isHolding       = true;
                    holdProgressSec = 0;
                    vibrateOnce(50); // tiny confirm buzz — "press registered"

                    // Schedule trigger after HOLD_DURATION_MS
                    handler.postDelayed(holdTriggerRunnable, HOLD_DURATION_MS);
                    // Progress ticks at 1s
                    handler.postDelayed(holdProgressRunnable, 1000);

                    String action2 = isRecording ? "STOP" : "START";
                    if (prefs.getBoolean("sosCountdownActive", false)) action2 = "CANCEL SOS";
                    showToast("🔴 Hold 3s to " + action2 + "…\nRelease to cancel");
                }

            } else if (action == KeyEvent.ACTION_UP) {
                // ── RELEASE — cancel if before 2s ─────────────────────────
                if (isHolding) {
                    cancelHold();
                    // Only tell user they released early if timer hadn't fired
                    Log.d(TAG, "🔼 Released early — hold cancelled");
                }
            }

            return true; // consume — prevents volume changing

        } catch (Exception e) {
            Log.e(TAG, "❌ onKeyEvent: " + e.getMessage(), e);
        }
        return false;
    }

    private void cancelHold() {
        isHolding = false;
        handler.removeCallbacks(holdTriggerRunnable);
        handler.removeCallbacks(holdProgressRunnable);
        dismissHoldNotif();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  State persistence
    // ═════════════════════════════════════════════════════════════════════════

    private void setRecording(boolean recording) {
        isRecording = recording;
        prefs.edit().putBoolean("isCurrentlyRecording", recording).apply();
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Toast
    // ═════════════════════════════════════════════════════════════════════════

    private void showToast(String msg) {
        handler.post(() -> {
            try {
                if (activeToast != null) activeToast.cancel();
                activeToast = Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT);
                activeToast.show();
            } catch (Exception e) { Log.e(TAG, "Toast: " + e.getMessage()); }
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Hold progress notification — FULLY SILENT (no beeps)
    // ═════════════════════════════════════════════════════════════════════════

    private void createNotifChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW = no sound, no beep, shows silently
            NotificationChannel ch = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "ERS Hold Progress",
                    NotificationManager.IMPORTANCE_LOW); // LOW = silent ✅
            ch.setSound(null, null);          // no sound ✅
            ch.enableVibration(false);        // no vibration ✅
            ch.setShowBadge(false);
            if (notifManager != null) notifManager.createNotificationChannel(ch);
        }
    }

    private void showHoldProgressNotif(int sec) {
        try {
            String action = isRecording ? "STOP recording" : "START recording";
            if (prefs.getBoolean("sosCountdownActive", false)) action = "CANCEL SOS";

            android.app.Notification n = new NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("🔴 Holding… " + sec + " / 3s")
                    .setContentText("Release to cancel · Keep holding to " + action)
                    .setPriority(NotificationCompat.PRIORITY_LOW)   // LOW = silent ✅
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setSilent(true)                                 // no beep ✅
                    .setProgress(3, sec, false)
                    .setOngoing(true)
                    .build();

            if (notifManager != null) notifManager.notify(NOTIF_ID, n);
        } catch (Exception e) { Log.e(TAG, "showHoldProgressNotif: " + e.getMessage()); }
    }

    private void dismissHoldNotif() {
        if (notifManager != null) notifManager.cancel(NOTIF_ID);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Service communication
    // ═════════════════════════════════════════════════════════════════════════

    private void sendToService(String action) {
        try {
            Intent i = new Intent(this, ERSRecordingService.class);
            i.setAction(action);
            i.putExtra("triggerType", triggerType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(i);
            else
                startService(i);
        } catch (Exception e) { Log.e(TAG, "sendToService: " + e.getMessage()); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Wake locks — all with explicit timeout
    // ═════════════════════════════════════════════════════════════════════════

    private void initWakeLocks() {
        try {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "ERS::AccessibilityWakeLock");
            wakeLock.setReferenceCounted(false);
            if (!wakeLock.isHeld()) wakeLock.acquire(WAKELOCK_TIMEOUT_MS);

            screenWakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                    PowerManager.ACQUIRE_CAUSES_WAKEUP   |
                    PowerManager.ON_AFTER_RELEASE,
                    "ERS::ScreenWakeLock");
            screenWakeLock.setReferenceCounted(false);
        } catch (Exception e) { Log.e(TAG, "initWakeLocks: " + e.getMessage()); }
    }

    private void ensureWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld())
                wakeLock.acquire(WAKELOCK_TIMEOUT_MS);
        } catch (Exception e) { Log.e(TAG, "ensureWakeLock: " + e.getMessage()); }
    }

    private void wakeScreen() {
        try {
            if (screenWakeLock != null) {
                if (screenWakeLock.isHeld()) screenWakeLock.release();
                screenWakeLock.acquire(30_000L);
            }
        } catch (Exception e) { Log.e(TAG, "wakeScreen: " + e.getMessage()); }
    }

    private void releaseWakeLocks() {
        try {
            if (wakeLock       != null && wakeLock.isHeld())       wakeLock.release();
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception e) { Log.e(TAG, "releaseWakeLocks: " + e.getMessage()); }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  Vibration
    // ═════════════════════════════════════════════════════════════════════════

    private void vibrateOnce(long ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else vibrator.vibrate(ms);
        } catch (Exception e) { Log.e(TAG, "vibrateOnce: " + e.getMessage()); }
    }

    private void vibratePattern(long[] pattern) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else vibrator.vibrate(pattern, -1);
        } catch (Exception e) { Log.e(TAG, "vibratePattern: " + e.getMessage()); }
    }
}
