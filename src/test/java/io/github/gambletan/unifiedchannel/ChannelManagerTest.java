package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.AccessMiddleware;
import io.github.gambletan.unifiedchannel.middleware.CommandMiddleware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ChannelManagerTest {

    private ChannelManager manager;
    private MockAdapter adapter1;
    private MockAdapter adapter2;

    @BeforeEach
    void setUp() {
        manager = new ChannelManager();
        adapter1 = new MockAdapter("channel1");
        adapter2 = new MockAdapter("channel2");
    }

    @Test
    void addChannel() {
        manager.addChannel(adapter1);
        assertEquals(List.of("channel1"), manager.getChannelIds());
    }

    @Test
    void addDuplicateChannelThrows() {
        manager.addChannel(adapter1);
        var dup = new MockAdapter("channel1");
        assertThrows(IllegalArgumentException.class, () -> manager.addChannel(dup));
    }

    @Test
    void getAdapter() {
        manager.addChannel(adapter1);
        assertSame(adapter1, manager.getAdapter("channel1"));
        assertNull(manager.getAdapter("nonexistent"));
    }

    @Test
    void runConnectsAll() throws Exception {
        manager.addChannel(adapter1).addChannel(adapter2);
        manager.run().get(5, TimeUnit.SECONDS);

        assertTrue(manager.isRunning());
        assertEquals(ChannelStatus.State.CONNECTED, adapter1.getStatus().state());
        assertEquals(ChannelStatus.State.CONNECTED, adapter2.getStatus().state());
    }

    @Test
    void shutdownDisconnectsAll() throws Exception {
        manager.addChannel(adapter1).addChannel(adapter2);
        manager.run().get(5, TimeUnit.SECONDS);
        manager.shutdown().get(5, TimeUnit.SECONDS);

        assertFalse(manager.isRunning());
        assertEquals(ChannelStatus.State.DISCONNECTED, adapter1.getStatus().state());
        assertEquals(ChannelStatus.State.DISCONNECTED, adapter2.getStatus().state());
    }

    @Test
    void getStatus() throws Exception {
        manager.addChannel(adapter1).addChannel(adapter2);
        manager.run().get(5, TimeUnit.SECONDS);

        var statuses = manager.getStatus();
        assertEquals(2, statuses.size());
        assertEquals(ChannelStatus.State.CONNECTED, statuses.get("channel1").state());
        assertEquals(ChannelStatus.State.CONNECTED, statuses.get("channel2").state());

        var single = manager.getStatus("channel1");
        assertNotNull(single);
        assertNull(manager.getStatus("nonexistent"));
    }

    @Test
    void send() throws Exception {
        manager.addChannel(adapter1);
        adapter1.connect().get();

        var msg = OutboundMessage.text("c1", "hello");
        manager.send("channel1", msg).get(5, TimeUnit.SECONDS);

        assertEquals(1, adapter1.sentMessages.size());
        assertEquals("hello", adapter1.sentMessages.get(0).text());
    }

    @Test
    void sendToUnknownChannelFails() {
        var msg = OutboundMessage.text("c1", "hello");
        var future = manager.send("nonexistent", msg);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void broadcast() throws Exception {
        manager.addChannel(adapter1).addChannel(adapter2);
        manager.run().get(5, TimeUnit.SECONDS);

        var msg = OutboundMessage.text("c1", "broadcast");
        manager.broadcast(msg).get(5, TimeUnit.SECONDS);

        assertEquals(1, adapter1.sentMessages.size());
        assertEquals(1, adapter2.sentMessages.size());
    }

    @Test
    void inboundMessageReachesListener() throws Exception {
        var received = new CopyOnWriteArrayList<UnifiedMessage>();
        var latch = new CountDownLatch(1);

        manager.addChannel(adapter1);
        manager.onMessage(msg -> {
            received.add(msg);
            latch.countDown();
        });
        manager.run().get(5, TimeUnit.SECONDS);

        // Simulate inbound message
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).text());
    }

    @Test
    void middlewarePipeline() throws Exception {
        var received = new CopyOnWriteArrayList<UnifiedMessage>();
        var latch = new CountDownLatch(1);

        var access = new AccessMiddleware(Set.of("allowed-user"));
        manager.addChannel(adapter1);
        manager.addMiddleware(access);
        manager.onMessage(msg -> {
            received.add(msg);
            latch.countDown();
        });
        manager.run().get(5, TimeUnit.SECONDS);

        // Blocked message
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("blocked-user"))
                .chatId("c1")
                .content(MessageContent.text("hi"))
                .build());

        // Give it a moment to process
        Thread.sleep(100);
        assertEquals(0, received.size());

        // Allowed message
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("allowed-user"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).text());
    }

    @Test
    void multipleMiddlewareChain() throws Exception {
        var received = new CopyOnWriteArrayList<UnifiedMessage>();
        var latch = new CountDownLatch(1);

        var access = new AccessMiddleware(Set.of("admin"));
        var commands = new CommandMiddleware();
        commands.registerSync("ping", ctx -> HandlerResult.text("pong"));

        manager.addChannel(adapter1);
        manager.addMiddleware(access);
        manager.addMiddleware(commands);
        manager.onMessage(msg -> {
            received.add(msg);
            latch.countDown();
        });
        manager.run().get(5, TimeUnit.SECONDS);

        // Non-command message passes through to listener
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("admin"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
    }

    @Test
    void commandMiddlewareInterceptsCommands() throws Exception {
        var received = new CopyOnWriteArrayList<UnifiedMessage>();

        var commands = new CommandMiddleware();
        commands.registerSync("ping", ctx -> HandlerResult.text("pong"));

        manager.addChannel(adapter1);
        manager.addMiddleware(commands);
        manager.onMessage(received::add);
        manager.run().get(5, TimeUnit.SECONDS);

        // Command message: intercepted by middleware, does NOT reach listener
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("/ping"))
                .build());

        Thread.sleep(200);
        // The command handler returns a result, so the terminal handler is not called
        assertEquals(0, received.size());

        // Non-command: passes through
        var latch = new CountDownLatch(1);
        manager.onMessage(msg -> latch.countDown());
        adapter1.simulateInbound(UnifiedMessage.builder()
                .channelId("channel1")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    // --- Mock Adapter ---

    static class MockAdapter implements ChannelAdapter {
        final String id;
        final List<Consumer<UnifiedMessage>> listeners = new CopyOnWriteArrayList<>();
        final List<OutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        volatile ChannelStatus status;

        MockAdapter(String id) {
            this.id = id;
            this.status = ChannelStatus.disconnected(id);
        }

        @Override public String channelId() { return id; }

        @Override
        public CompletableFuture<Void> connect() {
            status = ChannelStatus.connected(id);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disconnect() {
            status = ChannelStatus.disconnected(id);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onMessage(Consumer<UnifiedMessage> listener) {
            listeners.add(listener);
        }

        @Override
        public CompletableFuture<Void> send(OutboundMessage message) {
            sentMessages.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public ChannelStatus getStatus() { return status; }

        void simulateInbound(UnifiedMessage msg) {
            for (var listener : listeners) {
                listener.accept(msg);
            }
        }
    }
}
