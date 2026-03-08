package io.github.gambletan.unifiedchannel;

import java.util.concurrent.CompletableFuture;

/**
 * Middleware in the message processing pipeline.
 * Each middleware can inspect/modify the message, short-circuit, or delegate to the next handler.
 */
@FunctionalInterface
public interface Middleware {

    /**
     * Process an inbound message.
     *
     * @param message the inbound unified message
     * @param next    the next handler in the chain
     * @return a future resolving to the handler result
     */
    CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next);
}
