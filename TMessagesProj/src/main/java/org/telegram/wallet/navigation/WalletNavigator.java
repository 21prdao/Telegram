package org.telegram.wallet.navigation;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.wallet.ui.CreateRedPacketBottomSheet;
import org.telegram.wallet.ui.OpenRedPacketBottomSheet;

public final class WalletNavigator {

    private WalletNavigator() {
    }

    public static void openCreateRedPacket(BaseFragment parent, int account, long dialogId) {
        if (parent == null || parent.getParentActivity() == null) {
            return;
        }
        CreateRedPacketBottomSheet sheet =
                new CreateRedPacketBottomSheet(parent, account, dialogId);
        sheet.show();
    }

    public static void openClaimRedPacket(BaseFragment parent, String packetId) {
        if (parent == null || parent.getParentActivity() == null) {
            return;
        }
        OpenRedPacketBottomSheet sheet =
                new OpenRedPacketBottomSheet(parent, packetId);
        sheet.show();
    }
}