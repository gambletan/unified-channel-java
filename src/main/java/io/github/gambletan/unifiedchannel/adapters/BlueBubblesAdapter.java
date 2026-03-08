package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * BlueBubbles adapter stub.
 * <p>
 * TODO: Implement using the BlueBubbles REST API.
 * - Connect to BlueBubbles server (local network or Ngrok tunnel)
 * - Poll or use WebSocket for incoming messages
 * - Send messages via POST /api/v1/message/text
 * - Support text and attachment messages
 * - Handle group chats and individual conversations
 *
 * @see <a href="https://bluebubbles.app/web-api/">BlueBubbles API</a>
 */
public final class BlueBubblesAdapter extends AbstractAdapter {

    private final String serverUrl;
    private final String password;

    public BlueBubblesAdapter(String serverUrl, String password) {
        this.serverUrl = serverUrl;
        this.password = password;
    }

    @Override public String channelId() { return "bluebubbles"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("BlueBubbles adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("BlueBubbles send not yet implemented"));
    }
}
