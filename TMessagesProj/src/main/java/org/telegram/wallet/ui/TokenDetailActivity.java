package org.telegram.wallet.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.wallet.chain.Bep20Service;
import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.redpacket.RedPacketRepository;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Convert;

import java.math.BigDecimal;

public class TokenDetailActivity extends Activity {
    public static final String EXTRA_SYMBOL = "extra_symbol";
    public static final String EXTRA_CONTRACT_ADDRESS = "extra_contract_address";
    public static final String EXTRA_DECIMALS = "extra_decimals";
    public static final String EXTRA_BALANCE = "extra_balance";
    public static final String EXTRA_PRICE_USD = "extra_price_usd";
    public static final String EXTRA_USD_VALUE = "extra_usd_value";
    public static final String EXTRA_ICON_URL = "extra_icon_url";

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private String symbol = "TOKEN";
    private String contractAddress = "";
    private int decimals = 18;
    private String balance = "";
    private String priceUsd = "";
    private String usdValue = "";
    private String iconUrl = "";

    private LinearLayout iconHolder;
    private TextView titleView;
    private TextView headerValueView;
    private TextView headerBalanceView;
    private TextView symbolValueView;
    private TextView addressValueView;
    private TextView priceValueView;
    private TextView balanceValueView;
    private TextView holdingValueView;
    private TextView decimalsValueView;
    private TextView networkValueView;
    private final Handler tokenPriceRefreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable tokenPriceRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshTokenDetail();
            scheduleTokenPriceRefresh();
        }
    };
    private volatile boolean refreshingTokenDetail;

    public static Intent intentFor(Context context, String symbol, String contractAddress, int decimals, String balance, String priceUsd, String usdValue, String iconUrl) {
        Intent intent = new Intent(context, TokenDetailActivity.class);
        intent.putExtra(EXTRA_SYMBOL, symbol == null ? "" : symbol);
        intent.putExtra(EXTRA_CONTRACT_ADDRESS, contractAddress == null ? "" : contractAddress);
        intent.putExtra(EXTRA_DECIMALS, decimals);
        intent.putExtra(EXTRA_BALANCE, balance == null ? "" : balance);
        intent.putExtra(EXTRA_PRICE_USD, priceUsd == null ? "" : priceUsd);
        intent.putExtra(EXTRA_USD_VALUE, usdValue == null ? "" : usdValue);
        intent.putExtra(EXTRA_ICON_URL, iconUrl == null ? "" : iconUrl);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readIntent();
        Web3Ui.applySystemBars(this);
        setContentView(buildRoot());
        renderToken();
        refreshTokenDetail();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Web3Ui.applySystemBars(this);
        scheduleTokenPriceRefresh();
    }

    @Override
    protected void onPause() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        super.onDestroy();
    }

    private void scheduleTokenPriceRefresh() {
        tokenPriceRefreshHandler.removeCallbacks(tokenPriceRefreshRunnable);
        tokenPriceRefreshHandler.postDelayed(tokenPriceRefreshRunnable, WalletUiRefreshPolicy.TOKEN_PRICE_REFRESH_INTERVAL_MS);
    }

    private void readIntent() {
        Intent intent = getIntent();
        if (intent == null) return;
        symbol = safe(intent.getStringExtra(EXTRA_SYMBOL), "TOKEN").trim();
        if (TextUtils.isEmpty(symbol)) symbol = "TOKEN";
        contractAddress = safe(intent.getStringExtra(EXTRA_CONTRACT_ADDRESS), "").trim();
        decimals = intent.getIntExtra(EXTRA_DECIMALS, 18);
        if (decimals < 0 || decimals > 36) decimals = 18;
        balance = safe(intent.getStringExtra(EXTRA_BALANCE), "").trim();
        priceUsd = safe(intent.getStringExtra(EXTRA_PRICE_USD), "").trim();
        usdValue = safe(intent.getStringExtra(EXTRA_USD_VALUE), "").trim();
        iconUrl = safe(intent.getStringExtra(EXTRA_ICON_URL), "").trim();
    }

    private LinearLayout buildRoot() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Web3Ui.appBarSidePadding(this), 0, Web3Ui.appBarSidePadding(this), 0);
        bar.setBackgroundColor(p.appBarBg);

        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));

        titleView = Web3Ui.text(this, "代币详情", 18, p.primaryText, true);
        titleView.setGravity(Gravity.CENTER);
        bar.addView(titleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView spacer = new TextView(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Web3Ui.appBarHeight(this)));
        Web3Ui.attachSystemBarInsets(this, root, bar, Web3Ui.APP_BAR_HEIGHT_DP, null, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(12), dp(10), dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        content.addView(createHeaderCard(), Web3Ui.matchWrap());
        content.addView(createInfoCard(), Web3Ui.topMargin(this, 10));
        return root;
    }

    private LinearLayout createHeaderCard() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = Web3Ui.card(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(16), dp(18), dp(16), dp(18));

        iconHolder = new LinearLayout(this);
        iconHolder.setGravity(Gravity.CENTER);
        card.addView(iconHolder, new LinearLayout.LayoutParams(dp(64), dp(64)));

        headerValueView = Web3Ui.text(this, "--", 26, p.primaryText, true);
        headerValueView.setGravity(Gravity.CENTER);
        card.addView(headerValueView, Web3Ui.topMargin(this, 12));

        headerBalanceView = Web3Ui.text(this, "--", 14, p.secondaryText, false);
        headerBalanceView.setGravity(Gravity.CENTER);
        card.addView(headerBalanceView, Web3Ui.topMargin(this, 4));
        return card;
    }

    private LinearLayout createInfoCard() {
        LinearLayout card = Web3Ui.card(this);
        card.setPadding(dp(14), dp(4), dp(14), dp(4));
        symbolValueView = addInfoRow(card, "代币符号", symbol, false);
        addressValueView = addInfoRow(card, "代币地址", addressDisplayText(), !isNativeBnb() && !TextUtils.isEmpty(contractAddress));
        priceValueView = addInfoRow(card, "代币价格", "--", false);
        balanceValueView = addInfoRow(card, "持有数量", "--", false);
        holdingValueView = addInfoRow(card, "持仓估值", "--", false);
        decimalsValueView = addInfoRow(card, "小数位", String.valueOf(decimals), false);
        networkValueView = addInfoRow(card, "网络", "BNB Smart Chain", false);
        return card;
    }

    private TextView addInfoRow(LinearLayout parent, String label, String value, boolean copyable) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView labelView = Web3Ui.text(this, label, 14, p.secondaryText, false);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = Web3Ui.text(this, value, 14, p.primaryText, true);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        valueView.setSingleLine(!copyable);
        valueView.setMaxLines(copyable ? 2 : 1);
        valueView.setEllipsize(copyable ? TextUtils.TruncateAt.MIDDLE : TextUtils.TruncateAt.END);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (copyable) {
            Web3IconView copyIcon = new Web3IconView(this, Web3IconView.COPY, p.orange);
            LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(dp(20), dp(20));
            copyLp.leftMargin = dp(8);
            row.addView(copyIcon, copyLp);
            row.setOnClickListener(v -> copyContractAddress());
            valueView.setOnClickListener(v -> copyContractAddress());
            copyIcon.setOnClickListener(v -> copyContractAddress());
        }

        parent.addView(row, Web3Ui.matchWrap());
        return valueView;
    }

    private void renderToken() {
        titleView.setText(symbol);
        if (iconHolder != null) {
            iconHolder.removeAllViews();
            iconHolder.addView(Web3Ui.tokenBadge(this, symbol, iconUrl, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        }
        boolean hasKnownBalance = !TextUtils.isEmpty(balance) && !"--".equals(balance.trim());
        String safeBalance = hasKnownBalance ? balance : "--";
        String displayUsd = TextUtils.isEmpty(usdValue) ? (hasKnownBalance ? WalletUiFormat.calculateUsdValueText(safeBalance, priceUsd) : "--") : usdValue;
        headerValueView.setText(WalletUiFormat.formatUsdValue(displayUsd, safeBalance));
        headerBalanceView.setText(WalletUiFormat.formatTokenAmount(safeBalance) + " " + symbol);
        symbolValueView.setText(symbol);
        addressValueView.setText(addressDisplayText());
        priceValueView.setText(WalletUiFormat.formatUsdPrice(priceUsd));
        balanceValueView.setText(WalletUiFormat.formatTokenAmount(safeBalance) + " " + symbol);
        holdingValueView.setText(WalletUiFormat.formatUsdValue(displayUsd, safeBalance));
        decimalsValueView.setText(String.valueOf(decimals));
        networkValueView.setText("BNB Smart Chain");
    }

    private void refreshTokenDetail() {
        if (refreshingTokenDetail) return;
        refreshingTokenDetail = true;
        new Thread(() -> {
            String nextBalance = balance;
            String nextPrice = priceUsd;
            String nextValue = usdValue;
            String nextIcon = iconUrl;

            try {
                String selected = WalletStorage.getSelectedAddress(this);
                if (!TextUtils.isEmpty(selected)) {
                    if (isNativeBnb()) {
                        BigDecimal bnb = Convert.fromWei(
                                new BigDecimal(BscRpcClient.get().ethGetBalance(selected, DefaultBlockParameterName.LATEST).send().getBalance()),
                                Convert.Unit.ETHER
                        );
                        nextBalance = bnb.toPlainString();
                    } else if (isValidContractAddress(contractAddress)) {
                        nextBalance = new Bep20Service().getBalance(selected, contractAddress, decimals);
                    }
                }
            } catch (Throwable ignore) {
            }

            try {
                RedPacketRepository.TokenMetadata metadata = isNativeBnb()
                        ? RedPacketRepository.getInstance().getTokenMetadata()
                        : RedPacketRepository.getInstance().getTokenMetadataForContract(contractAddress, symbol);
                BigDecimal price = lookupPrice(metadata);
                if (price.compareTo(BigDecimal.ZERO) > 0) {
                    nextPrice = price.stripTrailingZeros().toPlainString();
                }
                String metadataIcon = lookupIcon(metadata);
                if (!TextUtils.isEmpty(metadataIcon)) {
                    nextIcon = metadataIcon;
                }
                if (!isNativeBnb() && (!TextUtils.isEmpty(nextPrice) || !TextUtils.isEmpty(nextIcon))) {
                    WalletStorage.updateCustomTokenMetadata(this, contractAddress, nextPrice, nextIcon);
                }
            } catch (Throwable ignore) {
            }

            nextValue = TextUtils.isEmpty(nextBalance) || "--".equals(nextBalance.trim())
                    ? "--"
                    : WalletUiFormat.calculateUsdValueText(nextBalance, nextPrice);
            final String finalBalance = nextBalance;
            final String finalPrice = nextPrice;
            final String finalValue = nextValue;
            final String finalIcon = nextIcon;
            runOnUiThread(() -> {
                refreshingTokenDetail = false;
                balance = finalBalance;
                priceUsd = finalPrice;
                usdValue = finalValue;
                iconUrl = finalIcon;
                renderToken();
            });
        }, "wallet-token-detail").start();
    }

    private BigDecimal lookupPrice(RedPacketRepository.TokenMetadata metadata) {
        if (metadata == null) return BigDecimal.ZERO;
        if (!isNativeBnb() && !TextUtils.isEmpty(contractAddress)) {
            BigDecimal byContract = metadata.prices.get(RedPacketRepository.priceKeyForContract(contractAddress));
            if (byContract != null && byContract.compareTo(BigDecimal.ZERO) > 0) return byContract;
        }
        if (!TextUtils.isEmpty(symbol)) {
            BigDecimal bySymbol = metadata.prices.get(RedPacketRepository.priceKeyForSymbol(symbol));
            if (bySymbol != null && bySymbol.compareTo(BigDecimal.ZERO) > 0) return bySymbol;
        }
        return BigDecimal.ZERO;
    }

    private String lookupIcon(RedPacketRepository.TokenMetadata metadata) {
        if (metadata == null) return "";
        if (!isNativeBnb() && !TextUtils.isEmpty(contractAddress)) {
            String byContract = metadata.iconUrls.get(RedPacketRepository.priceKeyForContract(contractAddress));
            if (!TextUtils.isEmpty(byContract)) return byContract;
        }
        if (!TextUtils.isEmpty(symbol)) {
            String bySymbol = metadata.iconUrls.get(RedPacketRepository.priceKeyForSymbol(symbol));
            if (!TextUtils.isEmpty(bySymbol)) return bySymbol;
        }
        return "";
    }

    private String addressDisplayText() {
        if (isNativeBnb()) return "BNB Smart Chain 原生币";
        return TextUtils.isEmpty(contractAddress) ? "--" : contractAddress;
    }

    private void copyContractAddress() {
        if (isNativeBnb() || TextUtils.isEmpty(contractAddress)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("token_contract", contractAddress));
            Toast.makeText(this, "代币地址已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNativeBnb() {
        return "BNB".equalsIgnoreCase(symbol) || ZERO_ADDRESS.equalsIgnoreCase(contractAddress);
    }

    private boolean isValidContractAddress(String address) {
        return !TextUtils.isEmpty(address) && address.trim().matches("^0x[0-9a-fA-F]{40}$") && !ZERO_ADDRESS.equalsIgnoreCase(address.trim());
    }

    private String safe(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private int dp(int value) {
        return Web3Ui.dp(this, value);
    }
}
