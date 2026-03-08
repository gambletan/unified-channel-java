package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.streaming.StreamingMiddleware;
import io.github.gambletan.unifiedchannel.streaming.StreamingReply;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class StreamingTest {

    @Test
    void streamingReplyCollect() {
        var chunks = List.of("Hello", " ", "World", "!").iterator();
        var reply = new StreamingReply(chunks);
        assertEquals("Hello World!", reply.collect());
    }

    @Test
    void streamingReplyHasNextAndNext() {
        var chunks = List.of("a", "b", "c").iterator();
        var reply = new StreamingReply(chunks);

        assertTrue(reply.hasNext());
        assertEquals("a", reply.next());
        assertTrue(reply.hasNext());
        assertEquals("b", reply.next());
        assertTrue(reply.hasNext());
        assertEquals("c", reply.next());
        assertFalse(reply.hasNext());
    }

    @Test
    void streamingReplyEmptyIterator() {
        var reply = new StreamingReply(List.<String>of().iterator());
        assertEquals("", reply.collect());
    }

    @Test
    void streamingMiddlewareCallsTypingCallback() throws Exception {
        var typingCalls = new CopyOnWriteArrayList<String>();
        var middleware = new StreamingMiddleware((channelId, chatId) ->
                typingCalls.add(channelId + ":" + chatId), 100);

        var msg = UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build();

        // Handler that takes a bit of time
        Handler slowHandler = m -> CompletableFuture.supplyAsync(() -> {
            try { Thread.sleep(350); } catch (InterruptedException ignored) {}
            return HandlerResult.text("done");
        });

        var result = middleware.process(msg, slowHandler).get(5, TimeUnit.SECONDS);

        assertInstanceOf(HandlerResult.TextReply.class, result);
        // Should have called typing at least once
        assertFalse(typingCalls.isEmpty());
        assertEquals("test:c1", typingCalls.get(0));

        middleware.shutdown();
    }

    @Test
    void streamingMiddlewareCancelsOnCompletion() throws Exception {
        var callCount = new AtomicInteger(0);
        var middleware = new StreamingMiddleware((channelId, chatId) ->
                callCount.incrementAndGet(), 50);

        var msg = UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity("u1"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .build();

        // Fast handler
        Handler fastHandler = m -> CompletableFuture.completedFuture(HandlerResult.text("quick"));
        middleware.process(msg, fastHandler).get(5, TimeUnit.SECONDS);

        var countAfterDone = callCount.get();
        Thread.sleep(200);
        // Count should not keep increasing after handler completes
        var countLater = callCount.get();
        assertTrue(countLater - countAfterDone <= 1, "Typing should stop after handler completes");

        middleware.shutdown();
    }

    @Test
    void streamingReplyNullThrows() {
        assertThrows(NullPointerException.class, () -> new StreamingReply(null));
    }
}
