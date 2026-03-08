package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Microsoft Teams adapter stub.
 * <p>
 * TODO: Implement using the Bot Framework / Microsoft Graph API.
 * - Register bot via Azure Bot Service
 * - Handle incoming activities via webhook (Activity objects)
 * - Send messages via POST to conversation endpoint
 * - Support Adaptive Cards for rich content
 * - Handle proactive messaging and 1:1 conversations
 *
 * @see <a href="https://learn.microsoft.com/en-us/microsoftteams/platform/bots/">MS Teams Bots</a>
 */
public final class MSTeamsAdapter extends AbstractAdapter {

    private final String appId;
    private final String appSecret;

    public MSTeamsAdapter(String appId, String appSecret) {
        this.appId = appId;
        this.appSecret = appSecret;
    }

    @Override public String channelId() { return "msteams"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("MS Teams adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("MS Teams send not yet implemented"));
    }
}
