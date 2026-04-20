package org.telegram.wallet.navigation;

import android.net.Uri;

import org.telegram.wallet.redpacket.RedPacketLinkParser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

public final class WalletIntentDispatcher {

    private WalletIntentDispatcher() {}

    public static boolean tryHandle(LaunchActivity activity, Uri data) {
        RedPacketLinkParser.Result result = RedPacketLinkParser.parse(
                data == null ? null : data.toString()
        );
        if (result == null) {
            return false;
        }

        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null) {
            return false;
        }

        WalletNavigator.openClaimRedPacket(fragment, result.packetId);
        return true;
    }
}