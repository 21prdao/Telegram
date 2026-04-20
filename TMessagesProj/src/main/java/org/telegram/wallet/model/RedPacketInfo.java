package org.telegram.wallet.model;

public class RedPacketInfo {
    public String packetId;                // 聊天里/后端用的红包ID
    public String packetIdHex;             // 链上 bytes32 hex，建议后端一起返回

    public String tokenSymbol;             // 当前先按 BNB
    public String totalAmountWei;
    public String amountPerClaimWei;
    public String totalAmountDisplay;
    public String amountPerClaimDisplay;

    public int totalCount;
    public int remainingCount;
    public long expiresAt;

    public String creatorWallet;
    public String contractAddress;

    public boolean canClaim;
    public boolean canRefund;
    public boolean hasClaimed;
    public boolean expired;
    public boolean refunded;
}