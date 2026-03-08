package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.RateLimitMiddleware;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

    private static UnifiedMessage textMsg(String senderId) {
        return UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity(senderId))
                .chatId("chat1")
                .content(MessageContent.text("hello"))
                .build();
    }

    private static UnifiedMessage textMsg(String senderId, String chatId) {
        return UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity(senderId))
                .chatId(chatId)
                .content(MessageContent.text("hello"))
                .build();
    }

    private static final Handler PASS_THROUGH = msg ->
            CompletableFuture.completedFuture(HandlerResult.text("ok"));

    @Test
    void allowsMessagesUnderLimit() throws Exception {
        var mw = new RateLimitMiddleware(3, 10_000);
        var result = mw.process(textMsg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("ok", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void blocksWhenLimitReached() throws Exception {
        var mw = new RateLimitMiddleware(2, 10_000);
        mw.process(textMsg("user1"), PASS_THROUGH).get();
        mw.process(textMsg("user1"), PASS_THROUGH).get();

        var result = mw.process(textMsg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);
    }

    @Test
    void windowResetAllowsMessages() throws Exception {
        // Use a very short window
        var mw = new RateLimitMiddleware(1, 50);
        mw.process(textMsg("user1"), PASS_THROUGH).get();

        // Should be blocked
        var blocked = mw.process(textMsg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, blocked);

        // Wait for window to expire
        Thread.sleep(60);

        var result = mw.process(textMsg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
    }

    @Test
    void multipleUsersIndependent() throws Exception {
        var mw = new RateLimitMiddleware(1, 10_000);

        var r1 = mw.process(textMsg("alice"), PASS_THROUGH).get();
        var r2 = mw.process(textMsg("bob"), PASS_THROUGH).get();

        assertInstanceOf(HandlerResult.TextReply.class, r1);
        assertInstanceOf(HandlerResult.TextReply.class, r2);

        // Both at limit
        assertInstanceOf(HandlerResult.Empty.class,
                mw.process(textMsg("alice"), PASS_THROUGH).get());
        assertInstanceOf(HandlerResult.Empty.class,
                mw.process(textMsg("bob"), PASS_THROUGH).get());
    }

    @Test
    void customKeyFunction() throws Exception {
        // Key by chatId instead of sender
        var mw = new RateLimitMiddleware(1, 10_000, msg -> msg.chatId(), null);

        mw.process(textMsg("alice", "room1"), PASS_THROUGH).get();

        // Same room, different user — blocked
        var result = mw.process(textMsg("bob", "room1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);

        // Different room — allowed
        var r2 = mw.process(textMsg("alice", "room2"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, r2);
    }

    @Test
    void customReplyText() throws Exception {
        var mw = new RateLimitMiddleware(1, 10_000, null, "Slow down!");
        mw.process(textMsg("user1"), PASS_THROUGH).get();

        var result = mw.process(textMsg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("Slow down!", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void burstAtExactLimit() throws Exception {
        var mw = new RateLimitMiddleware(5, 10_000);

        for (int i = 0; i < 5; i++) {
            var result = mw.process(textMsg("user1"), PASS_THROUGH).get();
            assertInstanceOf(HandlerResult.TextReply.class, result);
        }

        // 6th blocked
        assertInstanceOf(HandlerResult.Empty.class,
                mw.process(textMsg("user1"), PASS_THROUGH).get());
    }

    @Test
    void cleanupRemovesExpired() throws Exception {
        var mw = new RateLimitMiddleware(1, 50);
        mw.process(textMsg("alice"), PASS_THROUGH).get();
        mw.process(textMsg("bob"), PASS_THROUGH).get();

        Thread.sleep(60);
        mw.cleanup();

        // Both should be able to send again
        assertInstanceOf(HandlerResult.TextReply.class,
                mw.process(textMsg("alice"), PASS_THROUGH).get());
        assertInstanceOf(HandlerResult.TextReply.class,
                mw.process(textMsg("bob"), PASS_THROUGH).get());
    }
}
