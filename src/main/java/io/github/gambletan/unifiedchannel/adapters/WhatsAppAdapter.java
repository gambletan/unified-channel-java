package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.io.IOException;
import java.io.OutputStream;
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
 * WhatsApp Business Cloud API adapter.
 * <p>
 * Uses java.net.http for outbound REST calls and com.sun.net.httpserver
 * for the inbound webhook endpoint.
 *
 * @see <a href="https://developers.facebook.com/docs/whatsapp/cloud-api">WhatsApp Cloud API</a>
 */
public final class WhatsAppAdapter extends AbstractAdapter {

    private static final String API_BASE = "https://graph.facebook.com/v17.0/";
    private static final Pattern MSG_BODY_PAT = Pattern.compile("\"body\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern FROM_PAT = Pattern.compile("\"from\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"(wamid\\.[^\"]+)\"");
    private static final Pattern NAME_PAT = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TIMESTAMP_PAT = Pattern.compile("\"timestamp\"\\s*:\\s*\"(\\d+)\"");

    private final String phoneNumberId;
    private final String accessToken;
    private final int webhookPort;
    private final String verifyToken;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public WhatsAppAdapter(String phoneNumberId, String accessToken) {
        this(phoneNumberId, accessToken, 8080, "unified-channel-verify");
    }

    public WhatsAppAdapter(String phoneNumberId, String accessToken, int webhookPort, String verifyToken) {
        this.phoneNumberId = phoneNumberId;
        this.accessToken = accessToken;
        this.webhookPort = webhookPort;
        this.verifyToken = verifyToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "whatsapp"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                webhookServer = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(webhookPort), 0);
                webhookServer.createContext("/webhook", exchange -> {
                    try {
                        if ("GET".equals(exchange.getRequestMethod())) {
                            var query = exchange.getRequestURI().getQuery();
                            if (query != null && query.contains("hub.verify_token=" + verifyToken)) {
                                var challenge = extractQueryParam(query, "hub.challenge");
                                var resp = challenge != null ? challenge : "";
                                exchange.sendResponseHeaders(200, resp.length());
                                try (OutputStream os = exchange.getResponseBody()) { os.write(resp.getBytes()); }
                            } else {
                                exchange.sendResponseHeaders(403, 0);
                                exchange.getResponseBody().close();
                            }
                        } else if ("POST".equals(exchange.getRequestMethod())) {
                            var body = new String(exchange.getRequestBody().readAllBytes());
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                            processWebhook(body);
                        } else {
                            exchange.sendResponseHeaders(405, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        log.warning("Webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("WhatsApp webhook listening on port " + webhookPort);
            } catch (IOException e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start WhatsApp webhook server", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        if (webhookServer != null) {
            webhookServer.stop(1);
        }
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var json = "{\"messaging_product\":\"whatsapp\",\"to\":\"" + escapeJson(message.chatId())
                + "\",\"type\":\"text\",\"text\":{\"body\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}}";
        var request = HttpRequest.newBuilder(URI.create(API_BASE + phoneNumberId + "/messages"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        log.warning("WhatsApp send failed (" + resp.statusCode() + "): " + resp.body());
                    }
                });
    }

    private void processWebhook(String body) {
        if (!body.contains("\"messages\"")) return;

        var fromMatch = FROM_PAT.matcher(body);
        var bodyMatch = MSG_BODY_PAT.matcher(body);
        var idMatch = MSG_ID_PAT.matcher(body);

        if (!fromMatch.find() || !bodyMatch.find()) return;

        var from = fromMatch.group(1);
        var text = bodyMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var msgId = idMatch.find() ? idMatch.group(1) : null;

        var nameMatch = NAME_PAT.matcher(body);
        var senderName = nameMatch.find() ? nameMatch.group(1) : null;

        var timestampMatch = TIMESTAMP_PAT.matcher(body);
        var timestamp = timestampMatch.find()
                ? Instant.ofEpochSecond(Long.parseLong(timestampMatch.group(1)))
                : Instant.now();

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(from, null, senderName))
                .chatId(from)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(timestamp)
                .build();
        emit(msg);
    }

    private static String extractQueryParam(String query, String param) {
        for (var part : query.split("&")) {
            var kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(param)) return kv[1];
        }
        return null;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
