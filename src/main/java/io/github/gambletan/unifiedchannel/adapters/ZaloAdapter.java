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
 * Zalo Official Account API adapter.
 * <p>
 * Receives messages via webhook and sends via the OA message endpoint.
 *
 * @see <a href="https://developers.zalo.me/docs/official-account">Zalo OA API</a>
 */
public final class ZaloAdapter extends AbstractAdapter {

    private static final String SEND_URL = "https://openapi.zalo.me/v2.0/oa/message";
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern SENDER_ID_PAT = Pattern.compile("\"user_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"msg_id\"\\s*:\\s*\"([^\"]+)\"");

    private final String oaAccessToken;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public ZaloAdapter(String oaAccessToken) {
        this(oaAccessToken, 8086);
    }

    public ZaloAdapter(String oaAccessToken, int webhookPort) {
        this.oaAccessToken = oaAccessToken;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "zalo"; }

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
                        log.warning("Zalo webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("Zalo webhook listening on port " + webhookPort);
            } catch (IOException e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start Zalo webhook", e);
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
        var json = "{\"recipient\":{\"user_id\":\"" + escapeJson(message.chatId())
                + "\"},\"message\":{\"text\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}}";
        var request = HttpRequest.newBuilder(URI.create(SEND_URL))
                .header("Content-Type", "application/json")
                .header("access_token", oaAccessToken)
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("Zalo send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void processWebhook(String body) {
        var textMatch = TEXT_PAT.matcher(body);
        var senderMatch = SENDER_ID_PAT.matcher(body);
        if (!textMatch.find() || !senderMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var senderId = senderMatch.group(1);
        var msgIdMatch = MSG_ID_PAT.matcher(body);
        var msgId = msgIdMatch.find() ? msgIdMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(senderId))
                .chatId(senderId)
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
