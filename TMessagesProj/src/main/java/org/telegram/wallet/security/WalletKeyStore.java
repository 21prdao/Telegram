package org.telegram.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class WalletKeyStore {

    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "tg_wallet_aes_key";
    private static final String PREFS = "tg_wallet_secure";
    private static final String KEY_ENCRYPTED_PRIVATE = "encrypted_private_key";

    private WalletKeyStore() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static void savePrivateKey(Context context, String privateKeyHex) throws GeneralSecurityException {
        if (!isSupported()) {
            throw new GeneralSecurityException("Wallet requires API 23+ in this implementation");
        }

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        byte[] cipherText = cipher.doFinal(privateKeyHex.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();

        String payload = Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(cipherText, Base64.NO_WRAP);

        prefs(context).edit().putString(KEY_ENCRYPTED_PRIVATE, payload).apply();
    }

    @Nullable
    public static String loadPrivateKey(Context context) throws GeneralSecurityException {
        if (!isSupported()) {
            return null;
        }
        String payload = prefs(context).getString(KEY_ENCRYPTED_PRIVATE, null);
        if (payload == null || !payload.contains(":")) {
            return null;
        }

        String[] parts = payload.split(":", 2);
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));

        byte[] plain = cipher.doFinal(cipherText);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
            ks.load(null);

            if (!ks.containsAlias(KEY_ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                );
                generator.init(new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setUserAuthenticationRequired(false)
                        .build());
                generator.generateKey();
            }

            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null);
            return entry.getSecretKey();
        } catch (Exception e) {
            throw new GeneralSecurityException("Unable to access Android Keystore", e);
        }
    }
}