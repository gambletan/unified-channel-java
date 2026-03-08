package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.AccessMiddleware;
import io.github.gambletan.unifiedchannel.middleware.CommandMiddleware;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MiddlewareTest {

    private static UnifiedMessage textMsg(String senderId, String text) {
        return UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity(senderId, senderId))
                .chatId("chat1")
                .content(MessageContent.text(text))
                .build();
    }

    private static UnifiedMessage cmdMsg(String senderId, String text) {
        return UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity(senderId))
                .chatId("chat1")
                .content(MessageContent.command(text))
                .build();
    }

    private static final Handler PASS_THROUGH = msg ->
            CompletableFuture.completedFuture(HandlerResult.text("passed"));

    // --- AccessMiddleware ---

    @Test
    void accessMiddleware_openMode_allowsAll() throws Exception {
        var mw = new AccessMiddleware();
        var result = mw.process(textMsg("anyone", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
    }

    @Test
    void accessMiddleware_allowlist_allowsListed() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice", "bob"));
        var result = mw.process(textMsg("alice", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
    }

    @Test
    void accessMiddleware_allowlist_blocksUnlisted() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice"));
        var result = mw.process(textMsg("eve", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);
    }

    @Test
    void accessMiddleware_dynamicAllowDeny() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice"));

        // Eve blocked initially
        var r1 = mw.process(textMsg("eve", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, r1);

        // Allow Eve
        mw.allow("eve");
        var r2 = mw.process(textMsg("eve", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, r2);

        // Deny Alice
        mw.deny("alice");
        var r3 = mw.process(textMsg("alice", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, r3);
    }

    @Test
    void accessMiddleware_denyByDefault_blocksWhenEmpty() throws Exception {
        var mw = new AccessMiddleware(Set.of(), true);
        var result = mw.process(textMsg("anyone", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);
    }

    @Test
    void accessMiddleware_getAllowed() {
        var mw = new AccessMiddleware(Set.of("a", "b"));
        assertEquals(Set.of("a", "b"), mw.getAllowed());
    }

    @Test
    void accessMiddleware_dynamicAllow_thenDenyAll_opensIfNotDenyByDefault() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice"));
        mw.deny("alice");
        // Empty allowlist + not deny-by-default => open mode
        var result = mw.process(textMsg("random", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
    }

    @Test
    void accessMiddleware_denyByDefault_remainsClosedAfterDenyAll() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice"), true);
        mw.deny("alice");
        // Empty allowlist + deny-by-default => blocked
        var result = mw.process(textMsg("random", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);
    }

    @Test
    void accessMiddleware_allowMultipleUsers() throws Exception {
        var mw = new AccessMiddleware(Set.of(), true);
        mw.allow("u1");
        mw.allow("u2");
        mw.allow("u3");

        assertEquals(3, mw.getAllowed().size());
        var r1 = mw.process(textMsg("u1", "hi"), PASS_THROUGH).get();
        var r2 = mw.process(textMsg("u2", "hi"), PASS_THROUGH).get();
        var r3 = mw.process(textMsg("u3", "hi"), PASS_THROUGH).get();
        var r4 = mw.process(textMsg("u4", "hi"), PASS_THROUGH).get();

        assertInstanceOf(HandlerResult.TextReply.class, r1);
        assertInstanceOf(HandlerResult.TextReply.class, r2);
        assertInstanceOf(HandlerResult.TextReply.class, r3);
        assertInstanceOf(HandlerResult.Empty.class, r4);
    }

    // --- CommandMiddleware ---

    @Test
    void commandMiddleware_routesToHandler() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx ->
                HandlerResult.text("Available commands: /help, /status"));

        var result = mw.process(textMsg("u1", "/help"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("Available commands: /help, /status",
                ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_passesArgsInContext() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("echo", ctx -> HandlerResult.text(ctx.args()));

        var result = mw.process(textMsg("u1", "/echo hello world"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("hello world", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_unknownCommand_passesThrough() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx -> HandlerResult.text("help"));

        var result = mw.process(textMsg("u1", "/unknown"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("passed", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_nonCommand_passesThrough() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx -> HandlerResult.text("help"));

        var result = mw.process(textMsg("u1", "just a message"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("passed", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_customPrefix() throws Exception {
        var mw = new CommandMiddleware("!");
        mw.registerSync("ban", ctx -> HandlerResult.text("banned " + ctx.args()));

        var result = mw.process(textMsg("u1", "!ban user123"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("banned user123", ((HandlerResult.TextReply) result).text());

        // Slash prefix should pass through
        var r2 = mw.process(textMsg("u1", "/ban user123"), PASS_THROUGH).get();
        assertEquals("passed", ((HandlerResult.TextReply) r2).text());
    }

    @Test
    void commandMiddleware_caseInsensitive() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx -> HandlerResult.text("help text"));

        var result = mw.process(textMsg("u1", "/HELP"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("help text", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_asyncHandler() throws Exception {
        var mw = new CommandMiddleware();
        mw.register("async", ctx ->
                CompletableFuture.supplyAsync(() -> HandlerResult.text("async result")));

        var result = mw.process(textMsg("u1", "/async"), PASS_THROUGH).get();
        assertEquals("async result", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_getCommands() {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx -> HandlerResult.empty());
        mw.registerSync("status", ctx -> HandlerResult.empty());

        assertEquals(Set.of("help", "status"), mw.getCommands());
    }

    @Test
    void commandMiddleware_prefixOnly_passesThrough() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("help", ctx -> HandlerResult.text("help"));

        var result = mw.process(textMsg("u1", "/"), PASS_THROUGH).get();
        assertEquals("passed", ((HandlerResult.TextReply) result).text());
    }

    // --- New middleware tests ---

    @Test
    void commandMiddleware_unknownCommand_doesNotTriggerRegistered() throws Exception {
        var mw = new CommandMiddleware();
        var invoked = new AtomicReference<String>();
        mw.registerSync("help", ctx -> { invoked.set("help"); return HandlerResult.text("help"); });

        // /foo should pass through, never calling help handler
        mw.process(textMsg("u1", "/foo"), PASS_THROUGH).get();
        assertNull(invoked.get());
    }

    @Test
    void commandMiddleware_emptyArgsHandledCorrectly() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("status", ctx -> HandlerResult.text("args=[" + ctx.args() + "]"));

        var result = mw.process(textMsg("u1", "/status"), PASS_THROUGH).get();
        assertEquals("args=[]", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void commandMiddleware_contextContainsOriginalMessage() throws Exception {
        var mw = new CommandMiddleware();
        var captured = new AtomicReference<UnifiedMessage>();
        mw.registerSync("info", ctx -> {
            captured.set(ctx.message());
            return HandlerResult.text("ok");
        });

        var msg = textMsg("sender1", "/info extra");
        mw.process(msg, PASS_THROUGH).get();

        assertNotNull(captured.get());
        assertEquals("sender1", captured.get().sender().id());
        assertEquals("test", captured.get().channelId());
    }

    @Test
    void commandMiddleware_registerOverwritesPrevious() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("cmd", ctx -> HandlerResult.text("v1"));
        mw.registerSync("cmd", ctx -> HandlerResult.text("v2"));

        var result = mw.process(textMsg("u1", "/cmd"), PASS_THROUGH).get();
        assertEquals("v2", ((HandlerResult.TextReply) result).text());
    }

    @Test
    void multipleMiddleware_chainOrdering() throws Exception {
        var order = new CopyOnWriteArrayList<String>();

        Middleware mw1 = (msg, next) -> {
            order.add("A");
            return next.handle(msg);
        };
        Middleware mw2 = (msg, next) -> {
            order.add("B");
            return next.handle(msg);
        };
        Middleware mw3 = (msg, next) -> {
            order.add("C");
            return next.handle(msg);
        };

        // Chain: mw1 -> mw2 -> mw3 -> terminal
        Handler terminal = msg -> {
            order.add("terminal");
            return CompletableFuture.completedFuture(HandlerResult.empty());
        };

        Handler chain = msg -> mw1.process(msg, m1 -> mw2.process(m1, m2 -> mw3.process(m2, terminal)));
        chain.handle(textMsg("u1", "test")).get();

        assertEquals(List.of("A", "B", "C", "terminal"), order);
    }

    @Test
    void middlewareError_doesNotCrash_returnsFailedFuture() {
        Middleware errorMw = (msg, next) -> {
            throw new RuntimeException("boom");
        };

        // Calling directly - the exception propagates as a thrown exception
        assertThrows(RuntimeException.class,
                () -> errorMw.process(textMsg("u1", "test"), PASS_THROUGH));
    }

    @Test
    void middlewareError_asyncFuture_returnsFailedFuture() throws Exception {
        Middleware errorMw = (msg, next) ->
                CompletableFuture.failedFuture(new RuntimeException("async boom"));

        var future = errorMw.process(textMsg("u1", "test"), PASS_THROUGH);
        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void accessMiddleware_sendsBlockedIdMatchesExactly() throws Exception {
        var mw = new AccessMiddleware(Set.of("alice"));
        // "alice2" should be blocked (not a prefix match)
        var result = mw.process(textMsg("alice2", "hi"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.Empty.class, result);
    }

    @Test
    void commandMiddleware_multiWordArgs_preservedCorrectly() throws Exception {
        var mw = new CommandMiddleware();
        mw.registerSync("say", ctx -> HandlerResult.text(ctx.args()));

        var result = mw.process(textMsg("u1", "/say hello   world   foo"), PASS_THROUGH).get();
        // args preserves everything after command name
        assertEquals("hello   world   foo", ((HandlerResult.TextReply) result).text());
    }
}
