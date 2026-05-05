package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.wallet.chain.RedPacketContractService;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.model.RedPacketClaimRecord;
import org.telegram.wallet.model.RedPacketRefundRecord;
import org.telegram.wallet.model.RedPacketSendRecordDetail;
import org.telegram.wallet.redpacket.RedPacketRepository;
import org.telegram.wallet.security.WalletKeyStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RedPacketRecordDetailFragment extends Fragment {
    private static final String ARG_PACKET_ID = "arg_packet_id";
    private LinearLayout root;
    private volatile boolean refundSubmitting;

    public static RedPacketRecordDetailFragment newInstance(String packetId) {
        RedPacketRecordDetailFragment f = new RedPacketRecordDetailFragment();
        Bundle b = new Bundle();
        b.putString(ARG_PACKET_ID, packetId);
        f.setArguments(b);
        return f;
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scrollView = new ScrollView(getActivity());
        root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(18));
        scrollView.addView(root);
        load();
        return scrollView;
    }

    private void load() {
        root.removeAllViews();
        root.addView(Web3Ui.text(getActivity(), "红包详情加载中...", 14, Web3Ui.palette().secondaryText, false));
        final String packetId = getArguments() == null ? "" : getArguments().getString(ARG_PACKET_ID, "");
        new Thread(() -> {
            try {
                RedPacketSendRecordDetail detail = RedPacketRepository.getInstance().getSendRecordDetail(packetId);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> render(detail));
            } catch (Throwable t) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    root.removeAllViews();
                    root.addView(Web3Ui.text(getActivity(), "加载失败", 14, Web3Ui.palette().secondaryText, false));
                });
            }
        }).start();
    }

    private void render(RedPacketSendRecordDetail detail) {
        root.removeAllViews();
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        root.addView(Web3Ui.text(getActivity(), "红包详情", 18, Web3Ui.palette().primaryText, true));
        root.addView(Web3Ui.text(getActivity(), "Token: " + detail.tokenSymbol + "  总额: " + detail.totalAmount, 14, Web3Ui.palette().secondaryText, false));
        root.addView(Web3Ui.text(getActivity(), "发出时间: " + format.format(new Date(detail.createdAt)), 14, Web3Ui.palette().secondaryText, false));
        root.addView(Web3Ui.text(getActivity(), "领取记录", 16, Web3Ui.palette().primaryText, true), Web3Ui.topMargin(getActivity(), 10));
        if (detail.claimRecords.isEmpty()) {
            root.addView(Web3Ui.text(getActivity(), "暂无领取记录", 14, Web3Ui.palette().secondaryText, false));
        }
        for (RedPacketClaimRecord claim : detail.claimRecords) {
            LinearLayout card = Web3Ui.card(getActivity());
            card.setOrientation(LinearLayout.VERTICAL);
            card.addView(line("Telegram名字", TextUtils.isEmpty(claim.claimerName) ? WalletWorkflowCoordinator.shortAddress(claim.claimerAddress) : claim.claimerName));
            card.addView(line("时间", format.format(new Date(claim.claimedAt))));
            card.addView(line("领取数量", claim.amountWei));
            card.addView(line("Tx", TextUtils.isEmpty(claim.txHash) ? "-" : Web3Ui.shortHash(claim.txHash)));
            root.addView(card, Web3Ui.topMargin(getActivity(), 8));
        }

        root.addView(Web3Ui.text(getActivity(), "可回退记录", 16, Web3Ui.palette().primaryText, true), Web3Ui.topMargin(getActivity(), 14));
        if (detail.refundRecords.isEmpty()) {
            root.addView(Web3Ui.text(getActivity(), "暂无可回退记录", 14, Web3Ui.palette().secondaryText, false));
            return;
        }
        for (RedPacketRefundRecord refund : detail.refundRecords) {
            LinearLayout card = Web3Ui.card(getActivity());
            card.setOrientation(LinearLayout.VERTICAL);
            card.addView(line("回退ID", TextUtils.isEmpty(refund.refundId) ? "-" : refund.refundId));
            card.addView(line("剩余金额", TextUtils.isEmpty(refund.amountDisplay) ? refund.amountWei : refund.amountDisplay));
            card.addView(line("状态", refund.refunded ? "已回退" : (refund.canRefund ? "可回退" : "不可回退")));
            LinearLayout action = Web3Ui.actionButton(getActivity(), refund.refunded ? "已回退" : "回退剩余金额", 0, true);
            action.setOnClickListener(v -> onClickRefund(refund));
            action.setEnabled(!refund.refunded && refund.canRefund && !refundSubmitting);
            action.setAlpha(action.isEnabled() ? 1f : 0.55f);
            card.addView(action, Web3Ui.topMargin(getActivity(), 8));
            root.addView(card, Web3Ui.topMargin(getActivity(), 8));
        }
    }

    private void onClickRefund(RedPacketRefundRecord refund) {
        if (refundSubmitting || refund == null || !refund.canRefund || refund.refunded) {
            return;
        }
        String privateKeyHex;
        try {
            privateKeyHex = WalletKeyStore.loadPrivateKey(getActivity());
        } catch (Throwable t) {
            FileLog.e(t);
            Toast.makeText(getActivity(), "读取本地钱包失败", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(privateKeyHex)) {
            Toast.makeText(getActivity(), "请先创建或导入钱包", Toast.LENGTH_SHORT).show();
            return;
        }
        final String packetId = TextUtils.isEmpty(refund.packetIdHex) ? (getArguments() == null ? "" : getArguments().getString(ARG_PACKET_ID, "")) : refund.packetIdHex;
        final String contract = TextUtils.isEmpty(refund.contractAddress) ? WalletConfig.RED_PACKET_CONTRACT : refund.contractAddress;
        refundSubmitting = true;
        Toast.makeText(getActivity(), "正在提交回退交易…", Toast.LENGTH_SHORT).show();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                new RedPacketContractService().refund(privateKeyHex, contract, packetId);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    refundSubmitting = false;
                    Toast.makeText(getActivity(), "回退成功", Toast.LENGTH_SHORT).show();
                    load();
                });
            } catch (Throwable t) {
                FileLog.e(t);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    refundSubmitting = false;
                    Toast.makeText(getActivity(), "回退失败：" + (t.getMessage() == null ? "unknown" : t.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private TextView line(String k, String v) {
        TextView tv = Web3Ui.text(getActivity(), k + "： " + (v == null ? "-" : v), 13, Web3Ui.palette().secondaryText, false);
        tv.setGravity(Gravity.START);
        return tv;
    }
    private int dp(int v) { return Web3Ui.dp(getActivity(), v); }
}
