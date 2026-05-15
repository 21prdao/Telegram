package org.telegram.wallet.ui;

import android.app.Fragment;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.EditText;
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

    public static WalletListPageFragment newInstance() {
        return new WalletListPageFragment();
    }

    @Override
    public android.view.View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Web3Ui.Palette p = Web3Ui.palette();
        ScrollView scroll = new ScrollView(getActivity());
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(p.pageBg);
        LinearLayout root = new LinearLayout(getActivity());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Web3Ui.pageHorizontalPadding(getActivity()), dp(12), Web3Ui.pageHorizontalPadding(getActivity()), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletList), 22, p.primaryText, true), Web3Ui.matchWrap());
        root.addView(Web3Ui.text(getActivity(), LocaleController.getString(R.string.Web3WalletTapToSwitch), 14, p.secondaryText, false), Web3Ui.topMargin(getActivity(), 6));
        listContainer = new LinearLayout(getActivity());
        listContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer, Web3Ui.topMargin(getActivity(), 12));
        refresh();
        return scroll;
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
        card.setPadding(dp(12), dp(12), dp(10), dp(12));
        card.setBackground(selected ? Web3Ui.orangeGradient(getActivity(), 14) : Web3Ui.rounded(getActivity(), p.cardBg, 14));
        Web3Ui.setElevation(card, 0);

        LinearLayout info = new LinearLayout(getActivity());
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(info, infoLp);

        String name = wallet.name == null ? LocaleController.getString(R.string.Web3WalletDefaultName) : wallet.name;
        info.addView(Web3Ui.text(getActivity(), name + (selected ? LocaleController.getString(R.string.Web3WalletCurrentSuffix) : ""), 18, selected ? 0xFFFFFFFF : p.primaryText, true), Web3Ui.matchWrap());
        info.addView(Web3Ui.text(getActivity(), WalletWorkflowCoordinator.shortAddress(wallet.address), 14, selected ? 0xEEFFFFFF : p.secondaryText, false), Web3Ui.matchWrap());

        TextView delete = deleteWalletButton(selected);
        delete.setOnClickListener(v -> showDeleteWalletDialog(wallet));
        LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        deleteLp.leftMargin = dp(10);
        card.addView(delete, deleteLp);

        card.setOnClickListener(v -> {
            WalletStorage.setSelectedAddress(getActivity(), wallet.address);
            ((WalletWorkflowCoordinator.Host) getActivity()).toast(LocaleController.formatString(R.string.Web3WalletSwitchedTo, name));
            refresh();
        });
        return card;
    }

    private TextView deleteWalletButton(boolean selected) {
        Web3Ui.Palette p = Web3Ui.palette();
        int textColor = selected ? 0xFFFFFFFF : 0xFFE15249;
        int bgColor = selected ? 0x22FFFFFF : (p.dark ? 0x24E15249 : 0xFFFFEFEF);
        int strokeColor = selected ? 0x55FFFFFF : 0x44E15249;
        TextView tv = Web3Ui.text(getActivity(), "删除", 12, textColor, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(Web3Ui.roundedStroke(getActivity(), bgColor, strokeColor, 12, 1));
        return tv;
    }

    private void showDeleteWalletDialog(WalletAccount wallet) {
        if (getActivity() == null || wallet == null || TextUtils.isEmpty(wallet.address)) {
            return;
        }
        if (!WalletStorage.hasPaymentPassword(getActivity())) {
            toast("请先到安全中心设置支付密码后再删除钱包");
            return;
        }
        String name = TextUtils.isEmpty(wallet.name) ? LocaleController.getString(R.string.Web3WalletDefaultName) : wallet.name;
        LinearLayout content = new LinearLayout(getActivity());
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(Web3Dialog.tip(getActivity(), "删除钱包只会移除本机钱包数据和本机加密私钥。请确认已经离线备份私钥，否则删除后无法找回。"), Web3Ui.matchWrap());

        LinearLayout.LayoutParams msgLp = Web3Ui.topMargin(getActivity(), 12);
        content.addView(Web3Dialog.message(getActivity(), name + "\n" + WalletWorkflowCoordinator.shortAddress(wallet.address), false), msgLp);

        EditText password = Web3Dialog.input(getActivity(), "请输入支付密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout.LayoutParams passwordLp = Web3Ui.topMargin(getActivity(), 14);
        content.addView(Web3Dialog.field(getActivity(), "支付密码", password), passwordLp);

        Web3Dialog.show(getActivity(),
                "删除钱包",
                "需要验证支付密码",
                Web3IconView.LOCK,
                content,
                "确认删除",
                dialog -> {
                    String pwd = password.getText() == null ? "" : password.getText().toString();
                    if (TextUtils.isEmpty(pwd)) {
                        toast("请输入支付密码");
                        return false;
                    }
                    if (!WalletStorage.verifyPaymentPassword(getActivity(), pwd)) {
                        toast("支付密码错误");
                        return false;
                    }
                    boolean deleted = WalletStorage.deleteWallet(getActivity(), wallet.address);
                    if (!deleted) {
                        toast("钱包删除失败");
                        return false;
                    }
                    toast("钱包已删除");
                    refresh();
                    return true;
                },
                "取消",
                null);
    }

    private void toast(String text) {
        if (getActivity() instanceof WalletWorkflowCoordinator.Host) {
            ((WalletWorkflowCoordinator.Host) getActivity()).toast(text);
        }
    }

    private int dp(int value) {
        return Web3Ui.dp(getActivity(), value);
    }
}
