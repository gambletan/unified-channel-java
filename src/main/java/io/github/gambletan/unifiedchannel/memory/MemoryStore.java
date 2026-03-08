package io.github.gambletan.unifiedchannel.memory;

import java.util.List;

/**
 * Interface for conversation history storage backends.
 * <p>
 * Keys are typically derived from channel + chatId to keep per-conversation history.
 */
public interface MemoryStore {

    /** Get all history entries for the given key. */
    List<HistoryEntry> get(String key);

    /** Append an entry to the history for the given key. */
    void append(String key, HistoryEntry entry);

    /** Trim history to at most maxEntries (oldest entries removed first). */
    void trim(String key, int maxEntries);

    /** Clear all history for the given key. */
    void clear(String key);
}
