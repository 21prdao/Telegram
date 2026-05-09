package org.telegram.wallet.model;

public class RedPacketRefundRecord {
    public String refundId;
    /** 后端业务红包 ID，用于 /refund-confirm；链上退款仍使用 packetIdHex。 */
    public String packetId;
    public String amountWei;
    public String amountDisplay;
    public boolean canRefund;
    public boolean refunded;
    public long expiresAt;
    public String packetIdHex;
    public String contractAddress;
    public String status;
    public String txHash;
}
