package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class WalletManagerActivity extends Activity implements WalletWorkflowCoordinator.Host {

    private static final String TAG_HOME = "wallet_home";
    private static final String TAG_SEND = "wallet_send";
    private static final String TAG_SECURITY = "wallet_security";
    private static final String TAG_MANAGE = "wallet_manage";

    private int containerId;
    private LinearLayout homeTab;
    private LinearLayout sendTab;
    private LinearLayout securityTab;
    private LinearLayout manageTab;
    private WalletWorkflowCoordinator coordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Web3Ui.applySystemBars(this);
        coordinator = new WalletWorkflowCoordinator(this, this);
        setContentView(buildRootLayout());
        if (savedInstanceState == null) switchTo(TAG_HOME); else updateTabState(getCurrentTag());
    }

    @Override
    protected void onResume() {
        super.onResume();
        Web3Ui.applySystemBars(this);
        refreshCurrentFragment();
    }

    private LinearLayout buildRootLayout() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);
        root.addView(buildActionBar(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildBottomTabs(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return root;
    }

    private LinearLayout buildActionBar() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackgroundColor(p.appBarBg);

        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(56)));

        TextView title = Web3Ui.text(this, "Web3 Wallet Pro", 19, p.primaryText, true);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout settings = Web3Ui.iconButton(this, Web3IconView.SETTINGS);
        settings.setOnClickListener(v -> showDeveloperInfoDialog());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(56)));
        return bar;
    }

    private FrameLayout buildBottomTabs() {
        Web3Ui.Palette p = Web3Ui.palette();
        FrameLayout wrap = new FrameLayout(this);
        wrap.setBackgroundColor(p.pageBg);

        View divider = new View(this);
        divider.setBackgroundColor(p.dark ? 0x26314052 : 0xFFE5EAF2);
        wrap.addView(divider, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1), Gravity.TOP));

        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(4), dp(2), dp(4), dp(2));
        dock.setBackgroundColor(p.pageBg);

        homeTab = createTab(Web3IconView.WALLET, "资产");
        sendTab = createTab(Web3IconView.SEND, "转账");
        securityTab = createTab(Web3IconView.SHIELD, "安全");
        manageTab = createTab(Web3IconView.MANAGE, "管理");
        homeTab.setOnClickListener(v -> switchTo(TAG_HOME));
        sendTab.setOnClickListener(v -> switchTo(TAG_SEND));
        securityTab.setOnClickListener(v -> switchTo(TAG_SECURITY));
        manageTab.setOnClickListener(v -> switchTo(TAG_MANAGE));
        dock.addView(homeTab, tabLp());
        dock.addView(sendTab, tabLp());
        dock.addView(securityTab, tabLp());
        dock.addView(manageTab, tabLp());
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        dockLp.topMargin = dp(1);
        wrap.addView(dock, dockLp);
        return wrap;
    }

    private LinearLayout createTab(int icon, String text) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(2), 0, dp(2));
        tab.setBackgroundColor(0x00000000);

        Web3IconView iconView = new Web3IconView(this, icon, p.mutedText);
        tab.addView(iconView, new LinearLayout.LayoutParams(dp(20), dp(20)));

        TextView tv = Web3Ui.text(this, text, 11, p.mutedText, true);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLp.topMargin = dp(1);
        tab.addView(tv, tvLp);
        return tab;
    }

    private LinearLayout.LayoutParams tabLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
    }

    private void setTabActive(LinearLayout tab, boolean active) {
        Web3Ui.Palette p = Web3Ui.palette();
        int color = active ? p.orange : p.mutedText;
        tab.setBackgroundColor(0x00000000);
        if (tab.getChildAt(0) instanceof Web3IconView) ((Web3IconView) tab.getChildAt(0)).setIconColor(color);
        if (tab.getChildAt(1) instanceof TextView) ((TextView) tab.getChildAt(1)).setTextColor(color);
    }

    private void showDeveloperInfoDialog() {
        coordinator.checkConnectivity(status -> new android.app.AlertDialog.Builder(this).setTitle("开发者信息").setMessage(status).setPositiveButton("确定", null).show());
    }

    private void switchTo(String tag) {
        Fragment fragment = findOrCreate(tag);
        getFragmentManager().beginTransaction().replace(containerId, fragment, tag).commitAllowingStateLoss();
        updateTabState(tag);
    }

    private Fragment findOrCreate(String tag) {
        FragmentManager fm = getFragmentManager();
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) return existing;
        if (TAG_SEND.equals(tag)) return SendTokenFragment.newInstance();
        if (TAG_SECURITY.equals(tag)) return WalletBackupFragment.newInstance();
        if (TAG_MANAGE.equals(tag)) return WalletManageFragment.newInstance();
        return WalletHomeFragment.newInstance();
    }

    private void updateTabState(String currentTag) {
        setTabActive(homeTab, TAG_HOME.equals(currentTag));
        setTabActive(sendTab, TAG_SEND.equals(currentTag));
        setTabActive(securityTab, TAG_SECURITY.equals(currentTag));
        setTabActive(manageTab, TAG_MANAGE.equals(currentTag));
    }

    private String getCurrentTag() {
        Fragment current = getFragmentManager().findFragmentById(containerId);
        return current != null ? current.getTag() : TAG_HOME;
    }

    private void refreshCurrentFragment() {
        Fragment current = getFragmentManager().findFragmentById(containerId);
        if (current instanceof WalletRefreshable) ((WalletRefreshable) current).refresh();
    }

    public void openWalletListPage() { getFragmentManager().beginTransaction().replace(containerId, WalletListPageFragment.newInstance(), "wallet_list_page").addToBackStack("wallet_list_page").commitAllowingStateLoss(); }
    public void openTokenListPage() { getFragmentManager().beginTransaction().replace(containerId, TokenListPageFragment.tokenList(), "token_list_page").addToBackStack("token_list_page").commitAllowingStateLoss(); }
    public void openRedPacketRecordsPage() { getFragmentManager().beginTransaction().replace(containerId, TokenListPageFragment.redPacketRecords(), "redpacket_records_page").addToBackStack("redpacket_records_page").commitAllowingStateLoss(); }
    public void openRedPacketRecordDetailPage(String packetId) { getFragmentManager().beginTransaction().replace(containerId, RedPacketRecordDetailFragment.newInstance(packetId), "redpacket_record_detail_page").addToBackStack("redpacket_record_detail_page").commitAllowingStateLoss(); }

    private int dp(int value) { return Web3Ui.dp(this, value); }

    @Override public WalletWorkflowCoordinator coordinator() { return coordinator; }
    @Override public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
