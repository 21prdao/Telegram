package org.telegram.wallet.redpacket;

import android.util.Base64;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

public final class RedPacketMessageComposer {

    private RedPacketMessageComposer() {}

    public static String compose(
            BigDecimal total,
            String symbol,
            int count,
            String expireText,
            String url
    ) {
        return "🎁 " + symbol + " 红包\n"
                + "总额：" + total.stripTrailingZeros().toPlainString() + " " + symbol + "\n"
                + "份数：" + count + "\n"
                + expireText + "\n"
                + url;
    }

    public static String composeCompatMessage(
            String packetId,
            String symbol,
            String totalAmount,
            int count,
            long expiresAt,
            String claimUrl,
            String greeting,
            String packetType
    ) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("packetId", packetId);
            payload.put("symbol", symbol);
            payload.put("totalAmount", totalAmount);
            payload.put("count", count);
            payload.put("expiresAt", expiresAt);
            payload.put("claimUrl", claimUrl);
            payload.put("greeting", greeting == null ? "" : greeting);
            payload.put("packetType", packetType == null ? "equal" : packetType);

            String encoded = Base64.encodeToString(
                    payload.toString().getBytes(StandardCharsets.UTF_8),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING
            );
            return "tg://web3-red-packet?data=" + encoded;
        } catch (Throwable ignore) {
            return "tg://web3-red-packet?data=";
        }
    }
}
