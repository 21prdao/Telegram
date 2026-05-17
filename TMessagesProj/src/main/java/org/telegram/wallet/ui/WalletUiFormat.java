package org.telegram.wallet.ui;

import android.text.TextUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Number formatting helpers for wallet UI.
 * Keeps list/detail pages consistent and avoids showing misleading zero prices for unknown data.
 */
public final class WalletUiFormat {
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal AMOUNT_MIN = new BigDecimal("0.00000001");
    private static final BigDecimal CENT = new BigDecimal("0.01");
    private static final DecimalFormatSymbols US = DecimalFormatSymbols.getInstance(Locale.US);
    private static final char[] SUBSCRIPT_DIGITS = new char[]{'₀', '₁', '₂', '₃', '₄', '₅', '₆', '₇', '₈', '₉'};

    private WalletUiFormat() {
    }

    public static BigDecimal parseDecimal(String value) {
        String normalized = normalizeNumberText(value);
        if (TextUtils.isEmpty(normalized) || "--".equals(normalized)) return BigDecimal.ZERO;
        if (normalized.startsWith("<")) normalized = normalized.substring(1).trim();
        normalized = expandCompactZeroNotation(normalized);
        if (TextUtils.isEmpty(normalized)) return BigDecimal.ZERO;
        try {
            return new BigDecimal(normalized);
        } catch (Throwable ignore) {
            return BigDecimal.ZERO;
        }
    }

