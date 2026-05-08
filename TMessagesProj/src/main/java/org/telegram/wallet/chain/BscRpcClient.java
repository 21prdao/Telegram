package org.telegram.wallet.chain;

import org.telegram.wallet.config.WalletRuntimeConfig;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

public final class BscRpcClient {
    private static volatile Web3j instance;
    private static volatile String instanceRpcUrl;

    private BscRpcClient() {}

    public static Web3j get() {
        String rpcUrl = WalletRuntimeConfig.getBestRpcUrl();
        if (instance == null || instanceRpcUrl == null || !instanceRpcUrl.equals(rpcUrl)) {
            synchronized (BscRpcClient.class) {
                if (instance == null || instanceRpcUrl == null || !instanceRpcUrl.equals(rpcUrl)) {
                    instance = Web3j.build(new HttpService(rpcUrl));
                    instanceRpcUrl = rpcUrl;
                }
            }
        }
        return instance;
    }

    public static String getCurrentRpcUrl() {
        String rpcUrl = instanceRpcUrl;
        return rpcUrl == null ? WalletRuntimeConfig.getBestRpcUrl() : rpcUrl;
    }

    public static long getChainId() {
        return WalletRuntimeConfig.getChainId();
    }

    public static void selectRpcUrl(String rpcUrl) {
        WalletRuntimeConfig.selectRpcUrl(rpcUrl);
        resetWeb3jOnly();
    }

    public static void useAutoSelectBestRpc() {
        WalletRuntimeConfig.useAutoSelectBestRpc();
        resetWeb3jOnly();
    }

    public static void reset() {
        synchronized (BscRpcClient.class) {
            instance = null;
            instanceRpcUrl = null;
            WalletRuntimeConfig.invalidate();
        }
    }

    public static void resetWeb3jOnly() {
        synchronized (BscRpcClient.class) {
            instance = null;
            instanceRpcUrl = null;
        }
    }

    public static void refreshRuntimeConfig() {
        synchronized (BscRpcClient.class) {
            instance = null;
            instanceRpcUrl = null;
        }
        WalletRuntimeConfig.invalidate();
        WalletRuntimeConfig.get();
    }
}
