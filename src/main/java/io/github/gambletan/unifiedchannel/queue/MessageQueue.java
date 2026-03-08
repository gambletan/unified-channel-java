package io.github.gambletan.unifiedchannel.queue;

import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for a message queue that decouples receiving from processing.
 */
public interface MessageQueue {

    /**
     * Push a message onto the queue.
     *
     * @param message the message to enqueue
     * @return true if accepted, false if queue is full
     */
    boolean enqueue(UnifiedMessage message);

    /**
     * Register the processing callback.
     */
    void onProcess(MessageProcessor processor);

    /**
     * Start consuming messages.
     */
    void start();

    /**
     * Stop consuming (in-flight items finish).
     */
    void stop();

    /**
     * Current number of queued (unprocessed) items.
     */
    int size();

    /**
     * Returns a future that completes when the queue is empty and all in-flight work completes.
     */
    CompletableFuture<Void> drain();
}
