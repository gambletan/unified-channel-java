package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * LINE Messaging API adapter using webhook and REST push messages.
 *
 * @see <a href="https://developers.line.biz/en/docs/messaging-api/">LINE Messaging API</a>
 */
public final class LineAdapter extends AbstractAdapter {

    private static final String PUSH_URL = "https://api.line.me/v2/bot/message/push";
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern USER_ID_PAT = Pattern.compile("\"userId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern GROUP_ID_PAT = Pattern.compile("\"groupId\"\\s*:\\s*\"([^\"]+)\"");

    private final String channelAccessToken;
    private final String channelSecret;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public LineAdapter(String channelAccessToken, String channelSecret) {
        this(channelAccessToken, channelSecret, 8082);
    }

    public LineAdapter(String channelAccessToken, String channelSecret, int webhookPort) {
        this.channelAccessToken = channelAccessToken;
        this.channelSecret = channelSecret;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "line"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                webhookServer = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(webhookPort), 0);
                webhookServer.createContext("/webhook", exchange -> {
                    try {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            var body = new String(exchange.getRequestBody().readAllBytes());
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                            processWebhook(body);
                        } else {
                            exchange.sendResponseHeaders(405, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        log.warning("LINE webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("LINE webhook listening on port " + webhookPort);
            } catch (IOException e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start LINE webhook", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        if (webhookServer != null) webhookServer.stop(1);
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var json = "{\"to\":\"" + escapeJson(message.chatId())
                + "\",\"messages\":[{\"type\":\"text\",\"text\":\""
                + escapeJson(message.text() != null ? message.text() : "") + "\"}]}";
        var request = HttpRequest.newBuilder(URI.create(PUSH_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + channelAccessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("LINE send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void processWebhook(String body) {
        if (!body.contains("\"events\"")) return;
        var textMatch = TEXT_PAT.matcher(body);
        var userMatch = USER_ID_PAT.matcher(body);
        if (!textMatch.find() || !userMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var userId = userMatch.group(1);
        var msgIdMatch = MSG_ID_PAT.matcher(body);
        var msgId = msgIdMatch.find() ? msgIdMatch.group(1) : null;
        var groupMatch = GROUP_ID_PAT.matcher(body);
        var chatId = groupMatch.find() ? groupMatch.group(1) : userId;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(userId))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
