package org.telegram.wallet.ui;

import android.app.Fragment;
import android.graphics.Typeface;
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

import org.telegram.messenger.R;
import org.telegram.wallet.data.WalletStorage;

public class SendTokenFragment extends Fragment implements WalletRefreshable {
    private TextView fromWalletView;
    private EditText toAddressEdit;
    private EditText amountEdit;

    public static SendTokenFragment newInstance() { return new SendTokenFragment(); }

    @Override public android.view.View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(14));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        LinearLayout card = Web3Ui.card(getActivity());
        root.addView(card, Web3Ui.matchWrap());
        LinearLayout head = new LinearLayout(getActivity());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout icon = Web3Ui.iconCircleDrawable(getActivity(), R.drawable.icon_wallet_1_1, p.dark ? 0x24F08C22 : 0xFFFFF2DF, 38);
        head.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView title = Web3Ui.text(getActivity(), "转账", 20, p.primaryText, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(10);
        head.addView(title, titleLp);
        card.addView(head, Web3Ui.matchWrap());
        fromWalletView = Web3Ui.text(getActivity(), "付款钱包：--", 15, p.secondaryText, false);
        card.addView(fromWalletView, Web3Ui.topMargin(getActivity(), 10));
        card.addView(Web3Ui.text(getActivity(), "收款地址", 14, p.primaryText, true), Web3Ui.topMargin(getActivity(), 12));
        toAddressEdit = createInput("0x...");
        card.addView(toAddressEdit, Web3Ui.topMargin(getActivity(), 8));
        card.addView(Web3Ui.text(getActivity(), "BNB 数量", 14, p.primaryText, true), Web3Ui.topMargin(getActivity(), 12));
        amountEdit = createInput("例如 0.01");
        amountEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        card.addView(amountEdit, Web3Ui.topMargin(getActivity(), 8));
        LinearLayout submitButton = Web3Ui.actionButtonDrawable(getActivity(), "预览并提交", R.drawable.icon_wallet_1_3, true);
        submitButton.setOnClickListener(v -> onSubmit());
        card.addView(submitButton, Web3Ui.topMargin(getActivity(), 16));
        refresh();
        return scroll;
    }

    private EditText createInput(String hint) {
        Web3Ui.Palette p = Web3Ui.palette();
        EditText edit = new EditText(getActivity());
        edit.setHint(hint);
        edit.setTextSize(14f);
        edit.setSingleLine(true);
        edit.setTextColor(p.primaryText);
        edit.setHintTextColor(p.mutedText);
        edit.setTypeface(Typeface.DEFAULT);
        edit.setPadding(dp(12), 0, dp(12), 0);
        edit.setMinHeight(dp(46));
        edit.setBackground(Web3Ui.rounded(getActivity(), p.softCardBg, 12));
        return edit;
    }

    private void onSubmit() {
        String to = toAddressEdit.getText().toString().trim();
        String amount = amountEdit.getText().toString().trim();
        if (TextUtils.isEmpty(to) || !to.matches("^0x[0-9a-fA-F]{40}$")) { host().toast("收款地址格式错误"); return; }
        if (TextUtils.isEmpty(amount)) { host().toast("请输入 BNB 数量"); return; }
        String from = WalletStorage.getSelectedAddress(getActivity());
        LinearLayout content = new LinearLayout(getActivity());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(Web3Dialog.tip(getActivity(), "链上转账提交后不可撤回，请仔细核对收款地址和金额。"), Web3Ui.matchWrap());
        LinearLayout.LayoutParams summaryLp = Web3Ui.topMargin(getActivity(), 14);
        content.addView(Web3Dialog.message(getActivity(),
                "From: " + WalletWorkflowCoordinator.shortAddress(from) + "\n" +
                        "To: " + WalletWorkflowCoordinator.shortAddress(to) + "\n" +
                        "Amount: " + amount + " BNB",
                false), summaryLp);
        Web3Dialog.show(getActivity(),
                "确认转账",
                "BNB Smart Chain",
                Web3IconView.SEND,
                content,
                "确认发送",
                dialog -> {
                    coordinator().sendNativeTransfer(to, amount, this::refresh);
                    return true;
                },
                "取消",
                null);
    }

    @Override public void refresh() {
        String selected = WalletStorage.getSelectedAddress(getActivity());
        fromWalletView.setText(TextUtils.isEmpty(selected) ? "当前钱包：未创建" : "付款钱包：" + WalletWorkflowCoordinator.shortAddress(selected));
    }
    private WalletWorkflowCoordinator.Host host() { return (WalletWorkflowCoordinator.Host) getActivity(); }
    private WalletWorkflowCoordinator coordinator() { return host().coordinator(); }
    private int dp(int value) { return Web3Ui.dp(getActivity(), value); }
}
