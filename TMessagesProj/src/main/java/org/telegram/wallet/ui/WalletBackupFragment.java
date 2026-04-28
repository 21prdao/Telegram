package org.telegram.wallet.ui;

import android.app.AlertDialog;
import android.app.Fragment;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.wallet.data.WalletStorage;

public class WalletBackupFragment extends Fragment implements WalletRefreshable {
    private TextView selectedWalletView;
    private TextView paymentPasswordView;
    public static WalletBackupFragment newInstance() { return new WalletBackupFragment(); }

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
        FrameLayout icon = Web3Ui.iconCircle(getActivity(), Web3IconView.SHIELD, p.orange, p.dark ? 0x24F08C22 : 0xFFFFF2DF, 42);
        head.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = Web3Ui.text(getActivity(), "安全中心", 20, p.primaryText, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        head.addView(title, titleLp);
        card.addView(head, Web3Ui.matchWrap());
        android.view.View divider = new android.view.View(getActivity());
        divider.setBackgroundColor(p.border);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(12);
        card.addView(divider, divLp);
        TextView hint = Web3Ui.text(getActivity(), "建议：定期离线备份私钥，不要截图，不要上传网盘。", 14, p.secondaryText, false);
        hint.setLineSpacing(dp(2), 1.0f);
        card.addView(hint, Web3Ui.topMargin(getActivity(), 12));
        LinearLayout walletRow = infoRow(Web3IconView.WALLET, "当前钱包：");
        selectedWalletView = (TextView) walletRow.getChildAt(1);
        card.addView(walletRow, Web3Ui.topMargin(getActivity(), 12));
        LinearLayout passwordRow = infoRow(Web3IconView.LOCK, "支付密码：");
        paymentPasswordView = (TextView) passwordRow.getChildAt(1);
        card.addView(passwordRow, Web3Ui.topMargin(getActivity(), 8));
        LinearLayout backupButton = Web3Ui.actionButton(getActivity(), "查看当前钱包私钥", Web3IconView.KEY, true);
        backupButton.setOnClickListener(v -> showPrivateKey());
        card.addView(backupButton, Web3Ui.topMargin(getActivity(), 16));
        LinearLayout passwordButton = Web3Ui.actionButton(getActivity(), "设置/修改支付密码", Web3IconView.LOCK, false);
        passwordButton.setOnClickListener(v -> showSetPaymentPasswordDialog());
        card.addView(passwordButton, Web3Ui.topMargin(getActivity(), 10));
        refresh();
        return scroll;
    }

    private LinearLayout infoRow(int iconType, String prefix) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout row = new LinearLayout(getActivity());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(new Web3IconView(getActivity(), iconType, p.orange), new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView tv = Web3Ui.text(getActivity(), prefix, 14, p.primaryText, false);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvLp.leftMargin = dp(10);
        row.addView(tv, tvLp);
        return row;
    }

    private void showPrivateKey() {
        String key = WalletStorage.getSelectedPrivateKey(getActivity());
        if (key == null) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先创建或导入钱包"); return; }
        if (!WalletStorage.hasPaymentPassword(getActivity())) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先设置支付密码"); showSetPaymentPasswordDialog(this::showPrivateKeyDialog); return; }
        showVerifyPaymentPasswordDialog(this::showPrivateKeyDialog, "验证支付密码");
    }
    private void showPrivateKeyDialog() {
        String key = WalletStorage.getSelectedPrivateKey(getActivity());
        if (key == null) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("请先创建或导入钱包"); return; }
        new AlertDialog.Builder(getActivity()).setTitle("私钥（请妥善保管）").setMessage(key).setPositiveButton("我知道了", null).show();
    }
    private void showVerifyPaymentPasswordDialog(Runnable onSuccess, String title) {
        EditText input = new EditText(getActivity());
        input.setHint("请输入支付密码");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new AlertDialog.Builder(getActivity()).setTitle(title).setView(input).setPositiveButton("确认", (d, w) -> {
            String pwd = input.getText() == null ? "" : input.getText().toString().trim();
            if (!WalletStorage.verifyPaymentPassword(getActivity(), pwd)) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码错误"); return; }
            onSuccess.run();
        }).setNegativeButton("取消", null).show();
    }
    private void showSetPaymentPasswordDialog() { if (WalletStorage.hasPaymentPassword(getActivity())) { showVerifyPaymentPasswordDialog(() -> showSetPaymentPasswordDialog(null), "核对当前支付密码"); return; } showSetPaymentPasswordDialog(null); }
    private void showSetPaymentPasswordDialog(Runnable onSaved) {
        LinearLayout layout = new LinearLayout(getActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        EditText first = new EditText(getActivity()); first.setHint("输入支付密码"); first.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText second = new EditText(getActivity()); second.setHint("再次输入支付密码"); second.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        layout.addView(first); layout.addView(second);
        new AlertDialog.Builder(getActivity()).setTitle(WalletStorage.hasPaymentPassword(getActivity()) ? "重置支付密码" : "设置支付密码").setView(layout).setPositiveButton("保存", (d, w) -> {
            String a = first.getText() == null ? "" : first.getText().toString().trim();
            String b = second.getText() == null ? "" : second.getText().toString().trim();
            if (TextUtils.isEmpty(a) || a.length() < 4) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码至少 4 位"); return; }
            if (!TextUtils.equals(a, b)) { ((WalletWorkflowCoordinator.Host) getActivity()).toast("两次输入不一致"); return; }
            WalletStorage.setPaymentPassword(getActivity(), a);
            ((WalletWorkflowCoordinator.Host) getActivity()).toast("支付密码已保存");
            refresh();
            if (onSaved != null) onSaved.run();
        }).setNegativeButton("取消", null).show();
    }
    @Override public void refresh() {
        if (getActivity() == null || selectedWalletView == null || paymentPasswordView == null) return;
        String selected = WalletStorage.getSelectedAddress(getActivity());
        selectedWalletView.setText(selected == null ? "当前钱包：未创建" : "当前钱包：" + WalletWorkflowCoordinator.shortAddress(selected));
        paymentPasswordView.setText(WalletStorage.hasPaymentPassword(getActivity()) ? "支付密码：已设置" : "支付密码：未设置");
    }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
