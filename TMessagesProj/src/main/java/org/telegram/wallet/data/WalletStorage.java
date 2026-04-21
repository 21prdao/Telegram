package org.telegram.wallet.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.model.WalletAccount;
import org.telegram.wallet.security.WalletKeyStore;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class WalletStorage {

    private static final String PREFS = "tg_web3_wallet_meta";
    private static final String KEY_WALLETS = "wallets_json";
    private static final String KEY_SELECTED = "selected_wallet";
    private static final String KEY_TOKENS = "tokens_json";

    private WalletStorage() {
    }

    public static List<WalletAccount> getWallets(Context context) {
        String json = prefs(context).getString(KEY_WALLETS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            List<WalletAccount> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                WalletAccount account = new WalletAccount();
                account.address = o.optString("address", "");
                account.name = o.optString("name", "Wallet " + (i + 1));
                account.createdAt = o.optLong("createdAt", System.currentTimeMillis());
                result.add(account);
            }
            return result;
        } catch (Throwable ignore) {
            return new ArrayList<>();
        }
    }

    public static void createWallet(Context context, @Nullable String walletName) throws GeneralSecurityException {
        String privateKeyHex = generatePrivateKeyHex();
        addWallet(context, privateKeyHex, walletName);
    }

    public static void importWallet(Context context, String privateKeyHex, @Nullable String walletName) throws GeneralSecurityException {
        addWallet(context, normalizePrivateKey(privateKeyHex), walletName);
    }

    private static void addWallet(Context context, String privateKeyHex, @Nullable String walletName) throws GeneralSecurityException {
        Credentials credentials = Credentials.create(privateKeyHex);
        String address = credentials.getAddress();

        List<WalletAccount> wallets = getWallets(context);
        for (WalletAccount existing : wallets) {
            if (address.equalsIgnoreCase(existing.address)) {
                setSelectedAddress(context, existing.address);
                WalletKeyStore.saveWalletPrivateKey(context, existing.address, privateKeyHex);
                return;
            }
        }

        WalletAccount account = new WalletAccount();
        account.address = address;
        account.name = TextUtils.isEmpty(walletName) ? "Wallet " + (wallets.size() + 1) : walletName.trim();
        account.createdAt = System.currentTimeMillis();
        wallets.add(account);
        persistWallets(context, wallets);

        WalletKeyStore.saveWalletPrivateKey(context, address, privateKeyHex);
        setSelectedAddress(context, address);
    }

    public static String getSelectedAddress(Context context) {
        String selected = prefs(context).getString(KEY_SELECTED, null);
        if (!TextUtils.isEmpty(selected)) {
            return selected;
        }
        List<WalletAccount> wallets = getWallets(context);
        if (!wallets.isEmpty()) {
            selected = wallets.get(0).address;
            setSelectedAddress(context, selected);
            return selected;
        }
        return null;
    }

    public static void setSelectedAddress(Context context, String address) {
        prefs(context).edit().putString(KEY_SELECTED, address).apply();
        WalletKeyStore.setSelectedAddress(context, address);
    }

    public static boolean hasAnyWallet(Context context) {
        return !getWallets(context).isEmpty() && !TextUtils.isEmpty(getSelectedAddress(context));
    }

    @Nullable
    public static String getSelectedPrivateKey(Context context) {
        try {
            String selected = getSelectedAddress(context);
            return selected == null ? null : WalletKeyStore.loadWalletPrivateKey(context, selected);
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static List<TokenAsset> getTokens(Context context) {
        String json = prefs(context).getString(KEY_TOKENS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            List<TokenAsset> result = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                TokenAsset token = new TokenAsset();
                token.symbol = o.optString("symbol", "TOKEN");
                token.contractAddress = o.optString("contractAddress", "");
                token.decimals = o.optInt("decimals", 18);
                token.favorite = o.optBoolean("favorite", false);
                result.add(token);
            }
            return result;
        } catch (Throwable ignore) {
            return new ArrayList<>();
        }
    }

    public static List<TokenAsset> getFavoriteTokens(Context context) {
        List<TokenAsset> all = getTokens(context);
        List<TokenAsset> favorites = new ArrayList<>();
        for (TokenAsset token : all) {
            if (token.favorite) {
                favorites.add(token);
            }
        }
        return favorites;
    }

    public static void addOrUpdateCustomToken(Context context, String symbol, String contractAddress, int decimals, boolean favorite) {
        if (TextUtils.isEmpty(symbol) || TextUtils.isEmpty(contractAddress)) {
            return;
        }
        List<TokenAsset> all = getTokens(context);
        boolean updated = false;
        for (TokenAsset token : all) {
            if (contractAddress.equalsIgnoreCase(token.contractAddress)) {
                token.symbol = symbol;
                token.decimals = decimals;
                token.favorite = favorite;
                updated = true;
                break;
            }
        }
        if (!updated) {
            TokenAsset token = new TokenAsset();
            token.symbol = symbol;
            token.contractAddress = contractAddress;
            token.decimals = decimals;
            token.favorite = favorite;
            all.add(token);
        }
        persistTokens(context, all);
    }

    private static String generatePrivateKeyHex() {
        BigInteger curveN = Sign.CURVE.getN();
        SecureRandom random = new SecureRandom();
        BigInteger candidate;
        do {
            candidate = new BigInteger(256, random);
        } while (candidate.signum() <= 0 || candidate.compareTo(curveN) >= 0);
        ECKeyPair ecKeyPair = ECKeyPair.create(candidate);
        return normalizePrivateKey(ecKeyPair.getPrivateKey().toString(16));
    }

    private static String normalizePrivateKey(String privateKeyHex) {
        if (privateKeyHex == null) {
            return "";
        }
        String value = privateKeyHex.trim();
        if (value.startsWith("0x") || value.startsWith("0X")) {
            value = value.substring(2);
        }
        if (value.length() < 64) {
            StringBuilder sb = new StringBuilder(64);
            for (int i = value.length(); i < 64; i++) {
                sb.append('0');
            }
            sb.append(value);
            value = sb.toString();
        }
        return value;
    }

    private static void persistWallets(Context context, List<WalletAccount> wallets) {
        JSONArray arr = new JSONArray();
        for (WalletAccount account : wallets) {
            JSONObject o = new JSONObject();
            try {
                o.put("address", account.address);
                o.put("name", account.name);
                o.put("createdAt", account.createdAt);
                arr.put(o);
            } catch (Throwable ignore) {
            }
        }
        prefs(context).edit().putString(KEY_WALLETS, arr.toString()).apply();
    }

    private static void persistTokens(Context context, List<TokenAsset> tokens) {
        JSONArray arr = new JSONArray();
        for (TokenAsset token : tokens) {
            JSONObject o = new JSONObject();
            try {
                o.put("symbol", token.symbol);
                o.put("contractAddress", token.contractAddress);
                o.put("decimals", token.decimals);
                o.put("favorite", token.favorite);
                arr.put(o);
            } catch (Throwable ignore) {
            }
        }
        prefs(context).edit().putString(KEY_TOKENS, arr.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
