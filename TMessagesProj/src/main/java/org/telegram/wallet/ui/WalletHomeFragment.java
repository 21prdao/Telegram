package org.telegram.wallet.ui;

import android.app.Fragment;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;


public class WalletHomeFragment extends Fragment implements WalletRefreshable {

    private TextView totalAssetView;
    private TextView walletAddressView;
    private TextView chainNameView;
    private TextView tokenListView;
    private String currentAddress;

    public static WalletHomeFragment newInstance() {
        return new WalletHomeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(c(String.valueOf(Theme.key_windowBackgroundGray)));

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(4), dp(12), dp(20));
        scroll.addView(root);

        LinearLayout assetCard = createCard();
        root.addView(assetCard, matchWrap());

        TextView assetTitle = createBodyText(13, false, String.valueOf(Theme.key_windowBackgroundWhiteGrayText2));
        assetTitle.setText("总资产");
        assetCard.addView(assetTitle, matchWrap());

        totalAssetView = createBodyText(20, true, String.valueOf(Theme.key_windowBackgroundWhiteBlackText));
        totalAssetView.setPadding(0, dp(4), 0, 0);
        assetCard.addView(totalAssetView, matchWrap());

        walletAddressView = createBodyText(14, false, String.valueOf(Theme.key_windowBackgroundWhiteBlackText));
        walletAddressView.setPadding(0, dp(10), 0, 0);
        assetCard.addView(walletAddressView, matchWrap());

        Button copyBtn = createTextButton("复制地址");
        copyBtn.setOnClickListener(v -> copyAddress());
        assetCard.addView(copyBtn, topWrap(10));

        LinearLayout walletOps = new LinearLayout(getActivity());
        walletOps.setOrientation(LinearLayout.HORIZONTAL);
        walletOps.setPadding(0, dp(8), 0, 0);
        walletOps.addView(createQuickButton("创建", v -> coordinator().showCreateWalletDialog(this::refresh)), weightLp());
        walletOps.addView(createQuickButton("导入", v -> coordinator().showImportWalletDialog(this::refresh)), weightLp());
        walletOps.addView(createQuickButton("切换", v -> coordinator().showSwitchWalletDialog(this::refresh)), weightLp());
        assetCard.addView(walletOps, matchWrap());

        chainNameView = createBodyText(13, false, String.valueOf(Theme.key_windowBackgroundWhiteGrayText2));
        chainNameView.setPadding(0, dp(10), 0, 0);
        assetCard.addView(chainNameView, matchWrap());

        LinearLayout quickCard = createCard();
        root.addView(quickCard, topWrap(10));

        TextView quickTitle = createBodyText(15, true, String.valueOf(Theme.key_windowBackgroundWhiteBlackText));
        quickTitle.setText("快捷操作");
        quickCard.addView(quickTitle, matchWrap());

        LinearLayout actions = new LinearLayout(getActivity());
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);
        actions.setPadding(0, dp(10), 0, 0);
        quickCard.addView(actions, matchWrap());

        actions.addView(createQuickButton("发送", v -> ((WalletWorkflowCoordinator.Host) getActivity()).toast("请前往“转账”页操作")), weightLp());
        actions.addView(createQuickButton("收款", v -> showReceiveAddress()), weightLp());
        actions.addView(createQuickButton("红包", v -> ((WalletManagerActivity) getActivity()).toast("请在聊天中发起红包")), weightLp());
        actions.addView(createQuickButton("添加代币", v -> coordinator().showAddTokenDialog(this::refresh)), weightLp());

        LinearLayout tokenCard = createCard();
        root.addView(tokenCard, topWrap(10));
        TextView tokenTitle = createBodyText(15, true, String.valueOf(Theme.key_windowBackgroundWhiteBlackText));
        tokenTitle.setText("Token 列表");
        tokenCard.addView(tokenTitle, matchWrap());

        tokenListView = createBodyText(14, false, String.valueOf(Theme.key_windowBackgroundWhiteBlackText));
        tokenListView.setPadding(0, dp(8), 0, 0);
        tokenCard.addView(tokenListView, matchWrap());

        refresh();
        return scroll;
    }

    @Override
    public void refresh() {
        if (getActivity() == null) {
            return;
        }
        coordinator().loadBalances((selectedAddress, totalAsset, chainName, tokenLines) -> {
            currentAddress = selectedAddress;
            walletAddressView.setText(TextUtils.isEmpty(selectedAddress)
                    ? "钱包地址：未创建"
                    : "钱包地址：" + WalletWorkflowCoordinator.shortAddress(selectedAddress));
            totalAssetView.setText(totalAsset);
            chainNameView.setText("链：" + chainName);

            if (tokenLines == null || tokenLines.isEmpty()) {
                tokenListView.setText("暂无 Token，点击“添加代币”开始");
            } else {
                StringBuilder sb = new StringBuilder();
                for (String line : tokenLines) {
                    sb.append("• ").append(line).append("\n");
                }
                tokenListView.setText(sb.toString().trim());
            }
        });
    }

    private void copyAddress() {
        if (getActivity() == null || TextUtils.isEmpty(currentAddress)) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("wallet", currentAddress));
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("地址已复制");
        }
    }

    private void showReceiveAddress() {
        if (TextUtils.isEmpty(currentAddress)) {
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先创建或导入钱包");
            return;
        }
        new android.app.AlertDialog.Builder(getActivity())
                .setTitle("收款地址")
                .setMessage(currentAddress)
                .setPositiveButton("确定", null)
                .show();
    }

    private WalletWorkflowCoordinator coordinator() {
        return ((WalletWorkflowCoordinator.Host) getActivity()).coordinator();
    }

    private Button createQuickButton(String text, View.OnClickListener listener) {
        Button b = new Button(getActivity());
        b.setText(text);
        b.setTextSize(13f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
        b.setBackground(createButtonBackground());
        b.setOnClickListener(listener);
        b.setAllCaps(false);
        return b;
    }

    private Button createTextButton(String text) {
        Button b = new Button(getActivity());
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(c(String.valueOf(Theme.key_featuredStickers_buttonText)));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_featuredStickers_addButton)));
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        return b;
    }

    private GradientDrawable createButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(10));
        drawable.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        drawable.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return drawable;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        card.setBackground(bg);
        return card;
    }

    private TextView createBodyText(int size, boolean bold, String colorKey) {
        TextView tv = new TextView(getActivity());
        tv.setTextColor(c(colorKey));
        tv.setTextSize(size);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
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

    private LinearLayout.LayoutParams weightLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int c(String key) {
        return Theme.getColor(Integer.parseInt(key));
    }
}
