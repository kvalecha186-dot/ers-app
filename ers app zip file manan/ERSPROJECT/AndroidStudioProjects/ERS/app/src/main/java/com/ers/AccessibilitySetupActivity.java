package com.ers;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import android.content.Context;
import android.provider.Settings.SettingNotFoundException;
import android.text.TextUtils;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityManager;
import java.util.List;

/**
 * Accessibility Setup Guide Activity
 * 
 * Educates users about why AccessibilityService is needed and guides them
 * through enabling it. This is CRITICAL for the app to work with screen off.
 */
public class AccessibilitySetupActivity extends AppCompatActivity {
    
    private TextView titleText, descriptionText, step1Text, step2Text, step3Text, statusText;
    private MaterialButton openSettingsButton, testButton, continueButton, skipButton;
    private MaterialCardView mainCard, statusCard;
    private View statusIndicator;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accessibility_setup);
        
        // Initialize views
        titleText = findViewById(R.id.titleText);
        descriptionText = findViewById(R.id.descriptionText);
        step1Text = findViewById(R.id.step1Text);
        step2Text = findViewById(R.id.step2Text);
        step3Text = findViewById(R.id.step3Text);
        statusText = findViewById(R.id.statusText);
        openSettingsButton = findViewById(R.id.openSettingsButton);
        testButton = findViewById(R.id.testButton);
        continueButton = findViewById(R.id.continueButton);
        skipButton = findViewById(R.id.skipButton);
        mainCard = findViewById(R.id.mainCard);
        statusCard = findViewById(R.id.statusCard);
        statusIndicator = findViewById(R.id.statusIndicator);
        
        // Animate entrance
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in);
        mainCard.startAnimation(slideUp);
        
        openSettingsButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            openAccessibilitySettings();
        });
        
        testButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            checkAccessibilityStatus();
        });
        
        continueButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            if (isAccessibilityServiceEnabled()) {
                goToNextScreen();
            } else {
                Toast.makeText(this, "Please enable Accessibility Service first", 
                    Toast.LENGTH_LONG).show();
            }
        });
        
        skipButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            showSkipWarning();
        });
        
        // Initial status check
        checkAccessibilityStatus();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // Check status when user returns from settings
        checkAccessibilityStatus();
    }
    
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            
            Toast.makeText(this, 
                "Look for 'ERS Emergency Monitor' in the list", 
                Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, 
                "Please open Settings > Accessibility manually", 
                Toast.LENGTH_LONG).show();
        }
    }
    
    private boolean isAccessibilityServiceEnabled() {
        try {
            AccessibilityManager am = (AccessibilityManager) getSystemService(
                Context.ACCESSIBILITY_SERVICE);
            List<AccessibilityServiceInfo> enabledServices = 
                am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            
            String packageName = getPackageName();
            
            // Debug: Log all enabled services
            android.util.Log.d("AccessibilityCheck", "Package: " + packageName);
            android.util.Log.d("AccessibilityCheck", "Found " + enabledServices.size() + " enabled services:");
            for (AccessibilityServiceInfo service : enabledServices) {
                String serviceId = service.getId();
                android.util.Log.d("AccessibilityCheck", "Service ID: " + serviceId);
                
                // Check multiple possible formats
                if (serviceId.contains(packageName + "/" + packageName + ".VolumeButtonAccessibilityService") ||
                    serviceId.contains(packageName + "/.VolumeButtonAccessibilityService") ||
                    serviceId.contains("VolumeButtonAccessibilityService")) {
                    android.util.Log.d("AccessibilityCheck", "✓ MATCH FOUND!");
                    return true;
                }
            }
            android.util.Log.d("AccessibilityCheck", "✗ No match found");
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    private void checkAccessibilityStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        
        if (enabled) {
            // Service is enabled!
            statusCard.setVisibility(View.VISIBLE);
            statusText.setText("✅ ENABLED & WORKING");
            statusText.setTextColor(getResources().getColor(R.color.success));
            statusIndicator.setBackgroundResource(R.drawable.status_indicator);
            
            Animation pulse = AnimationUtils.loadAnimation(this, R.anim.pulse);
            statusIndicator.startAnimation(pulse);
            
            continueButton.setVisibility(View.VISIBLE);
            testButton.setText("✓ Service Active");
            testButton.setEnabled(false);
            
            Toast.makeText(this, 
                "✅ Perfect! Volume button detection is now active", 
                Toast.LENGTH_SHORT).show();
        } else {
            // Service not enabled
            statusCard.setVisibility(View.VISIBLE);
            statusText.setText("❌ NOT ENABLED");
            statusText.setTextColor(getResources().getColor(R.color.error));
            statusIndicator.clearAnimation();
            statusIndicator.setBackgroundColor(
                getResources().getColor(android.R.color.darker_gray));
            
            continueButton.setVisibility(View.GONE);
            testButton.setText("Check Status");
            testButton.setEnabled(true);
        }
    }
    
    private void showSkipWarning() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚠️ Warning: App Won't Work Properly")
            .setMessage("Without Accessibility Service:\n\n" +
                "❌ Volume buttons won't work with screen OFF\n" +
                "❌ Emergency recording will be unreliable\n" +
                "❌ The app's core function will be disabled\n\n" +
                "This service is ESSENTIAL for emergencies.\n\n" +
                "Are you sure you want to skip?")
            .setPositiveButton("Go Back", null)
            .setNegativeButton("Skip Anyway", (dialog, which) -> {
                goToNextScreen();
            })
            .show();
    }
    
    private void goToNextScreen() {
        Intent intent = new Intent(this, TestingActivity.class);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }
    
    @Override
    public void onBackPressed() {
        // Don't allow going back - must complete setup
        Toast.makeText(this, "Please complete the setup", Toast.LENGTH_SHORT).show();
    }
}
