package org.telegram.wallet.model;

import java.util.ArrayList;
import java.util.List;

public class RedPacketSendRecordDetail extends RedPacketSendRecord {
    public String packetIdHex;
    public String contractAddress;
    public String remainingAmountWei;
    public String remainingAmountDisplay;
    public long expiresAt;
    public boolean canRefund;
    public boolean refunded;
    public final List<RedPacketClaimRecord> claimRecords = new ArrayList<>();
    public final List<RedPacketRefundRecord> refundRecords = new ArrayList<>();
}
