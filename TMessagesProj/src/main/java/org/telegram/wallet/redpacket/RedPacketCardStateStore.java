package org.telegram.wallet.redpacket;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.util.concurrent.ConcurrentHashMap;

public final class RedPacketCardStateStore {

    public static final class State {
        public final String status;
        public final String amountText;

        public State(String status, String amountText) {
            this.status = status;
            this.amountText = amountText;
        }
    }

    private static final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    private RedPacketCardStateStore() {
    }

    public static void put(String packetId, String status, @Nullable String amountText) {
        if (TextUtils.isEmpty(packetId) || TextUtils.isEmpty(status)) {
            return;
        }
        states.put(packetId, new State(status, amountText));
    }

    @Nullable
    public static State get(String packetId) {
        if (TextUtils.isEmpty(packetId)) {
            return null;
        }
        return states.get(packetId);
    }
}
