package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Synology Chat adapter stub.
 * <p>
 * TODO: Implement using the Synology Chat Bot API.
 * - Register incoming/outgoing webhooks in Synology Chat
 * - Receive messages via outgoing webhook POST
 * - Send messages via POST to incoming webhook URL
 * - Support text and file link messages
 *
 * @see <a href="https://kb.synology.com/en-me/DSM/tutorial/How_to_configure_webhooks_and_slash_commands_in_Synology_Chat">Synology Chat Bots</a>
 */
public final class SynologyAdapter extends AbstractAdapter {

    private final String incomingWebhookUrl;

    public SynologyAdapter(String incomingWebhookUrl) {
        this.incomingWebhookUrl = incomingWebhookUrl;
    }

    @Override public String channelId() { return "synology"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Synology adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Synology send not yet implemented"));
    }
}
