package io.github.gambletan.unifiedchannel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Central orchestrator that ties channel adapters and middleware together.
 * <p>
 * Inbound messages flow through the middleware pipeline before reaching
 * registered message listeners. Outbound messages are dispatched directly
 * to the target adapter.
 */
public final class ChannelManager {

    private static final Logger LOG = Logger.getLogger(ChannelManager.class.getName());

    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final List<Middleware> middlewares = new CopyOnWriteArrayList<>();
    private final List<Consumer<UnifiedMessage>> messageListeners = new CopyOnWriteArrayList<>();
    private volatile boolean running;

    /** Register a channel adapter. */
    public ChannelManager addChannel(ChannelAdapter adapter) {
        if (adapters.containsKey(adapter.channelId())) {
            throw new IllegalArgumentException("Channel already registered: " + adapter.channelId());
        }
        adapters.put(adapter.channelId(), adapter);
        // Wire inbound messages into the pipeline
        adapter.onMessage(this::dispatchInbound);
        return this;
    }

    /** Append a middleware to the pipeline. */
    public ChannelManager addMiddleware(Middleware middleware) {
        middlewares.add(middleware);
        return this;
    }

    /** Register a listener invoked for every message that passes the middleware pipeline. */
    public ChannelManager onMessage(Consumer<UnifiedMessage> listener) {
        messageListeners.add(listener);
        return this;
    }

    /**
     * Send a message through a specific channel.
     *
     * @param channelId the target channel
     * @param message   the outbound message
     * @return a future that completes when the message is sent
     */
    public CompletableFuture<Void> send(String channelId, OutboundMessage message) {
        var adapter = adapters.get(channelId);
        if (adapter == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown channel: " + channelId));
        }
        return adapter.send(message);
    }

    /**
     * Broadcast a message to all connected channels.
     *
     * @param message the outbound message
     * @return a future that completes when all sends finish
     */
    public CompletableFuture<Void> broadcast(OutboundMessage message) {
        var futures = adapters.values().stream()
                .filter(a -> a.getStatus().state() == ChannelStatus.State.CONNECTED)
                .map(a -> a.send(message).exceptionally(ex -> {
                    LOG.log(Level.WARNING, "Broadcast failed on " + a.channelId(), ex);
                    return null;
                }))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Get status of all registered channels. */
    public Map<String, ChannelStatus> getStatus() {
        var result = new ConcurrentHashMap<String, ChannelStatus>();
        adapters.forEach((id, adapter) -> result.put(id, adapter.getStatus()));
        return Collections.unmodifiableMap(result);
    }

    /** Get a single channel's status, or null if not registered. */
    public ChannelStatus getStatus(String channelId) {
        var adapter = adapters.get(channelId);
        return adapter != null ? adapter.getStatus() : null;
    }

    /** Get a registered adapter by channel ID. */
    public ChannelAdapter getAdapter(String channelId) {
        return adapters.get(channelId);
    }

    /** Get all registered channel IDs. */
    public List<String> getChannelIds() {
        return List.copyOf(adapters.keySet());
    }

    /**
     * Connect all registered adapters and start processing messages.
     *
     * @return a future that completes when all adapters are connected
     */
    public CompletableFuture<Void> run() {
        running = true;
        var futures = adapters.values().stream()
                .map(adapter -> adapter.connect().exceptionally(ex -> {
                    LOG.log(Level.SEVERE, "Failed to connect " + adapter.channelId(), ex);
                    return null;
                }))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /**
     * Disconnect all adapters and stop processing.
     *
     * @return a future that completes when all adapters are disconnected
     */
    public CompletableFuture<Void> shutdown() {
        running = false;
        var futures = adapters.values().stream()
                .map(adapter -> adapter.disconnect().exceptionally(ex -> {
                    LOG.log(Level.WARNING, "Error disconnecting " + adapter.channelId(), ex);
                    return null;
                }))
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    /** Whether the manager is currently running. */
    public boolean isRunning() {
        return running;
    }

    // -- Internal pipeline --

    private void dispatchInbound(UnifiedMessage message) {
        buildPipeline(message, 0)
                .handle((result, ex) -> {
                    if (ex != null) {
                        LOG.log(Level.WARNING, "Pipeline error for " + message, ex);
                    }
                    return null;
                });
    }

    /**
     * Build the middleware chain recursively. The terminal handler notifies
     * all registered message listeners and returns an empty result.
     */
    private CompletableFuture<HandlerResult> buildPipeline(UnifiedMessage message, int index) {
        if (index >= middlewares.size()) {
            // Terminal handler: deliver to listeners
            return terminalHandler(message);
        }
        var mw = middlewares.get(index);
        Handler next = msg -> buildPipeline(msg, index + 1);
        return mw.process(message, next);
    }

    private CompletableFuture<HandlerResult> terminalHandler(UnifiedMessage message) {
        for (var listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Message listener error", e);
            }
        }
        return CompletableFuture.completedFuture(HandlerResult.empty());
    }
}
