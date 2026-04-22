package org.telegram.wallet.ui;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.wallet.model.RedPacketPayload;

public class RedPacketDetailBottomSheet extends OpenRedPacketBottomSheet {

    public RedPacketDetailBottomSheet(BaseFragment parentFragment, String packetId) {
        super(parentFragment, packetId);
    }

    public RedPacketDetailBottomSheet(BaseFragment parentFragment, RedPacketPayload payload) {
        this(parentFragment, payload != null ? payload.packetId : null);
    }
}
