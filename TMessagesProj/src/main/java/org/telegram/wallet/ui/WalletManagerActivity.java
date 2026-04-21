package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

    private View buildRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18), dp(20), dp(18), dp(10));

        TextView title = new TextView(this);
        title.setText("Web3 Wallet");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF1F2937);
        title.setTextSize(24f);
        header.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Telegram 风格 · TokenPocket 流程");
        subtitle.setTextColor(0xFF6B7280);
        subtitle.setTextSize(13f);
        subtitle.setPadding(0, dp(6), 0, 0);
        header.addView(subtitle);

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        FrameLayout container = new FrameLayout(this);
        containerId = View.generateViewId();
        container.setId(containerId);
        LinearLayout.LayoutParams containerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        containerLp.setMargins(dp(12), dp(4), dp(12), dp(8));
        root.addView(container, containerLp);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12), dp(6), dp(12), dp(12));
        tabs.setBackgroundColor(0xFFF5F7FB);

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
        tab.setTextColor(0xFF334155);
        tab.setBackground(tabBg(false));
        return tab;
    }

    private GradientDrawable tabBg(boolean active) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(active ? 0xFF229ED9 : 0xFFFFFFFF);
        bg.setStroke(dp(1), active ? 0xFF229ED9 : 0xFFDCE5F1);
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
        homeTab.setTextColor(TAG_HOME.equals(currentTag) ? 0xFFFFFFFF : 0xFF334155);

        sendTab.setBackground(tabBg(TAG_SEND.equals(currentTag)));
        sendTab.setTextColor(TAG_SEND.equals(currentTag) ? 0xFFFFFFFF : 0xFF334155);

        securityTab.setBackground(tabBg(TAG_SECURITY.equals(currentTag)));
        securityTab.setTextColor(TAG_SECURITY.equals(currentTag) ? 0xFFFFFFFF : 0xFF334155);
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

    @Override
    public WalletWorkflowCoordinator coordinator() {
        return coordinator;
    }

    @Override
    public void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
