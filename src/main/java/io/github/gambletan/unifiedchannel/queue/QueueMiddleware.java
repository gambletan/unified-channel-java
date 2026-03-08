package io.github.gambletan.unifiedchannel.queue;

import io.github.gambletan.unifiedchannel.Handler;
import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.Middleware;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Middleware that intercepts messages and enqueues them instead of processing inline.
 * The actual processing happens asynchronously via the queue's onProcess callback.
 */
public class QueueMiddleware implements Middleware {

    private static final Logger logger = Logger.getLogger(QueueMiddleware.class.getName());

    private final MessageQueue queue;

    public QueueMiddleware(MessageQueue queue) {
        this.queue = queue;
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        boolean accepted = queue.enqueue(message);
        if (!accepted) {
            logger.warning("Queue full, dropping message " + message.messageId());
        }
        // Message will be processed asynchronously; return empty (no inline reply)
        return CompletableFuture.completedFuture(HandlerResult.empty());
    }
}
