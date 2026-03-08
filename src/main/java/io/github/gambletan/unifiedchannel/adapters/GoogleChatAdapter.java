package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Google Chat adapter stub.
 * <p>
 * TODO: Implement using the Google Chat API.
 * - Register webhook or use service account for API access
 * - Subscribe to message events via Google Cloud Pub/Sub or HTTP endpoint
 * - Send messages via POST spaces/{space}/messages
 * - Support text, cards (Card v2), and interactive elements
 * - Handle slash commands registered in Google Chat configuration
 *
 * @see <a href="https://developers.google.com/workspace/chat/api">Google Chat API</a>
 */
public final class GoogleChatAdapter extends AbstractAdapter {

    private final String serviceAccountJson;

    public GoogleChatAdapter(String serviceAccountJson) {
        this.serviceAccountJson = serviceAccountJson;
    }

    @Override public String channelId() { return "googlechat"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Google Chat adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Google Chat send not yet implemented"));
    }
}
