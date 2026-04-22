package org.telegram.wallet.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.wallet.chain.Bep20Service;
import org.telegram.wallet.chain.BscRpcClient;
import org.telegram.wallet.chain.RedPacketContractService;
import org.telegram.wallet.config.WalletConfig;
import org.telegram.wallet.data.WalletStorage;
import org.telegram.wallet.model.CreateRedPacketPrepareResponse;
import org.telegram.wallet.model.TokenAsset;
import org.telegram.wallet.redpacket.RedPacketMessageComposer;
import org.telegram.wallet.redpacket.RedPacketRepository;
import org.telegram.wallet.security.WalletKeyStore;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class CreateRedPacketBottomSheet extends BottomSheet {

    private final BaseFragment parentFragment;
    private final int account;
    private final long dialogId;

    private FrameLayout rootLayout;
    private LinearLayout contentLayout;
    private FrameLayout loadingOverlay;
    private TextView loadingTextView;

    private TextView tokenSelectorView;
    private EditTextBoldCursor totalAmountEdit;
    private EditTextBoldCursor countEdit;
    private EditTextBoldCursor greetingEdit;
    private EditTextBoldCursor expiresAtEdit;
    private TextView errorView;
    private TextView createButton;
    private TextView cancelButton;

    private final List<TokenOption> tokenOptions = new ArrayList<>();
    private int selectedTokenIndex = 0;

    private volatile boolean submitting;

    public CreateRedPacketBottomSheet(BaseFragment parentFragment, int account, long dialogId) {
        super(parentFragment.getParentActivity(), false, parentFragment.getResourceProvider());
        this.parentFragment = parentFragment;
        this.account = account;
        this.dialogId = dialogId;

        fixNavigationBar();
        setApplyTopPadding(false);
        setApplyBottomPadding(false);
        setCanDismissWithSwipe(false);

        buildLayout(getContext());
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

        setCustomView(rootLayout);

        TextView titleView = createText(context, 22, Theme.key_windowBackgroundWhiteBlackText, Typeface.DEFAULT_BOLD);
        titleView.setText("发红包");
        contentLayout.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView subtitleView = createText(context, 14, Theme.key_windowBackgroundWhiteGrayText2, Typeface.DEFAULT);
        subtitleView.setPadding(0, AndroidUtilities.dp(8), 0, 0);
        subtitleView.setText("先创建链上红包，再自动把红包兼容消息发到当前聊天");
        contentLayout.addView(subtitleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addFieldLabel(context, "Token");
        tokenSelectorView = createActionButton(
                context,
                "",
                adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.12f),
                getThemedColor(Theme.key_windowBackgroundWhiteBlackText)
        );
        tokenSelectorView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        tokenSelectorView.setOnClickListener(v -> showTokenSelectorDialog());
        contentLayout.addView(tokenSelectorView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        addFieldLabel(context, "total amount");
        totalAmountEdit = createInput(context, "例如 0.05");
        totalAmountEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        totalAmountEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        contentLayout.addView(totalAmountEdit, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        addFieldLabel(context, "count");
        countEdit = createInput(context, "例如 5");
        countEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        countEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        contentLayout.addView(countEdit, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        addFieldLabel(context, "greeting");
        greetingEdit = createInput(context, "恭喜发财，大吉大利");
        greetingEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        greetingEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        contentLayout.addView(greetingEdit, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        addFieldLabel(context, "expiresAt（Unix 秒）");
        expiresAtEdit = createInput(context, "例如 1767225600");
        expiresAtEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        expiresAtEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        contentLayout.addView(expiresAtEdit, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48));

        TextView hintView = createText(context, 13, Theme.key_windowBackgroundWhiteGrayText2, Typeface.DEFAULT);
        hintView.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        hintView.setText("packetType 第一版固定 equal，要求 amountRaw % count == 0。");
        contentLayout.addView(hintView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        errorView = createText(context, 14, Theme.key_text_RedRegular, Typeface.DEFAULT);
        errorView.setPadding(0, AndroidUtilities.dp(14), 0, 0);
        errorView.setVisibility(View.GONE);
        contentLayout.addView(errorView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        createButton = createActionButton(
                context,
                "创建并发送",
                getThemedColor(Theme.key_featuredStickers_addButton),
                getThemedColor(Theme.key_featuredStickers_buttonText)
        );
        createButton.setOnClickListener(v -> onClickCreate());
        contentLayout.addView(createButton, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, 48, 0, 18, 0, 0));

        cancelButton = createActionButton(
                context,
                "取消",
                adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.16f),
                getThemedColor(Theme.key_windowBackgroundWhiteBlackText)
        );
        cancelButton.setOnClickListener(v -> dismiss());
        contentLayout.addView(cancelButton, LayoutHelper.createLinear(
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

        ProgressBar progressBar = new ProgressBar(context);
        overlayContent.addView(progressBar, LayoutHelper.createLinear(
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

        reloadTokenOptions();
    }

    private void addFieldLabel(Context context, String text) {
        TextView label = createText(context, 15, Theme.key_windowBackgroundWhiteBlackText, Typeface.DEFAULT_BOLD);
        label.setPadding(0, AndroidUtilities.dp(16), 0, AndroidUtilities.dp(8));
        label.setText(text);
        contentLayout.addView(label, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    @Override
    public void show() {
        reloadTokenOptions();
        super.show();

        AndroidUtilities.runOnUIThread(() -> {
            try {
                Window window = getWindow();
                if (window != null) {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
                    window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                    window.setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    );
                }
            } catch (Throwable ignore) {
            }

            focusInput(totalAmountEdit);
        }, 80);
    }

    private EditTextBoldCursor createInput(Context context, String hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(16);
        editText.setSingleLine(true);
        editText.setHint(hint);
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2));
        editText.setBackground(createInputBackground());
        editText.setPadding(
                AndroidUtilities.dp(14),
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(14),
                AndroidUtilities.dp(12)
        );

        editText.setFocusable(true);
        editText.setFocusableInTouchMode(true);
        editText.setClickable(true);
        editText.setLongClickable(true);
        editText.setTextIsSelectable(true);

        editText.setCursorColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorWidth(1.5f);
        editText.setCursorSize(AndroidUtilities.dp(20));

        editText.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN
                    || event.getAction() == MotionEvent.ACTION_UP) {
                focusInput((EditTextBoldCursor) v);
            }
            return false;
        });

        editText.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                AndroidUtilities.runOnUIThread(() ->
                        AndroidUtilities.showKeyboard((EditTextBoldCursor) v), 60);
            }
        });

        return editText;
    }

    private void focusInput(EditTextBoldCursor editText) {
        if (editText == null) {
            return;
        }
        editText.setFocusableInTouchMode(true);
        editText.requestFocus();
        editText.requestFocusFromTouch();

        AndroidUtilities.runOnUIThread(() -> {
            try {
                AndroidUtilities.showKeyboard(editText);
            } catch (Throwable ignore) {
            }
        }, 60);
    }

    private GradientDrawable createInputBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(adjustAlpha(getThemedColor(Theme.key_windowBackgroundWhiteGrayText2), 0.08f));
        drawable.setCornerRadius(AndroidUtilities.dp(12));
        return drawable;
    }

    private void onClickCreate() {
        if (submitting) {
            return;
        }
        errorView.setVisibility(View.GONE);

        if (!WalletConfig.isWalletSupportedOnThisDevice()) {
            showError("当前设备暂不支持钱包功能");
            return;
        }

        final TokenOption selectedToken = getSelectedToken();
        final String totalText = trim(totalAmountEdit.getText() == null ? null : totalAmountEdit.getText().toString());
        final String countText = trim(countEdit.getText() == null ? null : countEdit.getText().toString());
        final String greeting = trim(greetingEdit.getText() == null ? null : greetingEdit.getText().toString());
        final String expiresAtText = trim(expiresAtEdit.getText() == null ? null : expiresAtEdit.getText().toString());

        if (TextUtils.isEmpty(totalText)) {
            showError("请填写 total amount");
            return;
        }
        if (TextUtils.isEmpty(countText)) {
            showError("请填写 count");
            return;
        }
        if (TextUtils.isEmpty(expiresAtText)) {
            showError("请填写 expiresAt");
            return;
        }

        final BigDecimal totalAmount;
        final int count;
        final long expiresAtSeconds;
        try {
            totalAmount = new BigDecimal(totalText);
        } catch (Throwable t) {
            showError("total amount 格式不正确");
            return;
        }

        try {
            count = Integer.parseInt(countText);
        } catch (Throwable t) {
            showError("count 格式不正确");
            return;
        }

        try {
            expiresAtSeconds = Long.parseLong(expiresAtText);
        } catch (Throwable t) {
            showError("expiresAt 格式不正确");
            return;
        }

        if (totalAmount.signum() <= 0) {
            showError("红包总额必须大于 0");
            return;
        }
        if (count < 1 || count > 500) {
            showError("count 必须在 1~500");
            return;
        }
        if (expiresAtSeconds <= (System.currentTimeMillis() / 1000L)) {
            showError("expiresAt 必须大于当前时间");
            return;
        }

        final BigInteger amountRaw;
        try {
            amountRaw = toRawAmount(totalAmount, selectedToken.decimals);
        } catch (Throwable t) {
            showError("金额换算失败");
            return;
        }

        if (amountRaw.signum() <= 0) {
            showError("amountRaw 必须大于 0");
            return;
        }

        final BigInteger[] divRem = amountRaw.divideAndRemainder(BigInteger.valueOf(count));
        if (divRem[1].signum() != 0) {
            showError("equal 红包要求 amountRaw % count == 0");
            return;
        }
        final BigInteger amountPerClaimRaw = divRem[0];
        if (amountPerClaimRaw.signum() <= 0) {
            showError("单份金额必须大于 0");
            return;
        }

        final String privateKeyHex;
        try {
            privateKeyHex = WalletKeyStore.loadPrivateKey(getContext());
        } catch (Throwable t) {
            FileLog.e(t);
            showError("读取本地钱包失败：" + nonNullMessage(t));
            return;
        }

        if (TextUtils.isEmpty(privateKeyHex)) {
            showError("请先创建或导入钱包");
            return;
        }

        final String creatorWallet;
        try {
            creatorWallet = Credentials.create(privateKeyHex).getAddress();
        } catch (Throwable t) {
            FileLog.e(t);
            showError("本地钱包私钥格式错误");
            return;
        }

        submitting = true;
        setLoading(true, "正在创建红包…");

        Utilities.globalQueue.postRunnable(() -> {
            try {
                RedPacketContractService contractService = new RedPacketContractService();
                BigInteger gasFeeWei = contractService.estimateCreateGasFeeWei();
                BigInteger bnbBalanceWei = BscRpcClient.get()
                        .ethGetBalance(creatorWallet, DefaultBlockParameterName.LATEST)
                        .send()
                        .getBalance();

                if (selectedToken.isBnb()) {
                    BigInteger required = amountRaw.add(gasFeeWei);
                    if (bnbBalanceWei.compareTo(required) < 0) {
                        throw new IllegalStateException("BNB 余额不足，需覆盖 amount + gas");
                    }
                } else {
                    BigInteger tokenBalanceRaw = new Bep20Service().getBalanceRaw(creatorWallet, selectedToken.contractAddress);
                    if (tokenBalanceRaw.compareTo(amountRaw) < 0) {
                        throw new IllegalStateException(selectedToken.symbol + " 余额不足");
                    }
                    if (bnbBalanceWei.compareTo(gasFeeWei) < 0) {
                        throw new IllegalStateException("BNB 余额不足，无法支付 gas");
                    }
                }

                CreateRedPacketPrepareResponse prepare = RedPacketRepository.getInstance().prepareCreate(
                        dialogId,
                        creatorWallet,
                        selectedToken.symbol,
                        selectedToken.contractAddress,
                        selectedToken.decimals,
                        "equal",
                        greeting,
                        amountRaw,
                        count,
                        expiresAtSeconds
                );

                if (TextUtils.isEmpty(prepare.packetIdHex)) {
                    throw new IllegalStateException("prepareCreate must return packetIdHex");
                }
                String packetIdHex = prepare.packetIdHex;
                String contractAddress = firstNonEmpty(
                        prepare.contractAddress,
                        WalletConfig.RED_PACKET_CONTRACT
                );

                String txHash = contractService.create(
                        privateKeyHex,
                        contractAddress,
                        packetIdHex,
                        count,
                        amountPerClaimRaw,
                        expiresAtSeconds,
                        selectedToken.isBnb() ? null : selectedToken.contractAddress
                );

                RedPacketRepository.getInstance().confirmCreate(
                        prepare.packetId,
                        creatorWallet,
                        txHash
                );

                final String finalMessage = RedPacketMessageComposer.composeCompatMessage(
                        prepare.packetId,
                        selectedToken.symbol,
                        totalAmount.stripTrailingZeros().toPlainString(),
                        count,
                        expiresAtSeconds,
                        firstNonEmpty(
                                prepare.claimUrl,
                                "https://" + WalletConfig.RED_PACKET_HOST + "/p/" + prepare.packetId
                        ),
                        greeting,
                        "equal"
                );

                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        SendMessagesHelper.SendMessageParams params =
                                SendMessagesHelper.SendMessageParams.of(finalMessage, dialogId);
                        SendMessagesHelper.getInstance(account).sendMessage(params);

                        showToast("红包已创建并发送");
                        dismiss();
                    } catch (Throwable sendError) {
                        FileLog.e(sendError);
                        showError("链上红包已创建，但发送聊天消息失败：" + nonNullMessage(sendError));
                    } finally {
                        submitting = false;
                        setLoading(false, null);
                    }
                });
            } catch (Throwable t) {
                FileLog.e(t);
                AndroidUtilities.runOnUIThread(() -> {
                    submitting = false;
                    setLoading(false, null);
                    showError("创建失败：" + nonNullMessage(t));
                });
            }
        });
    }

    private BigInteger toRawAmount(BigDecimal amount, int decimals) {
        return amount.movePointRight(Math.max(decimals, 0)).toBigIntegerExact();
    }

    private void setLoading(boolean show, String message) {
        loadingOverlay.setVisibility(show ? View.VISIBLE : View.GONE);
        loadingTextView.setText(TextUtils.isEmpty(message) ? "处理中…" : message);

        setViewEnabled(createButton, !show);
        setViewEnabled(cancelButton, !show);
        setViewEnabled(tokenSelectorView, !show);
        setViewEnabled(totalAmountEdit, !show);
        setViewEnabled(countEdit, !show);
        setViewEnabled(greetingEdit, !show);
        setViewEnabled(expiresAtEdit, !show);
    }

    private void setViewEnabled(View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.6f);
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
        tv.setPadding(
                AndroidUtilities.dp(16),
                AndroidUtilities.dp(12),
                AndroidUtilities.dp(16),
                AndroidUtilities.dp(12)
        );
        return tv;
    }

    private RippleDrawable createRoundedButtonBackground(int bgColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(bgColor);
        content.setCornerRadius(AndroidUtilities.dp(12));
        return new RippleDrawable(
                ColorStateList.valueOf(0x22000000),
                content,
                null
        );
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
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

    private void reloadTokenOptions() {
        tokenOptions.clear();
        tokenOptions.add(TokenOption.bnb());

        Context context = getContext();
        if (context != null) {
            List<TokenAsset> customTokens = WalletStorage.getTokens(context);
            for (TokenAsset token : customTokens) {
                if (token == null || TextUtils.isEmpty(token.contractAddress)) {
                    continue;
                }
                tokenOptions.add(TokenOption.custom(token.symbol, token.contractAddress, token.decimals));
            }
        }

        if (selectedTokenIndex >= tokenOptions.size()) {
            selectedTokenIndex = 0;
        }
        updateTokenSelectorText();
    }

    private TokenOption getSelectedToken() {
        if (tokenOptions.isEmpty()) {
            return TokenOption.bnb();
        }
        int index = Math.max(0, Math.min(selectedTokenIndex, tokenOptions.size() - 1));
        return tokenOptions.get(index);
    }

    private void updateTokenSelectorText() {
        if (tokenSelectorView == null) {
            return;
        }
        TokenOption token = getSelectedToken();
        tokenSelectorView.setText(token.symbol + "  ·  " + (token.isBnb() ? "原生币" : shortAddress(token.contractAddress)));
    }

    private void showTokenSelectorDialog() {
        Context context = getContext();
        if (context == null || tokenOptions.isEmpty()) {
            return;
        }

        String[] labels = new String[tokenOptions.size()];
        for (int i = 0; i < tokenOptions.size(); i++) {
            TokenOption token = tokenOptions.get(i);
            labels[i] = token.symbol + (token.isBnb() ? " (BNB)" : " (" + shortAddress(token.contractAddress) + ")");
        }

        new AlertDialog.Builder(context)
                .setTitle("选择 Token")
                .setSingleChoiceItems(labels, selectedTokenIndex, (dialog, which) -> selectedTokenIndex = which)
                .setPositiveButton("确定", (dialog, which) -> updateTokenSelectorText())
                .setNegativeButton("取消", null)
                .show();
    }

    private String shortAddress(String address) {
        if (TextUtils.isEmpty(address) || address.length() < 10) {
            return address == null ? "" : address;
        }
        return address.substring(0, 6) + "..." + address.substring(address.length() - 4);
    }

    private static class TokenOption {
        final String symbol;
        final String contractAddress;
        final int decimals;

        private TokenOption(String symbol, String contractAddress, int decimals) {
            this.symbol = TextUtils.isEmpty(symbol) ? "TOKEN" : symbol;
            this.contractAddress = contractAddress;
            this.decimals = decimals <= 0 ? 18 : decimals;
        }

        static TokenOption bnb() {
            return new TokenOption("BNB", "", 18);
        }

        static TokenOption custom(String symbol, String contractAddress, int decimals) {
            return new TokenOption(symbol, contractAddress, decimals);
        }

        boolean isBnb() {
            return TextUtils.isEmpty(contractAddress);
        }
    }
}
