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
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;

/**
 * Feishu (Lark) adapter using Event Subscription webhook and REST API.
 * <p>
 * Handles tenant_access_token refresh and processes message events.
 *
 * @see <a href="https://open.feishu.cn/document/server-docs/im-v1/message/create">Feishu API</a>
 */
public final class FeishuAdapter extends AbstractAdapter {

    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String SEND_URL = "https://open.feishu.cn/open-apis/im/v1/messages";
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern OPEN_ID_PAT = Pattern.compile("\"open_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHAT_ID_PAT = Pattern.compile("\"chat_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"message_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOKEN_PAT = Pattern.compile("\"tenant_access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHALLENGE_PAT = Pattern.compile("\"challenge\"\\s*:\\s*\"([^\"]+)\"");

    private final String appId;
    private final String appSecret;
    private final int webhookPort;
    private final HttpClient httpClient;
    private com.sun.net.httpserver.HttpServer webhookServer;
    private volatile String tenantToken;
    private volatile long tokenExpiry;

    public FeishuAdapter(String appId, String appSecret) {
        this(appId, appSecret, 8083);
    }

    public FeishuAdapter(String appId, String appSecret, int webhookPort) {
        this.appId = appId;
        this.appSecret = appSecret;
        this.webhookPort = webhookPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "feishu"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                refreshToken();
                webhookServer = com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress(webhookPort), 0);
                webhookServer.createContext("/webhook", exchange -> {
                    try {
                        if ("POST".equals(exchange.getRequestMethod())) {
                            var body = new String(exchange.getRequestBody().readAllBytes());
                            if (body.contains("\"challenge\"")) {
                                var m = CHALLENGE_PAT.matcher(body);
                                if (m.find()) {
                                    var resp = "{\"challenge\":\"" + m.group(1) + "\"}";
                                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                                    exchange.sendResponseHeaders(200, resp.length());
                                    try (OutputStream os = exchange.getResponseBody()) { os.write(resp.getBytes()); }
                                    return;
                                }
                            }
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                            processEvent(body);
                        } else {
                            exchange.sendResponseHeaders(405, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        log.warning("Feishu webhook error: " + e.getMessage());
                        try { exchange.sendResponseHeaders(500, 0); exchange.getResponseBody().close(); }
                        catch (IOException ignored) {}
                    }
                });
                webhookServer.setExecutor(null);
                webhookServer.start();
                status = ChannelStatus.connected(channelId());
                log.info("Feishu webhook listening on port " + webhookPort);
            } catch (Exception e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to start Feishu adapter", e);
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
            var json = "{\"receive_id\":\"" + escapeJson(message.chatId())
                    + "\",\"msg_type\":\"text\",\"content\":\"{\\\"text\\\":\\\""
                    + escapeJson(message.text() != null ? message.text() : "") + "\\\"}\"}";
            var request = HttpRequest.newBuilder(URI.create(SEND_URL + "?receive_id_type=chat_id"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() >= 400)
                            log.warning("Feishu send failed (" + resp.statusCode() + "): " + resp.body());
                    });
        });
    }

    private void processEvent(String body) {
        var textMatch = TEXT_PAT.matcher(body);
        var openIdMatch = OPEN_ID_PAT.matcher(body);
        if (!textMatch.find() || !openIdMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var openId = openIdMatch.group(1);
        var chatIdMatch = CHAT_ID_PAT.matcher(body);
        var chatId = chatIdMatch.find() ? chatIdMatch.group(1) : openId;
        var msgIdMatch = MSG_ID_PAT.matcher(body);
        var msgId = msgIdMatch.find() ? msgIdMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(openId))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    private void refreshToken() throws Exception {
        var json = "{\"app_id\":\"" + appId + "\",\"app_secret\":\"" + appSecret + "\"}";
        var request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        var tokenMatch = TOKEN_PAT.matcher(response.body());
        if (tokenMatch.find()) {
            tenantToken = tokenMatch.group(1);
            tokenExpiry = System.currentTimeMillis() + 6000_000;
        } else {
            throw new RuntimeException("Failed to get Feishu tenant token: " + response.body());
        }
    }

    private CompletableFuture<String> ensureToken() {
        if (tenantToken != null && System.currentTimeMillis() < tokenExpiry) {
            return CompletableFuture.completedFuture(tenantToken);
        }
        return CompletableFuture.supplyAsync(() -> {
            try { refreshToken(); return tenantToken; }
            catch (Exception e) { throw new CompletionException(e); }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
