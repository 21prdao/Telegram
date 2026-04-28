package org.telegram.wallet.ui;

import android.app.Fragment;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.RedPacketSendRecord;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.redpacket.RedPacketRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TokenListPageFragment extends Fragment implements WalletRefreshable {

    private static final String ARG_RECORD = "arg_record";
    private boolean showRedPacketRecords;
    private LinearLayout listContainer;
    private volatile boolean syncingRecords;
    private boolean recordsSyncedOnce;

    public static TokenListPageFragment tokenList() {
        TokenListPageFragment f = new TokenListPageFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_RECORD, false);
        f.setArguments(b);
        return f;
    }

    public static TokenListPageFragment redPacketRecords() {
        TokenListPageFragment f = new TokenListPageFragment();
        Bundle b = new Bundle();
        b.putBoolean(ARG_RECORD, true);
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        showRedPacketRecords = getArguments() != null && getArguments().getBoolean(ARG_RECORD, false);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        TextView title = createText(18, true);
        title.setText(showRedPacketRecords ? "我的红包发送记录" : "代币列表");
        root.addView(title, matchWrap());

        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(8);
        root.addView(listContainer, lp);

        refresh();
        return root;
    }

    @Override
    public void refresh() {
        if (getActivity() == null || listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        if (showRedPacketRecords) {
            renderRedPacketRecords();
            if (!recordsSyncedOnce) {
                syncRedPacketRecordsFromServer();
            }
        } else {
            renderTokens();
        }
    }

    private void renderTokens() {
        List<TokenAsset> tokens = WalletStorage.getTokens(getActivity());
        if (tokens.isEmpty()) {
            TextView empty = createText(14, false);
            empty.setText("暂无自定义代币");
            listContainer.addView(empty, matchWrap());
            return;
        }
        for (TokenAsset token : tokens) {
            TextView item = createText(14, true);
            item.setBackground(cardBg());
            item.setText(token.symbol + "\n" + WalletWorkflowCoordinator.shortAddress(token.contractAddress) + "  ·  decimals=" + token.decimals);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(8);
            listContainer.addView(item, lp);
        }
    }

    private void renderRedPacketRecords() {
        List<RedPacketSendRecord> records = WalletStorage.getRedPacketSendRecords(getActivity());
        if (records.isEmpty()) {
            TextView empty = createText(14, false);
            empty.setText("暂无红包发送记录");
            listContainer.addView(empty, matchWrap());
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (RedPacketSendRecord record : records) {
            TextView item = createText(13, false);
            item.setBackground(cardBg());
            String when = format.format(new Date(record.createdAt));
            String tx = record.txHash == null || record.txHash.isEmpty() ? "-" : WalletWorkflowCoordinator.shortAddress(record.txHash);
            item.setText(record.tokenSymbol + " " + record.totalAmount + " / " + record.count + "份\n状态：" + record.status + "\n时间：" + when + "\nTx：" + tx);
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(8);
            listContainer.addView(item, lp);
        }
    }

    private void syncRedPacketRecordsFromServer() {
        if (getActivity() == null || syncingRecords) {
            return;
        }
        String address = WalletStorage.getSelectedAddress(getActivity());
        if (address == null || address.trim().isEmpty()) {
            return;
        }
        syncingRecords = true;
        new Thread(() -> {
            try {
                List<RedPacketSendRecord> remote = RedPacketRepository.getInstance().getSendRecords(address, 100);
                if (getActivity() == null) {
                    return;
                }
                WalletStorage.replaceRedPacketSendRecords(getActivity(), remote);
                recordsSyncedOnce = true;
                getActivity().runOnUiThread(this::refresh);
            } catch (Throwable ignore) {
            } finally {
                syncingRecords = false;
            }
        }, "wallet-records-sync").start();
    }

    private GradientDrawable cardBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return bg;
    }

    private TextView createText(int size, boolean bold) {
        TextView tv = new TextView(getActivity());
        tv.setTextSize(size);
        tv.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int c(String key) {
        return Theme.getColor(Integer.parseInt(key));
    }
}
