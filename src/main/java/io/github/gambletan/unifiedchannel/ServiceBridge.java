package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.CommandMiddleware;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * High-level bridge that exposes application functions as chat commands.
 * <p>
 * Wraps a {@link ChannelManager} and auto-registers a {@link CommandMiddleware}
 * to route commands to exposed handlers. Provides convenience methods for
 * common patterns like status checks, log viewers, and auto-generated help.
 *
 * <pre>{@code
 * var bridge = new ServiceBridge(manager)
 *     .expose("deploy", args -> deployService(args[0]), "Deploy a service")
 *     .exposeStatus(() -> getServiceStatus())
 *     .exposeLogs(args -> tailLogs(args.length > 0 ? Integer.parseInt(args[0]) : 20));
 * bridge.run();
 * }</pre>
 */
public final class ServiceBridge {

    private final ChannelManager manager;
    private final String prefix;
    private final Map<String, BridgeCommand> commands = new LinkedHashMap<>();
    private final CommandMiddleware commandMiddleware;

    public ServiceBridge(ChannelManager manager) {
        this(manager, "/");
    }

    public ServiceBridge(ChannelManager manager, String prefix) {
        this.manager = manager;
        this.prefix = prefix;
        this.commandMiddleware = new CommandMiddleware(prefix);
        manager.addMiddleware(commandMiddleware);

        // Auto-register /help
        commandMiddleware.registerSync("help", ctx -> HandlerResult.text(generateHelp()));
        commands.put("help", new BridgeCommand("help", "Show available commands", null, null));
    }

    /**
     * Expose a synchronous function as a chat command.
     *
     * @param name        command name (without prefix)
     * @param handler     function that receives split arguments and returns a text reply
     * @param description human-readable description for /help
     * @return this bridge for chaining
     */
    public ServiceBridge expose(String name, Function<String[], String> handler, String description) {
        commandMiddleware.registerSync(name, ctx -> {
            var args = ctx.args().isEmpty() ? new String[0] : ctx.args().split("\\s+");
            var result = handler.apply(args);
            return HandlerResult.text(result != null && !result.isBlank() ? result : "(no output)");
        });
        commands.put(name.toLowerCase(), new BridgeCommand(name, description, null, null));
        return this;
    }

    /**
     * Expose an async function as a chat command.
     *
     * @param name        command name (without prefix)
     * @param handler     function that receives split arguments and returns a future text reply
     * @param description human-readable description for /help
     * @return this bridge for chaining
     */
    public ServiceBridge exposeAsync(String name, Function<String[], CompletableFuture<String>> handler, String description) {
        commandMiddleware.register(name, ctx -> {
            var args = ctx.args().isEmpty() ? new String[0] : ctx.args().split("\\s+");
            return handler.apply(args).thenApply(result ->
                    HandlerResult.text(result != null && !result.isBlank() ? result : "(no output)"));
        });
        commands.put(name.toLowerCase(), new BridgeCommand(name, description, null, null));
        return this;
    }

    /**
     * Register a status check at /status.
     *
     * @param handler supplier that returns a status string
     * @return this bridge for chaining
     */
    public ServiceBridge exposeStatus(Supplier<String> handler) {
        return expose("status", args -> handler.get(), "Show service status");
    }

    /**
     * Register a log viewer at /logs.
     *
     * @param handler function that receives optional arguments (e.g. line count) and returns log text
     * @return this bridge for chaining
     */
    public ServiceBridge exposeLogs(Function<String[], String> handler) {
        return expose("logs", handler, "View recent logs");
    }

    /**
     * Get all registered commands and their descriptions.
     */
    public Map<String, BridgeCommand> getCommands() {
        return Map.copyOf(commands);
    }

    /**
     * Get the command prefix.
     */
    public String getPrefix() {
        return prefix;
    }

    /**
     * Get the underlying channel manager.
     */
    public ChannelManager getManager() {
        return manager;
    }

    /**
     * Auto-generate help text listing all registered commands.
     */
    private String generateHelp() {
        var sb = new StringBuilder("Available commands:\n");
        for (var entry : commands.entrySet()) {
            sb.append("  ").append(prefix).append(entry.getKey());
            if (entry.getValue().description() != null) {
                sb.append(" - ").append(entry.getValue().description());
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /**
     * Start the bridge (delegates to manager.run()).
     */
    public void run() {
        manager.run().join();
    }

    /**
     * Metadata for a registered bridge command.
     *
     * @param name        command name
     * @param description human-readable description
     * @param usage       optional usage pattern (e.g. "deploy <service>")
     * @param category    optional category for grouping
     */
    public record BridgeCommand(String name, String description, String usage, String category) {}
}
