package org.telegram.wallet.redpacket;

import android.net.Uri;
import android.text.TextUtils;

import com.google.android.exoplayer2.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.model.ClaimPrepareResponse;
import org.telegram.wallet.model.RedPacketInfo;
import org.telegram.wallet.model.CreateRedPacketPrepareResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 最小可运行版：
 * 1) 适配 OpenRedPacketBottomSheet 当前用到的方法：
 *    - getPacket(packetId)
 *    - prepareClaim(packetId, claimerAddress)
 * 2) 后端默认地址：
 *    https://{WalletConfig.RED_PACKET_HOST}/api/v1
 * 3) JSON 支持两种返回格式：
 *    A. { "data": { ... } }
 *    B. { ...直接就是业务字段... }
 */
public class RedPacketRepository {

    private static volatile RedPacketRepository instance;

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private final String baseUrl;

    public static RedPacketRepository getInstance() {
        if (instance == null) {
            synchronized (RedPacketRepository.class) {
                if (instance == null) {
                    instance = new RedPacketRepository(defaultBaseUrl());
                }
            }
        }
        return instance;
    }

    public RedPacketRepository(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
    }

    public RedPacketInfo getPacket(String packetId) throws Exception {
        return getPacket(packetId, null);
    }

    public RedPacketInfo getPacket(String packetId, String walletAddress) throws Exception {
        if (TextUtils.isEmpty(packetId)) {
            throw new IllegalArgumentException("packetId is empty");
        }

        String path = "/red-packets/" + Uri.encode(packetId);
        if (!TextUtils.isEmpty(walletAddress)) {
            path += "?wallet=" + Uri.encode(walletAddress);
        }

        JSONObject root = requestJson(
                "GET",
                path,
                null
        );
        JSONObject data = unwrapData(root);

        RedPacketInfo info = new RedPacketInfo();
        info.packetId = firstNonEmpty(
                optString(data, "packetId", "packet_id"),
                packetId
        );
        info.packetIdHex = firstNonEmpty(
                optString(data, "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"),
                null
        );

        info.tokenSymbol = firstNonEmpty(
                optString(data, "tokenSymbol", "symbol", "assetSymbol"),
                "BNB"
        );

        info.totalAmountWei = firstNonEmpty(
                optString(data, "totalAmountWei", "total_amount_wei", "amountTotalWei"),
                null
        );
        info.amountPerClaimWei = firstNonEmpty(
                optString(data, "amountPerClaimWei", "amount_per_claim_wei", "perClaimWei"),
                null
        );

        info.totalAmountDisplay = firstNonEmpty(
                optString(data, "totalAmountDisplay", "totalAmount", "total_amount_display"),
                displayFromWei18(info.totalAmountWei)
        );
        info.amountPerClaimDisplay = firstNonEmpty(
                optString(data, "amountPerClaimDisplay", "amountPerClaim", "amount_per_claim_display"),
                displayFromWei18(info.amountPerClaimWei)
        );

        info.totalCount = optInt(data, "totalCount", "total_count", "count");
        info.remainingCount = optInt(data, "remainingCount", "remaining_count");

        info.expiresAt = optLong(data, "expiresAt", "expires_at", "expireAt", "expire_at");
        info.creatorWallet = firstNonEmpty(
                optString(data, "creatorWallet", "creator_wallet", "creatorAddress"),
                null
        );
        info.contractAddress = firstNonEmpty(
                optString(data, "contractAddress", "contract_address"),
                null
        );

        String status = firstNonEmpty(
                optString(data, "status", "packetStatus", "packet_status"),
                ""
        ).toLowerCase(Locale.US);

        info.refunded = optBoolean(data, "refunded")
                || "refunded".equals(status);

        info.hasClaimed = optBoolean(data, "hasClaimed", "claimed")
                || "claimed".equals(status);

        info.expired = optBoolean(data, "expired")
                || "expired".equals(status)
                || isExpired(info.expiresAt);

        info.canRefund = optBoolean(data, "canRefund", "refundable");
        info.canClaim = optBoolean(data, "canClaim", "claimable");

        // 若后端没给 canClaim/canRefund，则做保底推导
        if (!hasAny(data, "canClaim", "claimable")) {
            info.canClaim = !info.refunded
                    && !info.hasClaimed
                    && !info.expired
                    && info.remainingCount > 0;
        }

        if (!hasAny(data, "canRefund", "refundable")) {
            info.canRefund = !info.refunded && info.expired && info.remainingCount > 0;
        }

        return info;
    }

