package io.github.gambletan.unifiedchannel;

import java.time.Instant;
import java.util.Objects;

/**
 * Status information for a channel adapter.
 *
 * @param channelId   adapter identifier
 * @param state       current connection state
 * @param connectedAt timestamp of last successful connection, or null
 * @param error       last error message, or null
 */
public record ChannelStatus(
        String channelId,
        State state,
        Instant connectedAt,
        String error
) {
    public enum State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    public ChannelStatus {
        Objects.requireNonNull(channelId, "channelId required");
        Objects.requireNonNull(state, "state required");
    }

    /** Convenience factory for a connected status. */
    public static ChannelStatus connected(String channelId) {
        return new ChannelStatus(channelId, State.CONNECTED, Instant.now(), null);
    }

    /** Convenience factory for a disconnected status. */
    public static ChannelStatus disconnected(String channelId) {
        return new ChannelStatus(channelId, State.DISCONNECTED, null, null);
    }

    /** Convenience factory for an error status. */
    public static ChannelStatus error(String channelId, String error) {
        return new ChannelStatus(channelId, State.ERROR, null, error);
    }
}
