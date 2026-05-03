package org.telegram.wallet.model;

import java.util.ArrayList;
import java.util.List;

public class RedPacketSendRecordDetail extends RedPacketSendRecord {
    public final List<RedPacketClaimRecord> claimRecords = new ArrayList<>();
}
