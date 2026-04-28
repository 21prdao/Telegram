package org.telegram.wallet.ui;

import android.app.Fragment;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;

public class WalletManageFragment extends Fragment implements WalletRefreshable {

    public static WalletManageFragment newInstance() {
        return new WalletManageFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        LinearLayout card = createCard();
        root.addView(card, matchWrap());

        TextView title = createText(17, true);
        title.setText("钱包管理");
        card.addView(title, matchWrap());

        TextView desc = createText(14, false);
        desc.setText("在独立页面中查看钱包列表、切换钱包和管理代币。\n并可查看我发出的红包记录。\n");
        desc.setPadding(0, dp(8), 0, 0);
        card.addView(desc, matchWrap());

        Button walletList = createPrimaryButton("钱包列表 / 切换钱包");
        walletList.setOnClickListener(v -> ((WalletManagerActivity) getActivity()).openWalletListPage());
        card.addView(walletList, topWrap(10));

        Button tokenList = createOutlineButton("代币列表");
        tokenList.setOnClickListener(v -> ((WalletManagerActivity) getActivity()).openTokenListPage());
        card.addView(tokenList, topWrap(10));

        Button redPacketRecords = createOutlineButton("我发出的红包记录");
        redPacketRecords.setOnClickListener(v -> ((WalletManagerActivity) getActivity()).openRedPacketRecordsPage());
        card.addView(redPacketRecords, topWrap(10));

        return root;
    }

    @Override
    public void refresh() {
    }

    private Button createPrimaryButton(String text) {
        Button b = new Button(getActivity());
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(c(String.valueOf(Theme.key_featuredStickers_buttonText)));
        b.setBackground(primaryBg());
        return b;
    }

    private Button createOutlineButton(String text) {
        Button b = new Button(getActivity());
        b.setText(text);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
        b.setBackground(outlineBg());
        return b;
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

    private GradientDrawable primaryBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_featuredStickers_addButton)));
        bg.setCornerRadius(dp(12));
        return bg;
    }

    private GradientDrawable outlineBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return bg;
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

    private int c(String key) {
        return Theme.getColor(Integer.parseInt(key));
    }
}
