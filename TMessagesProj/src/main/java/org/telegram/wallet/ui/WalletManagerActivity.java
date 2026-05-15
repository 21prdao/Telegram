package org.telegram.wallet.ui;

import android.app.Activity;
import android.content.Intent;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public class WalletManagerActivity extends Activity implements WalletWorkflowCoordinator.Host {

    private static final String TAG_HOME = "wallet_home";
    private static final String TAG_SECURITY = "wallet_security";
    private static final String TAG_MANAGE = "wallet_manage";

    private int containerId;
    private LinearLayout homeTab;
    private LinearLayout securityTab;
    private LinearLayout manageTab;
    private View actionBarView;
    private FrameLayout bottomTabsView;

    private static final int[] TAB_ICON_SELECTED = {
            R.drawable.icon_wallet_6_1,
            R.drawable.icon_wallet_4_1,
            R.drawable.icon_wallet_2_1
    };
    private static final int[] TAB_ICON_UNSELECTED = {
            R.drawable.icon_wallet_6_2,
            R.drawable.icon_wallet_4_2,
            R.drawable.icon_wallet_2_2
    };
    private WalletWorkflowCoordinator coordinator;
    private View rpcStatusDot;

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
        updateRpcNodeIndicator();
    }

    private LinearLayout buildRootLayout() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);
        actionBarView = buildActionBar();
        root.addView(actionBarView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Web3Ui.appBarHeight(this)));

        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        bottomTabsView = buildBottomTabs();
        root.addView(bottomTabsView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        Web3Ui.attachSystemBarInsets(this, root, actionBarView, Web3Ui.APP_BAR_HEIGHT_DP, bottomTabsView, 52);
        return root;
    }

    private LinearLayout buildActionBar() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Web3Ui.appBarSidePadding(this), 0, Web3Ui.appBarSidePadding(this), 0);
        bar.setBackgroundColor(p.appBarBg);

        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));

        TextView title = Web3Ui.text(this, LocaleController.getString(R.string.Web3WalletProTitle), 19, p.primaryText, true);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout nodeButton = Web3Ui.iconButton(this, Web3IconView.LINK);
        nodeButton.setOnClickListener(v -> startActivity(new Intent(this, WalletListPageActivity.class).putExtra(WalletListPageActivity.EXTRA_PAGE, WalletListPageActivity.PAGE_RPC_NODES)));
        rpcStatusDot = new View(this);
        rpcStatusDot.setBackground(Web3Ui.rounded(this, Web3Ui.palette().orange, 4));
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.RIGHT | Gravity.TOP);
        dotLp.topMargin = dp(12);
        dotLp.rightMargin = dp(8);
        nodeButton.addView(rpcStatusDot, dotLp);
        bar.addView(nodeButton, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));
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

        homeTab = createTab(TAB_ICON_SELECTED[0], LocaleController.getString(R.string.Web3WalletTabAssets));
        securityTab = createTab(TAB_ICON_SELECTED[1], LocaleController.getString(R.string.Web3WalletTabSecurity));
        manageTab = createTab(TAB_ICON_SELECTED[2], LocaleController.getString(R.string.Web3WalletTabManage));
        homeTab.setOnClickListener(v -> switchTo(TAG_HOME));
        securityTab.setOnClickListener(v -> switchTo(TAG_SECURITY));
        manageTab.setOnClickListener(v -> switchTo(TAG_MANAGE));
        dock.addView(homeTab, tabLp());
        dock.addView(securityTab, tabLp());
        dock.addView(manageTab, tabLp());
        FrameLayout.LayoutParams dockLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.BOTTOM);
        dockLp.topMargin = dp(1);
        wrap.addView(dock, dockLp);
        return wrap;
    }

    private LinearLayout createTab(int iconRes, String text) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(0, dp(2), 0, dp(2));
        tab.setBackgroundColor(0x00000000);

        ImageView iconView = new ImageView(this);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setImageResource(iconRes);
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
        int index = tab == homeTab ? 0 : tab == securityTab ? 1 : 2;
        if (tab.getChildAt(0) instanceof ImageView) {
            ((ImageView) tab.getChildAt(0)).setImageResource(active ? TAB_ICON_SELECTED[index] : TAB_ICON_UNSELECTED[index]);
        }
        if (tab.getChildAt(1) instanceof TextView) ((TextView) tab.getChildAt(1)).setTextColor(color);
    }

    private void showDeveloperInfoDialog() {
        coordinator.checkConnectivity(status -> {
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(Web3Dialog.message(this, status, false), Web3Ui.matchWrap());
            Web3Dialog.show(this,
                    LocaleController.getString(R.string.Web3WalletDeveloperInfo),
                    "当前节点检测",
                    Web3IconView.LINK,
                    content,
                    LocaleController.getString(R.string.OK),
                    null,
                    null,
                    null);
        });
    }

    private void updateRpcNodeIndicator() {
        final View dot = rpcStatusDot;
        if (dot == null) {
            return;
        }
        dot.setBackground(Web3Ui.rounded(this, Web3Ui.palette().orange, 4));
        new Thread(() -> {
            boolean ok = false;
            try {
                org.telegram.wallet.config.WalletRuntimeConfig.ChainConfig config = org.telegram.wallet.config.WalletRuntimeConfig.get(false);
                java.util.ArrayList<org.telegram.wallet.config.WalletRuntimeConfig.RpcEndpoint> nodes = new java.util.ArrayList<>();
                nodes.add(new org.telegram.wallet.config.WalletRuntimeConfig.RpcEndpoint("当前节点", config.bestRpcUrl, true, "current", false));
                java.util.List<org.telegram.wallet.config.WalletRuntimeConfig.RpcProbeResult> probes = org.telegram.wallet.config.WalletRuntimeConfig.probeRpcEndpoints(nodes, config.chainId);
                ok = !probes.isEmpty() && probes.get(0).ok;
            } catch (Throwable ignore) {
            }
            final int color = ok ? 0xFF009B72 : 0xFFE15249;
            runOnUiThread(() -> {
                if (rpcStatusDot != null) {
                    rpcStatusDot.setBackground(Web3Ui.rounded(this, color, 4));
                }
            });
        }, "wallet-rpc-status-dot").start();
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
        if (TAG_SECURITY.equals(tag)) return WalletBackupFragment.newInstance();
        if (TAG_MANAGE.equals(tag)) return WalletManageFragment.newInstance();
        return WalletHomeFragment.newInstance();
    }

    private void updateTabState(String currentTag) {
        setTabActive(homeTab, TAG_HOME.equals(currentTag));
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
    public void openRedPacketRecordDetailPage(String packetId) {
        Intent intent = new Intent(this, RedPacketRecordDetailActivity.class);
        intent.putExtra(RedPacketRecordDetailActivity.EXTRA_PACKET_ID, packetId);
        startActivity(intent);
    }

    private int dp(int value) { return Web3Ui.dp(this, value); }

    @Override public WalletWorkflowCoordinator coordinator() { return coordinator; }
    @Override public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
