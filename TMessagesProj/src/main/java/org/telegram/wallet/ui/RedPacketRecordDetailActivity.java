package org.telegram.wallet.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public class RedPacketRecordDetailActivity extends Activity {
    public static final String EXTRA_PACKET_ID = "extra_packet_id";
    private int containerId;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Web3Ui.applySystemBars(this);
        setContentView(buildRoot());
        if (savedInstanceState == null) {
            String packetId = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_PACKET_ID);
            if (TextUtils.isEmpty(packetId)) {
                finish();
                return;
            }
            getFragmentManager().beginTransaction()
                    .replace(containerId, RedPacketRecordDetailFragment.newInstance(packetId), "redpacket_record_detail_page")
                    .commitAllowingStateLoss();
        }
    }

    private LinearLayout buildRoot() {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.pageBg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Web3Ui.appBarSidePadding(this), 0, Web3Ui.appBarSidePadding(this), 0);
        bar.setBackgroundColor(p.appBarBg);

        FrameLayout back = Web3Ui.iconButton(this, Web3IconView.BACK);
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));

        TextView title = Web3Ui.text(this, LocaleController.getString(R.string.WalletMyRedPacketRecords), 18, p.primaryText, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView spacer = new TextView(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(Web3Ui.appBarButtonSize(this), Web3Ui.appBarHeight(this)));
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Web3Ui.appBarHeight(this)));
        Web3Ui.attachSystemBarInsets(this, root, bar, Web3Ui.APP_BAR_HEIGHT_DP, null, 0);

        FrameLayout container = new FrameLayout(this);
        containerId = android.view.View.generateViewId();
        container.setId(containerId);
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override protected void onResume() {
        super.onResume();
        Web3Ui.applySystemBars(this);
    }

    private int dp(int value) { return Web3Ui.dp(this, value); }
}
