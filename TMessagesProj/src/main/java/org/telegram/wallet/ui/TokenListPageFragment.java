package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
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
    private boolean showRedPacketRecords;
    private LinearLayout listContainer;
    private TextView summaryCountView;
    private volatile boolean syncingRecords;
    private volatile List<RedPacketSendRecord> remoteRedPacketRecords = new ArrayList<>();

    public static TokenListPageFragment tokenList() { TokenListPageFragment f = new TokenListPageFragment(); Bundle b = new Bundle(); b.putBoolean(ARG_RECORD, false); f.setArguments(b); return f; }
    public static TokenListPageFragment redPacketRecords() { TokenListPageFragment f = new TokenListPageFragment(); Bundle b = new Bundle(); b.putBoolean(ARG_RECORD, true); f.setArguments(b); return f; }

    @Override public android.view.View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        showRedPacketRecords = getArguments() != null && getArguments().getBoolean(ARG_RECORD, false);
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(showRedPacketRecords ? 8 : 18), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        if (showRedPacketRecords) root.addView(createRecordSummaryCard(), Web3Ui.matchWrap()); else root.addView(Web3Ui.sectionTitle(getActivity(), 0, "代币列表"), Web3Ui.matchWrap());
        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), showRedPacketRecords ? 16 : 18));
        refresh();
        return scroll;
    }

    @Override public void refresh() {
        if (getActivity() == null || listContainer == null) return;
        listContainer.removeAllViews();
        if (showRedPacketRecords) { renderRedPacketRecords(); syncRedPacketRecordsFromServer(); } else renderTokens();
    }

    private LinearLayout createRecordSummaryCard() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(18), dp(18), dp(18));
        card.setBackground(Web3Ui.softOrangeGradient(getActivity(), 20));
        Web3Ui.setElevation(card, 3);
        FrameLayout icon = Web3Ui.iconCircle(getActivity(), Web3IconView.RED_PACKET, 0xFFFFFFFF, p.orange, 68);
        card.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));
        LinearLayout copy = new LinearLayout(getActivity());
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyLp.leftMargin = dp(18);
        card.addView(copy, copyLp);
        copy.addView(Web3Ui.text(getActivity(), "红包发送记录", 18, p.primaryText, true), Web3Ui.matchWrap());
        summaryCountView = Web3Ui.text(getActivity(), "共 0 条记录", 14, p.secondaryText, false);
        copy.addView(summaryCountView, Web3Ui.topMargin(getActivity(), 4));
        return card;
    }

    private void renderTokens() {
        List<TokenAsset> tokens = WalletStorage.getTokens(getActivity());
        if (tokens.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), "暂无自定义代币", 14, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(42), 0, 0); listContainer.addView(empty, Web3Ui.matchWrap()); return;
        }
        for (TokenAsset token : tokens) listContainer.addView(createTokenCard(token), Web3Ui.topMargin(getActivity(), 12));
    }

    private LinearLayout createTokenCard(TokenAsset token) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(16), dp(16), dp(12), dp(16));
        card.setBackground(Web3Ui.roundedStroke(getActivity(), p.cardBg, p.strongBorder, 20, 1)); Web3Ui.setElevation(card, 3);
        card.addView(Web3Ui.tokenBadge(getActivity(), token.symbol, 58), new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout info = new LinearLayout(getActivity()); info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); infoLp.leftMargin = dp(16); card.addView(info, infoLp);
        info.addView(Web3Ui.text(getActivity(), token.symbol, 15, p.primaryText, true), Web3Ui.matchWrap());
        LinearLayout meta = new LinearLayout(getActivity()); meta.setOrientation(LinearLayout.HORIZONTAL); meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.addView(new Web3IconView(getActivity(), Web3IconView.COPY, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView addr = Web3Ui.text(getActivity(), WalletWorkflowCoordinator.shortAddress(token.contractAddress), 13, p.secondaryText, false); LinearLayout.LayoutParams addrLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); addrLp.leftMargin = dp(8); meta.addView(addr, addrLp);
        meta.addView(Web3Ui.text(getActivity(), "  |  ", 13, p.mutedText, false));
        meta.addView(new Web3IconView(getActivity(), Web3IconView.CUBE, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        meta.addView(Web3Ui.text(getActivity(), " decimals=" + token.decimals, 13, p.secondaryText, false));
        info.addView(meta, Web3Ui.topMargin(getActivity(), 4));
        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), new LinearLayout.LayoutParams(dp(22), dp(22)));
        return card;
    }

    private void renderRedPacketRecords() {
        List<RedPacketSendRecord> records = remoteRedPacketRecords;
        if (summaryCountView != null) summaryCountView.setText("共 " + records.size() + " 条记录");
        if (records.isEmpty()) {
            LinearLayout emptyCard = Web3Ui.card(getActivity());
            TextView empty = Web3Ui.text(getActivity(), syncingRecords ? "正在加载红包发送记录..." : "暂无红包发送记录", 14, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(12), 0, dp(12)); emptyCard.addView(empty, Web3Ui.matchWrap()); listContainer.addView(emptyCard, Web3Ui.matchWrap()); return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        for (RedPacketSendRecord record : records) listContainer.addView(createRedPacketCard(record, format), Web3Ui.topMargin(getActivity(), 12));
        LinearLayout footer = new LinearLayout(getActivity()); footer.setOrientation(LinearLayout.HORIZONTAL); footer.setGravity(Gravity.CENTER);
        footer.addView(new Web3IconView(getActivity(), Web3IconView.SHIELD, Web3Ui.palette().mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView text = Web3Ui.text(getActivity(), "区块链交易 · 安全透明 · 不可篡改", 13, Web3Ui.palette().mutedText, false);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); textLp.leftMargin = dp(6); footer.addView(text, textLp);
        listContainer.addView(footer, Web3Ui.topMargin(getActivity(), 14));
    }

    private LinearLayout createRedPacketCard(RedPacketSendRecord record, SimpleDateFormat format) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity()); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(16), dp(16), dp(12), dp(16)); card.setBackground(Web3Ui.roundedStroke(getActivity(), p.cardBg, p.border, 18, 1)); Web3Ui.setElevation(card, 2);
        card.addView(Web3Ui.tokenBadge(getActivity(), record.tokenSymbol, 48), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout content = new LinearLayout(getActivity()); content.setOrientation(LinearLayout.VERTICAL); LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); contentLp.leftMargin = dp(14); card.addView(content, contentLp);
        LinearLayout top = new LinearLayout(getActivity()); top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL); content.addView(top, Web3Ui.matchWrap());
        TextView amountView = Web3Ui.text(getActivity(), safe(record.tokenSymbol, "HTL") + "  " + Web3Ui.formatTokenAmount(record.totalAmount), 15, p.primaryText, true); amountView.setSingleLine(true); top.addView(amountView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView countView = Web3Ui.text(getActivity(), "|  " + record.count + "份", 13, p.orange, true); LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); countLp.leftMargin = dp(8); top.addView(countView, countLp);
        TextView status = Web3Ui.statusBadge(getActivity(), record.status); LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); statusLp.leftMargin = dp(10); top.addView(status, statusLp);
        content.addView(metaRow(Web3IconView.CLOCK, "时间", format.format(new Date(record.createdAt))), Web3Ui.topMargin(getActivity(), 12));
        content.addView(metaRow(Web3IconView.LINK, "Tx", TextUtils.isEmpty(record.txHash) ? "-" : Web3Ui.shortHash(record.txHash)), Web3Ui.topMargin(getActivity(), 4));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(20), dp(20)); chevronLp.leftMargin = dp(8); card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), chevronLp);
        return card;
    }

    private LinearLayout metaRow(int icon, String label, String value) { Web3Ui.Palette p = Web3Ui.palette(); LinearLayout row = new LinearLayout(getActivity()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(new Web3IconView(getActivity(), icon, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18))); TextView text = Web3Ui.text(getActivity(), label + "   " + value, 13, p.secondaryText, false); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.leftMargin = dp(8); row.addView(text, lp); return row; }
    private String safe(String value, String fallback) { return TextUtils.isEmpty(value) ? fallback : value; }

    private void syncRedPacketRecordsFromServer() {
        if (getActivity() == null || syncingRecords) return;
        String address = WalletStorage.getSelectedAddress(getActivity());
        if (address == null || address.trim().isEmpty()) return;
        syncingRecords = true;
        new Thread(() -> { try { List<RedPacketSendRecord> remote = RedPacketRepository.getInstance().getSendRecords(address, 100); if (getActivity() == null) return; remoteRedPacketRecords = remote != null ? remote : new ArrayList<>(); getActivity().runOnUiThread(this::refresh); } catch (Throwable ignore) { } finally { syncingRecords = false; } }, "wallet-records-sync").start();
    }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
