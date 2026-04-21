package org.telegram.wallet.ui;

import android.app.Fragment;
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

public class WalletHomeFragment extends Fragment implements WalletRefreshable {

    private TextView selectedWalletView;
    private TextView nativeBalanceView;
    private TextView favoriteTokensView;
    private TextView connectivityView;

    public static WalletHomeFragment newInstance() {
        return new WalletHomeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(10), dp(10), dp(20));
        scroll.addView(root);

        LinearLayout summaryCard = createCard();
        root.addView(summaryCard, matchWrap());

        selectedWalletView = createBodyText();
        nativeBalanceView = createBodyText();
        favoriteTokensView = createBodyText();

        summaryCard.addView(selectedWalletView, matchWrap());
        summaryCard.addView(nativeBalanceView, topWrap(8));
        summaryCard.addView(favoriteTokensView, topWrap(10));

        LinearLayout statusCard = createCard();
        root.addView(statusCard, topWrap(12));
        TextView statusTitle = createBodyText();
        statusTitle.setTypeface(Typeface.DEFAULT_BOLD);
        statusTitle.setText("环境连通状态");
        statusCard.addView(statusTitle, matchWrap());
        connectivityView = createBodyText();
        connectivityView.setPadding(0, dp(8), 0, 0);
        statusCard.addView(connectivityView, matchWrap());

        LinearLayout actionCard = createCard();
        root.addView(actionCard, topWrap(12));
        addButton(actionCard, "创建钱包", true, v -> coordinator().showCreateWalletDialog(this::refresh));
        addButton(actionCard, "导入钱包", false, v -> coordinator().showImportWalletDialog(this::refresh));
        addButton(actionCard, "切换钱包", false, v -> coordinator().showSwitchWalletDialog(this::refresh));
        addButton(actionCard, "添加自定义代币", false, v -> coordinator().showAddTokenDialog(this::refresh));
        addButton(actionCard, "刷新余额", false, v -> refresh());

        refresh();
        return scroll;
    }

    @Override
    public void refresh() {
        if (getActivity() == null) {
            return;
        }
        coordinator().loadBalances((selectedAddress, summary, favorites) -> {
            selectedWalletView.setText(TextUtils.isEmpty(selectedAddress)
                    ? "当前钱包：未创建"
                    : "当前钱包：" + WalletWorkflowCoordinator.shortAddress(selectedAddress));
            nativeBalanceView.setText(summary);
            favoriteTokensView.setText(favorites);
        });
        coordinator().checkConnectivity(status -> connectivityView.setText(status));
    }

    private WalletWorkflowCoordinator coordinator() {
        return ((WalletWorkflowCoordinator.Host) getActivity()).coordinator();
    }

    private void addButton(LinearLayout parent, String text, boolean primary, View.OnClickListener listener) {
        Button b = new Button(getActivity());
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
            drawable.setColor(0xFF229ED9);
        } else {
            drawable.setColor(0xFFFFFFFF);
            drawable.setStroke(dp(1), 0xFFD9E2F0);
        }
        return drawable;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(getActivity());
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
        TextView tv = new TextView(getActivity());
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
}
