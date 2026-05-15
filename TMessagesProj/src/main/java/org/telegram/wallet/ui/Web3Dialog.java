package org.telegram.wallet.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.R;

/**
 * Product-style modal components for the wallet module.
 * Keeps wallet dialogs visually consistent with the rest of the Web3 UI instead of
 * relying on the platform AlertDialog form style.
 */
public final class Web3Dialog {

    public interface Action {
        /** Return true to close the dialog, false to keep it open for validation errors. */
        boolean onClick(Dialog dialog);
    }

    private Web3Dialog() {
    }

    public static Dialog show(
            Context context,
            String title,
            CharSequence subtitle,
            int iconType,
            View content,
            String positiveText,
            Action positiveAction,
            String negativeText,
            Action negativeAction
    ) {
        final Web3Ui.Palette p = Web3Ui.palette();
        final Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout host = new FrameLayout(context);
        host.setPadding(dp(context, 20), 0, dp(context, 20), 0);

        MaxHeightScrollView scrollView = new MaxHeightScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scrollView.setMaxHeight(context.getResources().getDisplayMetrics().heightPixels - dp(context, 88));

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 18));
        panel.setBackground(Web3Ui.rounded(context, p.cardBg, 22));
        Web3Ui.setElevation(panel, 18);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (iconType != 0) {
            int iconBg = p.dark ? 0x24252F3A : 0xFFFFF2DF;
            FrameLayout icon = iconType == Web3IconView.WALLET
                    ? Web3Ui.iconCircleDrawable(context, R.drawable.icon_wallet_6_1, iconBg, 42)
                    : Web3Ui.iconCircle(context, iconType, p.orange, iconBg, 42);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 42), dp(context, 42));
            iconLp.rightMargin = dp(context, 12);
            header.addView(icon, iconLp);
        }
        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = Web3Ui.text(context, title, 20, p.primaryText, true);
        titleView.setIncludeFontPadding(false);
        titleBox.addView(titleView, Web3Ui.matchWrap());
        if (!TextUtils.isEmpty(subtitle)) {
            TextView subTitleView = Web3Ui.text(context, subtitle.toString(), 13, p.secondaryText, false);
            subTitleView.setLineSpacing(dp(context, 2), 1.0f);
            LinearLayout.LayoutParams subLp = Web3Ui.matchWrap();
            subLp.topMargin = dp(context, 5);
            titleBox.addView(subTitleView, subLp);
        }
        header.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(header, Web3Ui.matchWrap());

        if (content != null) {
            LinearLayout.LayoutParams contentLp = Web3Ui.matchWrap();
            contentLp.topMargin = dp(context, 18);
            panel.addView(content, contentLp);
        }

        if (!TextUtils.isEmpty(positiveText) || !TextUtils.isEmpty(negativeText)) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams btnRowLp = Web3Ui.matchWrap();
            btnRowLp.topMargin = dp(context, 20);

            if (!TextUtils.isEmpty(negativeText)) {
                TextView negative = button(context, negativeText, false);
                negative.setOnClickListener(v -> {
                    boolean close = negativeAction == null || negativeAction.onClick(dialog);
                    if (close) dialog.dismiss();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(context, 48), 1f);
                if (!TextUtils.isEmpty(positiveText)) lp.rightMargin = dp(context, 10);
                buttons.addView(negative, lp);
            }

            if (!TextUtils.isEmpty(positiveText)) {
                TextView positive = button(context, positiveText, true);
                positive.setOnClickListener(v -> {
                    boolean close = positiveAction == null || positiveAction.onClick(dialog);
                    if (close) dialog.dismiss();
                });
                buttons.addView(positive, new LinearLayout.LayoutParams(0, dp(context, 48), 1f));
            }
            panel.addView(buttons, btnRowLp);
        }

        scrollView.addView(panel, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        host.addView(scrollView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        dialog.setContentView(host);
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            lp.dimAmount = 0.48f;
            window.setAttributes(lp);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        });
        dialog.show();
        return dialog;
    }

    public static EditText input(Context context, String hint, int inputType) {
        return input(context, hint, inputType, 1, 1);
    }

    public static EditText input(Context context, String hint, int inputType, int minLines, int maxLines) {
        Web3Ui.Palette p = Web3Ui.palette();
        EditText editText = new EditText(context);
        editText.setTextSize(15f);
        editText.setTextColor(p.primaryText);
        editText.setHintTextColor(p.mutedText);
        editText.setHint(hint);
        editText.setTypeface(Typeface.DEFAULT);
        if (maxLines > 1) {
            editText.setInputType(inputType | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            editText.setInputType(inputType);
        }
        int inputHeight = dp(context, minLines > 1 ? 108 : 56);
        editText.setPadding(dp(context, 14), dp(context, 4), dp(context, 14), dp(context, 4));
        editText.setMinHeight(inputHeight);
        editText.setMinimumHeight(inputHeight);
        if (maxLines <= 1) {
            editText.setHeight(inputHeight);
        }
        editText.setSingleLine(maxLines <= 1);
        editText.setMinLines(minLines);
        editText.setMaxLines(Math.max(minLines, maxLines));
        editText.setGravity(minLines > 1 ? (Gravity.TOP | Gravity.LEFT) : Gravity.CENTER_VERTICAL);
        if (minLines > 1) {
            editText.setPadding(dp(context, 14), dp(context, 14), dp(context, 14), dp(context, 14));
        }
        editText.setBackground(Web3Ui.roundedStroke(context, p.softCardBg, p.border, 14, 1));
        return editText;
    }

    public static LinearLayout field(Context context, String label, EditText input) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = Web3Ui.text(context, label, 13, p.primaryText, true);
        labelView.setIncludeFontPadding(false);
        box.addView(labelView, Web3Ui.matchWrap());
        LinearLayout.LayoutParams inputLp = Web3Ui.matchWrap();
        inputLp.topMargin = dp(context, 8);
        box.addView(input, inputLp);
        return box;
    }

    public static TextView tip(Context context, CharSequence text) {
        Web3Ui.Palette p = Web3Ui.palette();
        TextView tv = Web3Ui.text(context, text == null ? "" : text.toString(), 13, p.orange, false);
        tv.setLineSpacing(dp(context, 2), 1.0f);
        tv.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
        tv.setBackground(Web3Ui.roundedStroke(context, p.dark ? 0x26362418 : 0xFFFFF7EC, Web3Ui.withAlpha(p.orange, 120), 14, 1));
        return tv;
    }

    public static TextView message(Context context, CharSequence text, boolean monospace) {
        Web3Ui.Palette p = Web3Ui.palette();
        TextView tv = Web3Ui.text(context, text == null ? "" : text.toString(), 14, p.secondaryText, false);
        tv.setLineSpacing(dp(context, 3), 1.0f);
        tv.setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        tv.setTextIsSelectable(true);
        if (monospace) {
            tv.setTypeface(Typeface.MONOSPACE);
        }
        tv.setBackground(Web3Ui.roundedStroke(context, p.softCardBg, p.border, 14, 1));
        return tv;
    }

    public static TextView chip(Context context, String text, boolean active) {
        Web3Ui.Palette p = Web3Ui.palette();
        TextView tv = Web3Ui.text(context, text, 12, active ? p.orange : p.secondaryText, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7));
        tv.setBackground(Web3Ui.roundedStroke(context,
                active ? (p.dark ? 0x29362418 : 0xFFFFF4E8) : p.softCardBg,
                active ? Web3Ui.withAlpha(p.orange, 130) : p.border,
                18,
                1));
        return tv;
    }

    public static LinearLayout listItem(Context context, String title, CharSequence subtitle, String trailing) {
        Web3Ui.Palette p = Web3Ui.palette();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12));
        row.setBackground(Web3Ui.roundedStroke(context, p.softCardBg, p.border, 14, 1));

        LinearLayout textBox = new LinearLayout(context);
        textBox.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = Web3Ui.text(context, title, 15, p.primaryText, true);
        titleView.setSingleLine(true);
        textBox.addView(titleView, Web3Ui.matchWrap());
        if (!TextUtils.isEmpty(subtitle)) {
            TextView subtitleView = Web3Ui.text(context, subtitle.toString(), 12, p.mutedText, false);
            subtitleView.setSingleLine(true);
            LinearLayout.LayoutParams subLp = Web3Ui.matchWrap();
            subLp.topMargin = dp(context, 4);
            textBox.addView(subtitleView, subLp);
        }
        row.addView(textBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(trailing)) {
            TextView trailingView = Web3Ui.text(context, trailing, 13, p.orange, true);
            trailingView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams trailingLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            trailingLp.leftMargin = dp(context, 12);
            row.addView(trailingView, trailingLp);
        }
        return row;
    }

    private static TextView button(Context context, String text, boolean primary) {
        Web3Ui.Palette p = Web3Ui.palette();
        TextView tv = Web3Ui.text(context, text, 15, primary ? Color.WHITE : p.primaryText, true);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setBackground(primary ? Web3Ui.orangeGradient(context, 14) : Web3Ui.rounded(context, p.softCardBg, 14));
        return tv;
    }

    private static int dp(Context context, float value) {
        return Web3Ui.dp(context, value);
    }

    private static final class MaxHeightScrollView extends ScrollView {
        private int maxHeight;

        MaxHeightScrollView(Context context) {
            super(context);
        }

        void setMaxHeight(int maxHeight) {
            this.maxHeight = Math.max(0, maxHeight);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeight > 0) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
