package org.telegram.wallet.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.web3j.crypto.Credentials;

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
    private static final String KEY_SELECTED_ADDRESS = "selected_wallet_address";
    private static final String KEY_ENCRYPTED_PRIVATE = "encrypted_private_key";

    private WalletKeyStore() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }

    public static void savePrivateKey(Context context, String privateKeyHex) throws GeneralSecurityException {
        String address = Credentials.create(privateKeyHex).getAddress();
        saveWalletPrivateKey(context, address, privateKeyHex);
        setSelectedAddress(context, address);
    }

    public static void saveWalletPrivateKey(Context context, String address, String privateKeyHex) throws GeneralSecurityException {
        String payload = encrypt(privateKeyHex);
        prefs(context).edit().putString(buildWalletKey(address), payload).apply();
    }

    @Nullable
    public static String loadPrivateKey(Context context) throws GeneralSecurityException {
        String selectedAddress = getSelectedAddress(context);
        if (selectedAddress != null) {
            String selectedKey = loadWalletPrivateKey(context, selectedAddress);
            if (selectedKey != null) {
                return selectedKey;
            }
        }

        String payload = prefs(context).getString(KEY_ENCRYPTED_PRIVATE, null);
        if (payload == null) {
            return null;
        }
        return decrypt(payload);
    }

    @Nullable
    public static String loadWalletPrivateKey(Context context, String address) throws GeneralSecurityException {
        String payload = prefs(context).getString(buildWalletKey(address), null);
        if (payload == null) {
            return null;
        }
        return decrypt(payload);
    }

    public static void setSelectedAddress(Context context, String address) {
        prefs(context).edit().putString(KEY_SELECTED_ADDRESS, address).apply();
    }

    @Nullable
    public static String getSelectedAddress(Context context) {
        return prefs(context).getString(KEY_SELECTED_ADDRESS, null);
    }

    private static String encrypt(String plainText) throws GeneralSecurityException {
        if (!isSupported()) {
            throw new GeneralSecurityException("Wallet requires API 23+ in this implementation");
        }
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);

        byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();

        return Base64.encodeToString(iv, Base64.NO_WRAP)
                + ":"
                + Base64.encodeToString(cipherText, Base64.NO_WRAP);
    }

    private static String decrypt(String payload) throws GeneralSecurityException {
        if (!isSupported() || payload == null || !payload.contains(":")) {
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

    private static String buildWalletKey(String address) {
        return "wallet_pk_" + (address == null ? "" : address.toLowerCase());
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
