package io.github.gambletan.unifiedchannel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    private ChannelManager manager;
    private MockAdapter adapter;
    private Scheduler scheduler;

    @BeforeEach
    void setUp() {
        manager = new ChannelManager();
        adapter = new MockAdapter("telegram");
        manager.addChannel(adapter);
        manager.run().join();
        scheduler = new Scheduler(manager);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
        manager.shutdown().join();
    }

    @Test
    void everySchedulesRepeatingTask() throws Exception {
        scheduler.every(50, "telegram", "chat1", "hello");
        Thread.sleep(180);

        assertTrue(adapter.sentMessages.size() >= 3,
                "Expected at least 3 sends, got " + adapter.sentMessages.size());
        assertEquals("hello", adapter.sentMessages.get(0).text());
    }

    @Test
    void onceSendsSingleMessage() throws Exception {
        scheduler.once(50, "telegram", "chat1", "one-shot");
        Thread.sleep(150);

        assertEquals(1, adapter.sentMessages.size());
        assertEquals("one-shot", adapter.sentMessages.get(0).text());
    }

    @Test
    void cancelStopsRepeatingTask() throws Exception {
        String taskId = scheduler.every(50, "telegram", "chat1", "ping");
        Thread.sleep(120);
        int countBefore = adapter.sentMessages.size();
        assertTrue(countBefore >= 2);

        scheduler.cancel(taskId);
        Thread.sleep(100);
        assertEquals(countBefore, adapter.sentMessages.size());
    }

    @Test
    void cancelReturnsFalseForUnknown() {
        assertFalse(scheduler.cancel("nonexistent"));
    }

    @Test
    void listReturnsActiveOnly() {
        String id1 = scheduler.every(10_000, "telegram", "c1", "a");
        String id2 = scheduler.once(10_000, "telegram", "c2", "b");
        scheduler.cancel(id1);

        var active = scheduler.list();
        assertEquals(1, active.size());
        assertEquals(id2, active.get(0).id());
        assertEquals("once", active.get(0).type());
        assertTrue(active.get(0).active());
    }

    @Test
    void stopCancelsAll() throws Exception {
        scheduler.every(50, "telegram", "c1", "a");
        scheduler.every(50, "telegram", "c2", "b");

        scheduler.stop();
        Thread.sleep(100);

        assertEquals(0, adapter.sentMessages.size());
    }

    @Test
    void supplierCallback() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        Supplier<String> supplier = () -> "count: " + counter.incrementAndGet();

        scheduler.every(50, "telegram", "chat1", (Object) supplier);
        Thread.sleep(120);
        scheduler.stop();

        assertTrue(adapter.sentMessages.size() >= 2);
        assertEquals("count: 1", adapter.sentMessages.get(0).text());
        assertEquals("count: 2", adapter.sentMessages.get(1).text());
    }

    @Test
    void parseCronValid() {
        var parsed = Scheduler.parseCron("0 9 * * *");
        assertEquals(List.of(0), parsed.minute());
        assertEquals(List.of(9), parsed.hour());
        assertEquals(31, parsed.dom().size());
        assertEquals(12, parsed.month().size());
        assertEquals(7, parsed.dow().size());
    }

    @Test
    void parseCronCommas() {
        var parsed = Scheduler.parseCron("0,30 9,17 * * 1,5");
        assertEquals(List.of(0, 30), parsed.minute());
        assertEquals(List.of(9, 17), parsed.hour());
        assertEquals(List.of(1, 5), parsed.dow());
    }

    @Test
    void parseCronInvalid() {
        assertThrows(IllegalArgumentException.class, () -> Scheduler.parseCron("0 9 *"));
    }

    @Test
    void parseCronOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> Scheduler.parseCron("60 9 * * *"));
    }

    @Test
    void cronMatchesCorrectly() {
        var parsed = Scheduler.parseCron("30 14 * * *");
        // 2026-03-04 14:30 Wednesday
        var match = LocalDateTime.of(2026, 3, 4, 14, 30, 0);
        assertTrue(Scheduler.cronMatches(parsed, match));

        var noMatch = LocalDateTime.of(2026, 3, 4, 14, 31, 0);
        assertFalse(Scheduler.cronMatches(parsed, noMatch));
    }

    // -- Mock adapter --

    private static class MockAdapter implements ChannelAdapter {
        private final String channelId;
        final CopyOnWriteArrayList<OutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        private Consumer<UnifiedMessage> listener;

        MockAdapter(String channelId) {
            this.channelId = channelId;
        }

        @Override public String channelId() { return channelId; }

        @Override
        public CompletableFuture<Void> send(OutboundMessage message) {
            sentMessages.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onMessage(Consumer<UnifiedMessage> listener) {
            this.listener = listener;
        }

        @Override
        public CompletableFuture<Void> connect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ChannelStatus getStatus() {
            return new ChannelStatus(channelId, ChannelStatus.State.CONNECTED, null, null);
        }
    }
}
