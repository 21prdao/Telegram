package org.telegram.wallet.ui;

import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

public class WalletHomeFragment extends Fragment implements WalletRefreshable {
    private TextView totalAssetView;
    private TextView walletAddressView;
    private TextView chainNameView;
    private LinearLayout tokenListContainer;
    private String currentAddress;
    private String currentChainName = "BNB Smart Chain";
    private final Handler tokenPriceRefreshHandler = new Handler(Looper.getMainLooper());
    private volatile boolean loadingBalances;
    private final Runnable tokenPriceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (isAdded() && getActivity() != null && tokenListContainer != null) {
                refresh();
                scheduleTokenPriceRefresh();
            }
        }
    };

    public static WalletHomeFragment newInstance() { return new WalletHomeFragment(); }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(14));
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

    @Override
    public void onResume() {
        super.onResume();
        if (tokenListContainer != null) {
            refresh();
            scheduleTokenPriceRefresh();
        }
    }

    @Override
    public void onPause() {
        stopTokenPriceRefresh();
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        stopTokenPriceRefresh();
        super.onDestroyView();
    }

    private void scheduleTokenPriceRefresh() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        tokenPriceRefreshHandler.postDelayed(tokenPriceRefreshRunnable, WalletUiRefreshPolicy.TOKEN_PRICE_REFRESH_INTERVAL_MS);
    }

    private void stopTokenPriceRefresh() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
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
        if (loadingBalances) return;
        loadingBalances = true;
        try {
            coordinator().loadBalances((selectedAddress, totalAsset, chainName, tokenLines) -> {
                loadingBalances = false;
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
        } catch (Throwable ignore) {
            loadingBalances = false;
        }
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

    private static final class TokenDisplayData {
        String symbol = "TOKEN";
        String amount = "--";
        String usdValue = "--";
        String priceUsd = "";
        String subtitle = "";
        String iconUrl = "";
        String contractAddress = "";
        int decimals = 18;
    }

    private View createTokenRow(String line) {
        Web3Ui.Palette p = Web3Ui.palette();
        TokenDisplayData token = parseTokenLine(line);
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackground(Web3Ui.rounded(getActivity(), p.softCardBg, 12));
        row.addView(Web3Ui.tokenBadge(getActivity(), token.symbol, token.iconUrl, 36), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(10);
        row.addView(info, infoLp);
        info.addView(Web3Ui.text(getActivity(), token.symbol, 15, p.primaryText, true), Web3Ui.matchWrap());
        TextView subtitleView = Web3Ui.text(getActivity(), WalletUiFormat.formatUsdPrice(token.priceUsd), 12, p.secondaryText, false);
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(subtitleView, Web3Ui.matchWrap());

        LinearLayout right = new LinearLayout(getActivity());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        String prettyAmount = WalletUiFormat.formatTokenAmount(token.amount);
        TextView amountView = Web3Ui.text(getActivity(), prettyAmount, 15, p.primaryText, true);
        amountView.setGravity(Gravity.RIGHT);
        amountView.setSingleLine(true);
        amountView.setEllipsize(TextUtils.TruncateAt.END);
        amountView.setMaxWidth(dp(184));
        right.addView(amountView);
        TextView usd = Web3Ui.text(getActivity(), WalletUiFormat.formatUsdValue(token.usdValue, token.amount), 11, p.mutedText, false);
        usd.setGravity(Gravity.RIGHT);
        usd.setSingleLine(true);
        usd.setEllipsize(TextUtils.TruncateAt.END);
        usd.setMaxWidth(dp(184));
        right.addView(usd);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rightLp.leftMargin = dp(8);
        row.addView(right, rightLp);
        row.setOnClickListener(v -> openTokenDetail(token));
        return row;
    }

    private TokenDisplayData parseTokenLine(String line) {
        TokenDisplayData token = new TokenDisplayData();
        if (TextUtils.isEmpty(line)) {
            token.subtitle = "BNB".equalsIgnoreCase(token.symbol) ? currentChainName : "";
            return token;
        }
        String raw = line.trim();
        if (raw.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(raw);
                token.symbol = firstNonEmpty(obj.optString("symbol", ""), obj.optString("tokenSymbol", ""), "TOKEN");
                token.amount = sanitizeAmount(firstNonEmpty(obj.optString("amount", ""), obj.optString("balance", ""), "0"), token.symbol);
                token.usdValue = firstNonEmpty(obj.optString("usdValue", ""), obj.optString("valueUsd", ""), obj.optString("fiatValueUsd", ""), "--");
                token.priceUsd = firstNonEmpty(obj.optString("priceUsd", ""), obj.optString("usdPrice", ""), obj.optString("price", ""), obj.optString("price_usd", ""), "");
                token.subtitle = firstNonEmpty(obj.optString("subtitle", ""), obj.optString("sub", ""), obj.optString("contract", ""), "");
                token.iconUrl = firstNonEmpty(obj.optString("iconUrl", ""), obj.optString("icon_url", ""), obj.optString("logoUrl", ""), obj.optString("logo", ""), obj.optString("imageUrl", ""), obj.optString("image", ""), "");
                token.contractAddress = firstNonEmpty(obj.optString("contractAddress", ""), obj.optString("tokenAddress", ""), obj.optString("address", ""), "");
                token.decimals = obj.optInt("decimals", obj.optInt("tokenDecimals", 18));
                if (token.decimals < 0 || token.decimals > 36) token.decimals = 18;
                if (TextUtils.isEmpty(token.subtitle) && "BNB".equalsIgnoreCase(token.symbol)) {
                    token.subtitle = currentChainName;
                }
                return token;
            } catch (Throwable ignore) {
                // 继续走旧格式解析。
            }
        }

        String[] parts = raw.split(":", 2);
        if (parts.length != 2) {
            token.symbol = raw;
            token.amount = "--";
            token.subtitle = "BNB".equalsIgnoreCase(token.symbol) ? currentChainName : "";
            return token;
        }

        token.symbol = firstNonEmpty(parts[0].trim(), "TOKEN");
        String rest = parts[1].trim();
        int pipe = rest.indexOf('|');
        String amountPart = pipe >= 0 ? rest.substring(0, pipe).trim() : rest;
        String usdPart = pipe >= 0 ? rest.substring(pipe + 1).trim() : "";
        token.amount = sanitizeAmount(amountPart, token.symbol);

        String subtitle = extractParenthetical(pipe >= 0 ? usdPart : rest);
        if (TextUtils.isEmpty(subtitle)) {
            subtitle = extractParenthetical(amountPart);
        }
        if (!TextUtils.isEmpty(subtitle)) {
            token.subtitle = subtitle;
        } else if ("BNB".equalsIgnoreCase(token.symbol)) {
            token.subtitle = currentChainName;
        }

        if (!TextUtils.isEmpty(usdPart)) {
            token.usdValue = sanitizeUsdValue(removeParenthetical(usdPart));
        }
        if (isZeroAmount(token.amount) && (TextUtils.isEmpty(token.usdValue) || "--".equals(token.usdValue))) {
            token.usdValue = "0.00";
        }
        return token;
    }

    private String sanitizeAmount(String amount, String symbol) {
        String value = amount == null ? "" : amount.trim();
        int paren = value.indexOf('(');
        if (paren >= 0) value = value.substring(0, paren).trim();
        if (!TextUtils.isEmpty(symbol) && value.toUpperCase(Locale.US).endsWith(" " + symbol.toUpperCase(Locale.US))) {
            value = value.substring(0, value.length() - symbol.length()).trim();
        }
        return TextUtils.isEmpty(value) ? "0" : value;
    }

    private String extractParenthetical(String value) {
        if (TextUtils.isEmpty(value)) return "";
        int start = value.lastIndexOf('(');
        int end = value.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return value.substring(start + 1, end).trim();
        }
        return "";
    }

    private String removeParenthetical(String value) {
        if (TextUtils.isEmpty(value)) return "";
        int start = value.lastIndexOf('(');
        int end = value.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return (value.substring(0, start) + value.substring(end + 1)).trim();
        }
        return value.trim();
    }

    private String sanitizeUsdValue(String value) {
        if (TextUtils.isEmpty(value)) return "--";
        String result = value.replace("≈", "").replace("$", "").trim();
        return TextUtils.isEmpty(result) ? "--" : result;
    }

    private boolean isZeroAmount(String amount) {
        return WalletUiFormat.isZeroAmount(amount);
    }

    private String formatTokenAmount(String amount) {
        return WalletUiFormat.formatTokenAmount(amount);
    }

    private String formatAssetAmount(String totalAsset) {
        String[] parts = totalAsset.split(" ");
        if (parts.length < 1) return totalAsset;
        String number = WalletUiFormat.formatTokenAmount(parts[0]);
        return parts.length > 1 ? number + " " + parts[1] : number;
    }

    private String formatMarketPriceDisplay(String priceUsd) {
        return WalletUiFormat.formatUsdPrice(priceUsd);
    }

    private String formatUsdDisplay(String usdValue, String amount) {
        return WalletUiFormat.formatUsdValue(usdValue, amount);
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!TextUtils.isEmpty(value)) return value;
        }
        return "";
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

    private void openTokenDetail(TokenDisplayData token) {
        if (getActivity() == null || token == null) return;
        Intent intent = TokenDetailActivity.intentFor(
                getActivity(),
                token.symbol,
                token.contractAddress,
                token.decimals,
                token.amount,
                token.priceUsd,
                token.usdValue,
                token.iconUrl
        );
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
