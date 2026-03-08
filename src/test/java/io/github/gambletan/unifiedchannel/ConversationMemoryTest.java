package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.memory.ConversationMemory;
import io.github.gambletan.unifiedchannel.memory.HistoryEntry;
import io.github.gambletan.unifiedchannel.memory.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ConversationMemoryTest {

    private InMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
    }

    @Test
    void appendAndGet() {
        store.append("key1", new HistoryEntry("user", "hello"));
        store.append("key1", new HistoryEntry("assistant", "hi there"));

        var history = store.get("key1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hello", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void getEmptyKey() {
        var history = store.get("nonexistent");
        assertTrue(history.isEmpty());
    }

    @Test
    void trim() {
        for (int i = 0; i < 10; i++) {
            store.append("key1", new HistoryEntry("user", "msg" + i));
        }
        assertEquals(10, store.get("key1").size());

        store.trim("key1", 5);
        var trimmed = store.get("key1");
        assertEquals(5, trimmed.size());
        // Should keep the latest 5
        assertEquals("msg5", trimmed.get(0).content());
        assertEquals("msg9", trimmed.get(4).content());
    }

    @Test
    void clear() {
        store.append("key1", new HistoryEntry("user", "hello"));
        store.append("key1", new HistoryEntry("assistant", "hi"));
        assertEquals(2, store.get("key1").size());

        store.clear("key1");
        assertTrue(store.get("key1").isEmpty());
    }

    @Test
    void separateKeys() {
        store.append("chat1", new HistoryEntry("user", "msg1"));
        store.append("chat2", new HistoryEntry("user", "msg2"));

        assertEquals(1, store.get("chat1").size());
        assertEquals(1, store.get("chat2").size());
        assertEquals("msg1", store.get("chat1").get(0).content());
        assertEquals("msg2", store.get("chat2").get(0).content());
    }

    @Test
    void historyEntryRecord() {
        var entry = new HistoryEntry("user", "hello", "sender1", "2024-01-01T00:00:00Z");
        assertEquals("user", entry.role());
        assertEquals("hello", entry.content());
        assertEquals("sender1", entry.sender());
        assertEquals("2024-01-01T00:00:00Z", entry.timestamp());
    }

    @Test
    void historyEntryConvenienceConstructors() {
        var simple = new HistoryEntry("assistant", "response");
        assertEquals("assistant", simple.role());
        assertEquals("response", simple.content());
        assertNull(simple.sender());
        assertNotNull(simple.timestamp());

        var withSender = new HistoryEntry("user", "hello", "user123");
        assertEquals("user123", withSender.sender());
    }

    @Test
    void middlewareSavesUserAndAssistantMessages() throws Exception {
        var memory = new ConversationMemory(store, 50);

        var msg = UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build();

        // Process through middleware with a handler that returns a text reply
        Handler replyHandler = m -> CompletableFuture.completedFuture(HandlerResult.text("hi back"));
        var result = memory.process(msg, replyHandler).get();

        assertInstanceOf(HandlerResult.TextReply.class, result);

        var history = store.get("test:c1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hello", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("hi back", history.get(1).content());
    }

    @Test
    void middlewareTrimsHistory() throws Exception {
        var memory = new ConversationMemory(store, 3);

        // Add 5 messages through middleware
        for (int i = 0; i < 5; i++) {
            var msg = UnifiedMessage.builder()
                    .channelId("test")
                    .sender(new Identity("u1"))
                    .chatId("c1")
                    .content(MessageContent.text("msg" + i))
                    .build();
            final int idx = i;
            Handler h = m -> CompletableFuture.completedFuture(HandlerResult.text("reply" + idx));
            memory.process(msg, h).get();
        }

        var history = store.get("test:c1");
        // Should be trimmed to 3
        assertTrue(history.size() <= 3);
    }

    @Test
    void defaultConstructor() {
        var memory = new ConversationMemory();
        assertEquals(50, memory.getMaxTurns());
        assertNotNull(memory.getStore());
    }
}
