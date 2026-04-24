package org.telegram.wallet.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.wallet.chain.RedPacketContractService;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.model.ClaimPrepareResponse;
import org.telegram.wallet.model.RedPacketInfo;
import org.telegram.wallet.redpacket.RedPacketRepository;
import org.telegram.wallet.security.WalletKeyStore;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 说明：
 * 1) 这是“聊天里点红包 -> 弹出领取 Sheet”的完整文件。
 * 2) 它不改 Telegram 消息类型，只负责打开/领取/退款的 UI 和交互。
 * 3) 依赖你已经有下面这些类：
 *    - RedPacketRepository
 *    - RedPacketInfo
 *    - ClaimPrepareResponse
 *    - RedPacketContractService
 *    - WalletKeyStore
 *
 * 建议路径：
 * TMessagesProj/src/main/java/org/telegram/wallet/ui/OpenRedPacketBottomSheet.java
 */
public class OpenRedPacketBottomSheet extends BottomSheet {

    private final BaseFragment parentFragment;
    private final String packetId;

    private FrameLayout rootLayout;
    private LinearLayout contentLayout;
    private FrameLayout loadingOverlay;
    private ProgressBar loadingProgress;
    private TextView loadingTextView;

    private TextView titleView;
    private TextView statusView;
    private TextView summaryView;

    private TextView packetIdView;
    private TextView creatorView;
    private TextView amountView;
    private TextView perClaimView;
    private TextView remainingView;
    private TextView expireView;
    private TextView walletView;
    private TextView errorView;

    private TextView claimButton;
    private TextView refundButton;
    private TextView closeButton;

    private volatile boolean loadingInfo;
    private volatile boolean submitting;
    private RedPacketInfo currentInfo;

    public OpenRedPacketBottomSheet(BaseFragment parentFragment, String packetId) {
        super(parentFragment.getParentActivity(), false, parentFragment.getResourceProvider());

        this.parentFragment = parentFragment;
        this.packetId = packetId;
        this.currentAccount = parentFragment.getCurrentAccount();

        fixNavigationBar();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);

