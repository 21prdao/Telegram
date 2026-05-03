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

import org.telegram.wallet.model.RedPacketClaimRecord;
import org.telegram.wallet.model.RedPacketSendRecordDetail;
import org.telegram.wallet.redpacket.RedPacketRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RedPacketRecordDetailFragment extends Fragment {
    private static final String ARG_PACKET_ID = "arg_packet_id";
    private LinearLayout root;

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
            return;
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
    }

    private TextView line(String k, String v) {
        TextView tv = Web3Ui.text(getActivity(), k + "： " + (v == null ? "-" : v), 13, Web3Ui.palette().secondaryText, false);
        tv.setGravity(Gravity.START);
        return tv;
    }
    private int dp(int v) { return Web3Ui.dp(getActivity(), v); }
}
