package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.queue.*;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MessageQueueTest {

    private static UnifiedMessage textMsg(String id, String text) {
        return UnifiedMessage.builder()
                .channelId("test")
                .messageId(id)
                .sender(new Identity("user1"))
                .chatId("chat1")
                .content(MessageContent.text(text))
                .build();
    }

    @Test
    void enqueueAndSize() {
        InMemoryQueue queue = new InMemoryQueue(2, 5);
        assertTrue(queue.enqueue(textMsg("1", "a")));
        assertTrue(queue.enqueue(textMsg("2", "b")));
        assertEquals(2, queue.size());
    }

    @Test
    void maxSizeOverflow() {
        InMemoryQueue queue = new InMemoryQueue(1, 3);
        for (int i = 0; i < 3; i++) {
            assertTrue(queue.enqueue(textMsg(String.valueOf(i), "msg" + i)));
        }
        assertEquals(3, queue.size());
        // 4th should be rejected
        assertFalse(queue.enqueue(textMsg("3", "overflow")));
        assertEquals(3, queue.size());
    }

    @Test
    void processMessages() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(2, 10);
        List<String> processed = new CopyOnWriteArrayList<>();

        queue.enqueue(textMsg("1", "hello"));
        queue.enqueue(textMsg("2", "world"));

        queue.onProcess(msg -> {
            processed.add(msg.text());
            return CompletableFuture.completedFuture(HandlerResult.text(msg.text()));
        });
        queue.start();
        queue.drain().get(5, TimeUnit.SECONDS);
        queue.stop();

        assertEquals(List.of("hello", "world"), processed);
        assertEquals(0, queue.size());
    }

    @Test
    void concurrencyLimit() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(2, 10);
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        List<CountDownLatch> latches = new CopyOnWriteArrayList<>();

        queue.onProcess(msg -> {
            int c = concurrent.incrementAndGet();
            maxConcurrent.accumulateAndGet(c, Math::max);
            CountDownLatch latch = new CountDownLatch(1);
            latches.add(latch);
            try {
                latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrent.decrementAndGet();
            return CompletableFuture.completedFuture(HandlerResult.empty());
        });

        for (int i = 0; i < 4; i++) {
            queue.enqueue(textMsg(String.valueOf(i), "msg" + i));
        }
        queue.start();

        // Wait for workers to pick up
        Thread.sleep(200);
        assertEquals(2, maxConcurrent.get());

        // Release all latches
        for (CountDownLatch l : latches) l.countDown();
        // Wait a bit for remaining items
        Thread.sleep(200);
        for (CountDownLatch l : latches) l.countDown();

        queue.drain().get(5, TimeUnit.SECONDS);
        queue.stop();
        assertEquals(0, concurrent.get());
    }

    @Test
    void drainEmptyQueue() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(2, 10);
        queue.onProcess(msg -> CompletableFuture.completedFuture(HandlerResult.empty()));
        queue.start();
        // Should not hang
        queue.drain().get(2, TimeUnit.SECONDS);
        queue.stop();
    }

    @Test
    void stopPreventsProcessing() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(1, 10);
        List<String> processed = new CopyOnWriteArrayList<>();

        queue.onProcess(msg -> {
            processed.add(msg.text());
            return CompletableFuture.completedFuture(HandlerResult.empty());
        });

        queue.enqueue(textMsg("1", "before"));
        queue.start();
        queue.drain().get(5, TimeUnit.SECONDS);
        assertEquals(List.of("before"), processed);

        queue.stop();
        // Re-create since executor is shut down
        InMemoryQueue queue2 = new InMemoryQueue(1, 10);
        queue2.enqueue(textMsg("2", "after-stop"));
        assertEquals(1, queue2.size());
    }

    @Test
    void errorHandling() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(2, 10);
        AtomicInteger callCount = new AtomicInteger(0);

        queue.onProcess(msg -> {
            callCount.incrementAndGet();
            if ("fail".equals(msg.text())) {
                return CompletableFuture.failedFuture(new RuntimeException("boom"));
            }
            return CompletableFuture.completedFuture(HandlerResult.text(msg.text()));
        });

        queue.enqueue(textMsg("1", "fail"));
        queue.enqueue(textMsg("2", "ok"));
        queue.start();
        queue.drain().get(5, TimeUnit.SECONDS);
        queue.stop();

        // Both processed despite error in first
        assertEquals(2, callCount.get());
    }

    @Test
    void queueMiddleware() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(1, 10);
        QueueMiddleware mw = new QueueMiddleware(queue);

        AtomicInteger nextCalled = new AtomicInteger(0);
        Handler next = msg -> {
            nextCalled.incrementAndGet();
            return CompletableFuture.completedFuture(HandlerResult.text("should not reach"));
        };

        UnifiedMessage msg = textMsg("1", "hello");
        HandlerResult result = mw.process(msg, next).get(2, TimeUnit.SECONDS);

        assertInstanceOf(HandlerResult.Empty.class, result);
        assertEquals(1, queue.size());
        assertEquals(0, nextCalled.get());
    }

    @Test
    void queueMiddlewareFull() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(1, 1);
        QueueMiddleware mw = new QueueMiddleware(queue);

        queue.enqueue(textMsg("1", "fill"));
        HandlerResult result = mw.process(textMsg("2", "overflow"),
                msg -> CompletableFuture.completedFuture(HandlerResult.empty()))
                .get(2, TimeUnit.SECONDS);

        assertInstanceOf(HandlerResult.Empty.class, result);
        assertEquals(1, queue.size());
    }

    @Test
    void queueProcessor() throws Exception {
        InMemoryQueue queue = new InMemoryQueue(2, 10);
        List<String> sentReplies = new CopyOnWriteArrayList<>();

        QueueProcessor processor = new QueueProcessor(queue,
                (chatId, result) -> {
                    if (result instanceof HandlerResult.TextReply tr) {
                        sentReplies.add(chatId + ":" + tr.text());
                    }
                    return CompletableFuture.completedFuture(null);
                });

        queue.enqueue(textMsg("1", "hello"));
        queue.enqueue(textMsg("2", "world"));

        processor.start(msg ->
                CompletableFuture.completedFuture(HandlerResult.text("reply: " + msg.text())));

        queue.drain().get(5, TimeUnit.SECONDS);
        processor.stop();

        assertEquals(2, sentReplies.size());
        assertTrue(sentReplies.contains("chat1:reply: hello"));
        assertTrue(sentReplies.contains("chat1:reply: world"));
    }
}
