package org.telegram.wallet.ui;

import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public class WalletHomeFragment extends Fragment implements WalletRefreshable {
    private TextView totalAssetView;
    private TextView walletAddressView;
    private TextView chainNameView;
    private LinearLayout tokenListContainer;
    private String currentAddress;
    private String currentChainName = "BNB Smart Chain";

    public static WalletHomeFragment newInstance() { return new WalletHomeFragment(); }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(10), dp(20), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout assetCard = Web3Ui.card(getActivity());
        assetCard.setBackground(Web3Ui.roundedStroke(getActivity(), p.cardBg, p.strongBorder, 22, 1));
        root.addView(assetCard, Web3Ui.matchWrap());

        LinearLayout labelRow = new LinearLayout(getActivity());
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.addView(Web3Ui.text(getActivity(), "总资产", 15, p.secondaryText, false));
        Web3IconView eye = new Web3IconView(getActivity(), Web3IconView.EYE, p.mutedText);
        LinearLayout.LayoutParams eyeLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        eyeLp.leftMargin = dp(8);
        labelRow.addView(eye, eyeLp);
        assetCard.addView(labelRow, Web3Ui.matchWrap());

        LinearLayout heroRow = new LinearLayout(getActivity());
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        assetCard.addView(heroRow, Web3Ui.topMargin(getActivity(), 6));
        totalAssetView = Web3Ui.text(getActivity(), "--", 34, p.primaryText, true);
        totalAssetView.setSingleLine(true);
        totalAssetView.setIncludeFontPadding(false);
        heroRow.addView(totalAssetView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout chainIcon = Web3Ui.iconCircle(getActivity(), Web3IconView.CUBE, p.orange, p.dark ? 0x22F08C22 : 0xFFFFF2DF, 64);
        heroRow.addView(chainIcon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout addressRow = new LinearLayout(getActivity());
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        walletAddressView = Web3Ui.text(getActivity(), "钱包地址：未创建", 16, p.secondaryText, false);
        addressRow.addView(walletAddressView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addressRow.addView(new Web3IconView(getActivity(), Web3IconView.COPY, p.mutedText), new LinearLayout.LayoutParams(dp(20), dp(20)));
        addressRow.setOnClickListener(v -> copyAddress());
        assetCard.addView(addressRow, Web3Ui.topMargin(getActivity(), 12));

        LinearLayout copyBtn = Web3Ui.actionButton(getActivity(), "复制地址", Web3IconView.COPY, true);
        copyBtn.setOnClickListener(v -> copyAddress());
        assetCard.addView(copyBtn, Web3Ui.topMargin(getActivity(), 16));

        LinearLayout walletOps = new LinearLayout(getActivity());
        walletOps.setOrientation(LinearLayout.HORIZONTAL);
        walletOps.setGravity(Gravity.CENTER);
        walletOps.addView(createSecondaryAction("创建", Web3IconView.PLUS, v -> coordinator().showCreateWalletDialog(this::refresh)), weightLp(0, 4));
        walletOps.addView(createSecondaryAction("导入", Web3IconView.IMPORT, v -> coordinator().showImportWalletDialog(this::refresh)), weightLp(4, 4));
        walletOps.addView(createSecondaryAction("切换", Web3IconView.SWITCH, v -> startActivity(new Intent(getActivity(), WalletListPageActivity.class))), weightLp(4, 0));
        assetCard.addView(walletOps, Web3Ui.topMargin(getActivity(), 14));

        LinearLayout chainRow = new LinearLayout(getActivity());
        chainRow.setGravity(Gravity.CENTER_VERTICAL);
        chainRow.setOrientation(LinearLayout.HORIZONTAL);
        chainRow.addView(new Web3IconView(getActivity(), Web3IconView.CUBE, p.mutedText), new LinearLayout.LayoutParams(dp(20), dp(20)));
        chainNameView = Web3Ui.text(getActivity(), "链：BNB Smart Chain", 15, p.mutedText, false);
        LinearLayout.LayoutParams chainLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chainLp.leftMargin = dp(8);
        chainRow.addView(chainNameView, chainLp);
        assetCard.addView(chainRow, Web3Ui.topMargin(getActivity(), 16));

        LinearLayout quickCard = Web3Ui.card(getActivity());
        root.addView(quickCard, Web3Ui.topMargin(getActivity(), 16));
        quickCard.addView(Web3Ui.sectionTitle(getActivity(), Web3IconView.LIGHTNING, "快捷操作"), Web3Ui.matchWrap());
        LinearLayout addTokenBtn = Web3Ui.actionButton(getActivity(), "添加代币", Web3IconView.PLUS, false);
        addTokenBtn.setOnClickListener(v -> openAddTokenPage());
        quickCard.addView(addTokenBtn, Web3Ui.topMargin(getActivity(), 14));

        LinearLayout tokenCard = Web3Ui.card(getActivity());
        root.addView(tokenCard, Web3Ui.topMargin(getActivity(), 16));
        tokenCard.addView(Web3Ui.sectionTitle(getActivity(), Web3IconView.COINS, "Token 列表"), Web3Ui.matchWrap());
        tokenListContainer = new LinearLayout(getActivity());
        tokenListContainer.setOrientation(LinearLayout.VERTICAL);
        tokenCard.addView(tokenListContainer, Web3Ui.topMargin(getActivity(), 12));
        refresh();
        return scroll;
    }

    @Override public void refresh() {
        if (getActivity() == null) return;
        coordinator().loadBalances((selectedAddress, totalAsset, chainName, tokenLines) -> {
            currentAddress = selectedAddress;
            currentChainName = TextUtils.isEmpty(chainName) ? "BNB Smart Chain" : chainName;
            walletAddressView.setText(TextUtils.isEmpty(selectedAddress) ? "钱包地址：未创建" : "钱包地址：" + WalletWorkflowCoordinator.shortAddress(selectedAddress));
            applyTotalAsset(totalAsset);
            chainNameView.setText("链：" + currentChainName);
            renderTokenLines(tokenLines);
        });
    }

    private void renderTokenLines(List<String> tokenLines) {
        if (tokenListContainer == null) return;
        tokenListContainer.removeAllViews();
        if (tokenLines == null || tokenLines.isEmpty()) {
            tokenListContainer.addView(Web3Ui.text(getActivity(), "暂无 Token，点击“添加代币”开始", 15, Web3Ui.palette().secondaryText, false), Web3Ui.matchWrap());
            return;
        }
        for (String line : tokenLines) tokenListContainer.addView(createTokenRow(line), Web3Ui.topMargin(getActivity(), 8));
    }

    private View createTokenRow(String line) {
        Web3Ui.Palette p = Web3Ui.palette();
        String symbol = "TOKEN", amount = "--", sub = "";
        if (!TextUtils.isEmpty(line)) {
            String[] parts = line.split(":", 2);
            if (parts.length == 2) {
                symbol = parts[0].trim();
                String rest = parts[1].trim();
                int contractStart = rest.indexOf('('), contractEnd = rest.indexOf(')');
                if (contractStart >= 0 && contractEnd > contractStart) {
                    amount = rest.substring(0, contractStart).trim();
                    sub = rest.substring(contractStart + 1, contractEnd).trim();
                } else {
                    amount = rest;
                    sub = "BNB".equalsIgnoreCase(symbol) ? currentChainName : "";
                }
            } else symbol = line;
        }
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(10), dp(10));
        row.setBackground(Web3Ui.roundedStroke(getActivity(), p.softCardBg, p.border, 14, 1));
        row.addView(Web3Ui.tokenBadge(getActivity(), symbol, 42), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(12);
        row.addView(info, infoLp);
        info.addView(Web3Ui.text(getActivity(), symbol, 17, p.primaryText, true), Web3Ui.matchWrap());
        info.addView(Web3Ui.text(getActivity(), sub, 13, p.secondaryText, false), Web3Ui.matchWrap());
        LinearLayout right = new LinearLayout(getActivity());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView amountView = Web3Ui.text(getActivity(), amount, 17, p.primaryText, true);
        amountView.setGravity(Gravity.RIGHT);
        right.addView(amountView);
        TextView usd = Web3Ui.text(getActivity(), "≈ $--", 12, p.mutedText, false);
        usd.setGravity(Gravity.RIGHT);
        right.addView(usd);
        row.addView(right);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronLp.leftMargin = dp(8);
        row.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, p.mutedText), chevronLp);
        return row;
    }

    private void applyTotalAsset(String totalAsset) {
        Web3Ui.Palette p = Web3Ui.palette();
        String value = TextUtils.isEmpty(totalAsset) ? "--" : totalAsset;
        SpannableString span = new SpannableString(value);
        int bnbIndex = value.indexOf("BNB");
        if (bnbIndex >= 0) {
            span.setSpan(new ForegroundColorSpan(p.orange), bnbIndex, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new StyleSpan(Typeface.BOLD), bnbIndex, value.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        totalAssetView.setText(span);
    }

    private LinearLayout createSecondaryAction(String text, int icon, View.OnClickListener listener) {
        LinearLayout button = Web3Ui.actionButton(getActivity(), text, icon, false);
        button.setOnClickListener(listener);
        return button;
    }

    private void openAddTokenPage() {
        Intent intent = new Intent(getActivity(), TokenListPageActivity.class);
        intent.putExtra(TokenListPageActivity.EXTRA_SHOW_RECORDS, false);
        intent.putExtra(TokenListPageActivity.EXTRA_AUTO_OPEN_ADD, true);
        startActivity(intent);
    }

    private void copyAddress() {
        if (getActivity() == null || TextUtils.isEmpty(currentAddress)) return;
        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("wallet", currentAddress));
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("地址已复制");
        }
    }

    private WalletWorkflowCoordinator coordinator() { return ((WalletWorkflowCoordinator.Host) getActivity()).coordinator(); }
    private LinearLayout.LayoutParams weightLp(int leftDp, int rightDp) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f); lp.setMargins(dp(leftDp), 0, dp(rightDp), 0); return lp; }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
