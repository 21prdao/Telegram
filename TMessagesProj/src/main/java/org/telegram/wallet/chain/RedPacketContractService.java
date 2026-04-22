package org.telegram.wallet.chain;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.wallet.config.WalletConfig;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthGasPrice;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;

/**
 * 当前版本按“原生 BNB 红包合约”写：
 * - createNativePacket(bytes32 packetId, uint32 count, uint64 expiresAt) payable
 * - createTokenPacket(bytes32 packetId, address token, uint256 totalAmount, uint32 count, uint64 expiresAt)
 * - claim(bytes32 packetId, bytes signature)
 * - refund(bytes32 packetId)
 *
 * 若你后面把合约签名改了，这里要一起改。
 */
public class RedPacketContractService {

    private static final BigInteger DEFAULT_GAS_LIMIT_CREATE = BigInteger.valueOf(320_000L);
    private static final BigInteger DEFAULT_GAS_LIMIT_CLAIM = BigInteger.valueOf(220_000L);
    private static final BigInteger DEFAULT_GAS_LIMIT_REFUND = BigInteger.valueOf(180_000L);

    private static final BigInteger MIN_GAS_PRICE_WEI = BigInteger.valueOf(3_000_000_000L); // 3 gwei
    private static final BigInteger GAS_PRICE_MULTIPLIER_NUM = BigInteger.valueOf(12L);
    private static final BigInteger GAS_PRICE_MULTIPLIER_DEN = BigInteger.TEN;

    public String create(
            String privateKeyHex,
            String contractAddress,
            String packetIdHex,
            int count,
            BigInteger amountPerClaimWei,
            long expiresAtSeconds,
            String tokenAddress
    ) throws Exception {
        validatePrivateKey(privateKeyHex);
        validateAddress(contractAddress);

        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        if (amountPerClaimWei == null || amountPerClaimWei.signum() <= 0) {
            throw new IllegalArgumentException("amountPerClaimWei must be > 0");
        }
        if (expiresAtSeconds <= 0) {
            throw new IllegalArgumentException("expiresAtSeconds must be > 0");
        }

        BigInteger totalAmount = amountPerClaimWei.multiply(BigInteger.valueOf(count));
        boolean isNative = TextUtils.isEmpty(tokenAddress) || isZeroAddress(tokenAddress);
        Function function;
        BigInteger txValue;
        if (isNative) {
            function = new Function(
                    "createNativePacket",
                    Arrays.asList(
                            toBytes32(packetIdHex),
                            new Uint32(BigInteger.valueOf(count)),
                            new Uint64(BigInteger.valueOf(expiresAtSeconds))
                    ),
                    Collections.emptyList()
            );
            txValue = totalAmount;
        } else {
            validateAddress(tokenAddress);
            function = new Function(
                    "createTokenPacket",
                    Arrays.<Type>asList(
                            toBytes32(packetIdHex),
                            new Address(tokenAddress),
                            new Uint256(totalAmount),
                            new Uint32(BigInteger.valueOf(count)),
                            new Uint64(BigInteger.valueOf(expiresAtSeconds))
                    ),
                    Collections.emptyList()
            );
            txValue = BigInteger.ZERO;
        }
        return sendFunctionTransaction(
                privateKeyHex,
                contractAddress,
                function,
                txValue,
                DEFAULT_GAS_LIMIT_CREATE
        );
    }

    public String claim(
            String privateKeyHex,
            String contractAddress,
            String packetIdHex,
            String signatureHex
    ) throws Exception {
        validatePrivateKey(privateKeyHex);
        validateAddress(contractAddress);

        if (TextUtils.isEmpty(signatureHex)) {
            throw new IllegalArgumentException("signatureHex is empty");
        }

        Function function = new Function(
                "claim",
                Arrays.asList(
                        toBytes32(packetIdHex),
                        new DynamicBytes(hexToBytes(signatureHex))
                ),
                Collections.emptyList()
        );

        return sendFunctionTransaction(
                privateKeyHex,
                contractAddress,
                function,
                BigInteger.ZERO,
                DEFAULT_GAS_LIMIT_CLAIM
        );
    }

    public String refund(
            String privateKeyHex,
            String contractAddress,
            String packetIdHex
    ) throws Exception {
        validatePrivateKey(privateKeyHex);
        validateAddress(contractAddress);

        Function function = new Function(
                "refund",
                Arrays.asList(
                        toBytes32(packetIdHex)
                ),
                Collections.emptyList()
        );

        return sendFunctionTransaction(
                privateKeyHex,
                contractAddress,
                function,
                BigInteger.ZERO,
                DEFAULT_GAS_LIMIT_REFUND
        );
    }

    public TransactionReceipt waitForReceipt(String txHash) throws Exception {
        return waitForReceipt(txHash, 120_000L, 1_500L);
    }