    public static String formatTokenAmount(String amount) {
        String raw = normalizeNumberText(amount);
        if (TextUtils.isEmpty(raw) || "--".equals(raw)) return "--";
        boolean lessThan = raw.startsWith("<");
        if (lessThan) raw = raw.substring(1).trim();
        raw = expandCompactZeroNotation(raw);
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.ZERO) == 0) return "0";
            BigDecimal abs = value.abs();
            String compactAmount = formatCompactTokenAmount(value);
            if (!TextUtils.isEmpty(compactAmount)) {
                return lessThan ? "<" + compactAmount : compactAmount;
            }
            if (lessThan || abs.compareTo(AMOUNT_MIN) < 0) {
                return value.signum() < 0 ? "<-0.00000001" : "<0.00000001";
            }
            if (abs.compareTo(new BigDecimal("1000000")) >= 0) {
                return formatDecimal(value, 2, RoundingMode.DOWN);
            }
            if (abs.compareTo(new BigDecimal("1000")) >= 0) {
                return formatDecimal(value, 4, RoundingMode.DOWN);
            }
            if (abs.compareTo(ONE) >= 0) {
                return formatDecimal(value, 6, RoundingMode.DOWN);
            }
            return formatDecimal(value, 8, RoundingMode.DOWN);
        } catch (Throwable ignore) {
            return amount == null ? "--" : amount.trim();
        }
    }

    /** Unit market price. Unknown prices are shown as "--". */
    public static String formatUsdPrice(String priceUsd) {
        String raw = normalizeNumberText(priceUsd);
        if (TextUtils.isEmpty(raw) || "--".equals(raw)) return "--";
        boolean lessThan = raw.startsWith("<");
        if (lessThan) raw = raw.substring(1).trim();
        raw = expandCompactZeroNotation(raw);
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.ZERO) <= 0) return "--";
            if (lessThan) return "$<" + formatReadableUsdPrice(value);
            return "$" + formatReadableUsdPrice(value);
        } catch (Throwable ignore) {
            return "--";
        }
    }

    /** Holding value. Unknown value displays as "--"; zero balance remains "$0.00". */
    public static String formatUsdValue(String usdValue, String amount) {
        String raw = normalizeNumberText(usdValue);
        if (TextUtils.isEmpty(raw) || "--".equals(raw)) {
            return isZeroAmount(amount) ? "$0.00" : "--";
        }
        boolean lessThan = raw.startsWith("<");
        if (lessThan) raw = raw.substring(1).trim();
        raw = expandCompactZeroNotation(raw);
        try {
            BigDecimal value = new BigDecimal(raw);
            if (value.compareTo(BigDecimal.ZERO) <= 0) return "$0.00";
            if (lessThan || value.compareTo(CENT) < 0) return "$<0.01";
            return "$" + formatMoneyDecimal(value);
        } catch (Throwable ignore) {
            return "--";
        }
    }

    public static String formatUsdValue(BigDecimal value) {
        if (value == null) return "--";
        if (value.compareTo(BigDecimal.ZERO) <= 0) return "$0.00";
        if (value.compareTo(CENT) < 0) return "$<0.01";
        return "$" + formatMoneyDecimal(value);
    }

    public static String calculateUsdValueText(String amount, String priceUsd) {
        BigDecimal amountValue = parseDecimal(amount);
        if (amountValue.compareTo(BigDecimal.ZERO) <= 0) return "0.00";
        BigDecimal priceValue = parseDecimal(priceUsd);
        if (priceValue.compareTo(BigDecimal.ZERO) <= 0) return "--";
        BigDecimal value = amountValue.multiply(priceValue);
        if (value.compareTo(CENT) < 0 && value.compareTo(BigDecimal.ZERO) > 0) return "<0.01";
        return formatMoneyDecimal(value);
    }

    public static boolean isZeroAmount(String amount) {
        String normalized = normalizeNumberText(amount);
        if (TextUtils.isEmpty(normalized) || "--".equals(normalized)) return false;
        try {
            return parseDecimal(normalized).compareTo(BigDecimal.ZERO) == 0;
        } catch (Throwable ignore) {
            return false;
        }
    }

    private static String formatReadableUsdPrice(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) return "--";
        String compact = formatCompactSmallDecimal(value, 4, 4);
        if (!TextUtils.isEmpty(compact)) return compact;
        if (value.compareTo(new BigDecimal("1000")) >= 0) return formatDecimal(value, 2, RoundingMode.HALF_UP);
        if (value.compareTo(ONE) >= 0) return formatDecimal(value, 2, RoundingMode.HALF_UP);
        if (value.compareTo(CENT) >= 0) return formatDecimal(value, 4, RoundingMode.HALF_UP);
        return formatDecimal(value, 8, RoundingMode.HALF_UP);
    }

    /**
     * TokenPocket-style compact small decimal display.
     * Example: 0.00008009 -> 0.0₄8009, 0.00000123 -> 0.0₅123.
     */
    private static String formatCompactTokenAmount(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) return "";
        String compact = formatCompactSmallDecimal(value.abs(), 6, 4);
        if (TextUtils.isEmpty(compact)) return "";
        return value.signum() < 0 ? "-" + compact : compact;
    }

    private static String formatCompactSmallDecimal(BigDecimal input, int minLeadingZeros, int significantDigits) {
        if (input == null || input.compareTo(BigDecimal.ZERO) <= 0) return "";
        BigDecimal value = input.stripTrailingZeros();
        if (value.compareTo(ONE) >= 0) return "";
        String plain = value.toPlainString();
        if (!plain.startsWith("0.")) return "";
        String decimals = plain.substring(2);
        int zeroCount = 0;
        while (zeroCount < decimals.length() && decimals.charAt(zeroCount) == '0') {
            zeroCount++;
        }
        if (zeroCount < minLeadingZeros || zeroCount >= decimals.length()) return "";
        String significant = decimals.substring(zeroCount);
        if (significant.length() > significantDigits) {
            significant = significant.substring(0, significantDigits);
        }
        significant = significant.replaceFirst("0+$", "");
        if (TextUtils.isEmpty(significant)) significant = "0";
        return "0.0" + toSubscript(zeroCount) + significant;
    }

    private static String toSubscript(int value) {
        if (value < 0) return "";
        String text = String.valueOf(value);
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(SUBSCRIPT_DIGITS[c - '0']);
            }
        }
        return builder.toString();
    }

    private static String normalizeNumberText(String value) {
        if (value == null) return "";
        String raw = value.trim();
        if (TextUtils.isEmpty(raw)) return "";
        raw = raw.replace("行情", "")
                .replace("≈", "")
                .replace("$", "")
                .replace(",", "")
                .trim();
        if (raw.equalsIgnoreCase("null") || raw.equalsIgnoreCase("nan")) return "";
        if ("—".equals(raw) || "-".equals(raw)) return "--";
        return raw;
    }

    private static String expandCompactZeroNotation(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String raw = value.trim();
        int marker = raw.indexOf("0.0");
        if (marker < 0) return raw;
        int subscriptStart = marker + 3;
        if (subscriptStart >= raw.length()) return raw;
        int subscriptEnd = subscriptStart;
        int zeros = 0;
        while (subscriptEnd < raw.length()) {
            int digit = subscriptDigit(raw.charAt(subscriptEnd));
            if (digit < 0) break;
            zeros = zeros * 10 + digit;
            subscriptEnd++;
        }
        if (subscriptEnd == subscriptStart || zeros <= 0) return raw;
        String significant = raw.substring(subscriptEnd).replaceAll("[^0-9]", "");
        if (TextUtils.isEmpty(significant)) significant = "0";
        StringBuilder expanded = new StringBuilder("0.");
        for (int i = 0; i < zeros; i++) expanded.append('0');
        expanded.append(significant);
        return expanded.toString();
    }

    private static int subscriptDigit(char c) {
        for (int i = 0; i < SUBSCRIPT_DIGITS.length; i++) {
            if (SUBSCRIPT_DIGITS[i] == c) return i;
        }
        return -1;
    }

    private static String formatMoneyDecimal(BigDecimal value) {
        if (value == null) return "0.00";
        DecimalFormat format = new DecimalFormat("#,##0.00", US);
        format.setRoundingMode(RoundingMode.HALF_UP);
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(2);
        return format.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static String formatDecimal(BigDecimal value, int maxFractionDigits, RoundingMode roundingMode) {
        if (value == null) return "0";
        BigDecimal rounded = value.setScale(maxFractionDigits, roundingMode).stripTrailingZeros();
        DecimalFormat format = new DecimalFormat("#,##0", US);
        format.setRoundingMode(roundingMode);
        format.setMaximumFractionDigits(maxFractionDigits);
        format.setMinimumFractionDigits(0);
        return format.format(rounded);
    }
}
