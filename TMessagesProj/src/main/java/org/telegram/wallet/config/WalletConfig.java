package org.telegram.wallet.config;

import android.os.Build;
import org.telegram.messenger.BuildConfig;

public final class WalletConfig {
    public static final boolean ENABLED = BuildConfig.WEB3_WALLET_ENABLED;
    public static final String RED_PACKET_HOST = BuildConfig.WEB3_RED_PACKET_HOST;
    public static final String BSC_RPC_URL = BuildConfig.BSC_RPC_URL;
    public static final long BSC_CHAIN_ID = BuildConfig.BSC_CHAIN_ID;
    public static final String RED_PACKET_CONTRACT = BuildConfig.RED_PACKET_CONTRACT;

    private WalletConfig() {}

    public static boolean isWalletSupportedOnThisDevice() {
        // Telegram 当前 minSdk 是 21；钱包建议先只开给 23+
        return ENABLED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
    }
}