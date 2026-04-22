package org.telegram.wallet.ui;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.wallet.model.RedPacketPayload;

public class RedPacketDetailBottomSheet extends OpenRedPacketBottomSheet {

    private final RedPacketPayload payload;

    public RedPacketDetailBottomSheet(BaseFragment parentFragment, String packetId) {
        super(parentFragment, packetId);
        this.payload = null;
    }

    public RedPacketDetailBottomSheet(BaseFragment parentFragment, RedPacketPayload payload) {
        super(parentFragment, payload != null ? payload.packetId : null);
        this.payload = payload;
    }

    public RedPacketPayload getPayload() {
        return payload;
    }
}
