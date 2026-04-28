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
        root.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(Web3Ui.sectionTitle(getActivity(), Web3IconView.WALLET, "钱包列表"), Web3Ui.matchWrap());
        root.addView(Web3Ui.text(getActivity(), "点击列表项即可切换钱包", 13, p.secondaryText, false), Web3Ui.topMargin(getActivity(), 8));
        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), 20));
        refresh();
        return scroll;
    }

    @Override public void refresh() {
        if (getActivity() == null || listContainer == null) return;
        listContainer.removeAllViews();
        String selected = WalletStorage.getSelectedAddress(getActivity());
        List<WalletAccount> wallets = WalletStorage.getWallets(getActivity());
        if (wallets.isEmpty()) {
            TextView empty = Web3Ui.text(getActivity(), "暂无钱包，请先创建或导入", 14, Web3Ui.palette().secondaryText, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(34), 0, 0);
            listContainer.addView(empty, Web3Ui.matchWrap());
            return;
        }
        for (WalletAccount wallet : wallets) {
            boolean isSelected = wallet.address != null && wallet.address.equalsIgnoreCase(selected);
            listContainer.addView(createWalletCard(wallet, isSelected), Web3Ui.topMargin(getActivity(), 12));
        }
    }

    private LinearLayout createWalletCard(WalletAccount wallet, boolean selected) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout card = new LinearLayout(getActivity());
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(14), dp(16));
        card.setBackground(selected ? Web3Ui.orangeGradient(getActivity(), 20) : Web3Ui.roundedStroke(getActivity(), p.cardBg, p.border, 20, 1));
        Web3Ui.setElevation(card, selected ? 4 : 2);
        FrameLayout icon = Web3Ui.iconCircle(getActivity(), Web3IconView.WALLET, selected ? 0xFFFFFFFF : p.secondaryText, selected ? 0x22FFFFFF : (p.dark ? 0x182F3A4A : 0xFFEFF3F8), 54);
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        infoLp.leftMargin = dp(16);
        card.addView(info, infoLp);
        String name = wallet.name == null ? "钱包" : wallet.name;
        info.addView(Web3Ui.text(getActivity(), name + (selected ? "（当前）" : ""), 15, selected ? 0xFFFFFFFF : p.primaryText, true), Web3Ui.matchWrap());
        info.addView(Web3Ui.text(getActivity(), WalletWorkflowCoordinator.shortAddress(wallet.address), 13, selected ? 0xEEFFFFFF : p.secondaryText, false), Web3Ui.matchWrap());
        card.addView(new Web3IconView(getActivity(), Web3IconView.CHEVRON, selected ? 0xFFFFFFFF : p.mutedText), new LinearLayout.LayoutParams(dp(22), dp(22)));
        card.setOnClickListener(v -> {
            WalletStorage.setSelectedAddress(getActivity(), wallet.address);
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("已切换到 " + name);
            refresh();
        });
        return card;
    }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
