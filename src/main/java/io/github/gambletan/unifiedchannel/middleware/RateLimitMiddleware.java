package io.github.gambletan.unifiedchannel.middleware;

import io.github.gambletan.unifiedchannel.Handler;
import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.Middleware;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Function;

/**
 * Rate-limiting middleware using a sliding window algorithm.
 * Limits how many messages a user (or custom key) can send within a time window.
 */
public final class RateLimitMiddleware implements Middleware {

    private final int maxMessages;
    private final long windowMs;
    private final Function<UnifiedMessage, String> keyFn;
    private final String replyText;

    /** Sliding window: key -> deque of timestamps (epoch ms). */
    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @param maxMessages maximum messages allowed within the window
     * @param windowMs    window size in milliseconds
     * @param keyFn       function to extract the rate-limit key from a message (default: sender ID)
     * @param replyText   optional reply text when rate limited (null = silent drop)
     */
    public RateLimitMiddleware(int maxMessages, long windowMs,
                               Function<UnifiedMessage, String> keyFn,
                               String replyText) {
        this.maxMessages = maxMessages;
        this.windowMs = windowMs;
        this.keyFn = keyFn != null ? keyFn : msg -> msg.sender().id();
        this.replyText = replyText;
    }

    public RateLimitMiddleware(int maxMessages, long windowMs) {
        this(maxMessages, windowMs, null, null);
    }

    public RateLimitMiddleware() {
        this(10, 60_000, null, null);
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        var key = keyFn.apply(message);
        var now = System.currentTimeMillis();
        var cutoff = now - windowMs;

        var timestamps = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        // Evict expired entries
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }

        if (timestamps.size() >= maxMessages) {
            if (replyText != null) {
                return CompletableFuture.completedFuture(HandlerResult.text(replyText));
            }
            return CompletableFuture.completedFuture(HandlerResult.empty());
        }

        timestamps.addLast(now);
        return next.handle(message);
    }

    /** Remove expired entries from all tracked keys. */
    public void cleanup() {
        var now = System.currentTimeMillis();
        var cutoff = now - windowMs;

        windows.forEach((key, timestamps) -> {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
                timestamps.pollFirst();
            }
            if (timestamps.isEmpty()) {
                windows.remove(key);
            }
        });
    }

    /** Reset all rate limit state. */
    public void reset() {
        windows.clear();
    }
}
