package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.wallet.chain.Bep20Service;
import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.model.WalletAccount;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.util.List;

public class WalletManagerActivity extends Activity {

    private LinearLayout content;
    private TextView selectedWalletView;
    private TextView nativeBalanceView;
    private TextView favoriteTokensView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF4F6FA);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        content.setPadding(p, p, p, dp(20));
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1F2937);
        title.setText("Web3 Wallet");
        content.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF6B7280);
        subtitle.setPadding(0, dp(6), 0, dp(14));
        subtitle.setText("BNB Chain · Telegram 风格面板");
        content.addView(subtitle);

        LinearLayout summaryCard = createCard();
        content.addView(summaryCard, matchWrap());

        selectedWalletView = createBodyText();
        nativeBalanceView = createBodyText();
        favoriteTokensView = createBodyText();

        summaryCard.addView(selectedWalletView, matchWrap());
        summaryCard.addView(nativeBalanceView, topWrap(8));
        summaryCard.addView(favoriteTokensView, topWrap(10));

        LinearLayout actionCard = createCard();
        content.addView(actionCard, topWrap(12));

        addButton(actionCard, "创建钱包", true, v -> showCreateWalletDialog());
        addButton(actionCard, "导入钱包", false, v -> showImportWalletDialog());
        addButton(actionCard, "切换钱包", false, v -> showSwitchWalletDialog());
        addButton(actionCard, "添加自定义代币", false, v -> showAddTokenDialog());
        addButton(actionCard, "刷新余额", false, v -> refreshBalances());
        addButton(actionCard, "关闭", false, v -> finish());

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        String selected = WalletStorage.getSelectedAddress(this);
        if (TextUtils.isEmpty(selected)) {
            selectedWalletView.setText("当前钱包：未创建");
            nativeBalanceView.setText("资产总览：BNB -");
        } else {
            selectedWalletView.setText("当前钱包：" + shortAddress(selected));
        }

        List<TokenAsset> favorites = WalletStorage.getFavoriteTokens(this);
        if (favorites.isEmpty()) {
            favoriteTokensView.setText("关注代币：暂无");
        } else {
            StringBuilder sb = new StringBuilder("关注代币：\n");
            for (TokenAsset token : favorites) {
                sb.append("• ")
                        .append(token.symbol)
                        .append("  ")
                        .append(shortAddress(token.contractAddress))
                        .append("\n");
            }
            favoriteTokensView.setText(sb.toString().trim());
        }
        refreshBalances();
    }

    private void refreshBalances() {
        String selected = WalletStorage.getSelectedAddress(this);
        if (TextUtils.isEmpty(selected)) {
            nativeBalanceView.setText("资产总览：BNB -");
            return;
        }
        new Thread(() -> {
            try {
                BigDecimal bnb = Convert.fromWei(
                        new BigDecimal(BscRpcClient.get().ethGetBalance(selected, DefaultBlockParameterName.LATEST)
                                .send().getBalance()),
                        Convert.Unit.ETHER
                );

                List<TokenAsset> favorites = WalletStorage.getFavoriteTokens(this);
                StringBuilder tokenBalances = new StringBuilder();
                Bep20Service bep20Service = new Bep20Service();
                for (TokenAsset token : favorites) {
                    String bal = bep20Service.getBalance(selected, token.contractAddress, token.decimals);
                    tokenBalances.append("\n").append(token.symbol).append(": ").append(bal);
                }
                runOnUiThread(() -> nativeBalanceView.setText("资产总览：BNB " + bnb.toPlainString() + tokenBalances));
            } catch (Throwable t) {
                runOnUiThread(() -> nativeBalanceView.setText("资产查询失败：" + t.getMessage()));
            }
        }).start();
    }

    private void showCreateWalletDialog() {
        final EditText input = new EditText(this);
        input.setHint("钱包名（可选）");
        new AlertDialog.Builder(this)
                .setTitle("创建钱包")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    try {
                        WalletStorage.createWallet(this, input.getText().toString());
                        toast("创建成功，已切换到新钱包");
                        refreshUi();
                    } catch (Throwable t) {
                        toast("创建失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showImportWalletDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText key = new EditText(this);
        key.setHint("私钥（hex）");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText name = new EditText(this);
        name.setHint("钱包名（可选）");
        layout.addView(key);
        layout.addView(name);

        new AlertDialog.Builder(this)
                .setTitle("导入钱包")
                .setView(layout)
                .setPositiveButton("导入", (d, w) -> {
                    try {
                        WalletStorage.importWallet(this, key.getText().toString().trim(), name.getText().toString().trim());
                        toast("导入成功，已切换到导入钱包");
                        refreshUi();
                    } catch (Throwable t) {
                        toast("导入失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSwitchWalletDialog() {
        List<WalletAccount> wallets = WalletStorage.getWallets(this);
        if (wallets.isEmpty()) {
            toast("请先创建或导入钱包");
            return;
        }
        String[] items = new String[wallets.size()];
        for (int i = 0; i < wallets.size(); i++) {
            items[i] = wallets.get(i).name + "  " + shortAddress(wallets.get(i).address);
        }
        new AlertDialog.Builder(this)
                .setTitle("切换钱包")
                .setItems(items, (d, which) -> {
                    WalletStorage.setSelectedAddress(this, wallets.get(which).address);
                    refreshUi();
                })
                .show();
    }

    private void showAddTokenDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText symbol = new EditText(this);
        symbol.setHint("代币符号，例如 USDT");
        EditText contract = new EditText(this);
        contract.setHint("合约地址");
        EditText decimals = new EditText(this);
        decimals.setHint("Decimals，默认 18");
        decimals.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(symbol);
        layout.addView(contract);
        layout.addView(decimals);

        new AlertDialog.Builder(this)
                .setTitle("添加自定义代币（默认加入关注）")
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    int dcm = 18;
                    if (!TextUtils.isEmpty(decimals.getText())) {
                        dcm = Integer.parseInt(decimals.getText().toString());
                    }
                    WalletStorage.addOrUpdateCustomToken(
                            this,
                            symbol.getText().toString().trim(),
                            contract.getText().toString().trim(),
                            dcm,
                            true
                    );
                    refreshUi();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addButton(LinearLayout parent, String text, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFFFFFFFF : 0xFF1F2937);
        b.setBackground(createButtonBackground(primary));
        b.setOnClickListener(listener);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        lp.topMargin = dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        parent.addView(b, lp);
    }

    private GradientDrawable createButtonBackground(boolean primary) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(12));
        if (primary) {
            drawable.setColor(0xFF2A9DFF);
        } else {
            drawable.setColor(0xFFFFFFFF);
            drawable.setStroke(dp(1), 0xFFD9E2F0);
        }
        return drawable;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), 0xFFE7EDF5);
        card.setBackground(bg);
        return card;
    }

    private TextView createBodyText() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFF1F2937);
        tv.setTextSize(14f);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams topWrap(int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private String shortAddress(String a) {
        if (TextUtils.isEmpty(a) || a.length() < 10) {
            return String.valueOf(a);
        }
        return a.substring(0, 6) + "..." + a.substring(a.length() - 4);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
