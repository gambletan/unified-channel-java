package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.memory.ConversationMemory;
import io.github.gambletan.unifiedchannel.memory.InMemoryStore;
import io.github.gambletan.unifiedchannel.middleware.AccessMiddleware;
import io.github.gambletan.unifiedchannel.middleware.CommandMiddleware;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration tests exercising the full pipeline:
 * adapter -> middleware chain -> handler -> outbound.
 */
class IntegrationTest {

    private static UnifiedMessage inbound(String channelId, String senderId, String chatId, String text) {
        return UnifiedMessage.builder()
                .channelId(channelId)
                .sender(new Identity(senderId))
                .chatId(chatId)
                .content(MessageContent.text(text))
                .build();
    }

    @Test
    void fullPipeline_mockAdapterThroughMiddlewareToHandler() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        var received = new CopyOnWriteArrayList<UnifiedMessage>();
        var latch = new CountDownLatch(1);

        var access = new AccessMiddleware(Set.of("admin"));
        var commands = new CommandMiddleware();
        commands.registerSync("ping", ctx -> HandlerResult.text("pong"));

        manager.addChannel(adapter);
        manager.addMiddleware(access);
        manager.addMiddleware(commands);
        manager.onMessage(msg -> {
            received.add(msg);
            latch.countDown();
        });
        manager.run().get(5, TimeUnit.SECONDS);

        // Non-command from allowed user reaches terminal handler
        adapter.simulateInbound(inbound("telegram", "admin", "chat1", "hello"));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0).text());
    }

    @Test
    void fullPipeline_commandInterceptedBeforeTerminal() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        var received = new CopyOnWriteArrayList<UnifiedMessage>();

        var commands = new CommandMiddleware();
        commands.registerSync("status", ctx -> HandlerResult.text("all good"));

        manager.addChannel(adapter);
        manager.addMiddleware(commands);
        manager.onMessage(received::add);
        manager.run().get(5, TimeUnit.SECONDS);

        // /status should be intercepted
        adapter.simulateInbound(inbound("telegram", "u1", "c1", "/status"));
        Thread.sleep(200);
        assertEquals(0, received.size());
    }

    @Test
    void fullPipeline_accessBlocksBeforeCommand() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        var received = new CopyOnWriteArrayList<UnifiedMessage>();

        var access = new AccessMiddleware(Set.of("admin"));
        var commands = new CommandMiddleware();
        commands.registerSync("secret", ctx -> HandlerResult.text("classified"));

        manager.addChannel(adapter);
        manager.addMiddleware(access);
        manager.addMiddleware(commands);
        manager.onMessage(received::add);
        manager.run().get(5, TimeUnit.SECONDS);

        // Unauthorized user tries a command -> blocked by access middleware
        adapter.simulateInbound(inbound("telegram", "hacker", "c1", "/secret"));
        Thread.sleep(200);
        assertEquals(0, received.size());
    }

    @Test
    void serviceBridgeThroughPipeline() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("discord");
        manager.addChannel(adapter);

        var bridge = new ServiceBridge(manager);
        bridge.expose("deploy", args -> "Deployed " + (args.length > 0 ? args[0] : "all"), "Deploy service");
        bridge.exposeStatus(() -> "Systems nominal");

        manager.run().get(5, TimeUnit.SECONDS);

        // Commands are registered
        assertTrue(bridge.getCommands().containsKey("deploy"));
        assertTrue(bridge.getCommands().containsKey("status"));
        assertTrue(bridge.getCommands().containsKey("help"));
    }

    @Test
    void broadcastToMultipleAdapters() throws Exception {
        var manager = new ChannelManager();
        var tg = new TestAdapter("telegram");
        var dc = new TestAdapter("discord");
        var sl = new TestAdapter("slack");

        manager.addChannel(tg).addChannel(dc).addChannel(sl);
        manager.run().get(5, TimeUnit.SECONDS);

        manager.broadcast(OutboundMessage.text("global", "Hello everyone")).get(5, TimeUnit.SECONDS);

        assertEquals(1, tg.sentMessages.size());
        assertEquals(1, dc.sentMessages.size());
        assertEquals(1, sl.sentMessages.size());
        assertEquals("Hello everyone", tg.sentMessages.get(0).text());
    }

    @Test
    void conversationMemoryIntegration() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        var memStore = new InMemoryStore();
        var memory = new ConversationMemory(memStore, 50);

        manager.addChannel(adapter);
        manager.addMiddleware(memory);
        manager.onMessage(msg -> {
            // Terminal handler does nothing (empty result from pipeline)
        });
        manager.run().get(5, TimeUnit.SECONDS);

        adapter.simulateInbound(inbound("telegram", "u1", "chat1", "hello"));
        Thread.sleep(200);

        var history = memStore.get("telegram:chat1");
        assertEquals(1, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hello", history.get(0).content());
    }

    @Test
    void fullPipeline_multipleMessages() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        var received = new CopyOnWriteArrayList<UnifiedMessage>();
        var latch = new CountDownLatch(3);

        manager.addChannel(adapter);
        manager.onMessage(msg -> { received.add(msg); latch.countDown(); });
        manager.run().get(5, TimeUnit.SECONDS);

        adapter.simulateInbound(inbound("telegram", "u1", "c1", "msg1"));
        adapter.simulateInbound(inbound("telegram", "u2", "c2", "msg2"));
        adapter.simulateInbound(inbound("telegram", "u3", "c3", "msg3"));

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(3, received.size());
    }

    @Test
    void richReplyThroughPipeline() throws Exception {
        var manager = new ChannelManager();
        var adapter = new TestAdapter("telegram");
        manager.addChannel(adapter);

        var commands = new CommandMiddleware();
        commands.registerSync("report", ctx -> {
            var reply = new RichReply()
                    .text("Status Report")
                    .divider()
                    .table(List.of("Service", "State"), List.of(List.of("API", "UP"), List.of("DB", "UP")));
            return HandlerResult.text(reply.toPlainText());
        });

        manager.addMiddleware(commands);
        manager.run().get(5, TimeUnit.SECONDS);

        adapter.simulateInbound(inbound("telegram", "u1", "c1", "/report"));
        Thread.sleep(200);
        // Command was handled; verify no crash
    }

    // --- Test Adapter ---

    static class TestAdapter implements ChannelAdapter {
        final String id;
        final CopyOnWriteArrayList<Consumer<UnifiedMessage>> listeners = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<OutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        volatile ChannelStatus status;

        TestAdapter(String id) {
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
            for (var l : listeners) l.accept(msg);
        }
    }
}
