package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Telegram Bot API adapter using java.net.http (no external dependencies).
 * <p>
 * Supports two modes:
 * <ul>
 *   <li>{@code POLLING} (default): long polling via getUpdates</li>
 *   <li>{@code WEBHOOK}: starts a local HTTP server and registers a webhook URL with Telegram</li>
 * </ul>
 */
public final class TelegramAdapter extends AbstractAdapter {

    /** Receive mode for the Telegram adapter. */
    public enum Mode { POLLING, WEBHOOK }

    /** Configuration for webhook mode. */
    public static final class WebhookConfig {
        private final String webhookUrl;
        private final int port;
        private final String path;

        public WebhookConfig(String webhookUrl) {
            this(webhookUrl, 8443, "/telegram-webhook");
        }

        public WebhookConfig(String webhookUrl, int port, String path) {
            this.webhookUrl = webhookUrl;
            this.port = port;
            this.path = path;
        }

        public String webhookUrl() { return webhookUrl; }
        public int port() { return port; }
        public String path() { return path; }
    }

    private static final String API_BASE = "https://api.telegram.org/bot";
    // Simple JSON field extractors (avoids GSON dependency for core)
    private static final Pattern UPDATE_ID_PAT = Pattern.compile("\"update_id\"\\s*:\\s*(\\d+)");
    private static final Pattern CHAT_ID_PAT = Pattern.compile("\"chat\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(-?\\d+)");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"message_id\"\\s*:\\s*(\\d+)");
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern FROM_ID_PAT = Pattern.compile("\"from\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(\\d+)");
    private static final Pattern USERNAME_PAT = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FIRST_NAME_PAT = Pattern.compile("\"first_name\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");

    private final String token;
    private final HttpClient httpClient;
    private final Mode mode;
    private final WebhookConfig webhookConfig;
    private final AtomicLong offset = new AtomicLong(0);
    private ScheduledExecutorService poller;
    private volatile boolean polling;
    private HttpServer webhookServer;

    /** Create a polling-mode adapter. */
    public TelegramAdapter(String token) {
        this(token, Mode.POLLING, null);
    }

    /** Create an adapter with explicit mode and optional webhook config. */
    public TelegramAdapter(String token, Mode mode, WebhookConfig webhookConfig) {
        this.token = token;
        this.mode = mode;
        this.webhookConfig = webhookConfig;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** Returns the current mode (POLLING or WEBHOOK). */
    public Mode mode() {
        return mode;
    }

    @Override
    public String channelId() {
        return "telegram";
    }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return apiGet("getMe").thenAccept(body -> {
            if (!body.contains("\"ok\":true")) {
                status = ChannelStatus.error(channelId(), "getMe failed: " + body);
                throw new RuntimeException("Telegram getMe failed");
            }
            status = ChannelStatus.connected(channelId());
            if (mode == Mode.WEBHOOK) {
                startWebhookServer();
            } else {
                startPolling();
            }
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        if (mode == Mode.WEBHOOK) {
            stopWebhookServer();
        } else {
            polling = false;
            if (poller != null) {
                poller.shutdown();
            }
        }
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var json = buildSendMessageJson(message);
        return apiPost("sendMessage", json).thenAccept(body -> {
            if (!body.contains("\"ok\":true")) {
                log.warning("sendMessage failed: " + body);
            }
        });
    }

    // -- Polling --

    private void startPolling() {
        polling = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "telegram-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        if (!polling) return;
        try {
            var url = apiUrl("getUpdates") + "?offset=" + offset.get() + "&timeout=30";
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(35))
                    .GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            processUpdates(response.body());
        } catch (Exception e) {
            if (polling) {
                log.fine("Poll error (will retry): " + e.getMessage());
            }
        }
    }

    // -- Webhook --

    private void startWebhookServer() {
        if (webhookConfig == null) {
            throw new IllegalStateException("webhookConfig is required for WEBHOOK mode");
        }

        try {
            var fullUrl = webhookConfig.webhookUrl().replaceAll("/+$", "") + webhookConfig.path();

            // Register webhook with Telegram
            var setWebhookJson = "{\"url\":\"" + escapeJson(fullUrl) + "\"}";
            apiPost("setWebhook", setWebhookJson).join();

            // Start local HTTP server
            webhookServer = HttpServer.create(new InetSocketAddress(webhookConfig.port()), 0);
            webhookServer.createContext(webhookConfig.path(), exchange -> {
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                try {
                    var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    processUpdates(body);
                    var resp = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, resp.length);
                    exchange.getResponseBody().write(resp);
                } catch (Exception e) {
                    log.warning("Webhook handler error: " + e.getMessage());
                    exchange.sendResponseHeaders(400, -1);
                } finally {
                    exchange.close();
                }
            });
            webhookServer.setExecutor(Executors.newCachedThreadPool(r -> {
                var t = new Thread(r, "telegram-webhook");
                t.setDaemon(true);
                return t;
            }));
            webhookServer.start();
            log.info("Telegram webhook server started on port " + webhookConfig.port());
        } catch (IOException e) {
            status = ChannelStatus.error(channelId(), "Failed to start webhook server: " + e.getMessage());
            throw new RuntimeException("Failed to start webhook server", e);
        }
    }

    private void stopWebhookServer() {
        if (webhookServer != null) {
            webhookServer.stop(1);
            webhookServer = null;
        }
        // Delete webhook from Telegram (best effort)
        try {
            apiGet("deleteWebhook").join();
        } catch (Exception e) {
            log.fine("Failed to delete webhook: " + e.getMessage());
        }
    }

    // -- Update processing (shared between polling and webhook) --

    private void processUpdates(String body) {
        // Find all update blocks using update_id as anchor
        var matcher = UPDATE_ID_PAT.matcher(body);
        while (matcher.find()) {
            long updateId = Long.parseLong(matcher.group(1));
            offset.set(updateId + 1);

            // Extract the region around this update for field parsing
            int start = Math.max(0, matcher.start() - 10);
            int end = Math.min(body.length(), matcher.start() + 2000);
            var region = body.substring(start, end);

            try {
                var msg = parseMessage(region);
                if (msg != null) {
                    emit(msg);
                }
            } catch (Exception e) {
                log.fine("Failed to parse update " + updateId + ": " + e.getMessage());
            }
        }
    }

    private UnifiedMessage parseMessage(String region) {
        var chatMatch = CHAT_ID_PAT.matcher(region);
        var textMatch = TEXT_PAT.matcher(region);
        var fromIdMatch = FROM_ID_PAT.matcher(region);
        var msgIdMatch = MSG_ID_PAT.matcher(region);

        if (!chatMatch.find() || !textMatch.find() || !fromIdMatch.find()) {
            return null; // Non-text message, skip
        }

        var chatId = chatMatch.group(1);
        var text = textMatch.group(1)
                .replace("\\\\", "\\")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
        var fromId = fromIdMatch.group(1);
        var msgId = msgIdMatch.find() ? msgIdMatch.group(1) : null;

        var usernameMatch = USERNAME_PAT.matcher(region);
        var firstNameMatch = FIRST_NAME_PAT.matcher(region);
        var username = usernameMatch.find() ? usernameMatch.group(1) : null;
        var displayName = firstNameMatch.find() ? firstNameMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var content = new MessageContent(contentType, text, null, null, null);

        return UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(fromId, username, displayName))
                .chatId(chatId)
                .content(content)
                .timestamp(Instant.now())
                .build();
    }

    // -- HTTP helpers --

    private String apiUrl(String method) {
        return API_BASE + token + "/" + method;
    }

    private CompletableFuture<String> apiGet(String method) {
        var request = HttpRequest.newBuilder(URI.create(apiUrl(method)))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private CompletableFuture<String> apiPost(String method, String jsonBody) {
        var request = HttpRequest.newBuilder(URI.create(apiUrl(method)))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private String buildSendMessageJson(OutboundMessage msg) {
        var sb = new StringBuilder("{");
        sb.append("\"chat_id\":").append(msg.chatId()).append(",");
        sb.append("\"text\":\"").append(escapeJson(msg.text() != null ? msg.text() : "")).append("\"");
        if (msg.replyToId() != null) {
            sb.append(",\"reply_to_message_id\":").append(msg.replyToId());
        }
        if (msg.parseMode() == OutboundMessage.ParseMode.MARKDOWN) {
            sb.append(",\"parse_mode\":\"MarkdownV2\"");
        } else if (msg.parseMode() == OutboundMessage.ParseMode.HTML) {
            sb.append(",\"parse_mode\":\"HTML\"");
        }
        // Inline keyboard buttons
        if (!msg.buttons().isEmpty()) {
            sb.append(",\"reply_markup\":{\"inline_keyboard\":[");
            for (int r = 0; r < msg.buttons().size(); r++) {
                if (r > 0) sb.append(",");
                sb.append("[");
                var row = msg.buttons().get(r);
                for (int c = 0; c < row.size(); c++) {
                    if (c > 0) sb.append(",");
                    var btn = row.get(c);
                    sb.append("{\"text\":\"").append(escapeJson(btn.label())).append("\"");
                    if (btn.url() != null) {
                        sb.append(",\"url\":\"").append(escapeJson(btn.url())).append("\"");
                    } else {
                        sb.append(",\"callback_data\":\"")
                                .append(escapeJson(btn.callbackData() != null ? btn.callbackData() : btn.label()))
                                .append("\"");
                    }
                    sb.append("}");
                }
                sb.append("]");
            }
            sb.append("]}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
