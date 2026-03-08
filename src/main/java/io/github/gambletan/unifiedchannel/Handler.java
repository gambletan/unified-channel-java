package io.github.gambletan.unifiedchannel;

import java.util.concurrent.CompletableFuture;

/**
 * Functional interface representing the next handler in a middleware chain.
 */
@FunctionalInterface
public interface Handler {
    CompletableFuture<HandlerResult> handle(UnifiedMessage message);
}
