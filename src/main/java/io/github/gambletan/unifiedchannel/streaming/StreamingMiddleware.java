package io.github.gambletan.unifiedchannel.streaming;

import io.github.gambletan.unifiedchannel.*;

import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * Middleware that manages typing indicators while processing messages.
 * <p>
 * When a message enters the pipeline, this middleware starts a periodic
 * "typing" signal and cancels it once the downstream handler returns.
 * The typing callback is provided at construction time (e.g., to send
 * platform-specific typing indicators).
 *
 * <pre>{@code
 * var streaming = new StreamingMiddleware((channelId, chatId) -> {
 *     manager.send(channelId, OutboundMessage.builder()
 *         .chatId(chatId).text("typing...").build());
 * });
 * manager.addMiddleware(streaming);
 * }</pre>
 */
public final class StreamingMiddleware implements Middleware {

    private static final Logger LOG = Logger.getLogger(StreamingMiddleware.class.getName());

    private final TypingCallback typingCallback;
    private final long intervalMs;
    private final ScheduledExecutorService scheduler;

    /**
     * Callback invoked periodically to signal typing activity.
     */
    @FunctionalInterface
    public interface TypingCallback {
        void onTyping(String channelId, String chatId);
    }

    /**
     * Create a streaming middleware with default 3-second typing interval.
     */
    public StreamingMiddleware(TypingCallback typingCallback) {
        this(typingCallback, 3000);
    }

    /**
     * Create a streaming middleware with custom typing interval.
     *
     * @param typingCallback called periodically while processing
     * @param intervalMs     typing indicator interval in milliseconds
     */
    public StreamingMiddleware(TypingCallback typingCallback, long intervalMs) {
        this.typingCallback = typingCallback;
        this.intervalMs = intervalMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "typing-indicator");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        // Start typing indicator
        var typingTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                typingCallback.onTyping(message.channelId(), message.chatId());
            } catch (Exception e) {
                LOG.fine("Typing indicator error: " + e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        return next.handle(message).whenComplete((result, ex) -> {
            // Stop typing indicator when processing completes
            typingTask.cancel(false);
        });
    }

    /**
     * Shut down the typing indicator scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
    }
}
