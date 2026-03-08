package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Zalo adapter stub.
 * <p>
 * TODO: Implement using the Zalo Official Account API.
 * - Register webhook for message events
 * - Send messages via POST /v2.0/oa/message
 * - Support text, image, file, and list/button templates
 * - Handle OA follower events (follow/unfollow)
 * - Manage access token refresh
 *
 * @see <a href="https://developers.zalo.me/docs/official-account">Zalo OA API</a>
 */
public final class ZaloAdapter extends AbstractAdapter {

    private final String oaAccessToken;

    public ZaloAdapter(String oaAccessToken) {
        this.oaAccessToken = oaAccessToken;
    }

    @Override public String channelId() { return "zalo"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Zalo adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Zalo send not yet implemented"));
    }
}
