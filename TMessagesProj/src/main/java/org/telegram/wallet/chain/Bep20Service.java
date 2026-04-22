package org.telegram.wallet.chain;

import android.text.TextUtils;

import org.telegram.wallet.config.WalletConfig;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Bep20Service {
    private static final BigInteger DEFAULT_GAS_LIMIT_APPROVE = BigInteger.valueOf(120_000L);
    private static final BigInteger MIN_GAS_PRICE_WEI = BigInteger.valueOf(3_000_000_000L); // 3 gwei


    public String getBalance(String owner, String contractAddress, int decimals) throws Exception {
        BigInteger raw = getBalanceRaw(owner, contractAddress);
        return new BigDecimal(raw).movePointLeft(Math.max(0, decimals)).stripTrailingZeros().toPlainString();
    }

    public BigInteger getBalanceRaw(String owner, String contractAddress) throws Exception {
        Function function = new Function(
                "balanceOf",
                Arrays.<Type>asList(new Address(owner)),
                Collections.singletonList(new TypeReference<Uint256>() {})
        );
        String data = FunctionEncoder.encode(function);
        EthCall response = BscRpcClient.get().ethCall(
                Transaction.createEthCallTransaction(owner, contractAddress, data),
                DefaultBlockParameterName.LATEST
        ).send();

        List<Type> outputs = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        if (outputs.isEmpty()) {
            return BigInteger.ZERO;
        }
        return (BigInteger) outputs.get(0).getValue();
    }

    public BigInteger getAllowanceRaw(String owner, String spender, String contractAddress) throws Exception {
        validateAddress(owner, "owner");
        validateAddress(spender, "spender");
        validateAddress(contractAddress, "contractAddress");

        Function function = new Function(
                "allowance",
                Arrays.<Type>asList(new Address(owner), new Address(spender)),
                Collections.singletonList(new TypeReference<Uint256>() {})
        );
        String data = FunctionEncoder.encode(function);
        EthCall response = BscRpcClient.get().ethCall(
                Transaction.createEthCallTransaction(owner, contractAddress, data),
                DefaultBlockParameterName.LATEST
        ).send();

        List<Type> outputs = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        if (outputs.isEmpty()) {
            return BigInteger.ZERO;
        }
        return (BigInteger) outputs.get(0).getValue();
    }

    public String approve(String privateKeyHex, String contractAddress, String spender, BigInteger amount) throws Exception {
        if (TextUtils.isEmpty(privateKeyHex)) {
            throw new IllegalArgumentException("privateKeyHex is empty");
        }
        validateAddress(contractAddress, "contractAddress");
        validateAddress(spender, "spender");
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be >= 0");
        }

        Credentials credentials = Credentials.create(normalizeHex(privateKeyHex));
        Function function = new Function(
                "approve",
                Arrays.<Type>asList(new Address(spender), new Uint256(amount)),
                Collections.emptyList()
        );
        String data = FunctionEncoder.encode(function);

        EthGetTransactionCount nonceResponse = BscRpcClient.get().ethGetTransactionCount(
                credentials.getAddress(),
                DefaultBlockParameterName.PENDING
        ).send();
        if (nonceResponse == null || nonceResponse.getTransactionCount() == null) {
            throw new IllegalStateException("Unable to fetch transaction nonce");
        }

        BigInteger gasPrice = resolveGasPrice();
        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonceResponse.getTransactionCount(),
                gasPrice,
                DEFAULT_GAS_LIMIT_APPROVE,
                contractAddress,
                BigInteger.ZERO,
                data
        );

        byte[] signedMessage = TransactionEncoder.signMessage(
                rawTransaction,
                WalletConfig.BSC_CHAIN_ID,
                credentials
        );
        EthSendTransaction sent = BscRpcClient.get().ethSendRawTransaction(Numeric.toHexString(signedMessage)).send();
        if (sent == null) {
            throw new IllegalStateException("ethSendRawTransaction returned null");
        }
        if (sent.hasError()) {
            String message = sent.getError() != null ? sent.getError().getMessage() : "unknown rpc error";
            throw new IllegalStateException("RPC rejected approve transaction: " + message);
        }
        String txHash = sent.getTransactionHash();
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalStateException("approve transaction hash is empty");
        }
        return txHash;
    }

    public TransactionReceipt waitForReceipt(String txHash, long timeoutMs, long pollIntervalMs) throws Exception {
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalArgumentException("txHash is empty");
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            EthGetTransactionReceipt receiptResponse = BscRpcClient.get().ethGetTransactionReceipt(txHash).send();
            if (receiptResponse != null && receiptResponse.getTransactionReceipt().isPresent()) {
                return receiptResponse.getTransactionReceipt().get();
            }
            Thread.sleep(pollIntervalMs);
        }
        throw new IllegalStateException("Timed out while waiting for receipt: " + txHash);
    }

    private BigInteger resolveGasPrice() {
        try {
            EthGasPrice gasPriceResponse = BscRpcClient.get().ethGasPrice().send();
            BigInteger gasPrice = gasPriceResponse != null ? gasPriceResponse.getGasPrice() : null;
            if (gasPrice == null || gasPrice.signum() <= 0) {
                return MIN_GAS_PRICE_WEI;
            }
            return gasPrice.max(MIN_GAS_PRICE_WEI);
        } catch (Throwable ignore) {
            return MIN_GAS_PRICE_WEI;
        }
    }

    private void validateAddress(String address, String fieldName) {
        if (TextUtils.isEmpty(address)) {
            throw new IllegalArgumentException(fieldName + " is empty");
        }
        String value = address.trim();
        if (!(value.startsWith("0x") || value.startsWith("0X")) || value.length() != 42) {
            throw new IllegalArgumentException("invalid EVM address for " + fieldName + ": " + address);
        }
    }

    private String normalizeHex(String value) {
        if (TextUtils.isEmpty(value)) {
            throw new IllegalArgumentException("hex string is empty");
        }
        String s = value.trim();
        if (!s.startsWith("0x") && !s.startsWith("0X")) {
            s = "0x" + s;
        }
        return s;
    }
}
