package org.telegram.wallet.ui;

import android.app.AlertDialog;
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

import org.telegram.wallet.data.WalletStorage;
import org.telegram.ui.ActionBar.Theme;

public class WalletBackupFragment extends Fragment implements WalletRefreshable {

    private TextView selectedWalletView;

    public static WalletBackupFragment newInstance() {
        return new WalletBackupFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        LinearLayout card = createCard();
        root.addView(card, matchWrap());

        TextView title = createText(17, true);
        title.setText("安全中心");
        card.addView(title, matchWrap());

        TextView hint = createText(14, false);
        hint.setPadding(0, dp(8), 0, 0);
        hint.setText("建议：定期离线备份私钥，不要截图，不要上传网盘。");
        card.addView(hint, matchWrap());

        selectedWalletView = createText(14, false);
        selectedWalletView.setPadding(0, dp(12), 0, 0);
        card.addView(selectedWalletView, matchWrap());

        Button backupButton = new Button(getActivity());
        backupButton.setText("查看当前钱包私钥");
        backupButton.setTypeface(Typeface.DEFAULT_BOLD);
        backupButton.setTextColor(c(String.valueOf(Theme.key_featuredStickers_buttonText)));
        backupButton.setBackground(primaryBg());
        backupButton.setOnClickListener(v -> showPrivateKey());

        LinearLayout.LayoutParams btnLp = matchWrap();
        btnLp.topMargin = dp(16);
        card.addView(backupButton, btnLp);

        refresh();
        return root;
    }

    private void showPrivateKey() {
        String key = WalletStorage.getSelectedPrivateKey(getActivity());
        if (key == null) {
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先创建或导入钱包");
            return;
        }
        new AlertDialog.Builder(getActivity())
                .setTitle("私钥（请妥善保管）")
                .setMessage(key)
                .setPositiveButton("我知道了", null)
                .show();
    }

    @Override
    public void refresh() {
        String selected = WalletStorage.getSelectedAddress(getActivity());
        selectedWalletView.setText(selected == null
                ? "当前钱包：未创建"
                : "当前钱包：" + WalletWorkflowCoordinator.shortAddress(selected));
    }

    private GradientDrawable primaryBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_featuredStickers_addButton)));
        bg.setCornerRadius(dp(12));
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
