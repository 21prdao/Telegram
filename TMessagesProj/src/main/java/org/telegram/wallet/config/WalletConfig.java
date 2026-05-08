package org.telegram.wallet.config;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class WalletConfig {
    public static final boolean ENABLED = BuildConfig.WEB3_WALLET_ENABLED;
    public static final String RED_PACKET_HOST = BuildConfig.WEB3_RED_PACKET_HOST;
    public static final String BSC_RPC_URL = BuildConfig.BSC_RPC_URL;
    public static final long BSC_CHAIN_ID = BuildConfig.BSC_CHAIN_ID;
    public static final String RED_PACKET_CONTRACT = BuildConfig.RED_PACKET_CONTRACT;

    private WalletConfig() {}

    public static List<String> getBuildRpcUrls() {
        ArrayList<String> urls = new ArrayList<>();
        addRpcUrls(urls, BSC_RPC_URL);
        if (urls.isEmpty()) {
            urls.add("https://data-seed-prebsc-1-s1.bnbchain.org:8545");
        }
        return Collections.unmodifiableList(urls);
    }

    private static void addRpcUrls(ArrayList<String> urls, String rawValue) {
        if (TextUtils.isEmpty(rawValue)) {
            return;
        }
        String[] parts = rawValue.split("[\\n,]+");
        for (String part : parts) {
            String url = normalizeRpcUrl(part);
            if (TextUtils.isEmpty(url)) {
                continue;
            }
            boolean exists = false;
            for (String existing : urls) {
                if (existing.equalsIgnoreCase(url)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                urls.add(url);
            }
        }
    }

    private static String normalizeRpcUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String result = value.trim();
        if (!(result.startsWith("http://") || result.startsWith("https://"))) {
            return "";
        }
        return trimTrailingSlash(result);
    }

    public static String getBuildRedPacketContract() {
        return RED_PACKET_CONTRACT == null ? "" : RED_PACKET_CONTRACT.trim();
    }

    public static String getRedPacketApiBaseUrl() {
        String host = RED_PACKET_HOST == null ? "" : RED_PACKET_HOST.trim();
        if (TextUtils.isEmpty(host)) {
            host = "127.0.0.1:8787";
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return trimTrailingSlash(host) + "/api/v1";
        }

        // Android emulator maps localhost to 10.0.2.2
        if (host.startsWith("127.0.0.1") || host.startsWith("localhost")) {
            host = host.replaceFirst("^(127\\.0\\.0\\.1|localhost)", "10.0.2.2");
        }
        return "http://" + host + "/api/v1";
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public static boolean isWalletSupportedOnThisDevice() {
        // Telegram 当前 minSdk 是 21；钱包建议先只开给 23+
        return ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
}