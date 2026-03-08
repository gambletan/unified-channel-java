package io.github.gambletan.unifiedchannel.memory;

import java.util.Objects;

/**
 * A single entry in conversation history.
 *
 * @param role      the role of the message author ("user", "assistant", "system")
 * @param content   the message content
 * @param sender    optional sender identifier
 * @param timestamp ISO-8601 timestamp string
 */
public record HistoryEntry(String role, String content, String sender, String timestamp) {

    public HistoryEntry {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public HistoryEntry(String role, String content) {
        this(role, content, null, java.time.Instant.now().toString());
    }

    public HistoryEntry(String role, String content, String sender) {
        this(role, content, sender, java.time.Instant.now().toString());
    }
}
