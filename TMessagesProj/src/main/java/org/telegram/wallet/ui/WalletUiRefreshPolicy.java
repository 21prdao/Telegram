package org.telegram.wallet.ui;

import org.telegram.wallet.config.WalletConfig;

/**
 * Wallet UI refresh intervals.
 * Price and valuation data are refreshed while wallet screens are visible.
 */
public final class WalletUiRefreshPolicy {
    /** Refresh market price / valuation every 60 seconds while the page is visible. */
    public static final long TOKEN_PRICE_REFRESH_INTERVAL_MS = WalletConfig.TOKEN_PRICE_REFRESH_INTERVAL_MS;

    private WalletUiRefreshPolicy() {
    }
}
