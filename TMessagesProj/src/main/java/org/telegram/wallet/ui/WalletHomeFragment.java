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

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.wallet.data.WalletStorage;

import java.util.List;
import java.math.BigDecimal;
import java.math.RoundingMode;

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
        root.setPadding(dp(14), dp(8), dp(14), dp(14));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout assetCard = Web3Ui.card(getActivity());
        root.addView(assetCard, Web3Ui.matchWrap());

        assetCard.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletTotalAssets), 14, p.secondaryText, false), Web3Ui.matchWrap());

        LinearLayout heroRow = new LinearLayout(getActivity());
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(Gravity.CENTER_VERTICAL);
        assetCard.addView(heroRow, Web3Ui.topMargin(getActivity(), 4));
        totalAssetView = Web3Ui.text(getActivity(), "--", 21, p.primaryText, true);
        totalAssetView.setSingleLine(true);
        totalAssetView.setIncludeFontPadding(false);
        heroRow.addView(totalAssetView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        FrameLayout chainIcon = Web3Ui.iconCircle(getActivity(), Web3IconView.CUBE, p.orange, p.dark ? 0x20F08C22 : 0xFFFFF2DF, 44);
        heroRow.addView(chainIcon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout addressRow = new LinearLayout(getActivity());
        addressRow.setOrientation(LinearLayout.HORIZONTAL);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        walletAddressView = Web3Ui.text(getActivity(), LocaleController.formatString(R.string.Web3WalletAddressLabel, LocaleController.getString(R.string.Web3WalletAddressNotCreated)), 14, p.secondaryText, false);
        addressRow.addView(walletAddressView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        addressRow.addView(new Web3IconView(getActivity(), Web3IconView.COPY, p.mutedText), new LinearLayout.LayoutParams(dp(20), dp(20)));
        addressRow.setOnClickListener(v -> copyAddress());
        assetCard.addView(addressRow, Web3Ui.topMargin(getActivity(), 8));

        LinearLayout copyBtn = Web3Ui.actionButton(getActivity(), LocaleController.getString(R.string.Web3WalletCopyAddress), Web3IconView.COPY, true);
        copyBtn.setOnClickListener(v -> copyAddress());
        assetCard.addView(copyBtn, Web3Ui.topMargin(getActivity(), 12));

        LinearLayout walletOps = new LinearLayout(getActivity());
        walletOps.setOrientation(LinearLayout.HORIZONTAL);
        walletOps.setGravity(Gravity.CENTER);
        walletOps.addView(createSecondaryAction(LocaleController.getString(R.string.Web3WalletCreate), 0, v -> coordinator().showCreateWalletDialog(this::refresh)), weightLp(0, 4));
        walletOps.addView(createSecondaryAction(LocaleController.getString(R.string.Web3WalletImport), 0, v -> coordinator().showImportWalletDialog(this::refresh)), weightLp(4, 4));
        walletOps.addView(createSecondaryAction(LocaleController.getString(R.string.Web3WalletSwitch), 0, v -> startActivity(new Intent(getActivity(), WalletListPageActivity.class))), weightLp(4, 0));
        assetCard.addView(walletOps, Web3Ui.topMargin(getActivity(), 10));

        LinearLayout chainRow = new LinearLayout(getActivity());
        chainRow.setGravity(Gravity.CENTER_VERTICAL);
        chainRow.setOrientation(LinearLayout.HORIZONTAL);
        chainRow.addView(new Web3IconView(getActivity(), Web3IconView.CUBE, p.mutedText), new LinearLayout.LayoutParams(dp(20), dp(20)));
        chainNameView = Web3Ui.text(getActivity(), LocaleController.formatString(R.string.Web3WalletChainLabel, "BNB Smart Chain"), 13, p.mutedText, false);
        LinearLayout.LayoutParams chainLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chainLp.leftMargin = dp(8);
        chainRow.addView(chainNameView, chainLp);
        chainRow.setOnClickListener(v -> startActivity(new Intent(getActivity(), WalletListPageActivity.class).putExtra(WalletListPageActivity.EXTRA_PAGE, WalletListPageActivity.PAGE_RPC_NODES)));
        assetCard.addView(chainRow, Web3Ui.topMargin(getActivity(), 10));

        LinearLayout tokenCard = Web3Ui.card(getActivity());
        root.addView(tokenCard, Web3Ui.topMargin(getActivity(), 10));
        LinearLayout tokenHeader = new LinearLayout(getActivity());
        tokenHeader.setOrientation(LinearLayout.HORIZONTAL);
        tokenHeader.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout tokenHeaderIcon = Web3Ui.iconCircleDrawable(getActivity(), R.drawable.icon_wallet_5_1, p.dark ? 0x22111111 : 0x11F08C22, 36);
        tokenHeader.addView(tokenHeaderIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView tokenTitle = Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletTokenList), 21, p.primaryText, true);
        LinearLayout.LayoutParams tokenTitleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tokenTitleLp.leftMargin = dp(8);
        tokenHeader.addView(tokenTitle, tokenTitleLp);
        FrameLayout addTokenIconBtn = Web3Ui.iconCircle(getActivity(), Web3IconView.PLUS, p.orange, p.dark ? 0x182F3A4A : 0xFFEFF3F8, 30);
        addTokenIconBtn.setOnClickListener(v -> openAddTokenPage());
        tokenHeader.addView(addTokenIconBtn, new LinearLayout.LayoutParams(dp(30), dp(30)));
        tokenCard.addView(tokenHeader, Web3Ui.matchWrap());
        tokenListContainer = new LinearLayout(getActivity());
        tokenListContainer.setOrientation(LinearLayout.VERTICAL);
        tokenCard.addView(tokenListContainer, Web3Ui.topMargin(getActivity(), 8));
        refresh();
        return scroll;
    }

    @Override public void refresh() {
        final android.app.Activity activity = getActivity();
        if (activity == null || !isAdded()) return;
        WalletStorage.HomeSnapshot snapshot = WalletStorage.getHomeSnapshot(activity);
        if (snapshot != null) {
            currentAddress = TextUtils.isEmpty(snapshot.selectedAddress) ? WalletStorage.getSelectedAddress(activity) : snapshot.selectedAddress;
            currentChainName = TextUtils.isEmpty(snapshot.chainName) ? "BNB Smart Chain" : snapshot.chainName;
            walletAddressView.setText(LocaleController.formatString(R.string.Web3WalletAddressLabel, TextUtils.isEmpty(currentAddress) ? LocaleController.getString(R.string.Web3WalletAddressNotCreated) : WalletWorkflowCoordinator.shortAddress(currentAddress)));
            applyTotalAsset(snapshot.totalAsset);
            chainNameView.setText(LocaleController.formatString(R.string.Web3WalletChainLabel, currentChainName));
            renderTokenLines(snapshot.tokenLines);
        }
        coordinator().loadBalances((selectedAddress, totalAsset, chainName, tokenLines) -> {
            if (!isAdded()) return;
            android.app.Activity callbackActivity = getActivity();
            if (callbackActivity == null || walletAddressView == null || totalAssetView == null || chainNameView == null || tokenListContainer == null) {
                return;
            }
            currentAddress = selectedAddress;
            currentChainName = TextUtils.isEmpty(chainName) ? "BNB Smart Chain" : chainName;
            walletAddressView.setText(LocaleController.formatString(R.string.Web3WalletAddressLabel, TextUtils.isEmpty(selectedAddress) ? LocaleController.getString(R.string.Web3WalletAddressNotCreated) : WalletWorkflowCoordinator.shortAddress(selectedAddress)));
            applyTotalAsset(totalAsset);
            chainNameView.setText(LocaleController.formatString(R.string.Web3WalletChainLabel, currentChainName));
            renderTokenLines(tokenLines);
            WalletStorage.saveHomeSnapshot(callbackActivity, selectedAddress, totalAsset, currentChainName, tokenLines);
        });
    }

    private void renderTokenLines(List<String> tokenLines) {
        if (tokenListContainer == null) return;
        tokenListContainer.removeAllViews();
        if (tokenLines == null || tokenLines.isEmpty()) {
            tokenListContainer.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletNoTokenHint), 14, Web3Ui.palette().secondaryText, false), Web3Ui.matchWrap());
            return;
        }
        for (String line : tokenLines) tokenListContainer.addView(createTokenRow(line), Web3Ui.topMargin(getActivity(), 6));
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
                String usdValue = "--";
                String amountPart = rest;
                int usdSplit = rest.indexOf("|");
                if (usdSplit >= 0) {
                    amountPart = rest.substring(0, usdSplit).trim();
                    rest = rest.substring(usdSplit + 1).trim();
                    int space = rest.indexOf("  ");
                    usdValue = (space > 0 ? rest.substring(0, space) : rest).trim();
                }
                if (contractStart >= 0 && contractEnd > contractStart) {
                    amount = amountPart;
                    sub = rest.substring(contractStart + 1, contractEnd).trim();
                } else {
                    amount = amountPart;
                    sub = "BNB".equalsIgnoreCase(symbol) ? currentChainName : "";
                }
            } else symbol = line;
        }
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(Web3Ui.rounded(getActivity(), p.softCardBg, 12));
        row.addView(Web3Ui.tokenBadge(getActivity(), symbol, 36), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(10);
        row.addView(info, infoLp);
        info.addView(Web3Ui.text(getActivity(), symbol, 15, p.primaryText, true), Web3Ui.matchWrap());
        info.addView(Web3Ui.text(getActivity(), sub, 12, p.secondaryText, false), Web3Ui.matchWrap());
        LinearLayout right = new LinearLayout(getActivity());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        String prettyAmount = formatTokenAmount(amount);
        TextView amountView = Web3Ui.text(getActivity(), prettyAmount + " " + symbol, 15, p.primaryText, true);
        amountView.setGravity(Gravity.RIGHT);
        right.addView(amountView);
        TextView usd = Web3Ui.text(getActivity(), "≈ $" + formatUsdLabel(line), 11, p.mutedText, false);
        usd.setGravity(Gravity.RIGHT);
        right.addView(usd);
        row.addView(right);
        return row;
    }

    private String formatTokenAmount(String amount) {
        try {
            BigDecimal d = new BigDecimal(amount);
            if (d.compareTo(BigDecimal.ZERO) == 0) return "0";
            if (d.abs().compareTo(new BigDecimal("1")) >= 0) return d.setScale(4, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
            return d.setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString();
        } catch (Throwable ignore) {
            return amount;
        }
    }

    private String formatAssetAmount(String totalAsset) {
        String[] parts = totalAsset.split(" ");
        if (parts.length < 1) return totalAsset;
        String number = formatTokenAmount(parts[0]);
        return parts.length > 1 ? number + " " + parts[1] : number;
    }

    private String formatUsdLabel(String line) {
        if (TextUtils.isEmpty(line)) return "--";
        int colon = line.indexOf(':');
        if (colon < 0) return "--";
        String rest = line.substring(colon + 1).trim();
        int pipe = rest.indexOf('|');
        if (pipe < 0) return "--";
        String tail = rest.substring(pipe + 1).trim();
        int contract = tail.indexOf("  (");
        return (contract > 0 ? tail.substring(0, contract) : tail).trim();
    }

    private void applyTotalAsset(String totalAsset) {
        Web3Ui.Palette p = Web3Ui.palette();
        String value = TextUtils.isEmpty(totalAsset) ? "--" : formatAssetAmount(totalAsset);
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
            ((WalletWorkflowCoordinator.Host) getActivity()).toast(LocaleController.getString(R.string.Web3WalletAddressCopied));
        }
    }

    private WalletWorkflowCoordinator coordinator() { return ((WalletWorkflowCoordinator.Host) getActivity()).coordinator(); }
    private LinearLayout.LayoutParams weightLp(int leftDp, int rightDp) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f); lp.setMargins(dp(leftDp), 0, dp(rightDp), 0); return lp; }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
