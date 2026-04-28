package org.telegram.wallet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class WalletListPageActivity extends Activity implements WalletWorkflowCoordinator.Host {
    private int containerId;
    private WalletWorkflowCoordinator coordinator;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Web3Ui.applySystemBars(this);
        coordinator = new WalletWorkflowCoordinator(this, this);
        setContentView(buildRoot("钱包列表 / 切换钱包"));
        if (savedInstanceState == null) getFragmentManager().beginTransaction().replace(containerId, WalletListPageFragment.newInstance(), "wallet_list_page").commitAllowingStateLoss();
    }
    @Override protected void onResume() { super.onResume(); Web3Ui.applySystemBars(this); }

    private LinearLayout buildRoot(String titleText) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), 0, dp(16), 0);
        bar.setBackgroundColor(p.appBarBg);
        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(56)));
        TextView title = Web3Ui.text(this, titleText, 18, p.primaryText, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(new TextView(this), new LinearLayout.LayoutParams(dp(44), dp(56)));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }
    private int dp(int value) { return Web3Ui.dp(this, value); }
    @Override public WalletWorkflowCoordinator coordinator() { return coordinator; }
    @Override public void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
