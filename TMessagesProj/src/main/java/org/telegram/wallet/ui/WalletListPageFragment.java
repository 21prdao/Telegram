package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.WalletAccount;

import java.util.List;

public class WalletListPageFragment extends Fragment implements WalletRefreshable {
    private LinearLayout listContainer;
    public static WalletListPageFragment newInstance() { return new WalletListPageFragment(); }

    @Override public android.view.View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(12), dp(10), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
//        root.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletList), 22, p.primaryText, true), Web3Ui.matchWrap());
        root.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletTapToSwitch), 14, p.secondaryText, false), Web3Ui.topMargin(getActivity(), 6));
        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), 12));
        refresh();
        return scroll;
    }

    @Override public void refresh() {
        if (getActivity() == null || listContainer == null) return;
        listContainer.removeAllViews();
        String selected = WalletStorage.getSelectedAddress(getActivity());
        List<WalletAccount> wallets = WalletStorage.getWallets(getActivity());
        if (wallets.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletNoWalletHint), 15, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(24), 0, 0);
            listContainer.addView(empty, Web3Ui.matchWrap());
            return;
        }
        for (WalletAccount wallet : wallets) {
            boolean isSelected = wallet.address != null && wallet.address.equalsIgnoreCase(selected);
            listContainer.addView(createWalletCard(wallet, isSelected), Web3Ui.topMargin(getActivity(), 8));
        }
    }

    private LinearLayout createWalletCard(WalletAccount wallet, boolean selected) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(selected ? Web3Ui.orangeGradient(getActivity(), 14) : Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);
        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(info, infoLp);
        String name = wallet.name == null ? LocaleController.getString(R.string.Web3WalletDefaultName) : wallet.name;
        info.addView(Web3Ui.text(getActivity(), name + (selected ? LocaleController.getString(R.string.Web3WalletCurrentSuffix) : ""), 18, selected ? 0xFFFFFFFF : p.primaryText, true), Web3Ui.matchWrap());
        info.addView(Web3Ui.text(getActivity(), WalletWorkflowCoordinator.shortAddress(wallet.address), 14, selected ? 0xEEFFFFFF : p.secondaryText, false), Web3Ui.matchWrap());
        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, selected ? 0xFFFFFFFF : p.mutedText), new LinearLayout.LayoutParams(dp(18), dp(18)));
        card.setOnClickListener(v -> {
            WalletStorage.setSelectedAddress(getActivity(), wallet.address);
            ((WalletWorkflowCoordinator.Host) getActivity()).toast(LocaleController.formatString(R.string.Web3WalletSwitchedTo, name));
            refresh();
        });
        return card;
    }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
