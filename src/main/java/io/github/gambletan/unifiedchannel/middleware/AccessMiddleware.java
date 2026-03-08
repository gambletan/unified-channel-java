package io.github.gambletan.unifiedchannel.middleware;

import io.github.gambletan.unifiedchannel.Handler;
import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.Middleware;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Middleware that enforces an allowlist of sender IDs.
 * Messages from senders not in the allowlist are silently dropped.
 * If the allowlist is empty, all messages are allowed (open mode).
 */
public final class AccessMiddleware implements Middleware {

    private static final Logger LOG = Logger.getLogger(AccessMiddleware.class.getName());

    private final Set<String> allowedSenderIds;
    private final boolean denyByDefault;

    /**
     * @param allowedSenderIds initial set of allowed sender IDs
     * @param denyByDefault    if true, messages from unknown senders are dropped even if the list is empty
     */
    public AccessMiddleware(Set<String> allowedSenderIds, boolean denyByDefault) {
        this.allowedSenderIds = ConcurrentHashMap.newKeySet();
        this.allowedSenderIds.addAll(allowedSenderIds);
        this.denyByDefault = denyByDefault;
    }

    /** Creates an access middleware with the given allowlist. Open mode if list is empty. */
    public AccessMiddleware(Set<String> allowedSenderIds) {
        this(allowedSenderIds, false);
    }

    /** Creates an open-mode middleware (all senders allowed). */
    public AccessMiddleware() {
        this(Set.of(), false);
    }

    /** Add a sender ID to the allowlist at runtime. */
    public void allow(String senderId) {
        allowedSenderIds.add(senderId);
    }

    /** Remove a sender ID from the allowlist at runtime. */
    public void deny(String senderId) {
        allowedSenderIds.remove(senderId);
    }

    /** Get an unmodifiable view of current allowed sender IDs. */
    public Set<String> getAllowed() {
        return Collections.unmodifiableSet(allowedSenderIds);
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        // Open mode: empty list + not deny-by-default => allow all
        if (allowedSenderIds.isEmpty() && !denyByDefault) {
            return next.handle(message);
        }

        var senderId = message.sender().id();
        if (allowedSenderIds.contains(senderId)) {
            return next.handle(message);
        }

        LOG.fine(() -> "Access denied for sender " + senderId + " on channel " + message.channelId());
        return CompletableFuture.completedFuture(HandlerResult.empty());
    }
}
