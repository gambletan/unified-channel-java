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
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Microsoft Teams adapter using Bot Framework webhook and REST API.
 * <p>
 * Receives activities via an HTTP webhook (com.sun.net.httpserver) and
 * sends replies via the Bot Framework REST API.
 *
 * @see <a href="https://learn.microsoft.com/en-us/microsoftteams/platform/bots/">MS Teams Bots</a>
 */
public final class MSTeamsAdapter extends AbstractAdapter {

    private static final String LOGIN_URL = "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token";
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern FROM_ID_PAT = Pattern.compile("\"from\"\\s*:\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern FROM_NAME_PAT = Pattern.compile("\"from\"\\s*:\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CONV_ID_PAT = Pattern.compile("\"conversation\"\\s*:\\{[^}]*\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SERVICE_URL_PAT = Pattern.compile("\"serviceUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACTIVITY_ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACCESS_TOKEN_PAT = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private final String appId;
    private final String appSecret;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;
    private volatile String botToken;
    private volatile long tokenExpiry;

    public MSTeamsAdapter(String appId, String appSecret) {
        this(appId, appSecret, 3978);
    }

    public MSTeamsAdapter(String appId, String appSecret, int webhookPort) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "msteams"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                acquireToken();
                webhookServer = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(webhookPort), 0);
                webhookServer.createContext("/api/messages", exchange -> {
                    try {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            var body = new String(exchange.getRequestBody().readAllBytes());
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                            processActivity(body);
                        } else {
                            exchange.sendResponseHeaders(405, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        log.warning("Teams webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("MS Teams webhook listening on port " + webhookPort);
            } catch (Exception e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start Teams adapter", e);
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
        return ensureToken().thenCompose(token -> {
            // chatId format: serviceUrl|conversationId
            var parts = message.chatId().split("\\|", 2);
            if (parts.length < 2) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("chatId must be serviceUrl|conversationId"));
            }
            var serviceUrl = parts[0];
            var conversationId = parts[1];
            var url = serviceUrl + "/v3/conversations/" + conversationId + "/activities";
            var json = "{\"type\":\"message\",\"text\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
            var request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() >= 400) {
                            log.warning("Teams send failed (" + resp.statusCode() + "): " + resp.body());
                        }
                    });
        });
    }

    private void processActivity(String body) {
        if (!body.contains("\"message\"")) return;

        var textMatch = TEXT_PAT.matcher(body);
        var fromMatch = FROM_ID_PAT.matcher(body);
        var convMatch = CONV_ID_PAT.matcher(body);
        var serviceMatch = SERVICE_URL_PAT.matcher(body);
        if (!textMatch.find() || !fromMatch.find() || !convMatch.find() || !serviceMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var fromId = fromMatch.group(1);
        var conversationId = convMatch.group(1);
        var serviceUrl = serviceMatch.group(1);
        var nameMatch = FROM_NAME_PAT.matcher(body);
        var fromName = nameMatch.find() ? nameMatch.group(1) : null;
        var activityIdMatch = ACTIVITY_ID_PAT.matcher(body);
        var activityId = activityIdMatch.find() ? activityIdMatch.group(1) : null;

        var chatId = serviceUrl + "|" + conversationId;
        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(activityId)
                .sender(new Identity(fromId, null, fromName))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    private void acquireToken() throws Exception {
        var formBody = "grant_type=client_credentials&client_id=" + appId
                + "&client_secret=" + appSecret
                + "&scope=https%3A%2F%2Fapi.botframework.com%2F.default";
        var request = HttpRequest.newBuilder(URI.create(LOGIN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        var tokenMatch = ACCESS_TOKEN_PAT.matcher(response.body());
        if (tokenMatch.find()) {
            botToken = tokenMatch.group(1);
            tokenExpiry = System.currentTimeMillis() + 3500_000;
        } else {
            throw new RuntimeException("Failed to acquire Teams bot token: " + response.body());
        }
    }

    private CompletableFuture<String> ensureToken() {
        if (botToken != null && System.currentTimeMillis() < tokenExpiry) {
            return CompletableFuture.completedFuture(botToken);
        }
        return CompletableFuture.supplyAsync(() -> {
            try { acquireToken(); return botToken; }
            catch (Exception e) { throw new CompletionException(e); }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
