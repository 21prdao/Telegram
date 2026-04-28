package org.telegram.wallet.ui;

import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class WalletManageFragment extends Fragment implements WalletRefreshable {
    public static WalletManageFragment newInstance() { return new WalletManageFragment(); }

    @Override public android.view.View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(8), dp(14), dp(14));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout card = Web3Ui.card(getActivity());
        root.addView(card, Web3Ui.matchWrap());
        LinearLayout head = new LinearLayout(getActivity());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout icon = Web3Ui.iconCircle(getActivity(), Web3IconView.WALLET, p.orange, p.dark ? 0x26F08C22 : 0xFFFFF2DF, 38);
        head.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView title = Web3Ui.text(getActivity(), "钱包管理", 20, p.primaryText, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        head.addView(title, titleLp);
        FrameLayout deco = Web3Ui.iconCircle(getActivity(), Web3IconView.MANAGE, p.orange, p.dark ? 0x1AF08C22 : 0xFFFFF2DF, 46);
        head.addView(deco, new LinearLayout.LayoutParams(dp(46), dp(46)));
        card.addView(head, Web3Ui.matchWrap());
        TextView desc = Web3Ui.text(getActivity(), "在独立页面中查看钱包列表、切换钱包和管理代币。\n并可查看我发出的红包记录。", 14, p.secondaryText, false);
        desc.setLineSpacing(dp(1), 1.0f);
        card.addView(desc, Web3Ui.topMargin(getActivity(), 14));

        LinearLayout walletList = Web3Ui.actionButton(getActivity(), "钱包列表 / 切换钱包", Web3IconView.WALLET, true);
        walletList.setOnClickListener(v -> startActivity(new Intent(getActivity(), WalletListPageActivity.class)));
        card.addView(walletList, Web3Ui.topMargin(getActivity(), 16));
        LinearLayout tokenList = Web3Ui.actionButton(getActivity(), "代币列表", Web3IconView.COINS, false);
        tokenList.setOnClickListener(v -> startTokenListPage(false, false));
        card.addView(tokenList, Web3Ui.topMargin(getActivity(), 10));
        LinearLayout redPacketRecords = Web3Ui.actionButton(getActivity(), "我发出的红包记录", Web3IconView.RED_PACKET, false);
        redPacketRecords.setOnClickListener(v -> startTokenListPage(true, false));
        card.addView(redPacketRecords, Web3Ui.topMargin(getActivity(), 10));
        return scroll;
    }

    private void startTokenListPage(boolean showRecords, boolean autoOpenAdd) {
        Intent intent = new Intent(getActivity(), TokenListPageActivity.class);
        intent.putExtra(TokenListPageActivity.EXTRA_SHOW_RECORDS, showRecords);
        intent.putExtra(TokenListPageActivity.EXTRA_AUTO_OPEN_ADD, autoOpenAdd);
        startActivity(intent);
    }
    @Override public void refresh() { }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