    public BigInteger estimateCreateGasFeeWei() {
        Web3j web3j = BscRpcClient.get();
        BigInteger gasPrice = resolveGasPrice(web3j);
        return gasPrice.multiply(DEFAULT_GAS_LIMIT_CREATE);
    }

    public TransactionReceipt waitForReceipt(String txHash, long timeoutMs, long pollIntervalMs) throws Exception {
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalArgumentException("txHash is empty");
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        Web3j web3j = BscRpcClient.get();

        while (System.currentTimeMillis() < deadline) {
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(txHash).send();
            if (receiptResponse != null && receiptResponse.getTransactionReceipt().isPresent()) {
                return receiptResponse.getTransactionReceipt().get();
            }
            Thread.sleep(pollIntervalMs);
        }

        throw new IllegalStateException("Timed out while waiting for receipt: " + txHash);
    }

    private String sendFunctionTransaction(
            String privateKeyHex,
            String contractAddress,
            Function function,
            BigInteger valueWei,
            BigInteger gasLimit
    ) throws Exception {
        Credentials credentials = Credentials.create(normalizeHex(privateKeyHex));
        Web3j web3j = BscRpcClient.get();

        EthGetTransactionCount nonceResponse = web3j.ethGetTransactionCount(
                credentials.getAddress(),
                DefaultBlockParameterName.PENDING
        ).send();

        if (nonceResponse == null || nonceResponse.getTransactionCount() == null) {
            throw new IllegalStateException("Unable to fetch transaction nonce");
        }

        BigInteger nonce = nonceResponse.getTransactionCount();
        BigInteger gasPrice = resolveGasPrice(web3j);
        String data = FunctionEncoder.encode(function);

        RawTransaction rawTransaction = RawTransaction.createTransaction(
                nonce,
                gasPrice,
                gasLimit,
                contractAddress,
                valueWei,
                data
        );

        byte[] signedMessage = TransactionEncoder.signMessage(
                rawTransaction,
                WalletConfig.BSC_CHAIN_ID,
                credentials
        );

        String hexValue = Numeric.toHexString(signedMessage);
        EthSendTransaction sent = web3j.ethSendRawTransaction(hexValue).send();

        if (sent == null) {
            throw new IllegalStateException("ethSendRawTransaction returned null");
        }

        if (sent.hasError()) {
            String message = sent.getError() != null ? sent.getError().getMessage() : "unknown rpc error";
            throw new IllegalStateException("RPC rejected transaction: " + message);
        }

        String txHash = sent.getTransactionHash();
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalStateException("Transaction hash is empty");
        }

        return txHash;
    }

    private BigInteger resolveGasPrice(Web3j web3j) {
        try {
            EthGasPrice gasPriceResponse = web3j.ethGasPrice().send();
            BigInteger gasPrice = gasPriceResponse != null ? gasPriceResponse.getGasPrice() : null;
            if (gasPrice == null || gasPrice.signum() <= 0) {
                gasPrice = MIN_GAS_PRICE_WEI;
            }
            if (gasPrice.compareTo(MIN_GAS_PRICE_WEI) < 0) {
                gasPrice = MIN_GAS_PRICE_WEI;
            }
            return gasPrice.multiply(GAS_PRICE_MULTIPLIER_NUM).divide(GAS_PRICE_MULTIPLIER_DEN);
        } catch (Throwable t) {
            FileLog.e(t);
            return MIN_GAS_PRICE_WEI;
        }
    }

    private Bytes32 toBytes32(String packetIdHex) {
        byte[] raw = hexToBytes(packetIdHex);
        if (raw.length > 32) {
            throw new IllegalArgumentException("packetIdHex is longer than 32 bytes");
        }

        byte[] out = new byte[32];
        System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        return new Bytes32(out);
    }

    private byte[] hexToBytes(String hexValue) {
        try {
            String normalized = normalizeHex(hexValue);
            return Numeric.hexStringToByteArray(normalized);
        } catch (Throwable t) {
            throw new IllegalArgumentException("Expected hex string but got: " + hexValue, t);
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

    private boolean isZeroAddress(String address) {
        String normalized = address.trim();
        return "0x0000000000000000000000000000000000000000".equalsIgnoreCase(normalized);
    }

    private void validatePrivateKey(String privateKeyHex) {
        if (TextUtils.isEmpty(privateKeyHex)) {
            throw new IllegalArgumentException("privateKeyHex is empty");
        }
    }

    private void validateAddress(String address) {
        if (TextUtils.isEmpty(address)) {
            throw new IllegalArgumentException("contractAddress is empty");
        }
        String v = address.trim();
        if (!(v.startsWith("0x") || v.startsWith("0X")) || v.length() != 42) {
            throw new IllegalArgumentException("invalid EVM address: " + address);
        }
    }
}
