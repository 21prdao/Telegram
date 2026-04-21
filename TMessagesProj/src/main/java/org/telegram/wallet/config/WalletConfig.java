package org.telegram.wallet.config;

import android.os.Build;
import android.text.TextUtils;

import org.telegram.messenger.BuildConfig;

public final class WalletConfig {
    public static final boolean ENABLED = BuildConfig.WEB3_WALLET_ENABLED;
    public static final String RED_PACKET_HOST = BuildConfig.WEB3_RED_PACKET_HOST;
    public static final String BSC_RPC_URL = BuildConfig.BSC_RPC_URL;
    public static final long BSC_CHAIN_ID = BuildConfig.BSC_CHAIN_ID;
    public static final String RED_PACKET_CONTRACT = BuildConfig.RED_PACKET_CONTRACT;

    private WalletConfig() {}


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