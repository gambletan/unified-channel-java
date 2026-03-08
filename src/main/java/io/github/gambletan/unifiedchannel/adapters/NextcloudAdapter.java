package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Nextcloud Talk adapter stub.
 * <p>
 * TODO: Implement using the Nextcloud Talk API (OCS).
 * - Authenticate via app password or OAuth2
 * - Poll for new messages via GET /ocs/v2.php/apps/spreed/api/v1/chat/{token}
 * - Send messages via POST /ocs/v2.php/apps/spreed/api/v1/chat/{token}
 * - Support text, file sharing, and reply-to
 * - Handle conversation joins/leaves
 *
 * @see <a href="https://nextcloud-talk.readthedocs.io/en/latest/">Nextcloud Talk API</a>
 */
public final class NextcloudAdapter extends AbstractAdapter {

    private final String serverUrl;
    private final String username;
    private final String password;

    public NextcloudAdapter(String serverUrl, String username, String password) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
    }

    @Override public String channelId() { return "nextcloud"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Nextcloud adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Nextcloud send not yet implemented"));
    }
}