    public ClaimPrepareResponse prepareClaim(String packetId, String claimerAddress) throws Exception {
        if (TextUtils.isEmpty(packetId)) {
            throw new IllegalArgumentException("packetId is empty");
        }
        if (TextUtils.isEmpty(claimerAddress)) {
            throw new IllegalArgumentException("claimerAddress is empty");
        }

        JSONObject body = new JSONObject();
        body.put("claimerAddress", claimerAddress);

        JSONObject root = requestJson(
                "POST",
                "/red-packets/" + Uri.encode(packetId) + "/claim/prepare",
                body
        );
        JSONObject data = unwrapData(root);

        ClaimPrepareResponse response = new ClaimPrepareResponse();
        response.packetIdHex = firstNonEmpty(
                optString(data, "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"),
                null
        );
        response.signatureHex = firstNonEmpty(
                optString(data, "signatureHex", "signature", "claimSignature"),
                null
        );
        response.contractAddress = firstNonEmpty(
                optString(data, "contractAddress", "contract_address"),
                null
        );
        response.chainId = optLong(data, "chainId", "chain_id");
        response.claimerAddress = firstNonEmpty(
                optString(data, "claimerAddress", "claimer_address"),
                claimerAddress
        );

        if (TextUtils.isEmpty(response.signatureHex)) {
            throw new IllegalStateException("prepareClaim succeeded but signatureHex is empty");
        }

        return response;
    }

    public CreateRedPacketPrepareResponse prepareCreate(
            long dialogId,
            String creatorWallet,
            String tokenSymbol,
            String tokenAddress,
            int tokenDecimals,
            String packetType,
            String greeting,
            BigInteger totalAmountWei,
            int count,
            long expiresAtSeconds
    ) throws Exception {
        if (TextUtils.isEmpty(creatorWallet)) {
            throw new IllegalArgumentException("creatorWallet is empty");
        }
        if (TextUtils.isEmpty(tokenSymbol)) {
            throw new IllegalArgumentException("tokenSymbol is empty");
        }
        if (tokenDecimals < 0) {
            throw new IllegalArgumentException("tokenDecimals must be >= 0");
        }
        if (totalAmountWei == null || totalAmountWei.signum() <= 0) {
            throw new IllegalArgumentException("totalAmountWei must be > 0");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        if (expiresAtSeconds <= 0) {
            throw new IllegalArgumentException("expiresAtSeconds must be > 0");
        }

        JSONObject body = new JSONObject();
        String tokenSymbolClean = tokenSymbol.trim();
        boolean isNativeBnb = "BNB".equalsIgnoreCase(tokenSymbolClean);
        body.put("dialogId", dialogId);
        body.put("creatorWallet", creatorWallet);
        body.put("tokenSymbol", tokenSymbolClean);
        body.put("tokenDecimals", tokenDecimals);
        if (!isNativeBnb && !TextUtils.isEmpty(tokenAddress)) {
            body.put("tokenAddress", tokenAddress);
        }
        body.put("packetType", TextUtils.isEmpty(packetType) ? "equal" : packetType);
        body.put("greeting", greeting == null ? "" : greeting);
        body.put("totalAmountWei", totalAmountWei.toString());
        body.put("count", count);
        body.put("expiresAt", expiresAtSeconds);

        JSONObject root = requestJson(
                "POST",
                "/red-packets/prepare-create",
                body
        );
        JSONObject data = unwrapData(root);

        CreateRedPacketPrepareResponse response = new CreateRedPacketPrepareResponse();
        response.packetId = firstNonEmpty(
                optString(data, "packetId", "packet_id"),
                null
        );
        response.packetIdHex = firstNonEmpty(
                optString(data, "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"),
                response.packetId
        );
        response.claimUrl = firstNonEmpty(
                optString(data, "claimUrl", "claim_url", "url"),
                null
        );
        response.contractAddress = firstNonEmpty(
                optString(data, "contractAddress", "contract_address"),
                null
        );
        response.expiresAt = optLong(data, "expiresAt", "expires_at");
        response.totalAmountWei = firstNonEmpty(
                optString(data, "totalAmountWei", "total_amount_wei"),
                totalAmountWei.toString()
        );
        response.count = optInt(data, "count", "totalCount", "total_count");
        response.tokenSymbol = firstNonEmpty(
                optString(data, "tokenSymbol", "symbol"),
                "BNB"
        );

        if (TextUtils.isEmpty(response.packetId)) {
            throw new IllegalStateException("prepareCreate succeeded but packetId is empty");
        }

        return response;
    }

    public void confirmCreate( String packetId, String creatorWallet, String txHash ) throws Exception {
        if (TextUtils.isEmpty(packetId)) {
            throw new IllegalArgumentException("packetId is empty");
        }
        if (TextUtils.isEmpty(creatorWallet)) {
            throw new IllegalArgumentException("creatorWallet is empty");
        }
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalArgumentException("txHash is empty");
        }

        JSONObject body = new JSONObject();
        body.put("creatorWallet", creatorWallet);
        body.put("txHash", txHash);

        try {
            requestJson(
                    "POST",
                    "/red-packets/" + Uri.encode(packetId) + "/create-confirm",
                    body
            );
        } catch (Throwable firstError) {
            Log.e("RedPacketRepository", firstError.toString());
        }
    }

