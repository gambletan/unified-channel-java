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
 * Google Chat adapter using webhook endpoint and REST API.
 *
 * @see <a href="https://developers.google.com/workspace/chat/api">Google Chat API</a>
 */
public final class GoogleChatAdapter extends AbstractAdapter {

    private static final String API_BASE = "https://chat.googleapis.com/v1/";
    private static final Pattern TEXT_PAT = Pattern.compile("\"argumentText\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern TEXT_ALT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern SENDER_NAME_PAT = Pattern.compile("\"sender\"\\s*:\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SENDER_DISPLAY_PAT = Pattern.compile("\"sender\"\\s*:\\{[^}]*\"displayName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SPACE_PAT = Pattern.compile("\"space\"\\s*:\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSG_NAME_PAT = Pattern.compile("\"name\"\\s*:\\s*\"(spaces/[^\"]+/messages/[^\"]+)\"");

    private final String serviceAccountJson;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public GoogleChatAdapter(String serviceAccountJson) {
        this(serviceAccountJson, 8084);
    }

    public GoogleChatAdapter(String serviceAccountJson, int webhookPort) {
        this.serviceAccountJson = serviceAccountJson;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "googlechat"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                webhookServer = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(webhookPort), 0);
                webhookServer.createContext("/chat", exchange -> {
                    try {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            var body = new String(exchange.getRequestBody().readAllBytes());
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                            processEvent(body);
                        } else {
                            exchange.sendResponseHeaders(405, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        log.warning("Google Chat webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("Google Chat webhook listening on port " + webhookPort);
            } catch (IOException e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start Google Chat webhook", e);
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
        var url = API_BASE + message.chatId() + "/messages";
        var json = "{\"text\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("Google Chat send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void processEvent(String body) {
        var textMatch = TEXT_PAT.matcher(body);
        if (!textMatch.find()) {
            textMatch = TEXT_ALT_PAT.matcher(body);
            if (!textMatch.find()) return;
        }
        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");

        var senderMatch = SENDER_NAME_PAT.matcher(body);
        var senderId = senderMatch.find() ? senderMatch.group(1) : "unknown";
        var displayMatch = SENDER_DISPLAY_PAT.matcher(body);
        var displayName = displayMatch.find() ? displayMatch.group(1) : null;

        var spaceMatch = SPACE_PAT.matcher(body);
        var chatId = spaceMatch.find() ? spaceMatch.group(1) : "unknown";
        var msgMatch = MSG_NAME_PAT.matcher(body);
        var msgId = msgMatch.find() ? msgMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(senderId, null, displayName))
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
