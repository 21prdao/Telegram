package org.telegram.wallet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.ui.ActionBar.Theme;

public class TokenListPageActivity extends Activity implements WalletWorkflowCoordinator.Host {

    public static final String EXTRA_SHOW_RECORDS = "extra_show_records";
    public static final String EXTRA_AUTO_OPEN_ADD = "extra_auto_open_add";

    private int containerId;
    private WalletWorkflowCoordinator coordinator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        coordinator = new WalletWorkflowCoordinator(this, this);
        boolean showRecords = getIntent() != null && getIntent().getBooleanExtra(EXTRA_SHOW_RECORDS, false);
        boolean autoOpenAdd = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_OPEN_ADD, false);
        setContentView(buildRoot(showRecords));
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(containerId, showRecords ? TokenListPageFragment.redPacketRecords() : TokenListPageFragment.tokenList(), "token_list_page")
                    .commitAllowingStateLoss();
            if (!showRecords && autoOpenAdd) {
                getWindow().getDecorView().post(() -> coordinator.showAddTokenDialog(this::refreshCurrentFragment));
            }
        }
    }

    private LinearLayout buildRoot(boolean showRecords) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(String.valueOf(Theme.key_windowBackgroundGray)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        bar.setBackgroundColor(c(String.valueOf(Theme.key_windowBackgroundWhite)));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(20f);
        back.setGravity(Gravity.CENTER);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(showRecords ? "我发出的红包记录" : "代币列表");
        title.setTextSize(18f);
        title.setGravity(Gravity.CENTER);
        title.setTextColor(c(String.valueOf(Theme.key_windowBackgroundWhiteBlackText)));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(title, titleLp);

        TextView right = new TextView(this);
        right.setText(showRecords ? "" : "添加");
        right.setTextSize(14f);
        right.setGravity(Gravity.CENTER);
        right.setTextColor(c(String.valueOf(Theme.key_featuredStickers_addButton)));
        right.setOnClickListener(v -> {
            if (!showRecords) {
                coordinator.showAddTokenDialog(this::refreshCurrentFragment);
            }
        });
        bar.addView(right, new LinearLayout.LayoutParams(dp(40), dp(40)));

        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMargins(dp(12), dp(10), dp(12), dp(10));
        root.addView(container, lp);
        return root;
    }

    private void refreshCurrentFragment() {
        android.app.Fragment fragment = getFragmentManager().findFragmentById(containerId);
        if (fragment instanceof WalletRefreshable) {
            ((WalletRefreshable) fragment).refresh();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private int c(String key) {
        return Theme.getColor(Integer.parseInt(key));
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
