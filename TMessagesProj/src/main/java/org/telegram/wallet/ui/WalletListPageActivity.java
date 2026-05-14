package org.telegram.wallet.ui;

import android.app.Activity;
import android.app.Fragment;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public class WalletListPageActivity extends Activity implements WalletWorkflowCoordinator.Host {
    public static final String EXTRA_PAGE = "page";
    public static final String PAGE_WALLET_LIST = "wallet_list";
    public static final String PAGE_RPC_NODES = "rpc_nodes";

    private int containerId;
    private WalletWorkflowCoordinator coordinator;
    private String page;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Web3Ui.applySystemBars(this);
        coordinator = new WalletWorkflowCoordinator(this, this);
        page = getIntent() == null ? PAGE_WALLET_LIST : getIntent().getStringExtra(EXTRA_PAGE);
        if (page == null) page = PAGE_WALLET_LIST;
        setContentView(buildRoot(resolveTitle()));
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction().replace(containerId, createFragment(), page).commitAllowingStateLoss();
        }
    }
    @Override protected void onResume() { super.onResume(); Web3Ui.applySystemBars(this); }

    private String resolveTitle() {
        if (PAGE_RPC_NODES.equals(page)) {
            return "币安智能链";
        }
        return LocaleController.getString(R.string.Web3WalletListSwitchTitle);
    }

    private Fragment createFragment() {
        if (PAGE_RPC_NODES.equals(page)) {
            return WalletRpcNodeFragment.newInstance();
        }
        return WalletListPageFragment.newInstance();
    }

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
        TextView right = Web3Ui.text(this, PAGE_RPC_NODES.equals(page) ? "刷新" : "", 15, p.primaryText, false);
        right.setGravity(Gravity.CENTER);
        right.setOnClickListener(v -> {
            Fragment fragment = getFragmentManager().findFragmentById(containerId);
            if (fragment instanceof WalletRpcNodeFragment) {
                ((WalletRpcNodeFragment) fragment).forceRefreshNodes();
            }
        });
        bar.addView(right, new LinearLayout.LayoutParams(dp(44), dp(56)));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        Web3Ui.attachSystemBarInsets(this, root, bar, 56, null, 0);
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