        buildLayout(getContext());
        loadPacketInfo();
    }

    private void buildLayout(Context context) {
        rootLayout = new FrameLayout(context);
        rootLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        nestedScrollChild = scrollView;

        contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        contentLayout.setPadding(
                AndroidUtilities.dp(20),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(20),
                AndroidUtilities.dp(20)
        );

        scrollView.addView(
                contentLayout,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        rootLayout.addView(
                scrollView,
                LayoutHelper.createFrame(
                        LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT,
                        Gravity.LEFT | Gravity.TOP
                )
        );

        // containerView = rootLayout;
        setCustomView(rootLayout);

        titleView = createText(context, 22, Theme.key_windowBackgroundWhiteBlackText, Typeface.DEFAULT_BOLD);
        titleView.setText("🎁 红包");
        contentLayout.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        statusView = createText(context, 15, Theme.key_featuredStickers_addButton, Typeface.DEFAULT_BOLD);
        statusView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        statusView.setText("正在加载红包信息…");
        contentLayout.addView(statusView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        summaryView = createText(context, 15, Theme.key_windowBackgroundWhiteGrayText2, Typeface.DEFAULT);
        summaryView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        summaryView.setText("");
        contentLayout.addView(summaryView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider1 = new View(context);
        divider1.setBackgroundColor(adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.18f));
        contentLayout.addView(divider1, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 1, 0, 16, 0, 16));

        packetIdView = createInfoText(context);
        creatorView = createInfoText(context);
        amountView = createInfoText(context);
        perClaimView = createInfoText(context);
        remainingView = createInfoText(context);
        expireView = createInfoText(context);
        walletView = createInfoText(context);

        contentLayout.addView(packetIdView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        contentLayout.addView(creatorView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        contentLayout.addView(amountView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        contentLayout.addView(perClaimView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        contentLayout.addView(remainingView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        contentLayout.addView(expireView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        contentLayout.addView(walletView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        errorView = createText(context, 14, Theme.key_text_RedRegular, Typeface.DEFAULT);
        errorView.setPadding(0, AndroidUtilities.dp(14), 0, 0);
        errorView.setVisibility(View.GONE);
        contentLayout.addView(errorView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        View divider2 = new View(context);
        divider2.setBackgroundColor(adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.18f));
        contentLayout.addView(divider2, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 1, 0, 18, 0, 18));

        claimButton = createActionButton(context, "打开红包",
                getThemedColor(Theme.key_featuredStickers_addButton),
                getThemedColor(Theme.key_featuredStickers_buttonText));
        claimButton.setOnClickListener(v -> onClickClaim());
        contentLayout.addView(claimButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        refundButton = createActionButton(context, "退回未领取金额",
                getThemedColor(Theme.key_text_RedRegular),
                Color.WHITE);
        refundButton.setOnClickListener(v -> onClickRefund());
        refundButton.setVisibility(View.GONE);
        contentLayout.addView(refundButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));

        closeButton = createActionButton(context, "关闭",
                adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.16f),
                getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        closeButton.setOnClickListener(v -> dismiss());
        contentLayout.addView(closeButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 12, 0, 0));

        loadingOverlay = new FrameLayout(context);
        loadingOverlay.setBackgroundColor(adjustAlpha(Color.BLACK, 0.12f));
        loadingOverlay.setVisibility(View.GONE);

        LinearLayout overlayContent = new LinearLayout(context);
        overlayContent.setOrientation(LinearLayout.VERTICAL);
        overlayContent.setGravity(Gravity.CENTER_HORIZONTAL);
        overlayContent.setPadding(
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(18)
        );

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getThemedColor(Theme.key_dialogBackground));
        bg.setCornerRadius(AndroidUtilities.dp(14));
        overlayContent.setBackground(bg);

        loadingProgress = new ProgressBar(context);
        overlayContent.addView(loadingProgress, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        loadingTextView = createText(context, 14, Theme.key_windowBackgroundWhiteBlackText, Typeface.DEFAULT);
        loadingTextView.setGravity(Gravity.CENTER_HORIZONTAL);
        loadingTextView.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        loadingTextView.setText("处理中…");
        overlayContent.addView(loadingTextView, LayoutHelper.createLinear(
                LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        loadingOverlay.addView(
                overlayContent,
                LayoutHelper.createFrame(
                        LayoutHelper.WRAP_CONTENT,
                        LayoutHelper.WRAP_CONTENT,
                        Gravity.CENTER
                )
        );

        rootLayout.addView(
                loadingOverlay,
                LayoutHelper.createFrame(
                        LayoutHelper.MATCH_PARENT,
                        LayoutHelper.MATCH_PARENT
                )
        );
    }

    private void loadPacketInfo() {
        if (loadingInfo || submitting) {
            return;
        }
        loadingInfo = true;
        setLoading(true, "正在加载红包信息…");
        errorView.setVisibility(View.GONE);

        Utilities.globalQueue.postRunnable(() -> {
            try {
                String localWallet = getLocalWalletAddressSafely();
                RedPacketInfo info = RedPacketRepository.getInstance().getPacket(packetId, localWallet);

                AndroidUtilities.runOnUIThread(() -> {
                    loadingInfo = false;
                    setLoading(false, null);
                    currentInfo = info;
                    bindInfo(info, localWallet);
                });
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> {
                    loadingInfo = false;
                    setLoading(false, null);
                    showError("加载红包失败：" + nonNullMessage(t));
                    bindEmptyState();
                });
            }
        });
    }

    private void bindEmptyState() {
        titleView.setText("🎁 红包");
        statusView.setText("红包信息不可用");
        statusView.setTextColor(getThemedColor(Theme.key_text_RedRegular));
        summaryView.setText("");

        packetIdView.setText("红包ID： " + safe(packetId));
        creatorView.setText("发送人： -");
        amountView.setText("总额： -");
        perClaimView.setText("单份： -");
        remainingView.setText("剩余： -");
        expireView.setText("有效期： -");
        walletView.setText("本地钱包： -");

        claimButton.setEnabled(false);
        refundButton.setVisibility(View.GONE);
    }

    private void bindInfo(RedPacketInfo info, String localWalletAddress) {
        if (info == null) {
            bindEmptyState();
            return;
        }

        String symbol = TextUtils.isEmpty(info.tokenSymbol) ? "BNB" : info.tokenSymbol;
        titleView.setText("🎁 " + symbol + " 红包");
        packetIdView.setText("红包ID： " + safe(info.packetId));
        creatorView.setText("发送人： " + safeShortAddress(info.creatorWallet));
        amountView.setText("总额： " + safeAmount(info.totalAmountDisplay, symbol));
        perClaimView.setText("单份： " + safeAmount(info.amountPerClaimDisplay, symbol));
        remainingView.setText("剩余： " + info.remainingCount + " / " + info.totalCount + " 份");
        expireView.setText("有效期： " + formatTime(info.expiresAt));
        walletView.setText("本地钱包： " + (TextUtils.isEmpty(localWalletAddress) ? "未检测到" : safeShortAddress(localWalletAddress)));

        summaryView.setText(buildSummary(info, symbol));
        statusView.setText(resolveStatusText(info));
        statusView.setTextColor(resolveStatusColor(info));

        claimButton.setEnabled(false);
        claimButton.setAlpha(0.5f);

        if (TextUtils.isEmpty(localWalletAddress)) {
            claimButton.setText("请先导入钱包");
            claimButton.setEnabled(true);
            claimButton.setAlpha(1f);
        } else if (info.refunded) {
            claimButton.setText("已退回");
        } else if (info.hasClaimed) {
            claimButton.setText("已领取");
        } else if (info.expired) {
            claimButton.setText("已过期");
        } else if (info.remainingCount <= 0) {
            claimButton.setText("已领完");
        } else if (info.canClaim) {
            claimButton.setText("打开红包");
            claimButton.setEnabled(true);
            claimButton.setAlpha(1f);
        } else {
            claimButton.setText("当前不可领取");
        }

        if (info.canRefund) {
            refundButton.setVisibility(View.VISIBLE);
            refundButton.setEnabled(true);
            refundButton.setAlpha(1f);
        } else {
            refundButton.setVisibility(View.GONE);
        }
    }

    private void onClickClaim() {
        if (submitting || loadingInfo) {
            return;
        }
        if (currentInfo == null) {
            loadPacketInfo();
            return;
        }

        String privateKeyHex;
        try {
            privateKeyHex = WalletKeyStore.loadPrivateKey(getContext());
        } catch (Throwable t) {
            FileLog.e(t);
            showError("读取本地钱包失败：" + nonNullMessage(t));
            return;
        }

        if (TextUtils.isEmpty(privateKeyHex)) {
            showToast("无钱包：请先创建或导入钱包");
            return;
        }

        if (currentInfo.refunded) {
            showToast("这个红包已经退回");
            return;
        }
        if (currentInfo.hasClaimed) {
            showToast("已领取：你已经领过这个红包");
            return;
        }
        if (currentInfo.expired) {
            showToast("已过期：这个红包已经过期");
            return;
        }
        if (currentInfo.remainingCount <= 0) {
            showToast("已抢完：红包已领完");
            return;
        }
        if (!currentInfo.canClaim) {
            showToast("当前账号暂时不能领取这个红包");
            return;
        }

        final String walletAddress;
        try {
            walletAddress = Credentials.create(privateKeyHex).getAddress();
        } catch (Throwable t) {
            FileLog.e(t);
            showError("钱包私钥格式错误");
            return;
        }

        submitting = true;
        setLoading(true, "正在领取红包…");
        errorView.setVisibility(View.GONE);

        Utilities.globalQueue.postRunnable(() -> {
            try {
                ClaimPrepareResponse prepare = RedPacketRepository.getInstance()
                        .prepareClaim(packetId, walletAddress);

                String contractAddress = firstNonEmpty(
                        prepare.contractAddress,
                        currentInfo.contractAddress,
                        WalletConfig.RED_PACKET_CONTRACT
                );

                String finalPacketId = firstNonEmpty(prepare.packetIdHex, packetId);
                RedPacketContractService contractService = new RedPacketContractService();
                String txHash = contractService.claim(
                        privateKeyHex,
                        contractAddress,
                        finalPacketId,
                        prepare.signatureHex
                );
                TransactionReceipt receipt = contractService.waitForReceipt(txHash);
                if (receipt == null || !"0x1".equalsIgnoreCase(receipt.getStatus())) {
                    throw new IllegalStateException("交易失败：链上执行未成功");
                }

                RedPacketRepository.getInstance().confirmClaim(packetId, walletAddress, txHash);
                final String claimedDisplay = firstNonEmpty(
                        currentInfo.amountPerClaimDisplay,
                        currentInfo.totalAmountDisplay,
                        "-"
                );
                final String symbol = firstNonEmpty(currentInfo.tokenSymbol, "BNB");

                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    setLoading(false, null);
                    showToast("领取成功：" + claimedDisplay + " " + symbol);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(
                            NotificationCenter.updateInterfaces,
                            MessagesController.UPDATE_MASK_MESSAGE_TEXT
                    );
                    loadPacketInfo();
                });
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    setLoading(false, null);
                    showError(resolveClaimErrorMessage(t));
                });
            }
        });
    }

    private void onClickRefund() {
        if (submitting || loadingInfo || currentInfo == null) {
            return;
        }

        String privateKeyHex;
        try {
            privateKeyHex = WalletKeyStore.loadPrivateKey(getContext());
        } catch (Throwable t) {
            FileLog.e(t);
            showError("读取本地钱包失败：" + nonNullMessage(t));
            return;
        }

        if (TextUtils.isEmpty(privateKeyHex)) {
            showToast("请先创建或导入钱包");
            return;
        }

        if (!currentInfo.canRefund) {
            showToast("当前不能退回这个红包");
            return;
        }

        submitting = true;
        setLoading(true, "正在退回红包…");
        errorView.setVisibility(View.GONE);

        Utilities.globalQueue.postRunnable(() -> {
            try {
                String contractAddress = firstNonEmpty(
                        currentInfo.contractAddress,
                        WalletConfig.RED_PACKET_CONTRACT
                );
                String finalPacketId = firstNonEmpty(currentInfo.packetIdHex, packetId);
                String txHash = new RedPacketContractService().refund(
                        privateKeyHex,
                        contractAddress,
                        finalPacketId
                );

                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    setLoading(false, null);
                    showToast("退回成功：" + safeShortHash(txHash));
                    loadPacketInfo();
                });
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    setLoading(false, null);
                    showError("退回失败：" + nonNullMessage(t));
                });
            }
        });
    }

    private void setLoading(boolean show, String message) {
        if (loadingOverlay == null) {
            return;
        }
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        loadingTextView.setText(TextUtils.isEmpty(message) ? "处理中…" : message);

        setViewEnabled(claimButton, !show);
        setViewEnabled(refundButton, !show);
        setViewEnabled(closeButton, !show);
    }

    private void showError(String msg) {
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(msg);
    }

    private void showToast(String msg) {
        Context context = getContext();
        if (context == null || TextUtils.isEmpty(msg)) {
            return;
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    private TextView createInfoText(Context context) {
        TextView tv = createText(context, 15, Theme.key_windowBackgroundWhiteBlackText, Typeface.DEFAULT);
        tv.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        return tv;
    }

    private TextView createText(Context context, int textSizeSp, int colorKey, Typeface typeface) {
        TextView tv = new TextView(context);
        tv.setTextSize(textSizeSp);
        tv.setTextColor(getThemedColor(colorKey));
        tv.setTypeface(typeface);
        return tv;
    }

    private TextView createActionButton(Context context, String text, int bgColor, int textColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(textColor);
        tv.setBackground(createRoundedButtonBackground(bgColor));
        tv.setMinHeight(AndroidUtilities.dp(52));
        tv.setPadding(
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(14),
                AndroidUtilities.dp(18),
                AndroidUtilities.dp(14)
        );
        return tv;
    }

    private RippleDrawable createRoundedButtonBackground(int bgColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(bgColor);
        content.setCornerRadius(AndroidUtilities.dp(16));
        return new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                content,
                null
        );
    }

    private void setViewEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.6f);
    }

    private String buildSummary(RedPacketInfo info, String symbol) {
        String total = safeAmount(info.totalAmountDisplay, symbol);
        return total + " · " + info.totalCount + " 份";
    }

    private int resolveStatusColor(RedPacketInfo info) {
        if (info == null) {
            return getThemedColor(Theme.key_text_RedRegular);
        }
        if (info.refunded || info.expired) {
            return getThemedColor(Theme.key_text_RedRegular);
        }
        if (info.hasClaimed || info.canClaim) {
            return getThemedColor(Theme.key_featuredStickers_addButton);
        }
        return getThemedColor(Theme.key_windowBackgroundWhiteGrayText2);
    }

    private String resolveStatusText(RedPacketInfo info) {
        if (info == null) {
            return "红包信息不可用";
        }
        if (info.refunded) {
            return "这个红包已退回";
        }
        if (info.hasClaimed) {
            return "你已经领取过这个红包";
        }
        if (info.expired) {
            return "这个红包已过期";
        }
        if (info.remainingCount <= 0) {
            return "这个红包已领完";
        }
        if (info.canClaim) {
            return "现在可以领取";
        }
        return "当前暂不可领取";
    }

    private String getLocalWalletAddressSafely() {
        try {
            String pk = WalletKeyStore.loadPrivateKey(getContext());
            if (TextUtils.isEmpty(pk)) {
                return null;
            }
            return Credentials.create(pk).getAddress();
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private String formatTime(long epoch) {
        if (epoch <= 0) {
            return "-";
        }
        long millis = epoch < 10_000_000_000L ? epoch * 1000L : epoch;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(millis));
    }

    private String safeShortAddress(String value) {
        if (TextUtils.isEmpty(value)) {
            return "-";
        }
        if (value.length() <= 12) {
            return value;
        }
        return value.substring(0, 6) + "..." + value.substring(value.length() - 4);
    }

    private String safeShortHash(String txHash) {
        if (TextUtils.isEmpty(txHash)) {
            return "-";
        }
        if (txHash.length() <= 14) {
            return txHash;
        }
        return txHash.substring(0, 8) + "..." + txHash.substring(txHash.length() - 6);
    }

    private String safeAmount(String value, String symbol) {
        if (TextUtils.isEmpty(value)) {
            return "-";
        }
        return value + " " + symbol;
    }

    private String safe(String value) {
        return TextUtils.isEmpty(value) ? "-" : value;
    }

    private String nonNullMessage(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        if (!TextUtils.isEmpty(t.getMessage())) {
            return t.getMessage();
        }
        return t.getClass().getSimpleName();
    }

    private String resolveClaimErrorMessage(Throwable t) {
        String raw = nonNullMessage(t);
        String lower = raw == null ? "" : raw.toLowerCase(Locale.US);

        if (containsAny(lower, "no wallet", "wallet is empty", "private key is empty")) {
            return "无钱包：请先创建或导入钱包";
        }
        if (containsAny(lower, "insufficient funds", "intrinsic gas too low", "out of gas")) {
            return "gas 不足：请补充 BNB 后重试";
        }
        if (containsAny(lower, "already claimed", "already", "claimed")) {
            return "已领取：你已经领取过该红包";
        }
        if (containsAny(lower, "sold out", "empty", "no remaining", "remaining=0")) {
            return "已抢完：红包已被抢完";
        }
        if (containsAny(lower, "expired")) {
            return "已过期：红包已过期";
        }
        if (containsAny(lower, "timed out while waiting for receipt", "链上执行未成功", "execution reverted", "transaction failed")) {
            return "交易失败：" + raw;
        }
        if (containsAny(lower, "http ", "unable to resolve host", "failed to connect", "timeout", "network")) {
            return "网络失败：" + raw;
        }
        return "领取失败：" + raw;
    }

    private boolean containsAny(String source, String... words) {
        if (TextUtils.isEmpty(source) || words == null) {
            return false;
        }
        for (String word : words) {
            if (!TextUtils.isEmpty(word) && source.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (!TextUtils.isEmpty(v)) {
                return v;
            }
        }
        return null;
    }

    private int adjustAlpha(int color, float factor) {
        int alpha = Math.round(Color.alpha(color) * factor);
        return (color & 0x00ffffff) | (alpha << 24);
    }
}
