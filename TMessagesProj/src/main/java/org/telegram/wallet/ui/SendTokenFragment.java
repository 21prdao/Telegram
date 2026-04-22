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
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.wallet.data.WalletStorage;
import org.telegram.ui.ActionBar.Theme;

public class SendTokenFragment extends Fragment implements WalletRefreshable {

    private TextView fromWalletView;
    private EditText toAddressEdit;
    private EditText amountEdit;

    public static SendTokenFragment newInstance() {
        return new SendTokenFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));

        LinearLayout card = createCard();
        root.addView(card, matchWrap());

        TextView title = createText(17, true);
        title.setText("转账（TokenPocket 风格：填写 → 预览 → 提交）");
        card.addView(title, matchWrap());

        fromWalletView = createText(14, false);
        fromWalletView.setPadding(0, dp(10), 0, 0);
        card.addView(fromWalletView, matchWrap());

        TextView toLabel = createText(14, true);
        toLabel.setPadding(0, dp(14), 0, dp(6));
        toLabel.setText("收款地址");
        card.addView(toLabel, matchWrap());

        toAddressEdit = new EditText(getActivity());
        toAddressEdit.setHint("0x...");
        toAddressEdit.setBackground(inputBg());
        toAddressEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(toAddressEdit, matchWrap());

        TextView amountLabel = createText(14, true);
        amountLabel.setPadding(0, dp(14), 0, dp(6));
        amountLabel.setText("BNB 数量");
        card.addView(amountLabel, matchWrap());

        amountEdit = new EditText(getActivity());
        amountEdit.setHint("例如 0.01");
        amountEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountEdit.setBackground(inputBg());
        amountEdit.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.addView(amountEdit, matchWrap());

        Button submitButton = new Button(getActivity());
        submitButton.setText("预览并提交");
        submitButton.setTypeface(Typeface.DEFAULT_BOLD);
        submitButton.setTextColor(c(String.valueOf(Theme.key_featuredStickers_buttonText)));
        submitButton.setBackground(primaryBg());
        submitButton.setOnClickListener(v -> onSubmit());

        LinearLayout.LayoutParams btnLp = matchWrap();
        btnLp.topMargin = dp(16);
        card.addView(submitButton, btnLp);

        refresh();
        return root;
    }

    private void onSubmit() {
        String to = toAddressEdit.getText().toString().trim();
        String amount = amountEdit.getText().toString().trim();
        if (TextUtils.isEmpty(to) || !to.matches("^0x[0-9a-fA-F]{40}$")) {
            host().toast("收款地址格式错误");
            return;
        }
        if (TextUtils.isEmpty(amount)) {
            host().toast("请输入 BNB 数量");
            return;
        }

        String from = WalletStorage.getSelectedAddress(getActivity());
        new AlertDialog.Builder(getActivity())
                .setTitle("确认转账")
                .setMessage("From: " + WalletWorkflowCoordinator.shortAddress(from)
                        + "\nTo: " + WalletWorkflowCoordinator.shortAddress(to)
                        + "\nAmount: " + amount + " BNB")
                .setPositiveButton("确认发送", (d, w) -> coordinator().sendNativeTransfer(to, amount, this::refresh))
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    public void refresh() {
        String selected = WalletStorage.getSelectedAddress(getActivity());
        fromWalletView.setText(TextUtils.isEmpty(selected)
                ? "当前钱包：未创建"
                : "付款钱包：" + WalletWorkflowCoordinator.shortAddress(selected));
    }

    private WalletWorkflowCoordinator.Host host() {
        return (WalletWorkflowCoordinator.Host) getActivity();
    }

    private WalletWorkflowCoordinator coordinator() {
        return host().coordinator();
    }

    private GradientDrawable inputBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return bg;
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
        tv.setGravity(Gravity.START);
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
