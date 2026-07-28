package com.ers;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class TestingActivity extends AppCompatActivity {
    private TextView titleText, instructionText, timerText, statusText;
    private MaterialButton startTestButton, skipButton;
    private MaterialCardView mainCard, settingsCard;
    private RadioGroup triggerTypeGroup;
    private RadioButton volumeDownRadio, volumeUpRadio;
    
    private Vibrator vibrator;
    private SharedPreferences preferences;
    private Handler handler = new Handler();
    
    private boolean testStarted = false;
    private boolean isHoldingButton = false;
    private boolean recordingPhase = false;
    private boolean waitingToStop = false;
    private boolean canStop = false;
    private long holdStartTime = 0;
    private static final long HOLD_DURATION = 5000; // 5 seconds
    private static final long RECORDING_WAIT = 5000; // 5 seconds wait before can stop
    
    private int currentTriggerType = 0; // 0 = Volume Down, 1 = Volume Up
    
    private MediaRecorder mediaRecorder;
    private String testRecordingPath;
    
    // Permission request code
    private static final int PERMISSION_REQUEST_CODE = 100;
    
    private Runnable holdCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (isHoldingButton) {
                long holdTime = System.currentTimeMillis() - holdStartTime;
                int seconds = (int) (holdTime / 1000);
                
                if (seconds < 5) {
                    statusText.setText("HOLDING... " + (5 - seconds) + "s");
                    handler.postDelayed(this, 100);
                } else {
                    // 5 seconds complete!
                    onHoldComplete();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_testing);

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        preferences = getSharedPreferences("ERSSettings", MODE_PRIVATE);

        // Initialize views
        titleText = findViewById(R.id.titleText);
        instructionText = findViewById(R.id.instructionText);
        timerText = findViewById(R.id.timerText);
        statusText = findViewById(R.id.statusText);
        startTestButton = findViewById(R.id.startTestButton);
        skipButton = findViewById(R.id.skipButton);
        mainCard = findViewById(R.id.mainCard);
        settingsCard = findViewById(R.id.settingsCard);
        triggerTypeGroup = findViewById(R.id.triggerTypeGroup);
        volumeDownRadio = findViewById(R.id.volumeDownRadio);
        volumeUpRadio = findViewById(R.id.volumeUpRadio);

        // Load saved trigger type
        currentTriggerType = preferences.getInt("triggerType", 0);
        if (currentTriggerType == 0) {
            volumeDownRadio.setChecked(true);
        } else {
            volumeUpRadio.setChecked(true);
        }

        // Animate card entrance
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        mainCard.startAnimation(slideUp);

        // Setup trigger type selection
        triggerTypeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.volumeDownRadio) {
                currentTriggerType = 0;
                preferences.edit().putInt("triggerType", 0).apply();
                Toast.makeText(this, "✓ Volume Down selected", Toast.LENGTH_SHORT).show();
            } else if (checkedId == R.id.volumeUpRadio) {
                currentTriggerType = 1;
                preferences.edit().putInt("triggerType", 1).apply();
                Toast.makeText(this, "✓ Volume Up selected", Toast.LENGTH_SHORT).show();
            }
            updateInstructions();
        });

        startTestButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            checkPermissionAndStartTest();
        });

        skipButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            skipToContactSetup();
        });

        updateInstructions();
    }

    private void checkPermissionAndStartTest() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            
            // Show rationale if needed
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                new AlertDialog.Builder(this)
                    .setTitle("Microphone Permission Required")
                    .setMessage("This app needs microphone access to test emergency recording functionality. Without this permission, you won't be able to complete the test.")
                    .setPositiveButton("Grant Permission", (dialog, which) -> {
                        requestAudioPermission();
                    })
                    .setNegativeButton("Skip Test", (dialog, which) -> {
                        skipToContactSetup();
                    })
                    .show();
            } else {
                requestAudioPermission();
            }
        } else {
            // Permission already granted
            startTest();
        }
    }

    private void requestAudioPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                PERMISSION_REQUEST_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted - start test
                Toast.makeText(this, "✓ Permission granted! Starting test...", Toast.LENGTH_SHORT).show();
                startTest();
            } else {
                // Permission denied
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    // User denied but can ask again
                    new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("Microphone permission is essential for testing the emergency recording feature. You can skip the test and continue with setup.")
                        .setPositiveButton("Try Again", (dialog, which) -> {
                            requestAudioPermission();
                        })
                        .setNegativeButton("Skip Test", (dialog, which) -> {
                            skipToContactSetup();
                        })
                        .show();
                } else {
                    // User denied with "Don't ask again"
                    new AlertDialog.Builder(this)
                        .setTitle("Permission Denied")
                        .setMessage("You've denied microphone permission. To enable it, go to:\n\nSettings > Apps > ERS > Permissions > Microphone\n\nOr skip the test and continue with setup.")
                        .setPositiveButton("Skip Test", (dialog, which) -> {
                            skipToContactSetup();
                        })
                        .setNeutralButton("OK", null)
                        .show();
                }
            }
        }
    }

    private void updateInstructions() {
        String buttonName = (currentTriggerType == 0) ? "Volume Down" : "Volume Up";
        if (!testStarted) {
            instructionText.setText("Selected: " + buttonName + " button\n\nYou'll learn to HOLD the button for 5 seconds\n\nPress Start Test when ready");
        }
    }

    private void startTest() {
        testStarted = true;
        recordingPhase = false;
        
        // Hide setup UI
        startTestButton.setVisibility(View.GONE);
        skipButton.setVisibility(View.GONE);
        settingsCard.setVisibility(View.GONE);
        
        String buttonName = (currentTriggerType == 0) ? "Volume Down" : "Volume Up";
        titleText.setText("🎯 Step 1: Start Recording");
        instructionText.setText("HOLD " + buttonName + " button for 5 SECONDS\n\nKeep holding until phone vibrates!");
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("Ready... HOLD the button NOW!");
        
        vibrateLong();
        
        Toast.makeText(this, "HOLD " + buttonName + " for 5 seconds!", Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!testStarted) {
            return super.onKeyDown(keyCode, event);
        }

        boolean isVolumeDown = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean isVolumeUp = (keyCode == KeyEvent.KEYCODE_VOLUME_UP);
        
        if ((currentTriggerType == 0 && isVolumeDown) || 
            (currentTriggerType == 1 && isVolumeUp)) {
            
            if (!isHoldingButton) {
                // Start holding
                isHoldingButton = true;
                holdStartTime = System.currentTimeMillis();
                vibrateShort();
                handler.post(holdCheckRunnable);
            }
            
            return true; // Consume the event
        }
        
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!testStarted) {
            return super.onKeyUp(keyCode, event);
        }

        boolean isVolumeDown = (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN);
        boolean isVolumeUp = (keyCode == KeyEvent.KEYCODE_VOLUME_UP);
        
        if ((currentTriggerType == 0 && isVolumeDown) || 
            (currentTriggerType == 1 && isVolumeUp)) {
            
            if (isHoldingButton) {
                long holdTime = System.currentTimeMillis() - holdStartTime;
                
                if (holdTime < HOLD_DURATION) {
                    // Released too early
                    isHoldingButton = false;
                    handler.removeCallbacks(holdCheckRunnable);
                    
                    int seconds = (int) (holdTime / 1000);
                    statusText.setText("Released too early! (" + seconds + "s) Try again!");
                    vibrateShort();
                    
                    // Reset after 2 seconds
                    new Handler().postDelayed(() -> {
                        if (testStarted && !recordingPhase) {
                            statusText.setText("Ready... HOLD the button NOW!");
                        } else if (waitingToStop && !canStop) {
                            statusText.setText("Wait " + getRemainingWaitTime() + "s before stopping...");
                        } else if (waitingToStop && canStop) {
                            statusText.setText("Ready... HOLD to STOP!");
                        }
                    }, 2000);
                }
            }
            
            return true;
        }
        
        return super.onKeyUp(keyCode, event);
    }

    private void onHoldComplete() {
        isHoldingButton = false;
        handler.removeCallbacks(holdCheckRunnable);
        vibrateLong();
        
        if (!recordingPhase) {
            // Complete hold for starting
            startRecordingPhase();
        } else if (waitingToStop && canStop) {
            // Complete hold for stopping
            stopRecordingPhase();
        }
    }

    private void startRecordingPhase() {
        recordingPhase = true;
        waitingToStop = true;
        canStop = false;
        
        titleText.setText("✅ Perfect Hold!");
        instructionText.setText("Recording started!\n\nWait 5 seconds before you can stop...");
        statusText.setText("Recording... Please wait...");
        timerText.setVisibility(View.VISIBLE);
        
        // Start actual recording
        startActualRecording();
        
        // 5 second countdown
        new CountDownTimer(RECORDING_WAIT, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long secondsRemaining = millisUntilFinished / 1000;
                timerText.setText(secondsRemaining + "s");
                statusText.setText("Wait " + secondsRemaining + "s before stopping...");
                
                Animation pulse = AnimationUtils.loadAnimation(TestingActivity.this, R.anim.pulse);
                timerText.startAnimation(pulse);
            }

            @Override
            public void onFinish() {
                canStop = true;
                timerText.setVisibility(View.GONE);
                showStopInstructions();
            }
        }.start();
    }

    private void startActualRecording() {
        // Double-check permission before recording
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission not granted", Toast.LENGTH_LONG).show();
            skipToContactSetup();
            return;
        }
        
        try {
            File musicDirectory = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            File outputDirectory = new File(musicDirectory, "ERS_Recordings");
            if (!outputDirectory.exists()) {
                outputDirectory.mkdirs();
            }
            
            testRecordingPath = outputDirectory.getAbsolutePath() + "/" + 
                "TEST_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(this);
            } else {
                mediaRecorder = new MediaRecorder();
            }
            
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioEncodingBitRate(192000);
            mediaRecorder.setAudioSamplingRate(48000);
            mediaRecorder.setOutputFile(testRecordingPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Recording failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            skipToContactSetup();
        } catch (IllegalStateException e) {
            e.printStackTrace();
            Toast.makeText(this, "Recording setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            skipToContactSetup();
        }
    }

    private void showStopInstructions() {
        String buttonName = (currentTriggerType == 0) ? "Volume Down" : "Volume Up";
        titleText.setText("🎯 Step 2: Stop Recording");
        instructionText.setText("Now HOLD " + buttonName + " button for 5 SECONDS to STOP\n\nKeep holding until phone vibrates!");
        statusText.setText("Ready... HOLD to STOP!");
        vibrateLong();
        
        Toast.makeText(this, "HOLD " + buttonName + " for 5 seconds to STOP!", Toast.LENGTH_LONG).show();
    }

    private void stopRecordingPhase() {
        waitingToStop = false;
        recordingPhase = false;
        testStarted = false;
        
        // Stop recording
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
                mediaRecorder.release();
                mediaRecorder = null;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        vibrateDouble();
        
        titleText.setText("✅ Excellent!");
        instructionText.setText("Perfect! You've learned how the app works.\n\nNow let's test sending the recording...");
        statusText.setText("Test completed successfully!");
        timerText.setVisibility(View.GONE);
        
        // Show demo send button
        new Handler().postDelayed(() -> {
            showDemoSendOptions();
        }, 1500);
    }

    private void showDemoSendOptions() {
        instructionText.setText("Let's test how recordings are sent in emergency:\n\n✉️ Choose WhatsApp or Email to see the demo");
        statusText.setVisibility(View.GONE);
        
        // Show WhatsApp and Email buttons
        skipButton.setVisibility(View.VISIBLE);
        skipButton.setText("Continue to Setup");
        skipButton.setBackgroundTintList(getResources().getColorStateList(R.color.primary));
        skipButton.setOnClickListener(v -> goToContactSetup());
        
        startTestButton.setVisibility(View.VISIBLE);
        startTestButton.setText("📱 Test WhatsApp Send");
        startTestButton.setBackgroundTintList(getResources().getColorStateList(R.color.success));
        startTestButton.setOnClickListener(v -> testWhatsAppSend());
        
        // Add email button
        settingsCard.setVisibility(View.VISIBLE);
        settingsCard.removeAllViews();
        
        MaterialButton emailButton = new MaterialButton(this);
        emailButton.setText("📧 Test Email Send");
        emailButton.setCornerRadius(50);
        emailButton.setBackgroundTintList(getResources().getColorStateList(R.color.info));
        emailButton.setTextColor(getResources().getColor(R.color.white));
        emailButton.setTextSize(16);
        android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(40, 40, 40, 40);
        emailButton.setLayoutParams(params);
        emailButton.setOnClickListener(v -> testEmailSend());
        settingsCard.addView(emailButton);
    }

    private void testWhatsAppSend() {
        if (testRecordingPath == null) {
            Toast.makeText(this, "No recording found", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            File recordingFile = new File(testRecordingPath);
            Uri fileUri = FileProvider.getUriForFile(this, "com.ers.fileprovider", recordingFile);
            
            Intent whatsappIntent = new Intent(Intent.ACTION_SEND);
            whatsappIntent.setType("audio/*");
            whatsappIntent.setPackage("com.whatsapp");
            whatsappIntent.putExtra(Intent.EXTRA_TEXT, 
                "🎯 DEMO: This is how ERS sends emergency recordings!\n\nIn real emergency, your location and timestamp will be included.");
            whatsappIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            whatsappIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(whatsappIntent);
            Toast.makeText(this, "✅ WhatsApp opened! This is how it works in emergency", Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "WhatsApp not installed", Toast.LENGTH_SHORT).show();
        }
    }

    private void testEmailSend() {
        if (testRecordingPath == null) {
            Toast.makeText(this, "No recording found", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            File recordingFile = new File(testRecordingPath);
            Uri fileUri = FileProvider.getUriForFile(this, "com.ers.fileprovider", recordingFile);
            
            Intent emailIntent = new Intent(Intent.ACTION_SEND);
            emailIntent.setType("audio/*");
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, "🎯 DEMO: ERS Emergency Recording Test");
            emailIntent.putExtra(Intent.EXTRA_TEXT, 
                "This is how ERS sends emergency recordings via email!\n\nIn real emergency, your location and timestamp will be included.");
            emailIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(Intent.createChooser(emailIntent, "Test Email Send"));
            Toast.makeText(this, "✅ Email app opened! This is how it works in emergency", Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error opening email", Toast.LENGTH_SHORT).show();
        }
    }

    private void skipToContactSetup() {
        goToContactSetup();
    }

    private void goToContactSetup() {
        // Clean up test recording
        if (testRecordingPath != null) {
            try {
                File file = new File(testRecordingPath);
                if (file.exists()) {
                    file.delete();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        Intent intent = new Intent(this, ContactSetupActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    private int getRemainingWaitTime() {
        long elapsed = System.currentTimeMillis() - holdStartTime;
        return (int) ((RECORDING_WAIT - elapsed) / 1000);
    }

    private void vibrateShort() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(100);
            }
        }
    }

    private void vibrateLong() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(500);
            }
        }
    }

    private void vibrateDouble() {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200, 100, 200}, -1));
            } else {
                vibrator.vibrate(new long[]{0, 200, 100, 200, 100, 200}, -1);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        
        if (mediaRecorder != null) {
            try {
                mediaRecorder.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (!testStarted) {
            super.onBackPressed();
        } else {
            Toast.makeText(this, "Please complete the test", Toast.LENGTH_SHORT).show();
        }
    }
}
