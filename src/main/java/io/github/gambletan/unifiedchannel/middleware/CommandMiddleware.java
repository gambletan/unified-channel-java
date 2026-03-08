package io.github.gambletan.unifiedchannel.middleware;

import io.github.gambletan.unifiedchannel.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Middleware that routes /commands to registered handlers.
 * <p>
 * A command is detected when the message text starts with the configured prefix
 * (default "/"). The command name is the first whitespace-delimited token after
 * the prefix. If a handler is registered for that command, it is invoked;
 * otherwise the message passes through to the next handler.
 */
public final class CommandMiddleware implements Middleware {

    private static final Logger LOG = Logger.getLogger(CommandMiddleware.class.getName());

    private final String prefix;
    private final Map<String, Function<CommandContext, CompletableFuture<HandlerResult>>> handlers;

    public CommandMiddleware(String prefix) {
        this.prefix = prefix;
        this.handlers = new ConcurrentHashMap<>();
    }

    public CommandMiddleware() {
        this("/");
    }

    /**
     * Register a command handler.
     *
     * @param command the command name (without prefix, e.g. "help")
     * @param handler function that processes the command and returns a result
     */
    public CommandMiddleware register(String command,
                                     Function<CommandContext, CompletableFuture<HandlerResult>> handler) {
        handlers.put(command.toLowerCase(), handler);
        return this;
    }

    /** Convenience: register a synchronous handler. */
    public CommandMiddleware registerSync(String command,
                                         Function<CommandContext, HandlerResult> handler) {
        handlers.put(command.toLowerCase(), ctx ->
                CompletableFuture.completedFuture(handler.apply(ctx)));
        return this;
    }

    /** Get registered command names. */
    public java.util.Set<String> getCommands() {
        return java.util.Collections.unmodifiableSet(handlers.keySet());
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        var text = message.text();
        if (text == null || !text.startsWith(prefix)) {
            return next.handle(message);
        }

        // Parse command and args
        var withoutPrefix = text.substring(prefix.length()).trim();
        if (withoutPrefix.isEmpty()) {
            return next.handle(message);
        }

        var parts = withoutPrefix.split("\\s+", 2);
        var commandName = parts[0].toLowerCase();
        var args = parts.length > 1 ? parts[1] : "";

        var handler = handlers.get(commandName);
        if (handler == null) {
            // No handler registered for this command; pass through
            return next.handle(message);
        }

        LOG.fine(() -> "Handling command /" + commandName + " from " + message.sender().id());
        var ctx = new CommandContext(commandName, args, message);
        return handler.apply(ctx);
    }

    /**
     * Context passed to command handlers.
     *
     * @param command the command name (without prefix)
     * @param args    the raw argument string after the command
     * @param message the original unified message
     */
    public record CommandContext(String command, String args, UnifiedMessage message) {}
}
