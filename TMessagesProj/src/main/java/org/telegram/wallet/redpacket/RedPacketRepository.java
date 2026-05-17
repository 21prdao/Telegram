package org.telegram.wallet.redpacket;

import android.net.Uri;
import android.text.TextUtils;

import com.google.android.exoplayer2.util.Log;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.model.ClaimPrepareResponse;
import org.telegram.wallet.model.RedPacketClaimRecord;
import org.telegram.wallet.model.RedPacketInfo;
import org.telegram.wallet.model.CreateRedPacketPrepareResponse;
import org.telegram.wallet.model.RedPacketSendRecord;
import org.telegram.wallet.model.RedPacketSendRecordDetail;
import org.telegram.wallet.model.RedPacketRefundRecord;
import org.telegram.wallet.model.TokenAsset;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        info.status = status;

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

        return response;
    }


    public List<TokenAsset> getDefaultTokens() throws Exception {
        JSONObject root = requestJson("GET", "/wallet/default-tokens", null);
        JSONObject data = unwrapData(root);
        JSONArray list = data != null ? data.optJSONArray("tokens") : null;
        List<TokenAsset> result = new ArrayList<>();
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            TokenAsset token = new TokenAsset();
            token.symbol = firstNonEmpty(optString(item, "symbol", "tokenSymbol"), "TOKEN");
            token.contractAddress = firstNonEmpty(optString(item, "contractAddress", "tokenAddress"), "");
            token.decimals = optInt(item, "decimals", "tokenDecimals");
            if (token.decimals <= 0) token.decimals = 18;
            token.favorite = true;
            token.priceUsd = firstNonEmpty(optString(item, "priceUsd", "usdPrice", "price", "price_usd"), "");
            token.iconUrl = resolvePublicUrl(firstNonEmpty(optString(item, "iconUrl", "icon_url", "logoUrl", "logo", "imageUrl", "image"), ""));
            if (!TextUtils.isEmpty(token.contractAddress)) {
                result.add(token);
            }
        }
        return result;
    }

    public static final class TokenMetadata {
        public final Map<String, BigDecimal> prices = new HashMap<>();
        public final Map<String, String> iconUrls = new HashMap<>();
    }

    public Map<String, BigDecimal> getTokenPrices() throws Exception {
        return getTokenMetadata().prices;
    }

    public TokenMetadata getTokenMetadata() throws Exception {
        JSONObject root = requestJson("GET", "/wallet/token-prices", null);
        JSONObject data = unwrapData(root);
        JSONArray list = data != null ? data.optJSONArray("prices") : null;
        TokenMetadata result = new TokenMetadata();
        if (list == null) {
            return result;
        }
        for (int i = 0; i < list.length(); i++) {
            putTokenMetadataItem(result, list.optJSONObject(i));
        }
        return result;
    }

    public TokenMetadata getTokenMetadataForContracts(List<TokenAsset> tokens) throws Exception {
        TokenMetadata result = new TokenMetadata();
        if (tokens == null || tokens.isEmpty()) {
            return result;
        }

        StringBuilder addresses = new StringBuilder();
        Map<String, Boolean> seen = new HashMap<>();
        for (TokenAsset token : tokens) {
            if (token == null || TextUtils.isEmpty(token.contractAddress)) {
                continue;
            }
            String address = token.contractAddress.trim().toLowerCase(Locale.US);
            if (!address.matches("^0x[0-9a-f]{40}$") || seen.containsKey(address)) {
                continue;
            }
            if (addresses.length() > 0) {
                addresses.append(',');
            }
            addresses.append(address);
            seen.put(address, true);
            if (seen.size() >= 100) {
                break;
            }
        }

        if (addresses.length() == 0) {
            return result;
        }

        JSONObject root = requestJson("GET", "/wallet/token-metadata?contractAddresses=" + Uri.encode(addresses.toString()), null);
        JSONObject data = unwrapData(root);
        JSONArray list = data != null ? data.optJSONArray("tokens") : null;
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                putTokenMetadataItem(result, list.optJSONObject(i));
            }
            return result;
        }

        JSONObject single = data != null ? data.optJSONObject("token") : null;
        putTokenMetadataItem(result, single != null ? single : data);
        return result;
    }

    public TokenMetadata getTokenMetadataForContract(String contractAddress, String symbol) throws Exception {
        TokenMetadata result = new TokenMetadata();
        if (TextUtils.isEmpty(contractAddress)) {
            return result;
        }

        String path = "/wallet/token-metadata?contractAddress=" + Uri.encode(contractAddress.trim());
        if (!TextUtils.isEmpty(symbol)) {
            path += "&symbol=" + Uri.encode(symbol.trim());
        }
        JSONObject root = requestJson("GET", path, null);
        JSONObject data = unwrapData(root);
        JSONObject single = data != null ? data.optJSONObject("token") : null;
        if (single != null) {
            putTokenMetadataItem(result, single);
        } else {
            putTokenMetadataItem(result, data);
        }
        return result;
    }

    private void putTokenMetadataItem(TokenMetadata result, JSONObject item) {
        if (result == null || item == null) return;
        BigDecimal price = parsePositiveDecimal(firstNonEmpty(optString(item, "priceUsd", "usdPrice", "price", "price_usd"), "0"));
        String symbol = firstNonEmpty(optString(item, "symbol", "tokenSymbol"), "");
        String contractAddress = firstNonEmpty(optString(item, "contractAddress", "tokenAddress"), "");
        String iconUrl = resolvePublicUrl(firstNonEmpty(optString(item, "iconUrl", "icon_url", "logoUrl", "logo", "imageUrl", "image"), ""));
        if (price.compareTo(BigDecimal.ZERO) > 0) {
            if (!TextUtils.isEmpty(symbol)) {
                result.prices.put(priceKeyForSymbol(symbol), price);
            }
            if (!TextUtils.isEmpty(contractAddress)) {
                result.prices.put(priceKeyForContract(contractAddress), price);
            }
        }
        if (!TextUtils.isEmpty(iconUrl)) {
            if (!TextUtils.isEmpty(symbol)) {
                result.iconUrls.put(priceKeyForSymbol(symbol), iconUrl);
            }
            if (!TextUtils.isEmpty(contractAddress)) {
                result.iconUrls.put(priceKeyForContract(contractAddress), iconUrl);
            }
        }
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
        response.createSignatureHex = firstNonEmpty(
                optString(data, "createSignatureHex", "createSignature", "signatureHex", "signature"),
                null
        );

        if (TextUtils.isEmpty(response.packetId)) {
            throw new IllegalStateException("prepareCreate succeeded but packetId is empty");
        }

        return response;
    }

    public void confirmCreate( String packetId, String creatorWallet, String txHash, String claimerName, String telegramId ) throws Exception {
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
        body.put("claimerName", claimerName == null ? "" : claimerName);
        body.put("telegramId", telegramId == null ? "" : telegramId);

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

    public void confirmClaim(String packetId, String claimerAddress, String txHash, String claimerName, String telegramId) throws Exception {
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
        body.put("claimerName", claimerName == null ? "" : claimerName);
        body.put("telegramId", telegramId == null ? "" : telegramId);

        try {
            requestJson(
                    "POST",
                    "/red-packets/" + Uri.encode(packetId) + "/claim-confirm",
                    body
            );
        } catch (Throwable firstError) {
            Log.e("RedPacketRepository", firstError.toString());
        }
    }

    public List<RedPacketSendRecord> getSendRecords(String creatorWallet, String status, int limit, int offset) throws Exception {
        if (TextUtils.isEmpty(creatorWallet)) {
            throw new IllegalArgumentException("creatorWallet is empty");
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        String path = "/red-packets/send-records?creatorWallet=" + Uri.encode(creatorWallet) + "&limit=" + safeLimit + "&offset=" + safeOffset;
        if (!TextUtils.isEmpty(status)) {
            path += "&status=" + Uri.encode(status);
        }
        JSONObject root = requestJson("GET", path, null);
        JSONArray recordsArr = root.optJSONArray("data");
        if (recordsArr == null) {
            recordsArr = unwrapData(root).optJSONArray("records");
        }
        List<RedPacketSendRecord> records = new ArrayList<>();
        if (recordsArr == null) {
            return records;
        }
        for (int i = 0; i < recordsArr.length(); i++) {
            JSONObject item = recordsArr.optJSONObject(i);
            if (item == null) {
                continue;
            }
            RedPacketSendRecord record = new RedPacketSendRecord();
            record.packetId = firstNonEmpty(
                    optString(item, "packetId", "packet_id", "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"),
                    ""
            );
            record.tokenSymbol = firstNonEmpty(optString(item, "tokenSymbol", "token_symbol"), "BNB");
            record.totalAmount = firstNonEmpty(
                    optString(item, "totalAmount", "totalAmountWei", "total_amount_wei"),
                    "0"
            );
            record.count = optInt(item, "count", "count_total", "totalCount");
            record.status = firstNonEmpty(optString(item, "status"), "PENDING");
            record.createdAt = optLong(item, "createdAt", "created_at");
            if (record.createdAt > 0 && record.createdAt < 10_000_000_000L) {
                record.createdAt *= 1000L;
            }
            record.expiresAt = optLong(item, "expiresAt", "expires_at", "expireAt", "expire_at");
            if (record.expiresAt > 0 && record.expiresAt < 10_000_000_000L) {
                record.expiresAt *= 1000L;
            }
            record.txHash = firstNonEmpty(optString(item, "txHash", "createTxHash", "create_tx_hash"), "");
            record.greeting = firstNonEmpty(optString(item, "greeting"), "");
            records.add(record);
        }
        return records;
    }

    public void confirmRefund(String packetId, String creatorAddress, String txHash) throws Exception {
        if (TextUtils.isEmpty(packetId) || TextUtils.isEmpty(creatorAddress) || TextUtils.isEmpty(txHash)) {
            throw new IllegalArgumentException("refund confirm params invalid");
        }
        JSONObject body = new JSONObject();
        body.put("creatorAddress", creatorAddress);
        body.put("txHash", txHash);

        Exception lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                requestJson("POST", "/red-packets/" + Uri.encode(packetId) + "/refund-confirm", body);
                return;
            } catch (Exception error) {
                lastError = error;
                if (!isRefundConfirmRetryable(error) || attempt == 4) {
                    throw error;
                }
                try {
                    Thread.sleep(1200L + attempt * 800L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
        }
        if (lastError != null) {
            throw lastError;
        }
    }

    private boolean isRefundConfirmRetryable(Throwable error) {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.US);
        return message.contains("transaction not confirmed")
                || message.contains("receipt timeout")
                || message.contains("refunded event not found")
                || message.contains("timeout")
                || message.contains("failed to connect")
                || message.contains("unable to resolve host");
    }

    public RedPacketSendRecordDetail getSendRecordDetail(String packetId) throws Exception {
        if (TextUtils.isEmpty(packetId)) {
            throw new IllegalArgumentException("packetId is empty");
        }
        JSONObject root = requestJson("GET", "/red-packets/send-records/" + Uri.encode(packetId), null);
        JSONObject data = unwrapData(root);

        RedPacketSendRecordDetail detail = new RedPacketSendRecordDetail();
        detail.packetId = firstNonEmpty(optString(data, "packetId", "packet_id"), "");
        detail.tokenSymbol = firstNonEmpty(optString(data, "tokenSymbol", "token_symbol"), "BNB");
        detail.totalAmount = firstNonEmpty(optString(data, "totalAmount", "totalAmountWei", "total_amount_wei"), "0");
        detail.count = optInt(data, "count", "count_total", "totalCount");
        detail.status = firstNonEmpty(optString(data, "status"), "PENDING");
        detail.createdAt = optLong(data, "createdAt", "created_at");
        if (detail.createdAt > 0 && detail.createdAt < 10_000_000_000L) {
            detail.createdAt *= 1000L;
        }
        detail.txHash = firstNonEmpty(optString(data, "txHash", "createTxHash", "create_tx_hash"), "");
        detail.greeting = firstNonEmpty(optString(data, "greeting"), "");
        detail.packetIdHex = firstNonEmpty(
                optString(data, "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"),
                detail.packetId
        );
        detail.contractAddress = firstNonEmpty(optString(data, "contractAddress", "contract_address"), "");
        detail.remainingAmountWei = firstNonEmpty(optString(data, "remainingAmountWei", "remaining_amount_wei"), "0");
        detail.remainingAmountDisplay = firstNonEmpty(optString(data, "remainingAmountDisplay", "remainingAmount", "remaining_amount"), "");
        detail.expiresAt = optLong(data, "expiresAt", "expires_at", "expireAt", "expire_at");
        String detailStatus = firstNonEmpty(optString(data, "status", "packetStatus", "packet_status"), "").toLowerCase(Locale.US);
        detail.refunded = optBoolean(data, "refunded") || "refunded".equals(detailStatus);
        detail.canRefund = optBoolean(data, "canRefund", "refundable");
        if (!hasAny(data, "canRefund", "refundable")) {
            detail.canRefund = !detail.refunded && isExpired(detail.expiresAt);
        }

        JSONArray claims = data.optJSONArray("claimRecords");
        if (claims == null) {
            claims = data.optJSONArray("claims");
        }
        if (claims != null) {
            for (int i = 0; i < claims.length(); i++) {
                JSONObject item = claims.optJSONObject(i);
                if (item == null) continue;
                RedPacketClaimRecord claim = new RedPacketClaimRecord();
                claim.claimerName = firstNonEmpty(optString(item, "claimerName", "claimer_name"), "");
                claim.claimerAddress = firstNonEmpty(optString(item, "claimerAddress", "claimer_address"), "");
                claim.telegramId = firstNonEmpty(optString(item, "telegramId", "telegram_id"), "");
                claim.claimedAt = optLong(item, "claimedAt", "created_at");
                if (claim.claimedAt > 0 && claim.claimedAt < 10_000_000_000L) {
                    claim.claimedAt *= 1000L;
                }
                claim.amountWei = firstNonEmpty(optString(item, "amountWei", "amount_wei"), "0");
                claim.txHash = firstNonEmpty(optString(item, "txHash", "tx_hash"), "");
                detail.claimRecords.add(claim);
            }
        }

        JSONArray refunds = data.optJSONArray("refundRecords");
        if (refunds == null) {
            refunds = data.optJSONArray("refunds");
        }
        if (refunds != null) {
            for (int i = 0; i < refunds.length(); i++) {
                JSONObject item = refunds.optJSONObject(i);
                if (item == null) continue;
                RedPacketRefundRecord refund = new RedPacketRefundRecord();
                refund.refundId = firstNonEmpty(optString(item, "refundId", "refund_id", "id"), "");
                refund.packetId = firstNonEmpty(optString(item, "packetId", "packet_id"), detail.packetId);
                refund.amountWei = firstNonEmpty(optString(item, "amountWei", "amount_wei", "remainingAmountWei"), "0");
                refund.amountDisplay = firstNonEmpty(optString(item, "amountDisplay", "amount", "remainingAmount"), "");
                refund.packetIdHex = firstNonEmpty(optString(item, "packetIdHex", "packet_id_hex", "onChainPacketId", "onChainPacketIdHex"), detail.packetIdHex);
                refund.contractAddress = firstNonEmpty(optString(item, "contractAddress", "contract_address"), detail.contractAddress);
                refund.status = firstNonEmpty(optString(item, "status"), "");
                refund.expiresAt = optLong(item, "expiresAt", "expires_at", "expireAt", "expire_at");
                refund.refunded = optBoolean(item, "refunded") || "refunded".equalsIgnoreCase(refund.status);
                refund.canRefund = optBoolean(item, "canRefund", "refundable");
                refund.txHash = firstNonEmpty(optString(item, "txHash", "tx_hash"), "");
                if (!hasAny(item, "canRefund", "refundable")) {
                    refund.canRefund = !refund.refunded && isExpired(refund.expiresAt) && !TextUtils.isEmpty(refund.amountWei) && !"0".equals(refund.amountWei);
                }
                detail.refundRecords.add(refund);
            }
        }
        if (detail.refundRecords.isEmpty() && (detail.canRefund || detail.refunded)) {
            RedPacketRefundRecord fallback = new RedPacketRefundRecord();
            fallback.refundId = detail.packetId;
            fallback.packetId = detail.packetId;
            fallback.amountWei = detail.remainingAmountWei;
            fallback.amountDisplay = detail.remainingAmountDisplay;
            fallback.canRefund = detail.canRefund;
            fallback.refunded = detail.refunded;
            fallback.expiresAt = detail.expiresAt;
            fallback.packetIdHex = detail.packetIdHex;
            fallback.contractAddress = detail.contractAddress;
            fallback.status = detail.status;
            detail.refundRecords.add(fallback);
        }
        return detail;
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

    private String resolvePublicUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return "";
        }
        String raw = value.trim();
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            return raw;
        }
        try {
            URL base = new URL(baseUrl);
            StringBuilder origin = new StringBuilder();
            origin.append(base.getProtocol()).append("://").append(base.getHost());
            if (base.getPort() > 0) {
                origin.append(":").append(base.getPort());
            }
            if (raw.startsWith("/")) {
                return origin + raw;
            }
            return origin + "/" + raw.replaceFirst("^/+", "");
        } catch (Throwable ignore) {
            return raw;
        }
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

    public static String priceKeyForSymbol(String symbol) {
        return "symbol:" + (symbol == null ? "" : symbol.trim().toUpperCase(Locale.US));
    }

    public static String priceKeyForContract(String contractAddress) {
        return "contract:" + (contractAddress == null ? "" : contractAddress.trim().toLowerCase(Locale.US));
    }

    public static BigDecimal parsePositiveDecimal(String value) {
        if (TextUtils.isEmpty(value)) {
            return BigDecimal.ZERO;
        }
        try {
            BigDecimal decimal = new BigDecimal(value.trim().replace(",", ""));
            return decimal.compareTo(BigDecimal.ZERO) > 0 ? decimal : BigDecimal.ZERO;
        } catch (Throwable ignore) {
            return BigDecimal.ZERO;
        }
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
