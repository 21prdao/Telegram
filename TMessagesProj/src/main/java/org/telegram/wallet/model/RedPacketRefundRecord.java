package org.telegram.wallet.model;

public class RedPacketRefundRecord {
    public String refundId;
    public String amountWei;
    public String amountDisplay;
    public boolean canRefund;
    public boolean refunded;
    public long expiresAt;
    public String packetIdHex;
    public String contractAddress;
    public String status;
}
