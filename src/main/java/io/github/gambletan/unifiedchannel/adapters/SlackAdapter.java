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
 * Slack adapter using Socket Mode (WebSocket) for receiving and Web API for sending.
 * Uses java.net.http only -- no Slack SDK dependency required for basic operation.
 */
public final class SlackAdapter extends AbstractAdapter {

    private static final String API_BASE = "https://slack.com/api";
    private static final Pattern OK_PAT = Pattern.compile("\"ok\"\\s*:\\s*true");
    private static final Pattern URL_PAT = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TYPE_PAT = Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern CHANNEL_PAT = Pattern.compile("\"channel\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern USER_PAT = Pattern.compile("\"user\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TS_PAT = Pattern.compile("\"ts\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ENVELOPE_ID_PAT = Pattern.compile("\"envelope_id\"\\s*:\\s*\"([^\"]+)\"");

    private final String botToken;
    private final String appToken;
    private final HttpClient httpClient;
    private WebSocket webSocket;
    private volatile boolean connected;

    /**
     * @param botToken xoxb-... bot token for Web API calls
     * @param appToken xapp-... app-level token for Socket Mode
     */
    public SlackAdapter(String botToken, String appToken) {
        this.botToken = botToken;
        this.appToken = appToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() {
        return "slack";
    }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);

        // Step 1: get a WebSocket URL via apps.connections.open
        return apiPost("apps.connections.open", "", appToken).thenCompose(body -> {
            if (!OK_PAT.matcher(body).find()) {
                status = ChannelStatus.error(channelId(), "connections.open failed: " + body);
                return CompletableFuture.<Void>failedFuture(new RuntimeException("Slack connect failed"));
            }

            var urlMatch = URL_PAT.matcher(body);
            if (!urlMatch.find()) {
                return CompletableFuture.<Void>failedFuture(new RuntimeException("No URL in response"));
            }

            // Step 2: connect WebSocket
            var wsUrl = urlMatch.group(1);
            var future = new CompletableFuture<Void>();
            httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                        private final StringBuilder buffer = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket ws) {
                            webSocket = ws;
                            connected = true;
                            status = ChannelStatus.connected(channelId());
                            future.complete(null);
                            ws.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                handleSocketMessage(buffer.toString());
                                buffer.setLength(0);
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
        var body = "channel=" + urlEncode(message.chatId())
                + "&text=" + urlEncode(message.text() != null ? message.text() : "");
        if (message.threadId() != null) {
            body += "&thread_ts=" + urlEncode(message.threadId());
        }
        return apiPost("chat.postMessage", body, botToken).thenAccept(resp -> {
            if (!OK_PAT.matcher(resp).find()) {
                log.warning("Slack chat.postMessage failed: " + resp);
            }
        });
    }

    // -- Socket Mode handling --

    private void handleSocketMessage(String payload) {
        // Acknowledge envelope if present
        var envelopeMatch = ENVELOPE_ID_PAT.matcher(payload);
        if (envelopeMatch.find() && webSocket != null) {
            webSocket.sendText("{\"envelope_id\":\"" + envelopeMatch.group(1) + "\"}", true);
        }

        var typeMatch = TYPE_PAT.matcher(payload);
        if (!typeMatch.find()) return;

        // We're interested in events_api type containing message events
        if (!"events_api".equals(typeMatch.group(1))) return;

        var textMatch = TEXT_PAT.matcher(payload);
        var channelMatch = CHANNEL_PAT.matcher(payload);
        var userMatch = USER_PAT.matcher(payload);

        if (!textMatch.find() || !channelMatch.find() || !userMatch.find()) return;

        var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var chatId = channelMatch.group(1);
        var userId = userMatch.group(1);
        var tsMatch = TS_PAT.matcher(payload);
        var ts = tsMatch.find() ? tsMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;

        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(ts)
                .sender(new Identity(userId))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    // -- HTTP helpers --

    private CompletableFuture<String> apiPost(String method, String formBody, String authToken) {
        var request = HttpRequest.newBuilder(URI.create(API_BASE + "/" + method))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body);
    }

    @SuppressWarnings("deprecation")
    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
