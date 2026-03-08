package io.github.gambletan.unifiedchannel.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link MemoryStore}.
 * Thread-safe via ConcurrentHashMap and synchronized list access.
 */
public final class InMemoryStore implements MemoryStore {

    private final Map<String, List<HistoryEntry>> store = new ConcurrentHashMap<>();

    @Override
    public List<HistoryEntry> get(String key) {
        var entries = store.get(key);
        if (entries == null) return List.of();
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    @Override
    public void append(String key, HistoryEntry entry) {
        store.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(entry);
    }

    @Override
    public void trim(String key, int maxEntries) {
        var entries = store.get(key);
        if (entries == null) return;
        synchronized (entries) {
            while (entries.size() > maxEntries) {
                entries.removeFirst();
            }
        }
    }

    @Override
    public void clear(String key) {
        store.remove(key);
    }
}
