package org.telegram.wallet.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
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

        Button passwordButton = new Button(getActivity());
        passwordButton.setText("设置/修改支付密码");
        passwordButton.setTypeface(Typeface.DEFAULT_BOLD);
        passwordButton.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
        passwordButton.setBackground(outlineBg());
        passwordButton.setOnClickListener(v -> showSetPaymentPasswordDialog());
        LinearLayout.LayoutParams pwdLp = matchWrap();
        pwdLp.topMargin = dp(10);
        card.addView(passwordButton, pwdLp);

        refresh();
        return root;
    }

    private void showPrivateKey() {
        String key = WalletStorage.getSelectedPrivateKey(getActivity());
        if (key == null) {
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先创建或导入钱包");
            return;
        }
        if (!WalletStorage.hasPaymentPassword(getActivity())) {
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先设置支付密码");
            showSetPaymentPasswordDialog(this::showPrivateKeyDialog);
            return;
        }
        showVerifyPaymentPasswordDialog(this::showPrivateKeyDialog, "验证支付密码");
    }

    private void showPrivateKeyDialog() {
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

    private void showVerifyPaymentPasswordDialog(Runnable onSuccess, String title) {
        android.widget.EditText input = new android.widget.EditText(getActivity());
        input.setHint("请输入支付密码");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(getActivity())
                .setTitle(title)
                .setView(input)
                .setPositiveButton("确认", (d, w) -> {
                    String pwd = input.getText() == null ? "" : input.getText().toString().trim();
                    if (!WalletStorage.verifyPaymentPassword(getActivity(), pwd)) {
                        ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码错误");
                        return;
                    }
                    onSuccess.run();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSetPaymentPasswordDialog() {
        if (WalletStorage.hasPaymentPassword(getActivity())) {
            showVerifyPaymentPasswordDialog(() -> showSetPaymentPasswordDialog(null), "核对当前支付密码");
            return;
        }
        showSetPaymentPasswordDialog(null);
    }

    private void showSetPaymentPasswordDialog(Runnable onSaved) {

        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        android.widget.EditText first = new android.widget.EditText(getActivity());
        first.setHint("输入支付密码");
        first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        android.widget.EditText second = new android.widget.EditText(getActivity());
        second.setHint("再次输入支付密码");
        second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(first);
        layout.addView(second);

        new AlertDialog.Builder(getActivity())
                .setTitle(WalletStorage.hasPaymentPassword(getActivity()) ? "重置支付密码" : "设置支付密码")
                .setView(layout)
                .setPositiveButton("保存", (d, w) -> {
                    String a = first.getText() == null ? "" : first.getText().toString().trim();
                    String b = second.getText() == null ? "" : second.getText().toString().trim();
                    if (TextUtils.isEmpty(a) || a.length() < 4) {
                        ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码至少 4 位");
                        return;
                    }
                    if (!TextUtils.equals(a, b)) {
                        ((WalletWorkflowCoordinator.Host) getActivity()).toast("两次输入不一致");
                        return;
                    }
                    WalletStorage.setPaymentPassword(getActivity(), a);
                    ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码已保存");
                    if (onSaved != null) {
                        onSaved.run();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private GradientDrawable outlineBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), c(String.valueOf(Theme.key_divider)));
        return bg;
    }

    @Override
    public void refresh() {
        String selected = WalletStorage.getSelectedAddress(getActivity());
        String walletText = selected == null
                ? "当前钱包：未创建"
                : "当前钱包：" + WalletWorkflowCoordinator.shortAddress(selected);
        walletText += WalletStorage.hasPaymentPassword(getActivity()) ? "\n支付密码：已设置" : "\n支付密码：未设置";
        selectedWalletView.setText(walletText);
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