    public void confirmClaim(String packetId, String claimerAddress, String txHash) throws Exception {
        if (TextUtils.isEmpty(packetId)) {
            throw new IllegalArgumentException("packetId is empty");
        }
        if (TextUtils.isEmpty(claimerAddress)) {
            throw new IllegalArgumentException("claimerAddress is empty");
        }
        if (TextUtils.isEmpty(txHash)) {
            throw new IllegalArgumentException("txHash is empty");
        }

        JSONObject body = new JSONObject();
        body.put("claimerAddress", claimerAddress);
        body.put("txHash", txHash);

        try {
            requestJson(
                    "POST",
                    "/red-packets/" + Uri.encode(packetId) + "/claim/confirm",
                    body
            );
        } catch (Throwable firstError) {
            Log.e("RedPacketRepository", firstError.toString());
        }
    }


    private JSONObject requestJson(String method, String relativePath, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        InputStream stream = null;
        try {
            URL url = new URL(buildUrl(relativePath));
            connection = (HttpURLConnection) url.openConnection();

            if (connection instanceof HttpsURLConnection) {
                // 默认 HTTPS 行为即可，这里不自定义 TrustManager
            }

            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("User-Agent", "TelegramWallet/1.0");

            if (body != null) {
                connection.setDoOutput(true);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                OutputStream os = connection.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }

            int code = connection.getResponseCode();
            stream = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String text = readFully(stream);

            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + ": " + extractServerMessage(text));
            }

            if (TextUtils.isEmpty(text)) {
                return new JSONObject();
            }

            return new JSONObject(text);
        } catch (Throwable t) {
            FileLog.e(t);
            throw t;
        } finally {
            try {
                if (stream != null) {
                    stream.close();
                }
            } catch (Throwable ignore) {
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject unwrapData(JSONObject root) {
        if (root == null) {
            return new JSONObject();
        }
        JSONObject data = root.optJSONObject("data");
        if (data != null) {
            return data;
        }
        JSONObject result = root.optJSONObject("result");
        if (result != null) {
            return result;
        }
        return root;
    }

    private String extractServerMessage(String body) {
        if (TextUtils.isEmpty(body)) {
            return "empty response";
        }
        try {
            JSONObject root = new JSONObject(body);
            JSONObject error = root.optJSONObject("error");
            if (error != null) {
                String fromError = firstNonEmpty(
                        optString(error, "message", "error_description", "detail"),
                        null
                );
                if (!TextUtils.isEmpty(fromError)) {
                    return fromError;
                }
            }

            String message = firstNonEmpty(
                    optString(root, "message", "error_description", "detail", "error"),
                    null
            );
            if (!TextUtils.isEmpty(message)) {
                return message;
            }
        } catch (Throwable ignore) {
        }
        return body;
    }

    private String buildUrl(String relativePath) {
        if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) {
            return relativePath;
        }
        if (!relativePath.startsWith("/")) {
            relativePath = "/" + relativePath;
        }
        return baseUrl + relativePath;
    }

    private static String defaultBaseUrl() {
        return WalletConfig.getRedPacketApiBaseUrl();
    }

    private static String normalizeBaseUrl(String raw) {
        if (TextUtils.isEmpty(raw)) {
            throw new IllegalArgumentException("baseUrl is empty");
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isExpired(long epoch) {
        if (epoch <= 0) {
            return false;
        }
        long seconds = epoch > 10_000_000_000L ? epoch / 1000L : epoch;
        long nowSeconds = System.currentTimeMillis() / 1000L;
        return nowSeconds >= seconds;
    }

    private static String displayFromWei18(String weiString) {
        if (TextUtils.isEmpty(weiString)) {
            return null;
        }
        try {
            BigDecimal wei = new BigDecimal(new BigInteger(weiString));
            BigDecimal unit = new BigDecimal("1000000000000000000");
            BigDecimal human = wei.divide(unit).stripTrailingZeros();
            return human.toPlainString();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String readFully(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8)
        );
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static boolean hasAny(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                return true;
            }
        }
        return false;
    }

    private static String optString(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) {
                continue;
            }
            Object value = obj.opt(key);
            if (value == null) {
                continue;
            }
            String s = String.valueOf(value).trim();
            if (!TextUtils.isEmpty(s) && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }

    private static int optInt(JSONObject obj, String... keys) {
        long v = optLong(obj, keys);
        if (v > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (v < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) v;
    }

    private static long optLong(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return 0L;
        }
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) {
                continue;
            }
            Object value = obj.opt(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            try {
                String s = String.valueOf(value).trim();
                if (TextUtils.isEmpty(s)) {
                    continue;
                }
                if (s.contains(".")) {
                    return (long) Double.parseDouble(s);
                }
                return Long.parseLong(s);
            } catch (Throwable ignore) {
            }
        }
        return 0L;
    }

    private static boolean optBoolean(JSONObject obj, String... keys) {
        if (obj == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!obj.has(key) || obj.isNull(key)) {
                continue;
            }
            Object value = obj.opt(key);
            if (value == null) {
                continue;
            }
            if (value instanceof Boolean) {
                return (Boolean) value;
            }
            if (value instanceof Number) {
                return ((Number) value).intValue() != 0;
            }
            String s = String.valueOf(value).trim().toLowerCase(Locale.US);
            if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
                return true;
            }
            if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
                return false;
            }
        }
        return false;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return null;
    }
}
