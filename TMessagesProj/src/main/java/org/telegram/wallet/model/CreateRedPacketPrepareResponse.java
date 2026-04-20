package org.telegram.wallet.model;

public class CreateRedPacketPrepareResponse {
    public String packetId;          // 聊天/后端用红包ID
    public String packetIdHex;       // 链上 bytes32 hex
    public String claimUrl;          // 聊天里发出去的领取链接
    public String contractAddress;   // 合约地址
    public long expiresAt;
    public String totalAmountWei;
    public int count;
    public String tokenSymbol;       // 默认 BNB
}