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
 * Synology Chat adapter using incoming/outgoing webhooks.
 * <p>
 * Receives messages via outgoing webhook POST, sends via incoming webhook URL.
 *
 * @see <a href="https://kb.synology.com/en-me/DSM/tutorial/How_to_configure_webhooks_and_slash_commands_in_Synology_Chat">Synology Chat Bots</a>
 */
public final class SynologyAdapter extends AbstractAdapter {

    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern USER_ID_PAT = Pattern.compile("\"user_id\"\\s*:\\s*(\\d+)");
    private static final Pattern USERNAME_PAT = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHANNEL_ID_PAT = Pattern.compile("\"channel_id\"\\s*:\\s*(\\d+)");

    private final String incomingWebhookUrl;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;

    public SynologyAdapter(String incomingWebhookUrl) {
        this(incomingWebhookUrl, 8085);
    }

    public SynologyAdapter(String incomingWebhookUrl, int webhookPort) {
        this.incomingWebhookUrl = incomingWebhookUrl;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "synology"; }

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
                        log.warning("Synology webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("Synology Chat webhook listening on port " + webhookPort);
            } catch (IOException e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start Synology webhook", e);
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
        var payload = "payload=" + urlEncode("{\"text\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}");
        var request = HttpRequest.newBuilder(URI.create(incomingWebhookUrl))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("Synology send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void processWebhook(String body) {
        var textMatch = TEXT_PAT.matcher(body);
        var userMatch = USER_ID_PAT.matcher(body);
        if (!textMatch.find() || !userMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var userId = userMatch.group(1);
        var usernameMatch = USERNAME_PAT.matcher(body);
        var username = usernameMatch.find() ? usernameMatch.group(1) : null;
        var channelMatch = CHANNEL_ID_PAT.matcher(body);
        var chatId = channelMatch.find() ? channelMatch.group(1) : userId;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .sender(new Identity(userId, username))
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

    private static String urlEncode(String s) {
        return s.replace("{", "%7B").replace("}", "%7D").replace("\"", "%22")
                .replace(":", "%3A").replace(",", "%2C").replace(" ", "+");
    }
}
