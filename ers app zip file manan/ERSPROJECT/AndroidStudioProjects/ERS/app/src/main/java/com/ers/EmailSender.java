package com.ers;

import android.util.Log;

import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

/**
 * EmailSender — Silent SMTP email via Gmail.
 *
 * Fires automatically the moment recording starts.
 * No user tap. No UI. Runs on a background thread.
 *
 * One-time setup:
 *   myaccount.google.com/apppasswords → create 16-char App Password
 *   Paste it into ERS Settings → Email Sender section.
 */
public class EmailSender {

    private static final String TAG       = "ERS_Email";
    private static final String SMTP_HOST = "smtp.gmail.com";
    private static final String SMTP_PORT = "465";

    public interface Callback {
        void onResult(boolean success, String error);
    }

    /**
     * Send emergency email via Gmail SMTP.
     * Call from a background thread only — never from main thread.
     */
    public static void send(String toEmail,
                             String fromEmail,
                             String appPwd,
                             String timestamp,
                             String location,
                             Callback callback) {

        // App Password is shown with spaces by Google — strip them
        String cleanPwd = appPwd.replaceAll("\\s", "");

        if (toEmail.isEmpty() || fromEmail.isEmpty() || cleanPwd.isEmpty()) {
            Log.w(TAG, "⚠️ SMTP config incomplete — skipping email");
            if (callback != null) callback.onResult(false, "Config incomplete");
            return;
        }

        Properties props = new Properties();
        props.put("mail.smtp.host",                   SMTP_HOST);
        props.put("mail.smtp.port",                   SMTP_PORT);
        props.put("mail.smtp.auth",                   "true");
        props.put("mail.smtp.socketFactory.port",     SMTP_PORT);
        props.put("mail.smtp.socketFactory.class",    "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.socketFactory.fallback", "false");
        props.put("mail.smtp.ssl.checkserveridentity","true");
        props.put("mail.smtp.timeout",                "15000");
        props.put("mail.smtp.connectiontimeout",      "15000");

        final String pwd  = cleanPwd;
        final String from = fromEmail;

        try {
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(from, pwd);
                }
            });

            // ── FIX: declare as MimeMessage, NOT Message interface ─────────
            // Message (interface) only has setText(String) — 1 arg.
            // MimeMessage (class) has setText(String, String charset) — 2 args.
            // Using the interface caused: "actual and formal argument lists differ in length"
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(fromEmail));
            msg.setRecipients(MimeMessage.RecipientType.TO,
                              InternetAddress.parse(toEmail));
            msg.setSubject("🚨 EMERGENCY ALERT — " + timestamp);

            // setContent is used instead of setText so charset is guaranteed UTF-8
            // and emoji in the body survive correctly on all mail clients
            msg.setContent(buildBody(timestamp, location), "text/plain; charset=UTF-8");

            Transport.send(msg);

            Log.d(TAG, "✅ Emergency email sent → " + toEmail);
            if (callback != null) callback.onResult(true, null);

        } catch (MessagingException e) {
            Log.e(TAG, "❌ SMTP error: " + e.getMessage(), e);
            if (callback != null) callback.onResult(false, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "❌ Email exception: " + e.getMessage(), e);
            if (callback != null) callback.onResult(false, e.getMessage());
        }
    }

    private static String buildBody(String timestamp, String location) {
        return  "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
              + "EMERGENCY ALERT\n"
              + "ERS — Emergency Recording System\n"
              + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n"
              + "Time:       " + timestamp + "\n"
              + "Location:   " + location  + "\n\n"
              + "AUDIO RECORDING HAS STARTED ON THIS DEVICE.\n\n"
              + "Please respond IMMEDIATELY.\n\n"
              + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
              + "Sent automatically by ERS when emergency trigger was activated.\n"
              + "Do not reply to this email.";
    }
}
