package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.AccessMiddleware;
import io.github.gambletan.unifiedchannel.middleware.CommandMiddleware;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

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
        // Empty list without deny-by-default => open mode
        // But "eve" is still in the list so it's not empty
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
}
