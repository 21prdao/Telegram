package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.Theme;

public class WalletManagerActivity extends Activity implements WalletWorkflowCoordinator.Host {

    private static final String TAG_HOME = "wallet_home";
    private static final String TAG_SEND = "wallet_send";
    private static final String TAG_SECURITY = "wallet_security";

    private int containerId;
    private TextView homeTab;
    private TextView sendTab;
    private TextView securityTab;
    private WalletWorkflowCoordinator coordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        coordinator = new WalletWorkflowCoordinator(this, this);
        setContentView(buildRootLayout());
        if (savedInstanceState == null) {
            switchTo(TAG_HOME);
        } else {
            updateTabState(getCurrentTag());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCurrentFragment();
    }

    private LinearLayout buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(Theme.key_windowBackgroundGray));

        root.addView(buildActionBar(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        containerLp.setMargins(dp(12), dp(10), dp(12), dp(10));
        root.addView(container, containerLp);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(8), dp(12), dp(12));
        tabs.setBackgroundColor(c(Theme.key_windowBackgroundGray));

        homeTab = createTab("资产");
        sendTab = createTab("转账");
        securityTab = createTab("安全");

        homeTab.setOnClickListener(v -> switchTo(TAG_HOME));
        sendTab.setOnClickListener(v -> switchTo(TAG_SEND));
        securityTab.setOnClickListener(v -> switchTo(TAG_SECURITY));

        tabs.addView(homeTab, tabLp());
        tabs.addView(sendTab, tabLp());
        tabs.addView(securityTab, tabLp());

        root.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return root;
    }

    private LinearLayout buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(c(Theme.key_windowBackgroundWhite));

        TextView back = createActionButton("←");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText("Web3 Wallet");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(19f);
        title.setTextColor(c(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        TextView settings = createActionButton("⚙");
        settings.setOnClickListener(v -> showDeveloperInfoDialog());
        bar.addView(settings, new LinearLayout.LayoutParams(dp(40), dp(40)));

        return bar;
    }

    private TextView createActionButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(20f);
        button.setTextColor(c(Theme.key_windowBackgroundWhiteBlackText));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(c(Theme.key_windowBackgroundWhite));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), c(Theme.key_divider));
        button.setBackground(bg);
        return button;
    }

    private void showDeveloperInfoDialog() {
        coordinator.checkConnectivity(status -> new android.app.AlertDialog.Builder(this)
                .setTitle("开发者信息")
                .setMessage(status)
                .setPositiveButton("确定", null)
                .show());
    }

    private LinearLayout.LayoutParams tabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private TextView createTab(String text) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setGravity(Gravity.CENTER);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setTextSize(14f);
        tab.setTextColor(c(Theme.key_windowBackgroundWhiteGrayText));
        tab.setBackground(tabBg(false));
        return tab;
    }

    private GradientDrawable tabBg(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(active ? c(Theme.key_featuredStickers_addButton) : c(Theme.key_windowBackgroundWhite));
        bg.setStroke(dp(1), active ? c(Theme.key_featuredStickers_addButton) : c(Theme.key_divider));
        return bg;
    }

    private void switchTo(String tag) {
        Fragment fragment = findOrCreate(tag);
        getFragmentManager().beginTransaction()
                .replace(containerId, fragment, tag)
                .commitAllowingStateLoss();
        updateTabState(tag);
    }

    private Fragment findOrCreate(String tag) {
        FragmentManager fm = getFragmentManager();
        Fragment existing = fm.findFragmentByTag(tag);
        if (existing != null) {
            return existing;
        }
        if (TAG_SEND.equals(tag)) {
            return SendTokenFragment.newInstance();
        }
        if (TAG_SECURITY.equals(tag)) {
            return WalletBackupFragment.newInstance();
        }
        return WalletHomeFragment.newInstance();
    }

    private void updateTabState(String currentTag) {
        homeTab.setBackground(tabBg(TAG_HOME.equals(currentTag)));
        homeTab.setTextColor(TAG_HOME.equals(currentTag) ? c(Theme.key_featuredStickers_buttonText) : c(Theme.key_windowBackgroundWhiteGrayText));

        sendTab.setBackground(tabBg(TAG_SEND.equals(currentTag)));
        sendTab.setTextColor(TAG_SEND.equals(currentTag) ? c(Theme.key_featuredStickers_buttonText) : c(Theme.key_windowBackgroundWhiteGrayText));

        securityTab.setBackground(tabBg(TAG_SECURITY.equals(currentTag)));
        securityTab.setTextColor(TAG_SECURITY.equals(currentTag) ? c(Theme.key_featuredStickers_buttonText) : c(Theme.key_windowBackgroundWhiteGrayText));
    }

    private String getCurrentTag() {
        Fragment current = getFragmentManager().findFragmentById(containerId);
        return current != null ? current.getTag() : TAG_HOME;
    }

    private void refreshCurrentFragment() {
        Fragment current = getFragmentManager().findFragmentById(containerId);
        if (current instanceof WalletRefreshable) {
            ((WalletRefreshable) current).refresh();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int c(String key) {
        return Theme.getColor(key);
    }

    @Override
    public WalletWorkflowCoordinator coordinator() {
        return coordinator;
    }

    @Override
    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
