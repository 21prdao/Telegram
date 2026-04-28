package org.telegram.wallet.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.ui.ActionBar.Theme;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Shared visual system for the Web3 wallet module. */
public final class Web3Ui {

    public static final int BRAND_ORANGE = 0xFFF08C22;
    public static final int BRAND_ORANGE_DARK = 0xFFD97813;
    public static final int ACTIVE_GREEN = 0xFF22C55E;

    private Web3Ui() {
    }

    public static final class Palette {
        public final boolean dark;
        public final int pageBg;
        public final int appBarBg;
        public final int cardBg;
        public final int softCardBg;
        public final int elevatedCardBg;
        public final int border;
        public final int strongBorder;
        public final int primaryText;
        public final int secondaryText;
        public final int mutedText;
        public final int orange;
        public final int orangePressed;
        public final int green;
        public final int grayBadgeBg;
        public final int pendingBadgeBg;

        private Palette(boolean dark) {
            this.dark = dark;
            this.orange = BRAND_ORANGE;
            this.orangePressed = BRAND_ORANGE_DARK;
            this.green = ACTIVE_GREEN;
            if (dark) {
                pageBg = 0xFF07111B;
                appBarBg = 0xFF0B1622;
                cardBg = 0xFF121E2B;
                softCardBg = 0xFF182535;
                elevatedCardBg = 0xFF1B2A3A;
                border = 0xFF314052;
                strongBorder = withAlpha(orange, 190);
                primaryText = 0xFFF8FAFC;
                secondaryText = 0xFFB4BFCC;
                mutedText = 0xFF7F8B99;
                grayBadgeBg = 0xFF303B49;
                pendingBadgeBg = 0xFF3A2A16;
            } else {
                pageBg = 0xFFF5F7FA;
                appBarBg = 0xFFFFFFFF;
                cardBg = 0xFFFFFFFF;
                softCardBg = 0xFFF1F4F8;
                elevatedCardBg = 0xFFFFFFFF;
                border = 0xFFE0E6EF;
                strongBorder = withAlpha(orange, 185);
                primaryText = 0xFF15202B;
                secondaryText = 0xFF5B6877;
                mutedText = 0xFF8A95A5;
                grayBadgeBg = 0xFFE9EEF5;
                pendingBadgeBg = 0xFFFFF2DF;
            }
        }
    }

    public static Palette palette() {
        int bg;
        try {
            bg = Theme.getColor(Theme.key_windowBackgroundGray);
        } catch (Throwable t) {
            bg = 0xFF0E1822;
        }
        return new Palette(isDark(bg));
    }

