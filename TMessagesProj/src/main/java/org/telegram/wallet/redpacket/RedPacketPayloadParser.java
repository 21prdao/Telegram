package org.telegram.wallet.redpacket;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.wallet.model.RedPacketPayload;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RedPacketPayloadParser {

    private static final Pattern WEB3_PACKET_URL_PATTERN = Pattern.compile("tg://web3-red-packet\\?data=[A-Za-z0-9_\\-=%]+", Pattern.CASE_INSENSITIVE);

    private RedPacketPayloadParser() {
    }

    @Nullable
    public static RedPacketPayload parseFromMessageText(@Nullable CharSequence text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = WEB3_PACKET_URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return parseFromUrl(matcher.group());
    }

    @Nullable
    public static RedPacketPayload parseFromUrl(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        Uri uri = Uri.parse(url);
        if (uri == null) {
            return null;
        }
        if (!"tg".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (!"web3-red-packet".equalsIgnoreCase(uri.getHost())) {
            return null;
        }

        String encoded = uri.getQueryParameter("data");
        if (TextUtils.isEmpty(encoded)) {
            return null;
        }

        try {
            String payloadJson = decodeBase64Url(encoded);
            JSONObject data = new JSONObject(payloadJson);

            RedPacketPayload payload = new RedPacketPayload();
            payload.packetId = optString(data, "packetId", "packet_id", "id");
            payload.status = firstNonEmpty(optString(data, "status", "state"), RedPacketPayload.STATUS_LOADING);
            payload.symbol = firstNonEmpty(optString(data, "symbol", "tokenSymbol", "asset"), "BNB");
            payload.totalAmount = firstNonEmpty(optString(data, "totalAmount", "amount", "total"), "");
            payload.count = data.optInt("count", data.optInt("totalCount", 0));
            payload.expiresAt = data.optLong("expiresAt", data.optLong("expireAt", 0));
            payload.greeting = firstNonEmpty(optString(data, "greeting", "blessing", "message"), "");
            payload.claimUrl = optString(data, "claimUrl", "claim_url", "url");
            payload.deepLink = url;
            if (TextUtils.isEmpty(payload.packetId)) {
                return null;
            }
            return payload;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String decodeBase64Url(String value) {
        String normalized = value.trim();
        int mod = normalized.length() % 4;
        if (mod != 0) {
            normalized = normalized + "====".substring(mod);
        }
        byte[] raw = Base64.decode(normalized, Base64.URL_SAFE | Base64.NO_WRAP);
        return new String(raw, StandardCharsets.UTF_8);
    }

    private static String optString(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = object.optString(key, "");
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }
        return "";
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }
}
