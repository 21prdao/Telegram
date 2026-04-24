package org.telegram.wallet.model;

import android.text.TextUtils;

public class RedPacketPayload {
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_CLAIMED = "claimed";
    public static final String STATUS_EMPTY = "empty";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_LOADING = "loading";

    public String packetId;
    public String status = STATUS_LOADING;
    public String symbol = "BNB";
    public String totalAmount;
    public int count;
    public long expiresAt;
    public String greeting;
    public String claimUrl;
    public String deepLink;

    public String normalizedStatus() {
        String value = status == null ? "" : status.trim().toLowerCase();
        if (STATUS_ACTIVE.equals(value)
                || "claimable".equals(value)
                || "open".equals(value)
                || "opened".equals(value)) {
            return STATUS_ACTIVE;
        }
        if (STATUS_CLAIMED.equals(value)
                || "received".equals(value)
                || "claimed_by_me".equals(value)
                || "already_claimed".equals(value)
                || "done".equals(value)
                || "completed".equals(value)) {
            return STATUS_CLAIMED;
        }
        if (STATUS_EMPTY.equals(value)
                || "soldout".equals(value)
                || "all_claimed".equals(value)
                || "finished".equals(value)) {
            return STATUS_EMPTY;
        }
        if (STATUS_EXPIRED.equals(value)
                || "timeout".equals(value)
                || "past".equals(value)
                || "history".equals(value)
                || "closed".equals(value)
                || "refunded".equals(value)) {
            return STATUS_EXPIRED;
        }

        // 兼容老消息：若消息里没有 status 字段，不应长期显示“加载中”，按有效期推导默认状态。
        if (TextUtils.isEmpty(value) || STATUS_LOADING.equals(value)) {
            if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt * 1000L) {
                return STATUS_EXPIRED;
            }
            return STATUS_ACTIVE;
        }
        return STATUS_ACTIVE;
    }

    public String titleText() {
        return TextUtils.isEmpty(symbol) ? "Web3 红包" : symbol + " 红包";
    }
}
