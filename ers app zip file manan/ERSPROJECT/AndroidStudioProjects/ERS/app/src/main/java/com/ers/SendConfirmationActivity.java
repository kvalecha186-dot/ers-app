package com.ers;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * SendConfirmationActivity
 *
 * Opens as a dialog after recording stops. Gives the user 4 options:
 *
 *   [WhatsApp Audio]  — shares the .m4a recording file to WhatsApp
 *                       routes directly to saved contact via "jid"
 *   [WhatsApp Text]   — opens WhatsApp with location pre-filled as text
 *   [Send SMS]        — re-sends the SMS alert manually (useful if auto
 *                       SMS failed due to no signal at trigger time)
 *   [Save Only]       — closes without sending (file is already saved)
 *
 * WHATSAPP AUDIO FIX:
 *   Previous MIME type was "audio/mp4" — newer WhatsApp (2.23+) requires
 *   "audio/*" wildcard or it silently refuses to accept the file.
 *   jid must be digits-only E.164 format WITHOUT + sign.
 *   e.g. +91XXXXXXXXXX stored in prefs → strip + → 91XXXXXXXXXX@s.whatsapp.net
 */
public class SendConfirmationActivity extends Activity {

    private static final String TAG           = "ERS_Share";
    private static final String WA_PACKAGE    = "com.whatsapp";
    private static final String WA_B_PACKAGE  = "com.whatsapp.w4b";
    private static final String FILE_PROVIDER = "com.ers.fileprovider";

    private String recordingPath;
    private String locationText;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs         = getSharedPreferences("ERSSettings", MODE_PRIVATE);
        recordingPath = getIntent().getStringExtra("recordingPath");
        locationText  = getIntent().getStringExtra("locationText");

