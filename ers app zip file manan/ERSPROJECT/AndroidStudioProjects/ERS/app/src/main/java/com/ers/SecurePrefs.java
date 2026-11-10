package com.ers;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts small secrets with an AES-256 key held by Android Keystore.
 * The key is non-exportable; only ciphertext is stored in SharedPreferences.
 */
public final class SecurePrefs {
    private static final String TAG = "ERS_SecurePrefs";
    private static final String PREFS = "ERSSettings";
    private static final String KEY_ALIAS = "com.ers.smtp-secret.v1";
    private static final String IV_KEY = "smtpPassword_encrypted";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private SecurePrefs() {}

    public static synchronized String getString(Context context, String name) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(name + "_encrypted", null);
        if (encrypted != null) {
            try {
                return decrypt(encrypted);
            } catch (Exception error) {
                Log.e(TAG, "Unable to decrypt stored secret", error);
                return "";
            }
        }

        // One-time migration from the previous plaintext preference format.
        String legacy = prefs.getString(name, "");
        if (legacy != null && !legacy.isEmpty()) {
            if (putString(context, name, legacy)) {
                prefs.edit().remove(name).apply();
                return legacy;
            }
            Log.e(TAG, "Could not migrate legacy secret; leaving it untouched");
        }
        return "";
    }

    public static synchronized boolean putString(Context context, String name, String value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(name + "_encrypted").remove(name).apply();
            return true;
        }
        try {
            String encrypted = encrypt(value);
            prefs.edit().putString(name + "_encrypted", encrypted).remove(name).apply();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to encrypt secret", error);
            return false;
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((SecretKey) keyStore.getKey(KEY_ALIAS, null));
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(iv.length + ciphertext.length);
        payload.put(iv).put(ciphertext);
        return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    }

    private static String decrypt(String value) throws Exception {
        byte[] payload = Base64.decode(value, Base64.NO_WRAP);
        if (payload.length <= IV_LENGTH_BYTES) throw new IllegalArgumentException("Invalid encrypted secret");
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        byte[] iv = new byte[IV_LENGTH_BYTES];
        buffer.get(iv);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }
}
