package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;

import org.telegram.wallet.chain.Bep20Service;
import org.telegram.wallet.chain.BnbNativeTransferService;
import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.config.WalletRuntimeConfig;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.model.WalletAccount;
import org.telegram.wallet.redpacket.RedPacketRepository;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.utils.Convert;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.math.RoundingMode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;

public class WalletWorkflowCoordinator {

    public interface Host {
        WalletWorkflowCoordinator coordinator();

        void toast(String msg);
    }

    public interface BalancesCallback {
        void onResult(String selectedAddress, String totalAsset, String chainName, List<String> tokenLines);
    }

    public interface StatusCallback {
        void onStatus(String status);
    }

    private final Activity activity;
    private final Host host;

    public WalletWorkflowCoordinator(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void showCreateWalletDialog(Runnable onDone) {
        final EditText input = new EditText(activity);
        input.setHint("钱包名（可选）");
        new AlertDialog.Builder(activity)
                .setTitle("创建钱包")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    try {
                        WalletStorage.createWallet(activity, input.getText().toString());
                        host.toast("创建成功，已切换到新钱包");
                        safeRun(onDone);
                    } catch (Throwable t) {
                        host.toast("创建失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showImportWalletDialog(Runnable onDone) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText key = new EditText(activity);
        key.setHint("私钥（hex）");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        EditText name = new EditText(activity);
        name.setHint("钱包名（可选）");
        layout.addView(key);
        layout.addView(name);

        new AlertDialog.Builder(activity)
                .setTitle("导入钱包")
                .setView(layout)
                .setPositiveButton("导入", (d, w) -> {
                    try {
                        WalletStorage.importWallet(activity, key.getText().toString().trim(), name.getText().toString().trim());
                        host.toast("导入成功，已切换到导入钱包");
                        safeRun(onDone);
                    } catch (Throwable t) {
                        host.toast("导入失败：" + t.getMessage());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void showSwitchWalletDialog(Runnable onDone) {
        List<WalletAccount> wallets = WalletStorage.getWallets(activity);
        if (wallets.isEmpty()) {
            host.toast("请先创建或导入钱包");
            return;
        }
        String[] items = new String[wallets.size()];
        for (int i = 0; i < wallets.size(); i++) {
            items[i] = wallets.get(i).name + "  " + shortAddress(wallets.get(i).address);
        }
        new AlertDialog.Builder(activity)
                .setTitle("切换钱包")
                .setItems(items, (d, which) -> {
                    WalletStorage.setSelectedAddress(activity, wallets.get(which).address);
                    safeRun(onDone);
                })
                .show();
    }

    public void showAddTokenDialog(Runnable onDone) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText symbol = new EditText(activity);
        symbol.setHint("代币符号，例如 USDT");
        EditText contract = new EditText(activity);
        contract.setHint("合约地址");
        EditText decimals = new EditText(activity);
        decimals.setHint("Decimals，默认 18");
        decimals.setInputType(InputType.TYPE_CLASS_NUMBER);
        layout.addView(symbol);
        layout.addView(contract);
        layout.addView(decimals);

        new AlertDialog.Builder(activity)
                .setTitle("添加自定义代币")
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    int dcm = 18;
                    if (!TextUtils.isEmpty(decimals.getText())) {
                        dcm = Integer.parseInt(decimals.getText().toString());
                    }
                    WalletStorage.addOrUpdateCustomToken(
                            activity,
                            symbol.getText().toString().trim(),
                            contract.getText().toString().trim(),
                            dcm,
                            true
                    );
                    safeRun(onDone);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void loadBalances(BalancesCallback callback) {
        String selected = WalletStorage.getSelectedAddress(activity);
        if (TextUtils.isEmpty(selected)) {
            callback.onResult(null, "--", "BNB Smart Chain", new java.util.ArrayList<>());
            return;
        }
        List<TokenAsset> tokens = WalletStorage.getTokens(activity);

        new Thread(() -> {
            try {
                BigDecimal bnb = Convert.fromWei(
                        new BigDecimal(BscRpcClient.get().ethGetBalance(selected, DefaultBlockParameterName.LATEST)
                                .send().getBalance()),
                        Convert.Unit.ETHER
                );
                java.util.ArrayList<String> tokenLines = new java.util.ArrayList<>();
                Bep20Service bep20Service = new Bep20Service();
                List<TokenAsset> defaultTokens = new java.util.ArrayList<>();
                try {
                    defaultTokens = RedPacketRepository.getInstance().getDefaultTokens();
                } catch (Throwable ignore) {
                }
                List<TokenAsset> mergedTokens = WalletStorage.mergeTokens(defaultTokens, tokens);
                Map<String, BigDecimal> priceMap = buildConfiguredPriceMap(mergedTokens);
                try {
                    priceMap.putAll(RedPacketRepository.getInstance().getTokenPrices());
                } catch (Throwable ignore) {
                }

                BigDecimal bnbPriceUsd = resolveUsdPrice("BNB", "", priceMap, "");
                tokenLines.add(buildTokenLine(
                        "BNB",
                        bnb.toPlainString(),
                        bnbPriceUsd,
                        calculateUsdValue(bnb, bnbPriceUsd),
                        "BNB Smart Chain"
                ));

                for (TokenAsset token : mergedTokens) {
                    String bal = bep20Service.getBalance(selected, token.contractAddress, token.decimals);
                    BigDecimal amount = safeDecimal(bal);
                    BigDecimal usdPrice = resolveUsdPrice(token.symbol, token.contractAddress, priceMap, token.priceUsd);
                    String usd = calculateUsdValue(amount, usdPrice);
                    tokenLines.add(buildTokenLine(token.symbol, bal, usdPrice, usd, shortAddress(token.contractAddress)));
                }
                activity.runOnUiThread(() -> callback.onResult(selected, bnb.toPlainString() + " BNB", "BNB Smart Chain", tokenLines));
            } catch (Throwable t) {
                activity.runOnUiThread(() -> callback.onResult(selected, "资产查询失败", "BNB Smart Chain", new java.util.ArrayList<>()));
            }
        }).start();
    }

    private Map<String, BigDecimal> buildConfiguredPriceMap(List<TokenAsset> tokens) {
        Map<String, BigDecimal> result = new HashMap<>();
        if (tokens == null) return result;
        for (TokenAsset token : tokens) {
            if (token == null) continue;
            BigDecimal price = RedPacketRepository.parsePositiveDecimal(token.priceUsd);
            if (price.compareTo(BigDecimal.ZERO) <= 0) continue;
            if (!TextUtils.isEmpty(token.symbol)) {
                result.put(RedPacketRepository.priceKeyForSymbol(token.symbol), price);
            }
            if (!TextUtils.isEmpty(token.contractAddress)) {
                result.put(RedPacketRepository.priceKeyForContract(token.contractAddress), price);
            }
        }
        return result;
    }

    private BigDecimal resolveUsdPrice(String symbol, String contractAddress, Map<String, BigDecimal> priceMap, String configuredPrice) {
        if (priceMap != null) {
            if (!TextUtils.isEmpty(contractAddress)) {
                BigDecimal byContract = priceMap.get(RedPacketRepository.priceKeyForContract(contractAddress));
                if (byContract != null && byContract.compareTo(BigDecimal.ZERO) > 0) return byContract;
            }
            if (!TextUtils.isEmpty(symbol)) {
                BigDecimal bySymbol = priceMap.get(RedPacketRepository.priceKeyForSymbol(symbol));
                if (bySymbol != null && bySymbol.compareTo(BigDecimal.ZERO) > 0) return bySymbol;
            }
        }
        BigDecimal configured = RedPacketRepository.parsePositiveDecimal(configuredPrice);
        if (configured.compareTo(BigDecimal.ZERO) > 0) return configured;
        if (TextUtils.isEmpty(symbol)) return BigDecimal.ZERO;
        String upper = symbol.trim().toUpperCase();
        if ("USDT".equals(upper) || "USDC".equals(upper) || "BUSD".equals(upper) || "USD".equals(upper)) {
            return BigDecimal.ONE;
        }
        if ("BNB".equals(upper)) {
            return getUsdPrice("BNB");
        }
        return BigDecimal.ZERO;
    }

    private String buildTokenLine(String symbol, String amount, BigDecimal priceUsd, String usdValue, String subtitle) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("symbol", TextUtils.isEmpty(symbol) ? "TOKEN" : symbol.trim());
            obj.put("amount", TextUtils.isEmpty(amount) ? "0" : amount.trim());
            obj.put("priceUsd", priceUsd != null && priceUsd.compareTo(BigDecimal.ZERO) > 0 ? priceUsd.stripTrailingZeros().toPlainString() : "");
            obj.put("usdValue", TextUtils.isEmpty(usdValue) ? "--" : usdValue);
            obj.put("subtitle", TextUtils.isEmpty(subtitle) ? "" : subtitle);
            return obj.toString();
        } catch (Throwable ignore) {
            String safeSymbol = TextUtils.isEmpty(symbol) ? "TOKEN" : symbol.trim();
            String safeAmount = TextUtils.isEmpty(amount) ? "0" : amount.trim();
            String safeUsd = TextUtils.isEmpty(usdValue) ? "--" : usdValue;
            String safeSubtitle = TextUtils.isEmpty(subtitle) ? "" : "  (" + subtitle + ")";
            return safeSymbol + ": " + safeAmount + "|" + safeUsd + safeSubtitle;
        }
    }

    private String calculateUsdValue(BigDecimal amount, BigDecimal priceUsd) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) return "0.00";
        if (priceUsd == null || priceUsd.compareTo(BigDecimal.ZERO) <= 0) return "--";
        return formatUsdValue(amount.multiply(priceUsd));
    }

    private BigDecimal safeDecimal(String value) {
        try {
            if (TextUtils.isEmpty(value)) return BigDecimal.ZERO;
            return new BigDecimal(value.trim().replace(",", ""));
        } catch (Throwable ignore) {
            return BigDecimal.ZERO;
        }
    }

    private String formatUsdValue(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) return "0.00";
        if (value.compareTo(new BigDecimal("0.01")) < 0) return "<0.01";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private BigDecimal getUsdPrice(String symbol) {
        if (TextUtils.isEmpty(symbol)) return BigDecimal.ZERO;
        String upper = symbol.toUpperCase();
        if ("USDT".equals(upper) || "USDC".equals(upper) || "BUSD".equals(upper) || "USD".equals(upper)) return BigDecimal.ONE;
        if (!"BNB".equals(upper)) return BigDecimal.ZERO;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.binance.com/api/v3/ticker/price?symbol=BNBUSDT");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return BigDecimal.ZERO;
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            JSONObject obj = new JSONObject(sb.toString());
            return RedPacketRepository.parsePositiveDecimal(obj.optString("price", "0"));
        } catch (Throwable ignore) {
            return BigDecimal.ZERO;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void checkConnectivity(StatusCallback callback) {
        new Thread(() -> {
            String status;
            try {
                WalletRuntimeConfig.ChainConfig config = WalletRuntimeConfig.get();
                String chain = BscRpcClient.get().ethChainId().send().getChainId().toString();
                status = "API: " + WalletConfig.getRedPacketApiBaseUrl() + "\n"
                        + "RPC: " + BscRpcClient.getCurrentRpcUrl() + "\n"
                        + "RPC chainId=" + chain + " / config chainId=" + config.chainId + "\n"
                        + "Contract: " + config.redPacketContract;
            } catch (Throwable t) {
                status = "连接检查失败：" + t.getMessage();
            }
            String finalStatus = status;
            activity.runOnUiThread(() -> callback.onStatus(finalStatus));
        }).start();
    }

    public void sendNativeTransfer(String to, String amount, Runnable onDone) {
        String privateKeyHex = WalletStorage.getSelectedPrivateKey(activity);
        if (TextUtils.isEmpty(privateKeyHex)) {
            host.toast("请先创建或导入钱包");
            return;
        }
        new Thread(() -> {
            try {
                String txHash = new BnbNativeTransferService().send(privateKeyHex, to, new BigDecimal(amount));
                activity.runOnUiThread(() -> {
                    host.toast("转账已提交：" + txHash);
                    safeRun(onDone);
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> host.toast("转账失败：" + t.getMessage()));
            }
        }).start();
    }

    private void safeRun(Runnable runnable) {
        if (runnable != null) {
            runnable.run();
        }
    }

    public static String shortAddress(String address) {
        if (TextUtils.isEmpty(address) || address.length() < 10) {
            return String.valueOf(address);
        }
        return address.substring(0, 6) + "..." + address.substring(address.length() - 4);
    }
}
