package org.telegram.wallet.redpacket;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.telegram.messenger.MessageObject;
import org.telegram.wallet.config.WalletConfig;

public final class RedPacketMessageCodec {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https://" + Pattern.quote(WalletConfig.RED_PACKET_HOST) + "/p/[A-Za-z0-9_-]+(?:\\?\\S+)?"
    );

    private RedPacketMessageCodec() {}

    @Nullable
    public static RedPacketLinkParser.Result tryParse(MessageObject messageObject) {
        if (messageObject == null) {
            return null;
        }

        if (messageObject.messageOwner != null
                && messageObject.messageOwner.media != null
                && messageObject.messageOwner.media.webpage != null
                && messageObject.messageOwner.media.webpage.url != null) {
            RedPacketLinkParser.Result fromWebPage =
                    RedPacketLinkParser.parse(messageObject.messageOwner.media.webpage.url);
            if (fromWebPage != null) {
                return fromWebPage;
            }
        }

        if (messageObject.messageText == null) {
            return null;
        }

        Matcher matcher = URL_PATTERN.matcher(messageObject.messageText.toString());
        if (!matcher.find()) {
            return null;
        }
        return RedPacketLinkParser.parse(matcher.group());
    }
}