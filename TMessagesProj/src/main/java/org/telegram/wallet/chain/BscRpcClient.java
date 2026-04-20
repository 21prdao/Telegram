package org.telegram.wallet.chain;

import org.telegram.wallet.config.WalletConfig;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

public final class BscRpcClient {
    private static volatile Web3j instance;

    private BscRpcClient() {}

    public static Web3j get() {
        if (instance == null) {
            synchronized (BscRpcClient.class) {
                if (instance == null) {
                    instance = Web3j.build(new HttpService(WalletConfig.BSC_RPC_URL));
                }
            }
        }
        return instance;
    }
}