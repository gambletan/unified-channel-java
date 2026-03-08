package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Mattermost adapter using WebSocket for receiving and REST API (v4) for sending.
 * Pure java.net.http implementation with no external dependencies.
 */
public final class MattermostAdapter extends AbstractAdapter {

    private static final Pattern EVENT_PAT = Pattern.compile("\"event\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern POST_PAT = Pattern.compile("\"post\"\\s*:\\s*\"(\\{[^\"]*\\})\"");
    private static final Pattern MSG_PAT = Pattern.compile("\"message\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern CHANNEL_ID_PAT = Pattern.compile("\"channel_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USER_ID_PAT = Pattern.compile("\"user_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ROOT_ID_PAT = Pattern.compile("\"root_id\"\\s*:\\s*\"([^\"]+)\"");

    private final String serverUrl;  // e.g. https://mattermost.example.com
    private final String token;      // personal access token or bot token
    private final HttpClient httpClient;
    private WebSocket webSocket;
    private volatile boolean connected;

    public MattermostAdapter(String serverUrl, String token) {
        this.serverUrl = serverUrl.replaceAll("/$", "");
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() {
        return "mattermost";
    }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);

        // Verify auth via /api/v4/users/me
        return apiGet("/api/v4/users/me").thenCompose(body -> {
            if (body.contains("\"status_code\"")) {
                status = ChannelStatus.error(channelId(), "Auth failed");
                return CompletableFuture.<Void>failedFuture(new RuntimeException("Mattermost auth failed"));
            }

            // Connect WebSocket
            var wsUrl = serverUrl.replace("https://", "wss://").replace("http://", "ws://")
                    + "/api/v4/websocket";
            var future = new CompletableFuture<Void>();

            httpClient.newWebSocketBuilder()
                    .header("Authorization", "Bearer " + token)
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();
                        private boolean authenticated;

                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            // Send auth challenge
                            ws.sendText("{\"seq\":1,\"action\":\"authentication_challenge\",\"data\":{\"token\":\""
                                    + token + "\"}}", true);
                            ws.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                var text = buffer.toString();
                                buffer.setLength(0);
                                if (!authenticated && text.contains("\"status\":\"OK\"")) {
                                    authenticated = true;
                                    connected = true;
                                    status = ChannelStatus.connected(channelId());
                                    future.complete(null);
                                } else if (authenticated) {
                                    handleEvent(text);
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                            connected = false;
                            status = ChannelStatus.disconnected(channelId());
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            connected = false;
                            status = ChannelStatus.error(channelId(), error.getMessage());
                            if (!future.isDone()) future.completeExceptionally(error);
                        }
                    });
            return future;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var json = new StringBuilder("{");
        json.append("\"channel_id\":\"").append(escapeJson(message.chatId())).append("\",");
        json.append("\"message\":\"").append(escapeJson(message.text() != null ? message.text() : "")).append("\"");
        if (message.replyToId() != null) {
            json.append(",\"root_id\":\"").append(escapeJson(message.replyToId())).append("\"");
        }
        json.append("}");

        var request = HttpRequest.newBuilder(URI.create(serverUrl + "/api/v4/posts"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.toString()))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        log.warning("Mattermost send failed (" + resp.statusCode() + "): " + resp.body());
                    }
                });
    }

    // -- Event handling --

    private void handleEvent(String payload) {
        var eventMatch = EVENT_PAT.matcher(payload);
        if (!eventMatch.find() || !"posted".equals(eventMatch.group(1))) return;

        // The post data is JSON-encoded inside a string field
        var postMatch = POST_PAT.matcher(payload);
        var region = postMatch.find()
                ? postMatch.group(1).replace("\\\"", "\"").replace("\\\\", "\\")
                : payload;

        var msgMatch = MSG_PAT.matcher(region);
        var chanMatch = CHANNEL_ID_PAT.matcher(region);
        var userMatch = USER_ID_PAT.matcher(region);

        if (!msgMatch.find() || !chanMatch.find() || !userMatch.find()) return;

        var text = msgMatch.group(1).replace("\\n", "\n");
        var chatId = chanMatch.group(1);
        var userId = userMatch.group(1);

        var idMatch = ID_PAT.matcher(region);
        var msgId = idMatch.find() ? idMatch.group(1) : null;
        var rootMatch = ROOT_ID_PAT.matcher(region);
        var threadId = rootMatch.find() ? rootMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;

        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(userId))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .threadId(threadId != null && !threadId.isEmpty() ? threadId : null)
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    // -- Helpers --

    private CompletableFuture<String> apiGet(String path) {
        var request = HttpRequest.newBuilder(URI.create(serverUrl + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
