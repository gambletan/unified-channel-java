package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * WhatsApp adapter stub.
 * <p>
 * TODO: Implement using the WhatsApp Business Cloud API.
 * - Register webhook for incoming messages
 * - Use /messages endpoint for sending
 * - Support text, media, template, and interactive messages
 * - Handle message status callbacks (delivered, read)
 *
 * @see <a href="https://developers.facebook.com/docs/whatsapp/cloud-api">WhatsApp Cloud API</a>
 */
public final class WhatsAppAdapter extends AbstractAdapter {

    private final String phoneNumberId;
    private final String accessToken;

    public WhatsAppAdapter(String phoneNumberId, String accessToken) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
    }

    @Override public String channelId() { return "whatsapp"; }

    @Override
    public CompletableFuture<Void> connect() {
        // TODO: Verify token via GET /v17.0/{phone-number-id}
        // TODO: Register webhook for message events
        return CompletableFuture.failedFuture(new UnsupportedOperationException("WhatsApp adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        // TODO: POST to https://graph.facebook.com/v17.0/{phone-number-id}/messages
        return CompletableFuture.failedFuture(new UnsupportedOperationException("WhatsApp send not yet implemented"));
    }
}
