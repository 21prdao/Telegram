package org.telegram.wallet.ui;

import android.app.Fragment;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.WalletAccount;

import java.util.List;

public class WalletListPageFragment extends Fragment implements WalletRefreshable {

    private LinearLayout listContainer;

    public static WalletListPageFragment newInstance() {
        return new WalletListPageFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        TextView title = createText(18, true);
        title.setText("钱包列表");
        root.addView(title, matchWrap());

        TextView hint = createText(13, false);
        hint.setText("点击列表项即可切换钱包");
        hint.setPadding(0, dp(6), 0, dp(8));
        root.addView(hint, matchWrap());

        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, matchWrap());

        refresh();
        return root;
    }

    @Override
    public void refresh() {
        if (getActivity() == null || listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        String selected = WalletStorage.getSelectedAddress(getActivity());
        List<WalletAccount> wallets = WalletStorage.getWallets(getActivity());
        if (wallets.isEmpty()) {
            TextView empty = createText(14, false);
            empty.setText("暂无钱包，请先创建或导入");
            listContainer.addView(empty, matchWrap());
            return;
        }

        for (WalletAccount wallet : wallets) {
            TextView item = createText(14, true);
            item.setBackground(cardBg(wallet.address != null && wallet.address.equalsIgnoreCase(selected)));
            String selectedTag = wallet.address != null && wallet.address.equalsIgnoreCase(selected) ? "（当前）" : "";
            item.setText(wallet.name + " " + selectedTag + "\n" + WalletWorkflowCoordinator.shortAddress(wallet.address));
            item.setPadding(dp(12), dp(12), dp(12), dp(12));
            item.setOnClickListener(v -> {
                WalletStorage.setSelectedAddress(getActivity(), wallet.address);
                ((WalletWorkflowCoordinator.Host) getActivity()).toast("已切换到 " + wallet.name);
                refresh();
            });
            LinearLayout.LayoutParams lp = matchWrap();
            lp.topMargin = dp(8);
            listContainer.addView(item, lp);
        }
    }

    private GradientDrawable cardBg(boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(selected ? c(String.valueOf(Theme.key_featuredStickers_addButton)) : c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return bg;
    }

    private TextView createText(int size, boolean bold) {
        TextView tv = new TextView(getActivity());
        tv.setTextSize(size);
        tv.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int c(String key) {
        return Theme.getColor(Integer.parseInt(key));
    }
}
