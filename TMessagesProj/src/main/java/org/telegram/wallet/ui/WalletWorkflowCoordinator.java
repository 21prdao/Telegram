package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.Dialog;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

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
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(Web3Dialog.tip(activity, "创建后请立即到安全中心离线备份私钥。私钥只会加密保存在本机，不会上传服务器。"), Web3Ui.matchWrap());

        EditText input = Web3Dialog.input(activity, "例如：BNB 主钱包", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout.LayoutParams nameLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "钱包名称（可选）", input), nameLp);

        Web3Dialog.show(activity,
                "创建钱包",
                "BNB Smart Chain",
                Web3IconView.WALLET,
                content,
                "创建钱包",
                dialog -> {
                    try {
                        WalletStorage.createWallet(activity, input.getText() == null ? "" : input.getText().toString().trim());
                        host.toast("创建成功，已切换到新钱包");
                        safeRun(onDone);
                        return true;
                    } catch (Throwable t) {
                        host.toast("创建失败：" + t.getMessage());
                        return false;
                    }
                },
                "取消",
                null);
    }

    public void showImportWalletDialog(Runnable onDone) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        TextView privateKeyChip = Web3Dialog.chip(activity, "私钥", true);
        chips.addView(privateKeyChip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        TextView chainChip = Web3Dialog.chip(activity, "BNB Smart Chain", false);
        LinearLayout.LayoutParams chainChipLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chainChipLp.leftMargin = Web3Ui.dp(activity, 8);
        chips.addView(chainChip, chainChipLp);
        content.addView(chips, Web3Ui.matchWrap());

        LinearLayout.LayoutParams tipLp = Web3Ui.topMargin(activity, 12);
        content.addView(Web3Dialog.tip(activity, "请确认私钥来自可信来源。导入后资产控制权以该私钥为准，任何人获得私钥都可以转走资产。"), tipLp);

        EditText key = Web3Dialog.input(activity, "粘贴 64 位十六进制私钥，支持 0x 前缀",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                4,
                6);
        LinearLayout.LayoutParams keyLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "私钥", key), keyLp);

        EditText name = Web3Dialog.input(activity, "例如：交易钱包", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        LinearLayout.LayoutParams nameLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "钱包名称（可选）", name), nameLp);

        Web3Dialog.show(activity,
                "导入钱包",
                "私钥导入 · 本机加密保存",
                Web3IconView.IMPORT,
                content,
                "导入钱包",
                dialog -> {
                    String privateKey = normalizePrivateKeyForUi(key.getText() == null ? "" : key.getText().toString());
                    if (TextUtils.isEmpty(privateKey)) {
                        host.toast("请输入私钥");
                        return false;
                    }
                    if (!privateKey.matches("^[0-9a-fA-F]{64}$")) {
                        host.toast("私钥格式错误，请输入 64 位 hex 私钥");
                        return false;
                    }
                    try {
                        WalletStorage.importWallet(activity, privateKey, name.getText() == null ? "" : name.getText().toString().trim());
                        host.toast("导入成功，已切换到导入钱包");
                        safeRun(onDone);
                        return true;
                    } catch (Throwable t) {
                        host.toast("导入失败：" + t.getMessage());
                        return false;
                    }
                },
                "取消",
                null);
    }

    public void showSwitchWalletDialog(Runnable onDone) {
        List<WalletAccount> wallets = WalletStorage.getWallets(activity);
        if (wallets.isEmpty()) {
            host.toast("请先创建或导入钱包");
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        final Dialog[] holder = new Dialog[1];
        String selected = WalletStorage.getSelectedAddress(activity);
        for (int i = 0; i < wallets.size(); i++) {
            WalletAccount wallet = wallets.get(i);
            boolean active = selected != null && selected.equalsIgnoreCase(wallet.address);
            LinearLayout item = Web3Dialog.listItem(activity, wallet.name, shortAddress(wallet.address), active ? "当前" : "切换");
            final int index = i;
            item.setOnClickListener(v -> {
                WalletStorage.setSelectedAddress(activity, wallets.get(index).address);
                safeRun(onDone);
                if (holder[0] != null) holder[0].dismiss();
            });
            LinearLayout.LayoutParams itemLp = Web3Ui.matchWrap();
            if (i > 0) itemLp.topMargin = Web3Ui.dp(activity, 10);
            content.addView(item, itemLp);
        }
        holder[0] = Web3Dialog.show(activity,
                "切换钱包",
                "选择当前要使用的钱包",
                Web3IconView.SWITCH,
                content,
                null,
                null,
                "关闭",
                null);
    }

    public void showAddTokenDialog(Runnable onDone) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(Web3Dialog.tip(activity, "请只添加 BNB Smart Chain 上的 BEP-20 代币合约。错误合约可能导致余额显示不正确。"), Web3Ui.matchWrap());

        EditText symbol = Web3Dialog.input(activity, "例如 USDT", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout.LayoutParams symbolLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "代币符号", symbol), symbolLp);

        EditText contract = Web3Dialog.input(activity, "0x...", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams contractLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "合约地址", contract), contractLp);

        EditText decimals = Web3Dialog.input(activity, "默认 18", InputType.TYPE_CLASS_NUMBER);
        LinearLayout.LayoutParams decimalsLp = Web3Ui.topMargin(activity, 14);
        content.addView(Web3Dialog.field(activity, "Decimals", decimals), decimalsLp);

        Web3Dialog.show(activity,
                "添加自定义代币",
                "BEP-20 Token",
                Web3IconView.PLUS,
                content,
                "保存",
                dialog -> {
                    String symbolText = symbol.getText() == null ? "" : symbol.getText().toString().trim().toUpperCase();
                    String contractText = contract.getText() == null ? "" : contract.getText().toString().trim();
                    if (TextUtils.isEmpty(symbolText)) {
                        host.toast("请输入代币符号");
                        return false;
                    }
                    if (!contractText.matches("^0x[0-9a-fA-F]{40}$")) {
                        host.toast("合约地址格式错误");
                        return false;
                    }
                    int dcm = 18;
                    String decimalsText = decimals.getText() == null ? "" : decimals.getText().toString().trim();
                    if (!TextUtils.isEmpty(decimalsText)) {
                        try {
                            dcm = Integer.parseInt(decimalsText);
                        } catch (Throwable ignore) {
                            host.toast("Decimals 必须是数字");
                            return false;
                        }
                    }
                    if (dcm < 0 || dcm > 36) {
                        host.toast("Decimals 范围应为 0-36");
                        return false;
                    }
                    WalletStorage.addOrUpdateCustomToken(activity, symbolText, contractText, dcm, true);
                    safeRun(onDone);
                    host.toast("代币已添加");
                    return true;
                },
                "取消",
                null);
    }

    private String normalizePrivateKeyForUi(String value) {
        if (value == null) return "";
        String privateKey = value.trim().replaceAll("\\s+", "");
        if (privateKey.startsWith("0x") || privateKey.startsWith("0X")) {
            privateKey = privateKey.substring(2);
        }
        return privateKey;
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
