package com.ers;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

public class ContactSetupActivity extends AppCompatActivity {

    private TextView      titleText, subtitleText;
    private EditText      emailInput, phoneInput;
    private MaterialButton saveButton, skipButton;
    private MaterialCardView mainCard;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_setup);

        prefs       = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        titleText   = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        emailInput  = findViewById(R.id.emailInput);
        phoneInput  = findViewById(R.id.phoneInput);
        saveButton  = findViewById(R.id.saveButton);
        skipButton  = findViewById(R.id.skipButton);
        mainCard    = findViewById(R.id.mainCard);

        emailInput.setText(prefs.getString("email", ""));
        phoneInput.setText(prefs.getString("phone", ""));

        mainCard.startAnimation(AnimationUtils.loadAnimation(this, R.anim.slide_up_fade_in));

        saveButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            saveContacts();
        });

        skipButton.setOnClickListener(v -> {
            v.startAnimation(AnimationUtils.loadAnimation(this, R.anim.button_click));
            skipSetup();
        });
    }

    private void saveContacts() {
        String email = emailInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();

        if (email.isEmpty() && phone.isEmpty()) {
            Toast.makeText(this, "⚠️ Please enter at least one contact", Toast.LENGTH_SHORT).show();
            return;
        }

        // Validate email format
        if (!email.isEmpty() &&
                !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Please enter a valid email address");
            emailInput.requestFocus();
            return;
        }

        // [BUG 5 FIX] Proper phone validation — must include country code (+ prefix)
        // Old code only checked length >= 10, which accepted numbers without country code.
        // A bare 10-digit number would have +91 blindly prepended, wrong for non-Indian users.
        if (!phone.isEmpty()) {
            String cleaned = phone.replaceAll("[\\s\\-()]", "");
            boolean hasPlus    = cleaned.startsWith("+");
            boolean longEnough = cleaned.replaceAll("[^0-9]", "").length() >= 10;

            if (!hasPlus) {
                phoneInput.setError(
                        "Please include country code (e.g. +91 for India, +1 for US, +44 for UK)");
                phoneInput.requestFocus();
                return;
            }
            if (!longEnough) {
                phoneInput.setError("Phone number too short — include full number with country code");
                phoneInput.requestFocus();
                return;
            }
        }

        SharedPreferences.Editor ed = prefs.edit();
        if (!email.isEmpty()) ed.putString("email", email);
        if (!phone.isEmpty()) ed.putString("phone", phone);
        ed.putBoolean("contactSetupShown", true);
        ed.apply();

        Toast.makeText(this, "✅ Contacts saved!", Toast.LENGTH_SHORT).show();
        goToMain();
    }

    private void skipSetup() {
        prefs.edit().putBoolean("contactSetupShown", true).apply();
        Toast.makeText(this, "⚠️ You can set contacts later in Settings", Toast.LENGTH_LONG).show();
        goToMain();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Allow going back to onboarding — [Bug 16 FIX]
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
