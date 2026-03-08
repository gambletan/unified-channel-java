package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.memory.ConversationMemory;
import io.github.gambletan.unifiedchannel.memory.HistoryEntry;
import io.github.gambletan.unifiedchannel.memory.InMemoryStore;
import io.github.gambletan.unifiedchannel.memory.SQLiteStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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

    // --- New tests ---

    @Test
    void multipleChatIsolation() throws Exception {
        var memory = new ConversationMemory(store, 50);

        var msg1 = UnifiedMessage.builder()
                .channelId("telegram")
                .sender(new Identity("u1"))
                .chatId("chat-a")
                .content(MessageContent.text("hello from A"))
                .build();
        var msg2 = UnifiedMessage.builder()
                .channelId("telegram")
                .sender(new Identity("u2"))
                .chatId("chat-b")
                .content(MessageContent.text("hello from B"))
                .build();

        Handler h = m -> CompletableFuture.completedFuture(HandlerResult.text("reply"));
        memory.process(msg1, h).get();
        memory.process(msg2, h).get();

        var historyA = store.get("telegram:chat-a");
        var historyB = store.get("telegram:chat-b");
        assertEquals(2, historyA.size()); // user + assistant
        assertEquals(2, historyB.size());
        assertEquals("hello from A", historyA.get(0).content());
        assertEquals("hello from B", historyB.get(0).content());
    }

    @Test
    void maxTurnsTrimmingExact() throws Exception {
        var memory = new ConversationMemory(store, 2);

        Handler h = m -> CompletableFuture.completedFuture(HandlerResult.text("reply"));

        // Send 3 messages (each creates 2 entries: user + assistant = 6 total before trim)
        for (int i = 0; i < 3; i++) {
            var msg = UnifiedMessage.builder()
                    .channelId("ch")
                    .sender(new Identity("u1"))
                    .chatId("c1")
                    .content(MessageContent.text("msg" + i))
                    .build();
            memory.process(msg, h).get();
        }

        var history = store.get("ch:c1");
        assertTrue(history.size() <= 2, "History should be trimmed to maxTurns=" + 2);
    }

    @Test
    void historyOrderingChronological() {
        store.append("key", new HistoryEntry("user", "first"));
        store.append("key", new HistoryEntry("assistant", "second"));
        store.append("key", new HistoryEntry("user", "third"));

        var history = store.get("key");
        assertEquals(3, history.size());
        assertEquals("first", history.get(0).content());
        assertEquals("second", history.get(1).content());
        assertEquals("third", history.get(2).content());
    }

    @Test
    void middlewareEmptyResultDoesNotSaveAssistant() throws Exception {
        var memory = new ConversationMemory(store, 50);

        var msg = UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build();

        // Handler returns empty (e.g. message dropped)
        Handler h = m -> CompletableFuture.completedFuture(HandlerResult.empty());
        memory.process(msg, h).get();

        var history = store.get("test:c1");
        assertEquals(1, history.size()); // Only user message, no assistant
        assertEquals("user", history.get(0).role());
    }

    // --- SQLiteStore tests ---

    @Test
    void sqliteStore_basicCrud(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db").toString();
        var sqlStore = new SQLiteStore(dbPath);

        // Append
        sqlStore.append("key1", new HistoryEntry("user", "hello", "sender1"));
        sqlStore.append("key1", new HistoryEntry("assistant", "hi back"));

        // Get
        var history = sqlStore.get("key1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hello", history.get(0).content());
        assertEquals("sender1", history.get(0).sender());
        assertEquals("assistant", history.get(1).role());
    }

    @Test
    void sqliteStore_getEmpty(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db").toString();
        var sqlStore = new SQLiteStore(dbPath);

        var history = sqlStore.get("nonexistent");
        assertTrue(history.isEmpty());
    }

    @Test
    void sqliteStore_trim(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db").toString();
        var sqlStore = new SQLiteStore(dbPath);

        for (int i = 0; i < 10; i++) {
            sqlStore.append("key1", new HistoryEntry("user", "msg" + i));
        }
        assertEquals(10, sqlStore.get("key1").size());

        sqlStore.trim("key1", 3);
        var trimmed = sqlStore.get("key1");
        assertEquals(3, trimmed.size());
        // Should keep the latest 3
        assertEquals("msg7", trimmed.get(0).content());
        assertEquals("msg9", trimmed.get(2).content());
    }

    @Test
    void sqliteStore_clear(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db").toString();
        var sqlStore = new SQLiteStore(dbPath);

        sqlStore.append("key1", new HistoryEntry("user", "hello"));
        sqlStore.append("key1", new HistoryEntry("assistant", "hi"));
        assertEquals(2, sqlStore.get("key1").size());

        sqlStore.clear("key1");
        assertTrue(sqlStore.get("key1").isEmpty());
    }

    @Test
    void sqliteStore_separateKeys(@TempDir Path tempDir) {
        var dbPath = tempDir.resolve("test.db").toString();
        var sqlStore = new SQLiteStore(dbPath);

        sqlStore.append("chat1", new HistoryEntry("user", "msg1"));
        sqlStore.append("chat2", new HistoryEntry("user", "msg2"));

        assertEquals(1, sqlStore.get("chat1").size());
        assertEquals(1, sqlStore.get("chat2").size());
        assertEquals("msg1", sqlStore.get("chat1").get(0).content());
        assertEquals("msg2", sqlStore.get("chat2").get(0).content());
    }
}
