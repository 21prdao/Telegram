package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.RedPacketSendRecord;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.redpacket.RedPacketRepository;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TokenListPageFragment extends Fragment implements WalletRefreshable {
    private static final String ARG_RECORD = "arg_record";
    private static final long RED_PACKET_FETCH_DEBOUNCE_MS = 1500L;

    private boolean showRedPacketRecords;
    private LinearLayout listContainer;
    private TextView summaryCountView;
    private volatile boolean syncingRecords;
    private volatile long lastRedPacketFetchAt;
    private volatile List<RedPacketSendRecord> remoteRedPacketRecords = new ArrayList<>();

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
        Web3Ui.Palette p = Web3Ui.palette();

        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(showRedPacketRecords ? 8 : 12), dp(14), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        if (showRedPacketRecords) {
            root.addView(createRecordSummaryCard(), Web3Ui.matchWrap());
        } else {
            root.addView(Web3Ui.sectionTitle(getActivity(), 0, "代币列表"), Web3Ui.matchWrap());
        }

        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), showRedPacketRecords ? 8 : 12));
        refresh();
        return scroll;
    }

    @Override
    public void refresh() {
        if (getActivity() == null || listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        if (showRedPacketRecords) {
            syncRedPacketRecordsFromServer();
            renderRedPacketRecords();
        } else {
            renderTokens();
        }
    }

    private LinearLayout createRecordSummaryCard() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);

        FrameLayout icon = Web3Ui.iconCircle(getActivity(), Web3IconView.RED_PACKET, p.orange, p.dark ? 0x22111111 : 0x11F08C22, 40);
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = new LinearLayout(getActivity());
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyLp.leftMargin = dp(10);
        card.addView(copy, copyLp);

        copy.addView(Web3Ui.text(getActivity(), "红包发送记录", 17, p.primaryText, true), Web3Ui.matchWrap());
        summaryCountView = Web3Ui.text(getActivity(), "共 0 条记录", 13, p.secondaryText, false);
        copy.addView(summaryCountView, Web3Ui.topMargin(getActivity(), 2));
        return card;
    }

    private void renderTokens() {
        List<TokenAsset> tokens = WalletStorage.getTokens(getActivity());
        if (tokens.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), "暂无自定义代币", 15, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, 0);
            listContainer.addView(empty, Web3Ui.matchWrap());
            return;
        }
        for (TokenAsset token : tokens) {
            listContainer.addView(createTokenCard(token), Web3Ui.topMargin(getActivity(), 8));
        }
    }

    private LinearLayout createTokenCard(TokenAsset token) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(10), dp(12));
        card.setBackground(Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);

        card.addView(Web3Ui.tokenBadge(getActivity(), token.symbol, 42), new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(12);
        card.addView(info, infoLp);

        info.addView(Web3Ui.text(getActivity(), token.symbol, 18, p.primaryText, true), Web3Ui.matchWrap());

        LinearLayout meta = new LinearLayout(getActivity());
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(new Web3IconView(getActivity(), Web3IconView.COPY, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));

        TextView addr = Web3Ui.text(getActivity(), WalletWorkflowCoordinator.shortAddress(token.contractAddress), 13, p.secondaryText, false);
        LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addrLp.leftMargin = dp(6);
        meta.addView(addr, addrLp);
        meta.addView(Web3Ui.text(getActivity(), "  |  ", 13, p.mutedText, false));
        meta.addView(new Web3IconView(getActivity(), Web3IconView.CUBE, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        meta.addView(Web3Ui.text(getActivity(), " decimals=" + token.decimals, 13, p.secondaryText, false));
        info.addView(meta, Web3Ui.topMargin(getActivity(), 4));

        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        return card;
    }

    private void renderRedPacketRecords() {
        List<RedPacketSendRecord> records = remoteRedPacketRecords;
        if (summaryCountView != null) {
            summaryCountView.setText("共 " + records.size() + " 条记录");
        }
        if (records.isEmpty()) {
            LinearLayout emptyCard = Web3Ui.card(getActivity());
            TextView empty = Web3Ui.text(getActivity(), syncingRecords ? "正在加载红包发送记录..." : "暂无红包发送记录", 15, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(12), 0, dp(12));
            emptyCard.addView(empty, Web3Ui.matchWrap());
            listContainer.addView(emptyCard, Web3Ui.matchWrap());
            return;
        }

        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (RedPacketSendRecord record : records) {
            listContainer.addView(createRedPacketCard(record, format), Web3Ui.topMargin(getActivity(), 8));
        }

        LinearLayout footer = new LinearLayout(getActivity());
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER);
        footer.addView(new Web3IconView(getActivity(), Web3IconView.SHIELD, Web3Ui.palette().mutedText), new LinearLayout.LayoutParams(dp(16), dp(16)));

        TextView text = Web3Ui.text(getActivity(), "区块链交易 · 安全透明 · 不可篡改", 12, Web3Ui.palette().mutedText, false);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        textLp.leftMargin = dp(6);
        footer.addView(text, textLp);
        listContainer.addView(footer, Web3Ui.topMargin(getActivity(), 10));
    }

    private LinearLayout createRedPacketCard(RedPacketSendRecord record, SimpleDateFormat format) {
        Web3Ui.Palette p = Web3Ui.palette();

        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(11), dp(8), dp(11));
        card.setBackground(Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);

        LinearLayout iconWrap = new LinearLayout(getActivity());
        iconWrap.setOrientation(LinearLayout.VERTICAL);
        iconWrap.setGravity(Gravity.CENTER);
        card.addView(iconWrap, new LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.WRAP_CONTENT));
        iconWrap.addView(Web3Ui.tokenBadge(getActivity(), record.tokenSymbol, 30), new LinearLayout.LayoutParams(dp(30), dp(30)));

        LinearLayout content = new LinearLayout(getActivity());
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        contentLp.leftMargin = dp(10);
        card.addView(content, contentLp);

        LinearLayout topRow = new LinearLayout(getActivity());
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(topRow, Web3Ui.matchWrap());

        LinearLayout titleBlock = new LinearLayout(getActivity());
        titleBlock.setOrientation(LinearLayout.HORIZONTAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);
        topRow.addView(titleBlock, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView amountView = Web3Ui.text(getActivity(), safe(record.tokenSymbol, "HTL") + "  " + Web3Ui.formatTokenAmount(record.totalAmount), 15, p.primaryText, true);
        amountView.setSingleLine(true);
        titleBlock.addView(amountView);

        TextView countView = Web3Ui.text(getActivity(), record.count + "份", 13, p.orange, true);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        countLp.leftMargin = dp(10);
        titleBlock.addView(countView, countLp);

        TextView statusView = compactStatusBadge(record.status);
        topRow.addView(statusView);

        content.addView(metaRowCompact(Web3IconView.CLOCK, "时间", format.format(new Date(record.createdAt))), Web3Ui.topMargin(getActivity(), 6));
        content.addView(metaRowCompact(Web3IconView.LINK, "Tx", TextUtils.isEmpty(record.txHash) ? "-" : Web3Ui.shortHash(record.txHash)), Web3Ui.topMargin(getActivity(), 2));

        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevronLp.leftMargin = dp(6);
        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), chevronLp);
        return card;
    }

    private TextView compactStatusBadge(String status) {
        Web3Ui.Palette p = Web3Ui.palette();
        String safeStatus = TextUtils.isEmpty(status) ? "-" : status;
        boolean active = "ACTIVE".equalsIgnoreCase(safeStatus);
        boolean pending = safeStatus.toUpperCase().contains("PENDING");

        int textColor;
        int bgColor;
        int strokeColor;
        if (active) {
            textColor = p.green;
            bgColor = p.dark ? 0x1722C55E : 0xFFEAF9F0;
            strokeColor = Web3Ui.withAlpha(p.green, 110);
        } else if (pending) {
            textColor = p.orange;
            bgColor = p.pendingBadgeBg;
            strokeColor = Web3Ui.withAlpha(p.orange, 90);
        } else {
            textColor = p.dark ? 0xFFC7D0DC : 0xFF667384;
            bgColor = p.grayBadgeBg;
            strokeColor = p.dark ? 0xFF4A5666 : 0xFFD7DEE8;
        }

        TextView tv = Web3Ui.text(getActivity(), safeStatus, 10.5f, textColor, false);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(150));
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(Web3Ui.roundedStroke(getActivity(), bgColor, strokeColor, 11, 1));
        return tv;
    }

    private LinearLayout metaRowCompact(int icon, String label, String value) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        row.addView(new Web3IconView(getActivity(), icon, p.mutedText), new LinearLayout.LayoutParams(dp(15), dp(15)));

        TextView labelView = Web3Ui.text(getActivity(), label, 14, p.secondaryText, false);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        labelLp.leftMargin = dp(7);
        row.addView(labelView, labelLp);

        TextView valueView = Web3Ui.text(getActivity(), value, 14, p.secondaryText, false);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        valueLp.leftMargin = dp(8);
        row.addView(valueView, valueLp);
        return row;
    }

    private String safe(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    private void syncRedPacketRecordsFromServer() {
        if (getActivity() == null || syncingRecords) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRedPacketFetchAt < RED_PACKET_FETCH_DEBOUNCE_MS) {
            return;
        }
        String address = WalletStorage.getSelectedAddress(getActivity());
        if (address == null || address.trim().isEmpty()) {
            return;
        }

        syncingRecords = true;
        lastRedPacketFetchAt = now;

        new Thread(() -> {
            List<RedPacketSendRecord> remote = null;
            boolean success = false;
            try {
                remote = RedPacketRepository.getInstance().getSendRecords(address, 100);
                success = true;
            } catch (Throwable ignore) {
            }

            final List<RedPacketSendRecord> finalRemote = remote;
            final boolean finalSuccess = success;
            if (getActivity() == null) {
                syncingRecords = false;
                return;
            }

            getActivity().runOnUiThread(() -> {
                syncingRecords = false;
                if (finalSuccess) {
                    remoteRedPacketRecords = finalRemote != null ? finalRemote : new ArrayList<>();
                }
                if (showRedPacketRecords && listContainer != null) {
                    listContainer.removeAllViews();
                    renderRedPacketRecords();
                }
            });
        }, "wallet-records-sync").start();
    }

    private int dp(int value) {
        return Web3Ui.dp(getActivity(), value);
    }
}
