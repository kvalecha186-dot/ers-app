package com.ers;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ERSRecordingService extends Service {

    private static final String TAG = "ERS_Service";

    // ── Notification channels ─────────────────────────────────────────────
    static final String CHANNEL_SERVICE = "ERSServiceChannel";
    static final String CHANNEL_SOS     = "ERSSOSChannel";
    static final String CHANNEL_ALERT   = "ERSAlertChannel";
    private static final int NOTIF_ID   = 1;

    // ── SMS broadcast actions ─────────────────────────────────────────────
    private static final String ACTION_SMS_SENT      = "com.ers.SMS_SENT";
    private static final String ACTION_SMS_DELIVERED = "com.ers.SMS_DELIVERED";

    // ── Timing ────────────────────────────────────────────────────────────
    private static final long RECORDING_MIN_WAIT_MS = 3_000L;
    private static final long AUTO_SHARE_DELAY_MS   = 4_000L;
    private static final long WAKELOCK_TIMEOUT_MS   = 2 * 60 * 60 * 1000L;
    private static final long TIMER_TICK_MS         = 1_000L;
    private static final int  SOS_COUNTDOWN_SECS    = 5;

    // ── State ─────────────────────────────────────────────────────────────
    private volatile boolean isRecording          = false;
    private volatile boolean canStopRecording     = false;
    private volatile boolean isSosCountdownActive = false;
    private          int     sosSecondsLeft       = SOS_COUNTDOWN_SECS;

    // ── Media ─────────────────────────────────────────────────────────────
    private MediaRecorder mediaRecorder;
    private String        currentRecordingPath;
    private int           originalAudioMode = AudioManager.MODE_NORMAL;

    // ── System services ───────────────────────────────────────────────────
    private SharedPreferences     prefs;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWakeLock;
    private KeyguardManager       keyguardManager;
    private AudioManager          audioManager;
    private AudioFocusRequest     audioFocusRequest;
    private int                   triggerType = 0;

    // ── SMS delivery receiver ─────────────────────────────────────────────
    private BroadcastReceiver smsReceiver;
    private boolean           smsReceiverRegistered = false;

    // ── Threading ─────────────────────────────────────────────────────────
    private final Handler         mainHandler     = new Handler(Looper.getMainLooper());
    private final ExecutorService recordingThread = Executors.newSingleThreadExecutor();
    private final ExecutorService smsThread       = Executors.newSingleThreadExecutor();

    // ── Timing state ──────────────────────────────────────────────────────
    private long recordingStartTime = 0;
    private long maxDurationMs      = 15 * 60 * 1000L;

    // ═════════════════════════════════════════════════════════════════════
    //  Runnables
    // ═════════════════════════════════════════════════════════════════════

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            if (!isRecording) return;
            long elapsed = System.currentTimeMillis() - recordingStartTime;
            String hint  = canStopRecording
                    ? "Hold " + triggerName() + " 3s to STOP"
                    : "Starting…";
            updateNotif(CHANNEL_SERVICE,
                    "REC " + fmt(elapsed), hint);
            mainHandler.postDelayed(this, TIMER_TICK_MS);
        }
    };

    private final Runnable allowStopRunnable = () -> canStopRecording = true;

    private final Runnable autoStopRunnable = () -> {
        canStopRecording = true;
        stopRecording();
    };

    private final Runnable shareRunnable = () -> {
        if (!isRecording && currentRecordingPath != null) {
            openShareScreen(currentRecordingPath);
            currentRecordingPath = null;
        }
    };

    private Runnable sosTickRunnable;

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onCreate() {
        super.onCreate();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        prefs           = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        triggerType     = prefs.getInt("triggerType", 0);
        audioManager    = (AudioManager)    getSystemService(Context.AUDIO_SERVICE);
        keyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ERS::WakeLock");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(WAKELOCK_TIMEOUT_MS);

        screenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP   |
                PowerManager.ON_AFTER_RELEASE,
                "ERS::ScreenWakeLock");
        screenWakeLock.setReferenceCounted(false);

        createNotifChannels();
        startForeground(NOTIF_ID, buildNotif(CHANNEL_SERVICE,
                "ERS Active",
                "Hold " + triggerName() + " 3s to START recording"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        triggerType = intent.getIntExtra("triggerType", prefs.getInt("triggerType", 0));
        switch (intent.getAction()) {
            case "ACTION_SOS_COUNTDOWN":   startSosCountdown();  break;
            case "ACTION_CANCEL_SOS":      cancelSosCountdown(); break;
            case "ACTION_START_RECORDING": startRecording();     break;
            case "ACTION_STOP_RECORDING":
                if (isSosCountdownActive) cancelSosCountdown();
                else                      stopRecording();
                break;
        }
        return START_STICKY;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  SOS Countdown
    // ═════════════════════════════════════════════════════════════════════

    private void startSosCountdown() {
        if (isSosCountdownActive || isRecording) return;
        isSosCountdownActive = true;
        sosSecondsLeft       = SOS_COUNTDOWN_SECS;
        prefs.edit().putBoolean("sosCountdownActive", true).apply();
        showSosCountdownNotif(sosSecondsLeft);
        scheduleSosTick();
    }

    private void scheduleSosTick() {
        sosTickRunnable = () -> {
            sosSecondsLeft--;
            if (sosSecondsLeft <= 0) {
                isSosCountdownActive = false;
                prefs.edit().putBoolean("sosCountdownActive", false).apply();
                startRecording();
            } else {
                showSosCountdownNotif(sosSecondsLeft);
                scheduleSosTick();
            }
        };
        mainHandler.postDelayed(sosTickRunnable, 1000);
    }

    private void cancelSosCountdown() {
        if (!isSosCountdownActive) return;
        isSosCountdownActive = false;
        prefs.edit()
             .putBoolean("sosCountdownActive",   false)
             .putBoolean("isCurrentlyRecording", false)
             .apply();
        if (sosTickRunnable != null) {
            mainHandler.removeCallbacks(sosTickRunnable);
            sosTickRunnable = null;
        }
        updateNotif(CHANNEL_SERVICE, "ERS Active",
                "SOS cancelled. Hold " + triggerName() + " 3s to START");
    }

    private void showSosCountdownNotif(int secondsLeft) {
        try {
            int piFlags = piFlags();
            Intent ci = new Intent(this, ERSRecordingService.class)
                    .setAction("ACTION_CANCEL_SOS");
            PendingIntent cancelPI = PendingIntent.getService(this, 100, ci, piFlags);

            android.app.Notification n = new NotificationCompat.Builder(this, CHANNEL_SOS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("SOS in " + secondsLeft + "s…")
                    .setContentText("Recording will start. Tap CANCEL to abort.")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setOngoing(true).setSilent(true)
                    .setProgress(SOS_COUNTDOWN_SECS,
                            SOS_COUNTDOWN_SECS - secondsLeft, false)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                               "CANCEL SOS", cancelPI)
                    .build();
            mgr().notify(NOTIF_ID, n);
        } catch (Exception e) { Log.e(TAG, "SOS notif: " + e.getMessage()); }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  START recording
    // ═════════════════════════════════════════════════════════════════════

    private void startRecording() {
        if (isRecording) return;
        maxDurationMs = getMaxDurationMs();
        updateNotif(CHANNEL_SERVICE, "REC 0:00", "Starting…");

        recordingThread.execute(() -> {
            try {
                mainHandler.post(this::wakeScreen);

                if (!requestAudioFocus()) {
                    mainHandler.post(() ->
                        updateNotif(CHANNEL_SERVICE, "Audio busy", "Try again"));
                    return;
                }

                File outFile = prepareOutputFile();
                if (outFile == null) {
                    releaseAudioFocus();
                    mainHandler.post(() ->
                        updateNotif(CHANNEL_SERVICE, "Storage error", "Check permissions"));
                    return;
                }
                currentRecordingPath = outFile.getAbsolutePath();

                originalAudioMode = audioManager.getMode();
                audioManager.setMode(AudioManager.MODE_NORMAL);
                if (audioManager.isSpeakerphoneOn()) audioManager.setSpeakerphoneOn(false);

                if (!initMediaRecorder()) {
                    releaseAudioFocus();
                    audioManager.setMode(originalAudioMode);
                    mainHandler.post(() ->
                        updateNotif(CHANNEL_SERVICE, "Recorder error", "Try again"));
                    return;
                }

                mediaRecorder.start();

                mainHandler.post(() -> {
                    isRecording        = true;
                    canStopRecording   = false;
                    recordingStartTime = System.currentTimeMillis();

                    mainHandler.post(timerTick);
                    mainHandler.postDelayed(allowStopRunnable, RECORDING_MIN_WAIT_MS);
                    if (maxDurationMs > 0)
                        mainHandler.postDelayed(autoStopRunnable, maxDurationMs);

                    // ── THREE ALERTS fire simultaneously ───────────────────
                    sendEmergencySms();    // 1. SMS — auto, no user tap
                    sendEmergencyEmail();  // 2. Email via SMTP — auto, no user tap
                    postWhatsAppAlert();   // 3. WhatsApp — 1-tap notification action

                    Log.d(TAG, "Recording started: " + currentRecordingPath);
                });

            } catch (Exception e) {
                Log.e(TAG, "startRecording: " + e.getMessage(), e);
                releaseAudioFocus();
                mainHandler.post(() ->
                    updateNotif(CHANNEL_SERVICE, "Error starting", "Tap to retry"));
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STOP recording
    // ═════════════════════════════════════════════════════════════════════

    private void stopRecording() {
        if (!isRecording) return;

        if (!canStopRecording) {
            long elapsed = System.currentTimeMillis() - recordingStartTime;
            int  remain  = Math.max(1, (int) ((RECORDING_MIN_WAIT_MS - elapsed) / 1000));
            updateNotif(CHANNEL_SERVICE, "Too soon",
                    "Wait " + remain + "s then hold again");
            return;
        }

        mainHandler.removeCallbacks(timerTick);
        mainHandler.removeCallbacks(allowStopRunnable);
        mainHandler.removeCallbacks(autoStopRunnable);

        final String savedPath = currentRecordingPath;

        recordingThread.execute(() -> {
            try {
                if (mediaRecorder != null) {
                    try { mediaRecorder.stop(); } catch (RuntimeException ignore) {}
                    cleanupMediaRecorder();
                }
                audioManager.setMode(originalAudioMode);
                releaseAudioFocus();

                mainHandler.post(() -> {
                    isRecording      = false;
                    canStopRecording = false;
                    updateNotif(CHANNEL_SERVICE, "Recording stopped",
                            "Opening share screen…");

                    if (savedPath != null && prefs.getBoolean("driveBackupEnabled", false))
                        uploadToDrive(savedPath);

                    currentRecordingPath = savedPath;
                    mainHandler.postDelayed(shareRunnable, AUTO_SHARE_DELAY_MS);
                });

            } catch (Exception e) {
                Log.e(TAG, "stopRecording: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    isRecording      = false;
                    canStopRecording = false;
                    cleanupMediaRecorder();
                    releaseAudioFocus();
                    audioManager.setMode(originalAudioMode);
                });
            }
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ALERT 1 — SMS  (FIXED)
    //
    //  Root causes of SMS not being received:
    //  1. Emoji (🚨) in SMS body — many Indian carriers (Jio, Airtel, Vi)
    //     encode emoji as UCS-2 which limits each part to 67 chars instead
    //     of 160. This causes unexpected splits OR silent message drops.
    //     FIX: plain ASCII body only.
    //
    //  2. No sentIntent/deliveredIntent — failure was invisible.
    //     FIX: BroadcastReceiver logs every failure code + updates notif.
    //
    //  3. SEND_SMS permission denied at runtime → silent return.
    //     FIX: explicit toast + notification so user knows to grant it.
    // ═════════════════════════════════════════════════════════════════════

    private void sendEmergencySms() {
        String phone = prefs.getString("phone", "").trim();

        if (phone.isEmpty()) {
            Log.w(TAG, "SMS: no phone number saved — skipping");
            return;
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SMS: SEND_SMS permission NOT granted");
            mainHandler.post(() -> {
                updateNotif(CHANNEL_SERVICE, "SMS permission missing",
                        "Go to App Settings and grant SMS permission");
                Toast.makeText(this,
                        "ERS: SMS permission missing! Grant it in App Settings.",
                        Toast.LENGTH_LONG).show();
            });
            return;
        }

        registerSmsDeliveryReceiver(); // register before sending

        smsThread.execute(() -> {
            try {
                String fmtPhone = formatPhone(phone);
                String ts       = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                                        Locale.US).format(new Date());
                String loc      = getLocationText();

                // ── PLAIN ASCII ONLY — no emoji ────────────────────────────
                // Emoji forces UCS-2 encoding on SMS: 67 chars/part instead
                // of 160 chars/part. Causes silent drops on many carriers.
                String msg = "EMERGENCY ALERT!\n"
                           + "Time: " + ts + "\n"
                           + "Location: " + loc + "\n"
                           + "Audio recording has started.\n"
                           + "Please respond immediately!";

                int piFlags = piFlags();

                // sentIntent — fires when SMS leaves the device
                PendingIntent sentPI = PendingIntent.getBroadcast(this, 1001,
                        new Intent(ACTION_SMS_SENT), piFlags);

                // deliveredIntent — fires when recipient's phone ACKs receipt
                PendingIntent deliveredPI = PendingIntent.getBroadcast(this, 1002,
                        new Intent(ACTION_SMS_DELIVERED), piFlags);

                SmsManager sms = getSmsManager();
                if (sms == null) {
                    Log.e(TAG, "SMS: SmsManager is null — no default SIM?");
                    return;
                }

                ArrayList<String> parts = sms.divideMessage(msg);
                Log.d(TAG, "SMS: sending " + parts.size() + " part(s) to " + fmtPhone);

                if (parts.size() > 1) {
                    ArrayList<PendingIntent> sentPIs =
                            new ArrayList<>(Collections.nCopies(parts.size(), sentPI));
                    ArrayList<PendingIntent> delPIs  =
                            new ArrayList<>(Collections.nCopies(parts.size(), deliveredPI));
                    sms.sendMultipartTextMessage(fmtPhone, null, parts, sentPIs, delPIs);
                } else {
                    sms.sendTextMessage(fmtPhone, null, msg, sentPI, deliveredPI);
                }

                Log.d(TAG, "SMS dispatched to " + fmtPhone);

            } catch (Exception e) {
                Log.e(TAG, "SMS exception: " + e.getMessage(), e);
                mainHandler.post(() ->
                    updateNotif(CHANNEL_SERVICE, "SMS failed",
                            "Error: " + e.getMessage()));
            }
        });
    }

    /** Register BroadcastReceiver for SMS sent/delivered confirmation */
    private void registerSmsDeliveryReceiver() {
        if (smsReceiverRegistered) return;

        smsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                boolean sent = ACTION_SMS_SENT.equals(action);

                switch (getResultCode()) {
                    case Activity.RESULT_OK:
                        if (sent) {
                            Log.d(TAG, "SMS sent OK");
                            mainHandler.post(() ->
                                updateNotif(CHANNEL_SERVICE, "SMS sent",
                                        "Waiting for delivery confirmation…"));
                        } else {
                            Log.d(TAG, "SMS delivered to recipient");
                            mainHandler.post(() ->
                                updateNotif(CHANNEL_SERVICE, "SMS DELIVERED",
                                        "Hold " + triggerName() + " 3s to STOP"));
                        }
                        break;
                    case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                        Log.e(TAG, "SMS error: generic failure");
                        mainHandler.post(() ->
                            updateNotif(CHANNEL_SERVICE, "SMS failed (generic)",
                                    "Check SIM / network"));
                        break;
                    case SmsManager.RESULT_ERROR_NO_SERVICE:
                        Log.e(TAG, "SMS error: no service");
                        mainHandler.post(() ->
                            updateNotif(CHANNEL_SERVICE, "SMS failed — No signal",
                                    "No network. Email alert still active."));
                        break;
                    case SmsManager.RESULT_ERROR_RADIO_OFF:
                        Log.e(TAG, "SMS error: radio off (airplane mode?)");
                        mainHandler.post(() ->
                            updateNotif(CHANNEL_SERVICE, "SMS failed — Airplane mode",
                                    "Disable airplane mode and retry"));
                        break;
                    case SmsManager.RESULT_ERROR_NULL_PDU:
                        Log.e(TAG, "SMS error: null PDU");
                        break;
                    default:
                        Log.e(TAG, "SMS error: code=" + getResultCode());
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SMS_SENT);
        filter.addAction(ACTION_SMS_DELIVERED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(smsReceiver, filter);
        }
        smsReceiverRegistered = true;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ALERT 2 — Auto Email via SMTP
    // ═════════════════════════════════════════════════════════════════════

    private void sendEmergencyEmail() {
        String toEmail  = prefs.getString("email",        "").trim();
        String fromAddr = prefs.getString("smtpEmail",    "").trim();
        String appPwd   = prefs.getString("smtpPassword", "").trim();

        if (toEmail.isEmpty() || fromAddr.isEmpty() || appPwd.isEmpty()) {
            Log.d(TAG, "Email skipped — SMTP not configured");
            return;
        }

        smsThread.execute(() -> {
            String ts  = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                               Locale.US).format(new Date());
            String loc = getLocationText();
            EmailSender.send(toEmail, fromAddr, appPwd, ts, loc, (ok, err) -> {
                if (ok) {
                    Log.d(TAG, "Email sent to " + toEmail);
                    mainHandler.post(() ->
                        updateNotif(CHANNEL_SERVICE, "SMS + Email sent",
                                "Hold " + triggerName() + " 3s to STOP"));
                } else {
                    Log.e(TAG, "Email failed: " + err);
                }
            });
        });
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ALERT 3 — WhatsApp notification action (IMPROVED)
    //
    //  Shows TWO action buttons on the recording notification:
    //  [Send WhatsApp Text] — opens WhatsApp with pre-filled location text
    //  Both buttons appear immediately when recording starts.
    //  One tap = WhatsApp opens. One more tap = message sent.
    //  (Zero-tap WhatsApp is impossible on Android without paid API)
    // ═════════════════════════════════════════════════════════════════════

    private void postWhatsAppAlert() {
        String phone = prefs.getString("phone", "").trim();
        if (phone.isEmpty()) return;

        try {
            String fmtPhone = formatPhone(phone);
            // wa.me needs E.164 WITHOUT the + sign  (e.g. 919876543210)
            String digits   = fmtPhone.replaceAll("[^0-9]", "");
            String ts       = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                                    Locale.US).format(new Date());
            String loc      = getLocationText();

            // Text message — no emoji so it works reliably
            String waText = Uri.encode(
                    "EMERGENCY ALERT!\n"
                  + "Time: " + ts + "\n"
                  + "Location: " + loc + "\n"
                  + "Audio recording started on my device.\n"
                  + "Please respond immediately!");

            Uri waUri = Uri.parse("https://wa.me/" + digits + "?text=" + waText);

            Intent waIntent = new Intent(Intent.ACTION_VIEW, waUri);
            waIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent waPI = PendingIntent.getActivity(this, 200, waIntent, piFlags());

            android.app.Notification notif = new NotificationCompat
                    .Builder(this, CHANNEL_ALERT)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("EMERGENCY RECORDING ACTIVE")
                    .setContentText("Tap to send WhatsApp alert to " + fmtPhone)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setAutoCancel(false)
                    .setOngoing(false)
                    .setContentIntent(waPI)
                    .addAction(R.drawable.ic_launcher_foreground,
                               "Send WhatsApp Alert", waPI)
                    .build();

            mgr().notify(202, notif);
            Log.d(TAG, "WhatsApp alert notification posted → " + fmtPhone);

        } catch (Exception e) {
            Log.e(TAG, "postWhatsAppAlert: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Drive backup
    // ═════════════════════════════════════════════════════════════════════

    private void uploadToDrive(String filePath) {
        try {
            File file = new File(filePath);
            if (!file.exists()) return;
            Uri uri = FileProvider.getUriForFile(this, "com.ers.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND)
                    .setType("audio/mp4")
                    .setPackage("com.google.android.apps.docs")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Drive upload skipped: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Open share screen
    // ═════════════════════════════════════════════════════════════════════

    private void openShareScreen(String path) {
        try {
            Intent i = new Intent(this, SendConfirmationActivity.class)
                    .putExtra("recordingPath", path)
                    .putExtra("locationText",  getLocationText())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            mainHandler.postDelayed(() ->
                updateNotif(CHANNEL_SERVICE, "ERS Active",
                        "Hold " + triggerName() + " 3s to START"), 3_000L);
        } catch (Exception e) {
            Log.e(TAG, "openShareScreen: " + e.getMessage());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private String getLocationText() {
        try {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED)
                return "Location permission not granted";

            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location loc = null;
            if (lm != null) {
                loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (loc == null)
                    loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (loc == null)
                    loc = lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER);
            }
            if (loc != null) {
                String lat = String.format(Locale.US, "%.6f", loc.getLatitude());
                String lng = String.format(Locale.US, "%.6f", loc.getLongitude());
                prefs.edit().putString("lastLat", lat)
                            .putString("lastLng", lng).apply();
                return "https://maps.google.com/?q=" + lat + "," + lng;
            }
        } catch (Exception e) {
            Log.e(TAG, "getLocation: " + e.getMessage());
        }
        return "Location unavailable (enable GPS)";
    }

    /**
     * Format phone to E.164 with + prefix.
     * Input must include country code digits.
     * +91XXXXXXXXXX  → +91XXXXXXXXXX  (kept as-is)
     * 91XXXXXXXXXX   → +91XXXXXXXXXX  (+ prepended)
     * 0XXXXXXXXX     → +0XXXXXXXXX   (user must fix — warn in settings)
     */
    private String formatPhone(String raw) {
        String cleaned = raw.replaceAll("[\\s\\-()]", "");
        if (!cleaned.startsWith("+"))
            cleaned = "+" + cleaned.replaceAll("[^0-9]", "");
        return cleaned;
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return getSystemService(SmsManager.class);
        return SmsManager.getDefault();
    }

    private long getMaxDurationMs() {
        switch (prefs.getInt("maxRecordingDuration", 1)) {
            case 0:  return  5 * 60 * 1000L;
            case 1:  return 15 * 60 * 1000L;
            case 2:  return 30 * 60 * 1000L;
            case 3:  return 0L;
            default: return 15 * 60 * 1000L;
        }
    }

    private String fmt(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60);
    }

    private String triggerName() {
        return triggerType == 1 ? "Vol Up" : "Vol Down";
    }

    private int piFlags() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private NotificationManager mgr() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private void wakeScreen() {
        try {
            if (screenWakeLock != null) {
                if (screenWakeLock.isHeld()) screenWakeLock.release();
                screenWakeLock.acquire(60_000L);
            }
        } catch (Exception e) { Log.e(TAG, "wakeScreen: " + e.getMessage()); }
    }

    private boolean requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
                audioFocusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(attrs)
                        .setAcceptsDelayedFocusGain(false)
                        .build();
                return audioManager.requestAudioFocus(audioFocusRequest)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            } else {
                //noinspection deprecation
                return audioManager.requestAudioFocus(null,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            }
        } catch (Exception e) { return true; }
    }

    private void releaseAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                //noinspection deprecation
                audioManager.abandonAudioFocus(null);
            }
        } catch (Exception ignore) {}
    }

    private File prepareOutputFile() {
        try {
            File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (base == null) base = getFilesDir();
            File dir = new File(base, "ERS_Recordings");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return new File(dir, "ERS_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss",
                            Locale.US).format(new Date()) + ".m4a");
        } catch (Exception e) { return null; }
    }

    private boolean initMediaRecorder() {
        try {
            if (mediaRecorder != null) {
                try { mediaRecorder.release(); } catch (Exception ignore) {}
                mediaRecorder = null;
            }
            mediaRecorder = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ? new MediaRecorder(this) : new MediaRecorder();
            try {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            } catch (RuntimeException e) {
                mediaRecorder.setAudioSource(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION);
            }
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(128_000);
            mediaRecorder.setAudioSamplingRate(44_100);
            mediaRecorder.setAudioChannels(1);
            mediaRecorder.setOutputFile(currentRecordingPath);
            mediaRecorder.prepare();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "initMediaRecorder: " + e.getMessage(), e);
            return false;
        }
    }

    private void cleanupMediaRecorder() {
        if (mediaRecorder == null) return;
        try { mediaRecorder.reset();   } catch (Exception ignore) {}
        try { mediaRecorder.release(); } catch (Exception ignore) {}
        mediaRecorder = null;
    }

    // ── Notifications ─────────────────────────────────────────────────────

    private void createNotifChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager m = mgr();
        if (m == null) return;

        // Foreground service — low importance (silent, always visible)
        NotificationChannel svc = new NotificationChannel(
                CHANNEL_SERVICE, "ERS Recording", NotificationManager.IMPORTANCE_LOW);
        svc.setShowBadge(false);
        svc.setSound(null, null);
        m.createNotificationChannel(svc);

        // SOS countdown — default importance
        NotificationChannel sos = new NotificationChannel(
                CHANNEL_SOS, "SOS Countdown", NotificationManager.IMPORTANCE_HIGH);
        sos.setSound(null, null);
        sos.enableVibration(true);
        m.createNotificationChannel(sos);

        // WhatsApp alert — high importance so heads-up shows
        NotificationChannel alert = new NotificationChannel(
                CHANNEL_ALERT, "Emergency Alerts", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("WhatsApp alert — tap to send emergency message");
        alert.enableVibration(true);
        m.createNotificationChannel(alert);
    }

    private android.app.Notification buildNotif(String ch, String title, String text) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags());
        return new NotificationCompat.Builder(this, ch)
                .setContentTitle(title).setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true).setSilent(true)
                .build();
    }

    private void updateNotif(String ch, String title, String text) {
        try { mgr().notify(NOTIF_ID, buildNotif(ch, title, text)); }
        catch (Exception e) { Log.e(TAG, "updateNotif: " + e.getMessage()); }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Boilerplate
    // ═════════════════════════════════════════════════════════════════════

    @Override
    public void onDestroy() {
        if (isSosCountdownActive) cancelSosCountdown();
        if (isRecording)          { canStopRecording = true; stopRecording(); }
        mainHandler.removeCallbacksAndMessages(null);
        recordingThread.shutdown();
        smsThread.shutdown();
        if (smsReceiverRegistered && smsReceiver != null) {
            try { unregisterReceiver(smsReceiver); } catch (Exception ignore) {}
            smsReceiverRegistered = false;
        }
        try {
            if (wakeLock       != null && wakeLock.isHeld())       wakeLock.release();
            if (screenWakeLock != null && screenWakeLock.isHeld()) screenWakeLock.release();
        } catch (Exception ignore) {}
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent i)  { return null; }
    @Override public void onTaskRemoved(Intent rootIntent) { /* keep alive */ }
}
