package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Uses long polling via getUpdates. For production, consider switching to webhooks.
 */
public final class TelegramAdapter extends AbstractAdapter {

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
    private final AtomicLong offset = new AtomicLong(0);
    private ScheduledExecutorService poller;
    private volatile boolean polling;

    public TelegramAdapter(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
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
            startPolling();
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        polling = false;
        if (poller != null) {
            poller.shutdown();
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
        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
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
