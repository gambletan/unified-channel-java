package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * LINE adapter stub.
 * <p>
 * TODO: Implement using the LINE Messaging API.
 * - Register webhook for incoming events
 * - Validate webhook signatures (X-Line-Signature)
 * - Send replies via POST /v2/bot/message/reply (within reply window)
 * - Send push messages via POST /v2/bot/message/push
 * - Support text, image, video, audio, location, sticker, flex messages
 *
 * @see <a href="https://developers.line.biz/en/docs/messaging-api/">LINE Messaging API</a>
 */
public final class LineAdapter extends AbstractAdapter {

    private final String channelAccessToken;
    private final String channelSecret;

    public LineAdapter(String channelAccessToken, String channelSecret) {
        this.channelAccessToken = channelAccessToken;
        this.channelSecret = channelSecret;
    }

    @Override public String channelId() { return "line"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("LINE adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("LINE send not yet implemented"));
    }
}
