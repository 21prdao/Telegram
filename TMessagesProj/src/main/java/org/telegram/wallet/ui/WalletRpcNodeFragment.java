package org.telegram.wallet.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.config.WalletRuntimeConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** RPC node manager. Shows server-provided nodes, locally probes latency/block height, and supports custom nodes. */
public class WalletRpcNodeFragment extends Fragment implements WalletRefreshable {
    private LinearLayout listContainer;
    private TextView modeTextView;
    private TextView titleHintView;
    private volatile boolean destroyed;
    private volatile int loadSeq;

    public static WalletRpcNodeFragment newInstance() {
        return new WalletRpcNodeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        destroyed = false;
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);

        ScrollView scrollView = new ScrollView(getActivity());
        scrollView.setFillViewport(false);
        scrollView.setBackgroundColor(p.pageBg);
        LinearLayout content = new LinearLayout(getActivity());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(14), dp(12), dp(14), dp(18));
        scrollView.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        content.addView(buildSpeedCard(), Web3Ui.matchWrap());

        LinearLayout header = new LinearLayout(getActivity());
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(18), 0, 0);
        TextView section = Web3Ui.text(getActivity(), "推荐节点", 15, p.secondaryText, false);
        header.addView(section, Web3Ui.matchWrap());
        titleHintView = Web3Ui.text(getActivity(), "正在从服务器获取节点并在本机测速...", 12, p.mutedText, false);
        LinearLayout.LayoutParams hintLp = Web3Ui.matchWrap();
        hintLp.topMargin = dp(4);
        header.addView(titleHintView, hintLp);
        content.addView(header, Web3Ui.matchWrap());

        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(listContainer, Web3Ui.topMargin(getActivity(), 8));

        root.addView(scrollView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(getActivity());
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(8), dp(14), dp(14));
        bottom.setBackgroundColor(p.pageBg);
        LinearLayout addButton = Web3Ui.actionButton(getActivity(), "添加自定义节点", Web3IconView.PLUS, true);
        addButton.setOnClickListener(v -> showAddCustomNodeDialog());
        bottom.addView(addButton, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        root.addView(bottom, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        loadNodes(true);
        return root;
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        super.onDestroyView();
    }

    @Override
    public void refresh() {
        loadNodes(false);
    }

    public void forceRefreshNodes() {
        loadNodes(true);
    }

    private LinearLayout buildSpeedCard() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = Web3Ui.card(getActivity());

        LinearLayout speedRow = new LinearLayout(getActivity());
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Web3Ui.text(getActivity(), "节点速度", 17, p.primaryText, true);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        speedRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        speedRow.addView(legend("快", 0xFF009B72));
        speedRow.addView(legend("中", 0xFFEAB308));
        speedRow.addView(legend("慢", 0xFFE15249));
        card.addView(speedRow, Web3Ui.matchWrap());

        TextView desc = Web3Ui.text(getActivity(),
                "区块高度越大，代表节点数据同步更完整，其稳定性更好。在节点速度差不多的情况下，选择高度值大的节点，体验更好。",
                14, p.secondaryText, false);
        desc.setLineSpacing(dp(2), 1.0f);
        desc.setPadding(0, dp(12), 0, 0);
        card.addView(desc, Web3Ui.matchWrap());

        modeTextView = Web3Ui.text(getActivity(), "", 13, p.secondaryText, false);
        LinearLayout.LayoutParams modeLp = Web3Ui.matchWrap();
        modeLp.topMargin = dp(12);
        card.addView(modeTextView, modeLp);
        return card;
    }

    private LinearLayout legend(String label, int color) {
        LinearLayout row = new LinearLayout(getActivity());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        row.setLayoutParams(lp);
        row.addView(dot(color), new LinearLayout.LayoutParams(dp(8), dp(8)));
        TextView tv = Web3Ui.text(getActivity(), label, 13, Web3Ui.palette().primaryText, false);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.leftMargin = dp(4);
        row.addView(tv, tvLp);
        return row;
    }

    private View dot(int color) {
        View view = new View(getActivity());
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        view.setBackground(bg);
        return view;
    }

    private void loadNodes(boolean forceRefresh) {
        if (getActivity() == null) return;
        final int seq = ++loadSeq;
        renderLoading();
        new Thread(() -> {
            NodeLoadResult result = new NodeLoadResult();
            try {
                WalletRuntimeConfig.ChainConfig config = WalletRuntimeConfig.get(forceRefresh);
                result.chainId = config.chainId;
                result.autoSelect = config.autoSelectRpc;
                result.configBestRpcUrl = config.bestRpcUrl;
                result.manualRpcUrl = config.selectedRpcUrl;
                result.probes = new ArrayList<>(WalletRuntimeConfig.probeRpcEndpoints(config.rpcEndpoints, config.chainId));
                WalletRuntimeConfig.RpcProbeResult bestProbe = WalletRuntimeConfig.selectBestProbe(result.probes);
                if (result.autoSelect && bestProbe != null) {
                    WalletRuntimeConfig.rememberAutoBestRpcUrl(bestProbe.url);
                    result.selectedRpcUrl = bestProbe.url;
                    BscRpcClient.resetWeb3jOnly();
                } else {
                    result.selectedRpcUrl = result.configBestRpcUrl;
                }
            } catch (Throwable t) {
                result.error = t.getMessage();
            }
            NodeLoadResult finalResult = result;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (!destroyed && seq == loadSeq && getActivity() != null) {
                    renderNodes(finalResult);
                }
            });
        }, "wallet-rpc-node-load").start();
    }

    private void renderLoading() {
        Web3Ui.Palette p = Web3Ui.palette();
        if (titleHintView != null) titleHintView.setText("正在从服务器获取节点并在本机测速...");
        if (modeTextView != null) modeTextView.setText("当前模式：自动选择最佳节点");
        if (listContainer == null || getActivity() == null) return;
        listContainer.removeAllViews();
        TextView loading = Web3Ui.text(getActivity(), "正在检测节点，请稍候...", 15, p.secondaryText, false);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(24), 0, dp(24));
        listContainer.addView(loading, Web3Ui.matchWrap());
    }

    private void renderNodes(NodeLoadResult result) {
        if (getActivity() == null || listContainer == null) return;
        Web3Ui.Palette p = Web3Ui.palette();
        listContainer.removeAllViews();

        if (!TextUtils.isEmpty(result.error)) {
            titleHintView.setText("节点加载失败");
            TextView error = Web3Ui.text(getActivity(), "加载失败：" + result.error, 15, p.secondaryText, false);
            error.setGravity(Gravity.CENTER);
            error.setPadding(0, dp(24), 0, dp(24));
            listContainer.addView(error, Web3Ui.matchWrap());
            return;
        }

        List<WalletRuntimeConfig.RpcProbeResult> probes = result.probes == null ? new ArrayList<>() : result.probes;
        titleHintView.setText("节点列表来自服务器配置；连接时间、区块高度由当前客户端本机检测。链 ID：" + result.chainId);
        if (result.autoSelect) {
            String bestName = nodeNameForUrl(probes, result.selectedRpcUrl);
            modeTextView.setText("当前模式：自动选择最佳节点" + (TextUtils.isEmpty(bestName) ? "" : "（" + bestName + "）"));
        } else {
            String selectedName = nodeNameForUrl(probes, result.selectedRpcUrl);
            modeTextView.setText("当前模式：手动选择节点" + (TextUtils.isEmpty(selectedName) ? "" : "（" + selectedName + "）"));
        }

        if (!result.autoSelect) {
            LinearLayout autoButton = Web3Ui.actionButton(getActivity(), "恢复自动选择最佳节点", Web3IconView.LIGHTNING, false);
            autoButton.setOnClickListener(v -> {
                BscRpcClient.useAutoSelectBestRpc();
                Toast.makeText(getActivity(), "已恢复自动选择最佳节点", Toast.LENGTH_SHORT).show();
                loadNodes(true);
            });
            listContainer.addView(autoButton, Web3Ui.topMargin(getActivity(), 0));
        }

        if (probes.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), "暂无可用节点，请在后台配置 RPC URL，或添加自定义节点。", 15, p.secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, dp(24));
            listContainer.addView(empty, Web3Ui.topMargin(getActivity(), 8));
            return;
        }

        for (WalletRuntimeConfig.RpcProbeResult probe : probes) {
            boolean selected = !TextUtils.isEmpty(result.selectedRpcUrl) && result.selectedRpcUrl.equalsIgnoreCase(probe.url);
            LinearLayout row = createNodeRow(probe, selected);
            row.setOnClickListener(v -> onSelectProbe(probe));
            row.setOnLongClickListener(v -> {
                if (probe.custom) {
                    showDeleteCustomNodeDialog(probe);
                    return true;
                }
                return false;
            });
            listContainer.addView(row, Web3Ui.topMargin(getActivity(), 8));
        }
    }

    private LinearLayout createNodeRow(WalletRuntimeConfig.RpcProbeResult probe, boolean selected) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(13), dp(12), dp(13));
        row.setBackground(Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(row, 0);

        LinearLayout left = new LinearLayout(getActivity());
        left.setOrientation(LinearLayout.VERTICAL);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameRow = new LinearLayout(getActivity());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = Web3Ui.text(getActivity(), displayNodeName(probe), 16, p.primaryText, false);
        name.setSingleLine(true);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (probe.custom) {
            TextView tag = badge("自定义", p.orange, p.dark ? 0x24F08C22 : 0xFFFFF2DF);
            nameRow.addView(tag);
        }
        left.addView(nameRow, Web3Ui.matchWrap());

        TextView url = Web3Ui.text(getActivity(), probe.url, 12, p.mutedText, false);
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams urlLp = Web3Ui.matchWrap();
        urlLp.topMargin = dp(4);
        left.addView(url, urlLp);

        LinearLayout right = new LinearLayout(getActivity());
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT);
        rightLp.leftMargin = dp(8);
        row.addView(right, rightLp);

        LinearLayout speedRow = new LinearLayout(getActivity());
        speedRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        TextView latency = Web3Ui.text(getActivity(), latencyText(probe), 15, p.primaryText, false);
        latency.setGravity(Gravity.RIGHT);
        speedRow.addView(latency);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.leftMargin = dp(8);
        speedRow.addView(dot(speedColor(probe)), dotLp);
        right.addView(speedRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView block = Web3Ui.text(getActivity(), secondaryText(probe), 12, p.mutedText, false);
        block.setGravity(Gravity.RIGHT);
        block.setSingleLine(true);
        block.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams blockLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blockLp.topMargin = dp(4);
        right.addView(block, blockLp);

        TextView check = Web3Ui.text(getActivity(), selected ? "✓" : "", 21, p.orange, true);
        check.setGravity(Gravity.CENTER);
        row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(40)));
        return row;
    }

    private TextView badge(String text, int color, int bgColor) {
        TextView tv = Web3Ui.text(getActivity(), text, 11, color, false);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(7), dp(3), dp(7), dp(3));
        tv.setBackground(Web3Ui.rounded(getActivity(), bgColor, 8));
        return tv;
    }

    private void onSelectProbe(WalletRuntimeConfig.RpcProbeResult probe) {
        if (probe == null || TextUtils.isEmpty(probe.url) || getActivity() == null) return;
        if (!probe.ok) {
            Toast.makeText(getActivity(), "该节点当前不可用，请刷新后再试", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            BscRpcClient.selectRpcUrl(probe.url);
            Toast.makeText(getActivity(), "已切换到节点：" + displayNodeName(probe), Toast.LENGTH_SHORT).show();
            loadNodes(false);
        } catch (Throwable t) {
            Toast.makeText(getActivity(), "切换失败：" + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showAddCustomNodeDialog() {
        if (getActivity() == null) return;
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        layout.setPadding(pad, dp(8), pad, 0);

        TextView warning = Web3Ui.text(getActivity(),
                "安全提醒：\n请添加安全可信任的节点，连接恶意节点可能导致资产风险。",
                14,
                p.orange,
                false);
        warning.setLineSpacing(dp(2), 1.0f);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        warning.setBackground(Web3Ui.roundedStroke(getActivity(), p.dark ? 0x222E261A : 0xFFFFF7EC, p.orange, 12, 1));
        layout.addView(warning, Web3Ui.matchWrap());

        EditText nameInput = new EditText(getActivity());
        nameInput.setSingleLine(true);
        nameInput.setHint("自定义-1");
        nameInput.setTextColor(p.primaryText);
        nameInput.setHintTextColor(p.mutedText);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams nameLp = Web3Ui.matchWrap();
        nameLp.topMargin = dp(14);
        layout.addView(nameInput, nameLp);

        EditText urlInput = new EditText(getActivity());
        urlInput.setHint("请输入自定义节点链接");
        urlInput.setTextColor(p.primaryText);
        urlInput.setHintTextColor(p.mutedText);
        urlInput.setSingleLine(false);
        urlInput.setMinLines(2);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        LinearLayout.LayoutParams urlLp = Web3Ui.matchWrap();
        urlLp.topMargin = dp(10);
        layout.addView(urlInput, urlLp);

        new AlertDialog.Builder(getActivity())
                .setTitle("添加自定义节点")
                .setView(layout)
                .setPositiveButton("确认添加", (dialog, which) -> {
                    try {
                        WalletRuntimeConfig.addCustomRpcEndpoint(nameInput.getText().toString(), urlInput.getText().toString());
                        Toast.makeText(getActivity(), "自定义节点已添加", Toast.LENGTH_SHORT).show();
                        loadNodes(true);
                    } catch (Throwable t) {
                        Toast.makeText(getActivity(), t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showDeleteCustomNodeDialog(WalletRuntimeConfig.RpcProbeResult probe) {
        if (getActivity() == null) return;
        new AlertDialog.Builder(getActivity())
                .setTitle("删除自定义节点")
                .setMessage(displayNodeName(probe) + "\n" + probe.url)
                .setPositiveButton("删除", (dialog, which) -> {
                    if (WalletRuntimeConfig.removeCustomRpcEndpoint(probe.url)) {
                        BscRpcClient.resetWeb3jOnly();
                        Toast.makeText(getActivity(), "已删除自定义节点", Toast.LENGTH_SHORT).show();
                        loadNodes(true);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String displayNodeName(WalletRuntimeConfig.RpcProbeResult probe) {
        if (probe == null) return "RPC 节点";
        if (!TextUtils.isEmpty(probe.name)) return probe.name;
        return probe.custom ? "自定义节点" : "BSC-Binance";
    }

    private String nodeNameForUrl(List<WalletRuntimeConfig.RpcProbeResult> probes, String url) {
        if (TextUtils.isEmpty(url) || probes == null) return "";
        for (WalletRuntimeConfig.RpcProbeResult probe : probes) {
            if (probe != null && url.equalsIgnoreCase(probe.url)) return displayNodeName(probe);
        }
        return "";
    }

    private String latencyText(WalletRuntimeConfig.RpcProbeResult probe) {
        if (probe == null || !probe.ok) return "不可用";
        if (probe.latencyMs >= Long.MAX_VALUE / 2) return "超时";
        return String.format(Locale.US, "%d ms", probe.latencyMs);
    }

    private String secondaryText(WalletRuntimeConfig.RpcProbeResult probe) {
        if (probe == null) return "";
        if (probe.ok) return "区块高度 " + probe.blockNumber;
        return TextUtils.isEmpty(probe.error) ? "连接失败" : probe.error;
    }

    private int speedColor(WalletRuntimeConfig.RpcProbeResult probe) {
        if (probe == null || !probe.ok) return 0xFFE15249;
        if (probe.latencyMs <= 300) return 0xFF009B72;
        if (probe.latencyMs <= 900) return 0xFFEAB308;
        return 0xFFE15249;
    }

    private static final class NodeLoadResult {
        long chainId;
        boolean autoSelect = true;
        String selectedRpcUrl = "";
        String configBestRpcUrl = "";
        String manualRpcUrl = "";
        String error = "";
        List<WalletRuntimeConfig.RpcProbeResult> probes = new ArrayList<>();
    }

    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
