package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
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
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        content.setPadding(p, p, p, p);
        scroll.addView(content);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setTextSize(22f);
        title.setText("Web3 Wallet (BNB Chain)");
        content.addView(title);

        selectedWalletView = new TextView(this);
        selectedWalletView.setPadding(0, dp(8), 0, 0);
        content.addView(selectedWalletView);

        nativeBalanceView = new TextView(this);
        nativeBalanceView.setPadding(0, dp(4), 0, 0);
        content.addView(nativeBalanceView);

        favoriteTokensView = new TextView(this);
        favoriteTokensView.setPadding(0, dp(10), 0, 0);
        content.addView(favoriteTokensView);

        addButton("创建钱包", v -> showCreateWalletDialog());
        addButton("导入钱包", v -> showImportWalletDialog());
        addButton("切换钱包", v -> showSwitchWalletDialog());
        addButton("添加自定义代币", v -> showAddTokenDialog());
        addButton("刷新余额", v -> refreshBalances());
        addButton("关闭", v -> finish());

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
            nativeBalanceView.setText("BNB 余额：- ");
        } else {
            selectedWalletView.setText("当前钱包：" + shortAddress(selected));
        }

        List<TokenAsset> favorites = WalletStorage.getFavoriteTokens(this);
        if (favorites.isEmpty()) {
            favoriteTokensView.setText("Favorite 代币：暂无");
        } else {
            StringBuilder sb = new StringBuilder("Favorite 代币：\n");
            for (TokenAsset token : favorites) {
                sb.append("• ").append(token.symbol)
                        .append("  ").append(shortAddress(token.contractAddress))
                        .append("\n");
            }
            favoriteTokensView.setText(sb.toString().trim());
        }
        refreshBalances();
    }

    private void refreshBalances() {
        String selected = WalletStorage.getSelectedAddress(this);
        if (TextUtils.isEmpty(selected)) {
            nativeBalanceView.setText("BNB 余额：- ");
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
                runOnUiThread(() -> nativeBalanceView.setText("BNB 余额：" + bnb.toPlainString() + tokenBalances));
            } catch (Throwable t) {
                runOnUiThread(() -> nativeBalanceView.setText("BNB 余额查询失败：" + t.getMessage()));
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
                .setTitle("添加自定义代币（默认加入 Favorite）")
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

    private void addButton(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = dp(10);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(b, lp);
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