        // ── Guard: path must exist ─────────────────────────────────────
        if (recordingPath == null || recordingPath.isEmpty()) {
            Log.e(TAG, "No recording path in Intent");
            finish();
            return;
        }
        File file = new File(recordingPath);
        if (!file.exists() || file.length() == 0) {
            Toast.makeText(this,
                    "Recording file not found. Check Recordings screen.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // ── Resolve location ───────────────────────────────────────────
        if (locationText == null
                || locationText.contains("unavailable")
                || locationText.contains("not granted")) {
            String lat = prefs.getString("lastLat", "");
            String lng = prefs.getString("lastLng", "");
            locationText = (!lat.isEmpty() && !lng.isEmpty())
                    ? "https://maps.google.com/?q=" + lat + "," + lng
                    : "Location unavailable — enable GPS";
        }

        // ── Build dialog ───────────────────────────────────────────────
        String phone = prefs.getString("phone", "").trim();
        String email = prefs.getString("email", "").trim();
        double sizeMB = file.length() / (1024.0 * 1024.0);

        String msg = String.format(Locale.US,
                "Recording: %.2f MB\nLocation: %s\n\nSMS was sent automatically at trigger time.\nChoose action:",
                sizeMB, locationText);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Emergency Recording Ready")
                .setMessage(msg)
                .setCancelable(false);

        // Button 1 — WhatsApp Audio
        if (!phone.isEmpty()) {
            b.setPositiveButton("WhatsApp Audio",
                    (d, w) -> sendAudioViaWhatsApp(file, phone));
        }

        // Button 2 — WhatsApp Text with location (neutral slot)
        if (!phone.isEmpty()) {
            b.setNeutralButton("WhatsApp Text+Location",
                    (d, w) -> sendWhatsAppTextWithLocation(phone));
        }

        // Button 3 — Negative slot shows the most critical option
        // If no WhatsApp, show SMS resend; otherwise Save
        if (!phone.isEmpty()) {
            b.setNegativeButton("Resend SMS",
                    (d, w) -> resendSmsManually(phone));
        } else {
            b.setNegativeButton("Save Only", (d, w) -> finish());
        }

        // We only have 3 button slots in AlertDialog.
        // If user has email, show a second dialog after WhatsApp
        AlertDialog dialog = b.create();
        dialog.show();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  1. WhatsApp Audio (FIXED)
    //
    //  Key fixes:
    //  a) MIME type: "audio/*" instead of "audio/mp4"
    //     WhatsApp 2.23+ rejects exact MIME on some devices
    //  b) jid: digits only, NO + sign
    //     "+91XXXXXXXXXX" → "91XXXXXXXXXX@s.whatsapp.net"
    //  c) grantUriPermission explicitly before startActivity
    // ══════════════════════════════════════════════════════════════════════

    private void sendAudioViaWhatsApp(File file, String phone) {
        try {
            String fmtPhone   = formatPhone(phone);
            // jid = E.164 digits only (no + sign) + @s.whatsapp.net
            String jidDigits  = fmtPhone.replaceAll("[^0-9]", "");
            String jid        = jidDigits + "@s.whatsapp.net";

            Log.d(TAG, "WhatsApp audio → jid=" + jid);

            Uri uri = FileProvider.getUriForFile(this, FILE_PROVIDER, file);

            // Grant URI read to WhatsApp explicitly
            grantUriPermission(WA_PACKAGE,   uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            grantUriPermission(WA_B_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent waIntent = new Intent(Intent.ACTION_SEND);
            waIntent.setType("audio/*");                               // FIXED: wildcard
            waIntent.putExtra(Intent.EXTRA_STREAM, uri);
            waIntent.putExtra("jid", jid);                             // direct contact route
            waIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Try WhatsApp
            if (isInstalled(WA_PACKAGE)) {
                waIntent.setPackage(WA_PACKAGE);
                try {
                    startActivity(waIntent);
                    Toast.makeText(this,
                            "WhatsApp opened — tap the send button to deliver audio",
                            Toast.LENGTH_LONG).show();
                    markSent(file);
                    handler.postDelayed(this::finish, 2000);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "WhatsApp (with jid) failed: " + e.getMessage());
                    // retry without jid
                    waIntent.removeExtra("jid");
                    try {
                        startActivity(waIntent);
                        Toast.makeText(this,
                                "WhatsApp opened — select contact and tap send",
                                Toast.LENGTH_LONG).show();
                        markSent(file);
                        handler.postDelayed(this::finish, 2000);
                        return;
                    } catch (Exception e2) {
                        Log.w(TAG, "WhatsApp (no jid) failed: " + e2.getMessage());
                    }
                }
            }

            // Try WhatsApp Business
            if (isInstalled(WA_B_PACKAGE)) {
                waIntent.setPackage(WA_B_PACKAGE);
                waIntent.putExtra("jid", jid);
                try {
                    startActivity(waIntent);
                    Toast.makeText(this,
                            "WhatsApp Business opened — tap send",
                            Toast.LENGTH_LONG).show();
                    markSent(file);
                    handler.postDelayed(this::finish, 2000);
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "WhatsApp Business failed: " + e.getMessage());
                }
            }

            // Fallback — system chooser
            Log.w(TAG, "WhatsApp unavailable — opening system chooser");
            openChooser(file);

        } catch (Exception e) {
            Log.e(TAG, "sendAudioViaWhatsApp: " + e.getMessage(), e);
            openChooser(file);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  2. WhatsApp Text + Location
    //  Opens WhatsApp chat to the saved contact with location pre-filled.
    //  User taps Send (one tap).
    // ══════════════════════════════════════════════════════════════════════

    private void sendWhatsAppTextWithLocation(String phone) {
        try {
            String fmtPhone = formatPhone(phone);
            String digits   = fmtPhone.replaceAll("[^0-9]", "");
            String ts       = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                                    Locale.US).format(new Date());
            String text     = Uri.encode(
                    "EMERGENCY ALERT!\n"
                  + "Time: " + ts + "\n"
                  + "Location: " + locationText + "\n"
                  + "Audio recording in progress. Please respond immediately!");

            Uri uri = Uri.parse("https://wa.me/" + digits + "?text=" + text);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            handler.postDelayed(this::finish, 1500);

        } catch (Exception e) {
            Log.e(TAG, "sendWhatsAppTextWithLocation: " + e.getMessage());
            Toast.makeText(this, "Could not open WhatsApp.", Toast.LENGTH_SHORT).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  3. Manual SMS resend
    //  Fires if the automatic SMS failed (no signal at trigger time).
    //  Uses same body as the auto-SMS — plain ASCII, no emoji.
    // ══════════════════════════════════════════════════════════════════════

    private void resendSmsManually(String phone) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,
                    "SMS permission not granted. Grant it in App Settings.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        try {
            String fmtPhone = formatPhone(phone);
            String ts       = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                                    Locale.US).format(new Date());
            String msg = "EMERGENCY ALERT (manual resend)\n"
                       + "Time: " + ts + "\n"
                       + "Location: " + locationText + "\n"
                       + "Recording saved on device. Please respond!";

            SmsManager sms = getSmsManager();
            if (sms == null) {
                Toast.makeText(this, "SmsManager unavailable — check SIM.", Toast.LENGTH_LONG).show();
                return;
            }

            ArrayList<String> parts = sms.divideMessage(msg);
            if (parts.size() > 1) {
                sms.sendMultipartTextMessage(fmtPhone, null, parts,
                        new ArrayList<>(Collections.nCopies(parts.size(), null)),
                        new ArrayList<>(Collections.nCopies(parts.size(), null)));
            } else {
                sms.sendTextMessage(fmtPhone, null, msg, null, null);
            }

            Toast.makeText(this, "SMS sent to " + fmtPhone, Toast.LENGTH_LONG).show();
            Log.d(TAG, "Manual SMS sent to " + fmtPhone);
            handler.postDelayed(this::finish, 2000);

        } catch (Exception e) {
            Log.e(TAG, "resendSmsManually: " + e.getMessage(), e);
            Toast.makeText(this, "SMS failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  System share chooser (fallback)
    // ══════════════════════════════════════════════════════════════════════

    private void openChooser(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, FILE_PROVIDER, file);
            String ts = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss",
                            Locale.US).format(new Date());

            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("audio/*")
                    .putExtra(Intent.EXTRA_STREAM,  uri)
                    .putExtra(Intent.EXTRA_TEXT,
                            "EMERGENCY ALERT\nLocation: " + locationText + "\nTime: " + ts)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(share, "Share Emergency Recording via"));
            markSent(file);
            handler.postDelayed(this::finish, 1500);

        } catch (Exception e) {
            Log.e(TAG, "openChooser: " + e.getMessage());
            Toast.makeText(this,
                    "File saved. Open Recordings screen to share manually.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private String formatPhone(String raw) {
        String c = raw.replaceAll("[\\s\\-()]", "");
        if (!c.startsWith("+")) c = "+" + c.replaceAll("[^0-9]", "");
        return c;
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void markSent(File file) {
        prefs.edit()
             .putBoolean("sent_" + file.getName(), true)
             .apply();
    }

    @SuppressWarnings("deprecation")
    private SmsManager getSmsManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            return getSystemService(SmsManager.class);
        return SmsManager.getDefault();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
