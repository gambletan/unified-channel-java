package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Matrix adapter stub.
 * <p>
 * TODO: Implement using the Matrix Client-Server API.
 * - Login via /_matrix/client/v3/login (password or token)
 * - Long-poll or use /sync for receiving events
 * - Send messages via PUT /_matrix/client/v3/rooms/{roomId}/send/m.room.message/{txnId}
 * - Support text, media (m.image, m.file), reactions, edits, and threads
 * - Handle E2EE rooms via libolm bindings (optional)
 *
 * @see <a href="https://spec.matrix.org/latest/client-server-api/">Matrix CS API</a>
 */
public final class MatrixAdapter extends AbstractAdapter {

    private final String homeserverUrl;
    private final String accessToken;

    public MatrixAdapter(String homeserverUrl, String accessToken) {
        this.homeserverUrl = homeserverUrl;
        this.accessToken = accessToken;
    }

    @Override public String channelId() { return "matrix"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Matrix adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Matrix send not yet implemented"));
    }
}
