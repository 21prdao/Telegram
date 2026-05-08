package org.telegram.wallet.navigation;

import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.ui.CreateRedPacketBottomSheet;
import org.telegram.wallet.ui.WalletManagerActivity;
import org.telegram.wallet.ui.RedPacketDetailBottomSheet;
import org.telegram.wallet.model.RedPacketPayload;

public final class WalletNavigator {

    private WalletNavigator() {
    }


    public static void openWalletManager(Context context) {
        warmUpRuntimeConfig();
        if (context == null) {
            return;
        }
        Intent intent = new Intent(context, WalletManagerActivity.class);
        context.startActivity(intent);
    }

    public static void openCreateRedPacket(BaseFragment parent, int account, long dialogId) {
        warmUpRuntimeConfig();
        if (parent == null || parent.getParentActivity() == null) {
            return;
        }
        CreateRedPacketBottomSheet sheet =
                new CreateRedPacketBottomSheet(parent, account, dialogId);
        sheet.show();
    }

    public static void openClaimRedPacket(BaseFragment parent, String packetId) {
        warmUpRuntimeConfig();
        if (parent == null || parent.getParentActivity() == null) {
            return;
        }
        RedPacketDetailBottomSheet sheet =
                new RedPacketDetailBottomSheet(parent, packetId);
        sheet.show();
    }

    public static void openRedPacketDetail(BaseFragment parent, RedPacketPayload payload) {
        warmUpRuntimeConfig();
        if (parent == null || parent.getParentActivity() == null || payload == null) {
            return;
        }
        RedPacketDetailBottomSheet sheet = new RedPacketDetailBottomSheet(parent, payload);
        sheet.show();
    }

    private static void warmUpRuntimeConfig() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                BscRpcClient.refreshRuntimeConfig();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
    }
}
