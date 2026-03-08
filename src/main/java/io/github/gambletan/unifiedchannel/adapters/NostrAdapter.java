package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Nostr adapter stub.
 * <p>
 * TODO: Implement using the Nostr protocol (NIP-01, NIP-04).
 * - Connect to relay(s) via WebSocket
 * - Subscribe to events matching pubkey filters (REQ)
 * - Publish events (EVENT) with kind 1 (text notes) or kind 4 (encrypted DMs)
 * - Sign events with secp256k1 (consider nostr-java or custom impl)
 * - Support NIP-04 encrypted direct messages
 *
 * @see <a href="https://github.com/nostr-protocol/nips">Nostr NIPs</a>
 */
public final class NostrAdapter extends AbstractAdapter {

    private final String privateKeyHex;
    private final String[] relayUrls;

    public NostrAdapter(String privateKeyHex, String... relayUrls) {
        this.privateKeyHex = privateKeyHex;
        this.relayUrls = relayUrls;
    }

    @Override public String channelId() { return "nostr"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Nostr adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Nostr send not yet implemented"));
    }
}