    public static boolean isDark(int color) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        double luminance = 0.299d * r + 0.587d * g + 0.114d * b;
        return luminance < 145d;
    }

    public static void applySystemBars(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 21) {
            return;
        }
        Palette p = palette();
        Window window = activity.getWindow();
        window.setStatusBarColor(p.pageBg);
        window.setNavigationBarColor(p.pageBg);
        if (Build.VERSION.SDK_INT >= 23) {
            int flags = window.getDecorView().getSystemUiVisibility();
            if (!p.dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                if (!p.dark) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    public static int dp(Context context, float value) {
        return (int) Math.ceil(value * context.getResources().getDisplayMetrics().density);
    }

    public static TextView text(Context context, String value, float sp, int color, boolean bold) {
        TextView tv = new TextView(context);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(true);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    public static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable roundedStroke(Context context, int color, int strokeColor, float radiusDp, float strokeDp) {
        GradientDrawable drawable = rounded(context, color, radiusDp);
        drawable.setStroke(dp(context, strokeDp), strokeColor);
        return drawable;
    }

    public static GradientDrawable orangeGradient(Context context, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFFA126, 0xFFFF8416});
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), 0xFFFFB546);
        return drawable;
    }

    public static GradientDrawable softOrangeGradient(Context context, float radiusDp) {
        Palette p = palette();
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                p.dark ? new int[]{0x44F08C22, 0x14121E2B} : new int[]{0xFFFFF1DE, 0xFFFFFFFF});
        drawable.setCornerRadius(dp(context, radiusDp));
        drawable.setStroke(dp(context, 1), withAlpha(BRAND_ORANGE, 190));
        return drawable;
    }

    public static LinearLayout card(Context context) {
        Palette p = palette();
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(roundedStroke(context, p.cardBg, p.border, 20, 1));
        setElevation(card, 2);
        return card;
    }

    public static LinearLayout sectionTitle(Context context, int icon, String title) {
        Palette p = palette();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (icon != 0) {
            FrameLayout badge = iconCircle(context, icon, p.orange, p.dark ? 0x22111111 : 0x11F08C22, 36);
            row.addView(badge, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
        }
        TextView tv = text(context, title, 22, p.primaryText, true);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (icon != 0) {
            tvLp.leftMargin = dp(context, 10);
        }
        row.addView(tv, tvLp);
        return row;
    }

    public static FrameLayout iconButton(Context context, int icon) {
        Palette p = palette();
        FrameLayout box = new FrameLayout(context);
        box.setBackground(roundedStroke(context, p.cardBg, p.border, 16, 1));
        Web3IconView iconView = new Web3IconView(context, icon, p.primaryText);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(context, 24), dp(context, 24), Gravity.CENTER);
        box.addView(iconView, iconLp);
        setElevation(box, 2);
        return box;
    }

    public static FrameLayout iconCircle(Context context, int icon, int iconColor, int bgColor, int sizeDp) {
        FrameLayout box = new FrameLayout(context);
        box.setBackground(roundedStroke(context, bgColor, withAlpha(iconColor, 90), sizeDp / 2f, 1));
        Web3IconView iconView = new Web3IconView(context, icon, iconColor);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(context, sizeDp * 0.56f), dp(context, sizeDp * 0.56f), Gravity.CENTER);
        box.addView(iconView, iconLp);
        return box;
    }

    public static LinearLayout actionButton(Context context, String label, int icon, boolean primary) {
        Palette p = palette();
        LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setMinimumHeight(dp(context, primary ? 56 : 52));
        button.setBackground(primary ? orangeGradient(context, 15) : roundedStroke(context, p.softCardBg, p.border, 15, 1));
        if (icon != 0) {
            Web3IconView iconView = new Web3IconView(context, icon, primary ? Color.WHITE : p.orange);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(context, 22), dp(context, 22));
            iconLp.rightMargin = dp(context, 8);
            button.addView(iconView, iconLp);
        }
        TextView tv = text(context, label, 16, primary ? Color.WHITE : p.primaryText, true);
        tv.setGravity(Gravity.CENTER);
        button.addView(tv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setElevation(button, primary ? 3 : 1);
        return button;
    }

    public static TextView tokenBadge(Context context, String symbol, int sizeDp) {
        TextView tv = text(context, tokenLetter(symbol), Math.max(15, sizeDp / 2.2f), Color.WHITE, true);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFFB12B, 0xFFFF6A18});
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(context, 1), 0xFFFFD46B);
        tv.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            tv.setElevation(dp(context, 2));
        }
        return tv;
    }

    public static TextView statusBadge(Context context, String status) {
        Palette p = palette();
        String safeStatus = TextUtils.isEmpty(status) ? "-" : status;
        boolean active = "ACTIVE".equalsIgnoreCase(safeStatus);
        boolean pending = safeStatus.toUpperCase().contains("PENDING");
        int textColor;
        int bgColor;
        int strokeColor;
        if (active) {
            textColor = p.green;
            bgColor = p.dark ? 0x2433CC75 : 0xFFE6F9EE;
            strokeColor = withAlpha(p.green, 110);
        } else if (pending) {
            textColor = p.orange;
            bgColor = p.pendingBadgeBg;
            strokeColor = withAlpha(p.orange, 110);
        } else {
            textColor = p.dark ? 0xFFC7D0DC : 0xFF5E6A78;
            bgColor = p.grayBadgeBg;
            strokeColor = p.dark ? 0xFF465565 : 0xFFD8E0EA;
        }
        TextView tv = text(context, "●  " + safeStatus, pending ? 11 : 12, textColor, false);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        tv.setBackground(roundedStroke(context, bgColor, strokeColor, 10, 1));
        return tv;
    }

    public static String formatTokenAmount(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return "--";
        }
        String value = raw.trim();
        try {
            BigDecimal decimal = new BigDecimal(value);
            if (value.matches("^[0-9]+$") && value.length() > 12) {
                decimal = decimal.divide(BigDecimal.TEN.pow(18), 18, RoundingMode.DOWN);
            }
            if (decimal.abs().compareTo(BigDecimal.ONE) >= 0) {
                return decimal.setScale(2, RoundingMode.DOWN).toPlainString();
            }
            return decimal.stripTrailingZeros().toPlainString();
        } catch (Throwable ignore) {
            return value;
        }
    }

    public static String shortHash(String hash) {
        if (TextUtils.isEmpty(hash) || hash.length() < 12) {
            return TextUtils.isEmpty(hash) ? "-" : hash;
        }
        return hash.substring(0, 6) + "..." + hash.substring(hash.length() - 4);
    }

    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    public static void setElevation(View view, float dp) {
        if (Build.VERSION.SDK_INT >= 21) {
            view.setElevation(dp(view.getContext(), dp));
        }
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams topMargin(Context context, int topDp) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(context, topDp);
        return lp;
    }

    private static String tokenLetter(String symbol) {
        if (TextUtils.isEmpty(symbol)) {
            return "T";
        }
        String s = symbol.trim().toUpperCase();
        return s.substring(0, 1);
    }
}
