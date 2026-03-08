package io.github.gambletan.unifiedchannel.queue;

import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Convenience: creates a processor that pulls from the queue and sends replies
 * through the provided send function.
 */
public class QueueProcessor {

    private final MessageQueue queue;
    private final BiFunction<String, HandlerResult, CompletableFuture<Void>> sendReply;

    public QueueProcessor(
            MessageQueue queue,
            BiFunction<String, HandlerResult, CompletableFuture<Void>> sendReply
    ) {
        this.queue = queue;
        this.sendReply = sendReply;
    }

    /**
     * Wire up a handler and start the queue.
     */
    public void start(MessageProcessor handler) {
        queue.onProcess(msg -> handler.process(msg).thenCompose(result -> {
            if (result != null && !(result instanceof HandlerResult.Empty) && msg.chatId() != null) {
                return sendReply.apply(msg.chatId(), result);
            }
            return CompletableFuture.completedFuture(null);
        }).thenApply(v -> HandlerResult.empty()));
        queue.start();
    }

    /**
     * Stop the queue.
     */
    public void stop() {
        queue.stop();
    }
}
