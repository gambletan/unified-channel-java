package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.ChannelAdapter;
import io.github.gambletan.unifiedchannel.ChannelStatus;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Base class for channel adapters. Provides message listener management
 * and status tracking.
 */
public abstract class AbstractAdapter implements ChannelAdapter {

    protected final Logger log = Logger.getLogger(getClass().getName());
    protected final List<Consumer<UnifiedMessage>> listeners = new CopyOnWriteArrayList<>();
    protected volatile ChannelStatus status;

    protected AbstractAdapter() {
        this.status = ChannelStatus.disconnected(channelId());
    }

    @Override
    public void onMessage(Consumer<UnifiedMessage> listener) {
        listeners.add(listener);
    }

    @Override
    public ChannelStatus getStatus() {
        return status;
    }

    /** Dispatch an inbound message to all registered listeners. */
    protected void emit(UnifiedMessage message) {
        for (var listener : listeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.warning("Listener error on " + channelId() + ": " + e.getMessage());
            }
        }
    }
}
