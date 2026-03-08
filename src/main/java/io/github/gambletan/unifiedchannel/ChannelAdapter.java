package io.github.gambletan.unifiedchannel;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Interface that all channel adapters must implement.
 * Each adapter bridges a specific messaging platform to the unified API.
 */
public interface ChannelAdapter {

    /** Unique identifier for this channel (e.g. "telegram", "discord"). */
    String channelId();

    /** Connect to the messaging platform and start receiving messages. */
    CompletableFuture<Void> connect();

    /** Disconnect from the messaging platform and release resources. */
    CompletableFuture<Void> disconnect();

    /** Register a listener for inbound messages. */
    void onMessage(Consumer<UnifiedMessage> listener);

    /** Send an outbound message through this channel. */
    CompletableFuture<Void> send(OutboundMessage message);

    /** Get the current status of this adapter. */
    ChannelStatus getStatus();
}
