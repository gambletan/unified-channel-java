package io.github.gambletan.unifiedchannel.queue;

import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Functional interface for processing a queued message.
 */
@FunctionalInterface
public interface MessageProcessor {
    CompletableFuture<HandlerResult> process(UnifiedMessage message);
}
