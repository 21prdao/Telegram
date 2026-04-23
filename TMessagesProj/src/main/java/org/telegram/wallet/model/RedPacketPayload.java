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
                || STATUS_CLAIMED.equals(value)
                || STATUS_EMPTY.equals(value)
                || STATUS_EXPIRED.equals(value)
                || STATUS_LOADING.equals(value)) {
            return value;
        }
        return STATUS_LOADING;
    }

    public String titleText() {
        return TextUtils.isEmpty(symbol) ? "Web3 红包" : symbol + " 红包";
    }
}
