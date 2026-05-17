package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.content.Intent;
import android.net.Uri;
import android.widget.TextView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.RedPacketSendRecord;
import org.telegram.wallet.model.RedPacketSendRecordDetail;
import org.telegram.wallet.model.RedPacketClaimRecord;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.redpacket.RedPacketRepository;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TokenListPageFragment extends Fragment implements WalletRefreshable {
    private static final String ARG_RECORD = "arg_record";
    private static final long RED_PACKET_FETCH_DEBOUNCE_MS = 1500L;

    private boolean showRedPacketRecords;
    private LinearLayout listContainer;
    private TextView summaryCountView;
    private volatile boolean syncingRecords;
    private volatile long lastRedPacketFetchAt;
    private volatile List<RedPacketSendRecord> remoteRedPacketRecords = new ArrayList<>();
    private static final int RECORD_PAGE_SIZE = 10;
    private int currentRecordOffset = 0;
    private boolean hasMoreRecords = true;
    private SwipeRefreshLayout swipeRefreshLayout;
    private String currentStatusFilter = "all";
    private volatile boolean tokenListMetadataLoading;
    private final Handler tokenPriceRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable tokenPriceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!showRedPacketRecords && isAdded() && getActivity() != null && listContainer != null) {
                refresh();
                scheduleTokenPriceRefresh();
            }
        }
    };

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
        root.setPadding(dp(10), dp(showRedPacketRecords ? 8 : 12), dp(10), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        if (showRedPacketRecords) {
            root.addView(createRecordSummaryCard(), Web3Ui.matchWrap());
        }
//        else {
//            root.addView(Web3Ui.sectionTitle(getActivity(), 0, LocaleController.getString(R.string.WalletTokenList)), Web3Ui.matchWrap());
//        }

        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), showRedPacketRecords ? 8 : 12));
        if (showRedPacketRecords) {
            swipeRefreshLayout = new SwipeRefreshLayout(getActivity());
            swipeRefreshLayout.addView(scroll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            swipeRefreshLayout.setOnRefreshListener(() -> {
                currentRecordOffset = 0;
                hasMoreRecords = true;
                syncRedPacketRecordsFromServer(false);
            });
            refresh();
            return swipeRefreshLayout;
        }
        refresh();
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!showRedPacketRecords && listContainer != null) {
            refresh();
            scheduleTokenPriceRefresh();
        }
    }

    @Override
    public void onPause() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        super.onDestroyView();
    }

    private void scheduleTokenPriceRefresh() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        tokenPriceRefreshHandler.postDelayed(tokenPriceRefreshRunnable, WalletUiRefreshPolicy.TOKEN_PRICE_REFRESH_INTERVAL_MS);
    }

    @Override
    public void refresh() {
        if (getActivity() == null || listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        if (showRedPacketRecords) {
            currentRecordOffset = 0;
            hasMoreRecords = true;
            syncRedPacketRecordsFromServer(false);
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

        FrameLayout icon = Web3Ui.iconCircleDrawable(getActivity(), R.drawable.icon_wallet_3_1, p.dark ? 0x22111111 : 0x11F08C22, 40);
        card.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout copy = new LinearLayout(getActivity());
        copy.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        copyLp.leftMargin = dp(10);
        card.addView(copy, copyLp);

        copy.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.WalletRedPacketRecords), 17, p.primaryText, true), Web3Ui.matchWrap());
        LinearLayout summaryRow = new LinearLayout(getActivity());
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        summaryCountView = Web3Ui.text(getActivity(), "总共 0 条记录", 13, p.secondaryText, false);
        summaryRow.addView(summaryCountView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        summaryRow.addView(filterButton("全部", "all"));
        copy.addView(summaryRow, Web3Ui.topMargin(getActivity(), 2));
        return card;
    }

    private TextView filterButton(String text, String status) {
        Web3Ui.Palette p = Web3Ui.palette();
        TextView tv = Web3Ui.text(getActivity(), text + "  ▾", 11, p.primaryText, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(Web3Ui.roundedStroke(getActivity(), p.grayBadgeBg, p.border, 12, 1));
        tv.setOnClickListener(v -> showStatusFilterPopup(tv));
        return tv;
    }

    private void showStatusFilterPopup(TextView anchor) {
        if (getActivity() == null || anchor == null) {
            return;
        }
        final String[] labels = new String[]{"全部", "进行中", "已领完", "已过期", "已退款"};
        final String[] values = new String[]{"all", "active", "empty", "expired", "refunded"};
        Web3Ui.Palette p = Web3Ui.palette();

        LinearLayout panel = new LinearLayout(getActivity());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setBackground(Web3Ui.roundedStroke(getActivity(), p.cardBg, p.border, 16, 1));
        Web3Ui.setElevation(panel, 14);

        final PopupWindow popup = new PopupWindow(getActivity());
        for (int i = 0; i < labels.length; i++) {
            TextView row = statusPopupRow(labels[i], values[i], anchor, popup);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            if (i > 0) {
                rowLp.topMargin = dp(2);
            }
            panel.addView(row, rowLp);
        }

        int popupWidth = dp(146);
        popup.setContentView(panel);
        popup.setWidth(popupWidth);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            popup.setElevation(dp(12));
        }
        int xoff = anchor.getWidth() - popupWidth;
        popup.showAsDropDown(anchor, xoff, dp(8));
    }

    private TextView statusPopupRow(String label, String value, TextView anchor, PopupWindow popup) {
        Web3Ui.Palette p = Web3Ui.palette();
        boolean active = value.equalsIgnoreCase(currentStatusFilter);
        TextView row = Web3Ui.text(getActivity(), (active ? "✓  " : "    ") + label, 14, active ? p.orange : p.primaryText, active);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setBackground(Web3Ui.rounded(getActivity(), active ? (p.dark ? 0x29362418 : 0xFFFFF4E8) : 0x00000000, 12));
        row.setOnClickListener(v -> {
            currentStatusFilter = value;
            anchor.setText(label + "  ▾");
            if (popup != null) {
                popup.dismiss();
            }
            if (showRedPacketRecords && listContainer != null) {
                listContainer.removeAllViews();
                renderRedPacketRecords();
            }
        });
        return row;
    }

    private void renderTokens() {
        List<TokenAsset> localTokens = WalletStorage.getTokens(getActivity());
        if (localTokens.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), "暂无代币", 15, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(28), 0, 0);
            listContainer.addView(empty, Web3Ui.matchWrap());
        }
        for (TokenAsset token : localTokens) {
            listContainer.addView(createTokenCard(token), Web3Ui.topMargin(getActivity(), 8));
        }
        if (tokenListMetadataLoading) {
            return;
        }
        tokenListMetadataLoading = true;
        new Thread(() -> {
            try {
                List<TokenAsset> defaults = RedPacketRepository.getInstance().getDefaultTokens();
                List<TokenAsset> merged = WalletStorage.mergeTokens(defaults, localTokens);
                try {
                    RedPacketRepository.TokenMetadata metadata = RedPacketRepository.getInstance().getTokenMetadataForContracts(merged);
                    applyTokenMetadata(merged, metadata);
                } catch (Throwable ignore) {
                }
                if (getActivity() == null) {
                    return;
                }
                getActivity().runOnUiThread(() -> {
                    listContainer.removeAllViews();
                    if (merged.isEmpty()) {
                        TextView empty = Web3Ui.text(getActivity(), "暂无代币", 15, Web3Ui.palette().secondaryText, false);
                        empty.setGravity(Gravity.CENTER);
                        empty.setPadding(0, dp(28), 0, 0);
                        listContainer.addView(empty, Web3Ui.matchWrap());
                        return;
                    }
                    for (TokenAsset token : merged) {
                        listContainer.addView(createTokenCard(token), Web3Ui.topMargin(getActivity(), 8));
                    }
                });
            } catch (Throwable ignore) {
            } finally {
                tokenListMetadataLoading = false;
            }
        }, "wallet-token-list").start();
    }

    private void applyTokenMetadata(List<TokenAsset> tokens, RedPacketRepository.TokenMetadata metadata) {
        if (tokens == null || metadata == null) return;
        for (TokenAsset token : tokens) {
            if (token == null) continue;
            String iconUrl = lookupIcon(metadata.iconUrls, token.symbol, token.contractAddress);
            BigDecimal price = lookupPrice(metadata.prices, token.symbol, token.contractAddress);
            String priceText = price.compareTo(BigDecimal.ZERO) > 0 ? price.stripTrailingZeros().toPlainString() : "";
            if (!TextUtils.isEmpty(iconUrl)) {
                token.iconUrl = iconUrl;
            }
            if (!TextUtils.isEmpty(priceText)) {
                token.priceUsd = priceText;
            }
            if (!TextUtils.isEmpty(iconUrl) || !TextUtils.isEmpty(priceText)) {
                if (getActivity() != null) {
                    WalletStorage.updateCustomTokenMetadata(getActivity(), token.contractAddress, priceText, iconUrl);
                }
            }
        }
    }

    private String lookupIcon(Map<String, String> iconMap, String symbol, String contractAddress) {
        if (iconMap == null) return "";
        if (!TextUtils.isEmpty(contractAddress)) {
            String byContract = iconMap.get(RedPacketRepository.priceKeyForContract(contractAddress));
            if (!TextUtils.isEmpty(byContract)) return byContract;
        }
        if (!TextUtils.isEmpty(symbol)) {
            String bySymbol = iconMap.get(RedPacketRepository.priceKeyForSymbol(symbol));
            if (!TextUtils.isEmpty(bySymbol)) return bySymbol;
        }
        return "";
    }

    private BigDecimal lookupPrice(Map<String, BigDecimal> priceMap, String symbol, String contractAddress) {
        if (priceMap == null) return BigDecimal.ZERO;
        if (!TextUtils.isEmpty(contractAddress)) {
            BigDecimal byContract = priceMap.get(RedPacketRepository.priceKeyForContract(contractAddress));
            if (byContract != null && byContract.compareTo(BigDecimal.ZERO) > 0) return byContract;
        }
        if (!TextUtils.isEmpty(symbol)) {
            BigDecimal bySymbol = priceMap.get(RedPacketRepository.priceKeyForSymbol(symbol));
            if (bySymbol != null && bySymbol.compareTo(BigDecimal.ZERO) > 0) return bySymbol;
        }
        return BigDecimal.ZERO;
    }

    private LinearLayout createTokenCard(TokenAsset token) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(10), dp(12));
        card.setBackground(Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);

        card.addView(Web3Ui.tokenBadge(getActivity(), token.symbol, token.iconUrl, 42), new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(12);
        card.addView(info, infoLp);

        info.addView(Web3Ui.text(getActivity(), token.symbol, 18, p.primaryText, true), Web3Ui.matchWrap());
        TextView price = Web3Ui.text(getActivity(), WalletUiFormat.formatUsdPrice(token.priceUsd), 13, p.secondaryText, false);
        price.setSingleLine(true);
        price.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(price, Web3Ui.topMargin(getActivity(), 4));

        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        card.setOnClickListener(v -> openTokenDetail(token));
        return card;
    }

    private void openTokenDetail(TokenAsset token) {
        if (getActivity() == null || token == null) return;
        Intent intent = TokenDetailActivity.intentFor(
                getActivity(),
                token.symbol,
                token.contractAddress,
                token.decimals,
                "",
                token.priceUsd,
                "",
                token.iconUrl
        );
        getActivity().startActivity(intent);
    }


    private String formatMarketPriceDisplay(String priceUsd) {
        return WalletUiFormat.formatUsdPrice(priceUsd);
    }

    private String normalizeStatus(String status) {
        String safeStatus = safe(status, "").trim().toLowerCase(Locale.US);
        if (safeStatus.isEmpty()) {
            return "unknown";
        }
        if (safeStatus.contains("pending")) {
            return "pending";
        }
        if ("active".equals(safeStatus) || "claimable".equals(safeStatus) || "created".equals(safeStatus)) {
            return "active";
        }
        if ("empty".equals(safeStatus) || "claimed".equals(safeStatus) || "finished".equals(safeStatus) || "completed".equals(safeStatus)) {
            return "empty";
        }
        if ("expired".equals(safeStatus) || "expire".equals(safeStatus)) {
            return "expired";
        }
        if ("refunded".equals(safeStatus) || "refund".equals(safeStatus)) {
            return "refunded";
        }
        return safeStatus;
    }

    private String statusLabel(String status) {
        String normalized = normalizeStatus(status);
        switch (normalized) {
            case "active":
                return "进行中";
            case "empty":
                return "已领完";
            case "expired":
                return "已过期";
            case "refunded":
                return "已退款";
            case "pending":
                return "处理中";
            default:
                return TextUtils.isEmpty(status) ? "-" : status;
        }
    }

    private void renderRedPacketRecords() {
        List<RedPacketSendRecord> allRecords = new ArrayList<>();
        List<RedPacketSendRecord> records = new ArrayList<>();
        for (RedPacketSendRecord item : remoteRedPacketRecords) {
            if (item == null || "PENDING_CREATE_CONFIRM".equalsIgnoreCase(item.status) || "pending_create_confirm".equalsIgnoreCase(item.status)) {
                continue;
            }
            allRecords.add(item);
            if ("all".equalsIgnoreCase(currentStatusFilter) || currentStatusFilter.equalsIgnoreCase(normalizeStatus(item.status))) {
                records.add(item);
            }
        }
        if (summaryCountView != null) {
            summaryCountView.setText("总共 " + allRecords.size() + " 条记录");
        }
        if (records.isEmpty()) {
            LinearLayout emptyCard = Web3Ui.card(getActivity());
            TextView empty = Web3Ui.text(getActivity(), syncingRecords ? LocaleController.getString(R.string.WalletRedPacketRecordsLoading) : LocaleController.getString(R.string.WalletRedPacketRecordsEmpty), 15, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(12), 0, dp(12));
            emptyCard.addView(empty, Web3Ui.matchWrap());
            listContainer.addView(emptyCard, Web3Ui.matchWrap());
            return;
        }

        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < records.size(); i++) {
            listContainer.addView(createRedPacketCard(records.get(i), format), Web3Ui.topMargin(getActivity(), 8));
        }
        if (hasMoreRecords) {
            LinearLayout moreBtn = Web3Ui.actionButton(getActivity(), syncingRecords ? "加载中..." : "加载更多", 0, true);
            moreBtn.setEnabled(!syncingRecords);
            moreBtn.setOnClickListener(v -> syncRedPacketRecordsFromServer(true));
            listContainer.addView(moreBtn, Web3Ui.topMargin(getActivity(), 10));
        }

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

        TextView statusView = compactStatusBadge(statusLabel(record.status), normalizeStatus(record.status));
        topRow.addView(statusView);

        content.addView(metaRowCompact(Web3IconView.CLOCK, "时间", format.format(new Date(record.createdAt)), null), Web3Ui.topMargin(getActivity(), 6));
        if (record.expiresAt > 0) {
            content.addView(metaRowCompact(Web3IconView.CLOCK, "到期", format.format(new Date(record.expiresAt)), null), Web3Ui.topMargin(getActivity(), 2));
        }
        content.addView(metaRowCompact(Web3IconView.LINK, "Tx", TextUtils.isEmpty(record.txHash) ? "-" : Web3Ui.shortHash(record.txHash), record.txHash), Web3Ui.topMargin(getActivity(), 2));

        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(16), dp(16));
        chevronLp.leftMargin = dp(6);
        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), chevronLp);
        card.setOnClickListener(v -> openRecordDetail(record));
        return card;
    }

    private TextView compactStatusBadge(String statusText, String normalizedStatus) {
        Web3Ui.Palette p = Web3Ui.palette();
        String safeStatusText = TextUtils.isEmpty(statusText) ? "-" : statusText;
        boolean active = "active".equalsIgnoreCase(normalizedStatus);
        boolean pending = "pending".equalsIgnoreCase(normalizedStatus);

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

        TextView tv = Web3Ui.text(getActivity(), safeStatusText, 10.5f, textColor, false);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth(dp(150));
        tv.setPadding(dp(10), dp(5), dp(10), dp(5));
        tv.setBackground(Web3Ui.roundedStroke(getActivity(), bgColor, strokeColor, 11, 1));
        return tv;
    }

    private LinearLayout metaRowCompact(int icon, String label, String value, String linkValue) {
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
        if ("Tx".equals(label) && !TextUtils.isEmpty(linkValue) && linkValue.startsWith("0x")) {
            TextView go = Web3Ui.text(getActivity(), "链接", 12, p.green, true);
            LinearLayout.LayoutParams goLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            goLp.leftMargin = dp(8);
            go.setOnClickListener(v -> openTx(linkValue));
            row.addView(go, goLp);
        }
        return row;
    }

    private String safe(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }


    private void openRecordDetail(RedPacketSendRecord record) {
        if (record == null || TextUtils.isEmpty(record.packetId)) {
            if (getActivity() instanceof WalletWorkflowCoordinator.Host) {
                ((WalletWorkflowCoordinator.Host) getActivity()).toast("该红包记录缺少ID，暂时无法查看详情");
            }
            return;
        }
        if (getActivity() instanceof WalletManagerActivity) {
            ((WalletManagerActivity) getActivity()).openRedPacketRecordDetailPage(record.packetId);
            return;
        }
        if (getActivity() instanceof TokenListPageActivity) {
            ((TokenListPageActivity) getActivity()).openRedPacketRecordDetailPage(record.packetId);
        }
    }

    private void openTx(String hash) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://bscscan.com/tx/" + hash)));
        } catch (Throwable ignore) {}
    }

    private void syncRedPacketRecordsFromServer(boolean append) {
        if (getActivity() == null || syncingRecords) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!append && now - lastRedPacketFetchAt < RED_PACKET_FETCH_DEBOUNCE_MS) {
            return;
        }
        if (append && !hasMoreRecords) {
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
                int offset = append ? currentRecordOffset : 0;
                remote = RedPacketRepository.getInstance().getSendRecords(address, "", RECORD_PAGE_SIZE, offset);
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
                if (swipeRefreshLayout != null) {
                    swipeRefreshLayout.setRefreshing(false);
                }
                if (finalSuccess) {
                    if (!append) {
                        remoteRedPacketRecords = finalRemote != null ? finalRemote : new ArrayList<>();
                    } else if (finalRemote != null && !finalRemote.isEmpty()) {
                        List<RedPacketSendRecord> merged = new ArrayList<>(remoteRedPacketRecords);
                        merged.addAll(finalRemote);
                        remoteRedPacketRecords = merged;
                    }
                    int fetched = finalRemote == null ? 0 : finalRemote.size();
                    if (!append) {
                        currentRecordOffset = fetched;
                    } else {
                        currentRecordOffset += fetched;
                    }
                    hasMoreRecords = fetched >= RECORD_PAGE_SIZE;
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
