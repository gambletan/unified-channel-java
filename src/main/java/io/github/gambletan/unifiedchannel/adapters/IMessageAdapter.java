package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * iMessage adapter stub.
 * <p>
 * TODO: Implement via AppleScript bridge or BlueBubbles/AirMessage relay.
 * - macOS only: use osascript to send via Messages.app
 * - Receive: poll chat.db (~/Library/Messages/chat.db) or use relay server
 * - Alternative: use BlueBubblesAdapter as the transport layer
 * - Support text and attachment messages
 * - Handle group chats (iMessage groups)
 * <p>
 * Note: Direct iMessage API access is not available; this adapter relies
 * on platform-specific workarounds.
 */
public final class IMessageAdapter extends AbstractAdapter {

    public IMessageAdapter() {}

    @Override public String channelId() { return "imessage"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("iMessage adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("iMessage send not yet implemented"));
    }
}
