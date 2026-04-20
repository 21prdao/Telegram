package org.telegram.wallet.redpacket;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.wallet.config.WalletConfig;

import java.util.List;

public final class RedPacketLinkParser {

    public static final class Result {
        public final String packetId;

        public Result(String packetId) {
            this.packetId = packetId;
        }
    }

    private RedPacketLinkParser() {
    }

    @Nullable
    public static Result parse(@Nullable String url) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }

        Uri uri = Uri.parse(url);
        if (uri == null) {
            return null;
        }

        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (!WalletConfig.RED_PACKET_HOST.equalsIgnoreCase(uri.getHost())) {
            return null;
        }

        List<String> segments = uri.getPathSegments();
        if (segments.size() < 2) {
            return null;
        }
        if (!"p".equals(segments.get(0))) {
            return null;
        }

        String packetId = segments.get(1);
        if (TextUtils.isEmpty(packetId)) {
            return null;
        }
        return new Result(packetId);
    }
}