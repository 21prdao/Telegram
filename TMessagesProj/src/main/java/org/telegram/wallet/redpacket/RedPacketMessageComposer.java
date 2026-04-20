package org.telegram.wallet.redpacket;

import java.math.BigDecimal;

public final class RedPacketMessageComposer {

    private RedPacketMessageComposer() {}

    public static String compose(
            BigDecimal total,
            String symbol,
            int count,
            String expireText,
            String url
    ) {
        return "🎁 " + symbol + " 红包\n"
                + "总额：" + total.stripTrailingZeros().toPlainString() + " " + symbol + "\n"
                + "份数：" + count + "\n"
                + expireText + "\n"
                + url;
    }
}