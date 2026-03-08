package io.github.gambletan.unifiedchannel.memory;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Middleware that maintains conversation history per chat.
 * <p>
 * On each inbound message:
 * <ol>
 *   <li>Loads existing history for the conversation key (channel:chatId)</li>
 *   <li>Appends the user message to history</li>
 *   <li>Stores history in the message metadata (accessible via raw map)</li>
 *   <li>Delegates to the next handler</li>
 *   <li>If the handler returns a text reply, saves it as an assistant message</li>
 *   <li>Trims history to maxTurns</li>
 * </ol>
 */
public final class ConversationMemory implements Middleware {

    private final MemoryStore store;
    private final int maxTurns;

    public ConversationMemory() {
        this(new InMemoryStore(), 50);
    }

    public ConversationMemory(MemoryStore store, int maxTurns) {
        this.store = store;
        this.maxTurns = maxTurns;
    }

    /** Get the underlying memory store. */
    public MemoryStore getStore() {
        return store;
    }

    /** Get the max turns setting. */
    public int getMaxTurns() {
        return maxTurns;
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        var key = conversationKey(message);

        // Save user message
        var userEntry = new HistoryEntry(
                "user",
                message.text(),
                message.sender().id(),
                message.timestamp().toString());
        store.append(key, userEntry);

        // Get history for downstream consumers
        var history = store.get(key);

        // Build a new message with history in metadata
        var enriched = UnifiedMessage.builder()
                .channelId(message.channelId())
                .messageId(message.messageId())
                .sender(message.sender())
                .chatId(message.chatId())
                .content(message.content())
                .replyToId(message.replyToId())
                .threadId(message.threadId())
                .timestamp(message.timestamp())
                .raw(java.util.Map.of("history", history))
                .build();

        return next.handle(enriched).thenApply(result -> {
            // Save assistant reply to history
            if (result instanceof HandlerResult.TextReply textReply) {
                store.append(key, new HistoryEntry("assistant", textReply.text()));
            }
            // Trim to max turns
            store.trim(key, maxTurns);
            return result;
        });
    }

    private static String conversationKey(UnifiedMessage message) {
        return message.channelId() + ":" + message.chatId();
    }
}
