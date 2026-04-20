package org.telegram.wallet.chain;

import java.math.BigDecimal;

import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.Transfer;
import org.web3j.utils.Convert;

public final class BnbNativeTransferService {

    public String send(String privateKeyHex, String toAddress, BigDecimal amountBnb) throws Exception {
        Credentials credentials = Credentials.create(privateKeyHex);
        TransactionReceipt receipt = Transfer.sendFunds(
                BscRpcClient.get(),
                credentials,
                toAddress,
                amountBnb,
                Convert.Unit.ETHER
        ).send();
        return receipt.getTransactionHash();
    }
}