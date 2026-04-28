package org.telegram.wallet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class TokenListPageActivity extends Activity implements WalletWorkflowCoordinator.Host {
    public static final String EXTRA_SHOW_RECORDS = "extra_show_records";
    public static final String EXTRA_AUTO_OPEN_ADD = "extra_auto_open_add";
    private int containerId;
    private WalletWorkflowCoordinator coordinator;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Web3Ui.applySystemBars(this);
        coordinator = new WalletWorkflowCoordinator(this, this);
        boolean showRecords = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_RECORDS, false);
        boolean autoOpenAdd = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_OPEN_ADD, false);
        setContentView(buildRoot(showRecords));
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction().replace(containerId, showRecords ? TokenListPageFragment.redPacketRecords() : TokenListPageFragment.tokenList(), "token_list_page").commitAllowingStateLoss();
            if (!showRecords && autoOpenAdd) getWindow().getDecorView().post(() -> coordinator.showAddTokenDialog(this::refreshCurrentFragment));
        }
    }

    @Override protected void onResume() { super.onResume(); Web3Ui.applySystemBars(this); refreshCurrentFragment(); }

    private LinearLayout buildRoot(boolean showRecords) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(12), dp(20), dp(10));
        bar.setBackgroundColor(p.pageBg);
        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Web3Ui.text(this, showRecords ? "我发出的红包记录" : "代币列表", 23, p.primaryText, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (showRecords) {
            TextView spacer = new TextView(this);
            bar.addView(spacer, new LinearLayout.LayoutParams(dp(48), dp(48)));
        } else {
            TextView right = Web3Ui.text(this, "添加", 16, p.orange, true);
            right.setGravity(Gravity.CENTER);
            right.setOnClickListener(v -> coordinator.showAddTokenDialog(this::refreshCurrentFragment));
            bar.addView(right, new LinearLayout.LayoutParams(dp(54), dp(48)));
        }
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void refreshCurrentFragment() { android.app.Fragment fragment = getFragmentManager().findFragmentById(containerId); if (fragment instanceof WalletRefreshable) ((WalletRefreshable) fragment).refresh(); }
    private int dp(int value) { return Web3Ui.dp(this, value); }
    @Override public WalletWorkflowCoordinator coordinator() { return coordinator; }
    @Override public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
