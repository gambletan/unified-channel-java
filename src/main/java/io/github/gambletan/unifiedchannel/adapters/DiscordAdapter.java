package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Discord adapter using the Discord Gateway (WebSocket) and REST API.
 * Pure java.net.http implementation -- no JDA dependency required.
 * <p>
 * Connects to the Gateway for receiving messages and uses the REST API for sending.
 */
public final class DiscordAdapter extends AbstractAdapter {

    private static final String API_BASE = "https://discord.com/api/v10";
    private static final String GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    private static final Pattern OP_PAT = Pattern.compile("\"op\"\\s*:\\s*(\\d+)");
    private static final Pattern TYPE_PAT = Pattern.compile("\"t\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SEQ_PAT = Pattern.compile("\"s\"\\s*:\\s*(\\d+)");
    private static final Pattern HEARTBEAT_PAT = Pattern.compile("\"heartbeat_interval\"\\s*:\\s*(\\d+)");
    private static final Pattern CONTENT_PAT = Pattern.compile("\"content\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern CHANNEL_ID_PAT = Pattern.compile("\"channel_id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern MSG_ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern AUTHOR_ID_PAT = Pattern.compile("\"author\"\\s*:\\{[^}]*\"id\"\\s*:\\s*\"(\\d+)\"");
    private static final Pattern AUTHOR_USER_PAT = Pattern.compile("\"author\"\\s*:\\{[^}]*\"username\"\\s*:\\s*\"([^\"]+)\"");

    private final String token;
    private final HttpClient httpClient;
    private WebSocket webSocket;
    private ScheduledExecutorService heartbeatExecutor;
    private volatile long lastSequence = -1;
    private volatile boolean connected;

    public DiscordAdapter(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() {
        return "discord";
    }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        var future = new CompletableFuture<Void>();

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(GATEWAY_URL), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        webSocket = ws;
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            handleGatewayMessage(buffer.toString(), future);
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
                        if (!future.isDone()) {
                            future.completeExceptionally(error);
                        }
                    }
                })
                .exceptionally(ex -> {
                    status = ChannelStatus.error(channelId(), ex.getMessage());
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdown();
        }
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var json = "{\"content\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
        var url = API_BASE + "/channels/" + message.chatId() + "/messages";
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bot " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        log.warning("Discord send failed (" + resp.statusCode() + "): " + resp.body());
                    }
                });
    }

    // -- Gateway handling --

    private void handleGatewayMessage(String payload, CompletableFuture<Void> connectFuture) {
        var opMatch = OP_PAT.matcher(payload);
        if (!opMatch.find()) return;
        int op = Integer.parseInt(opMatch.group(1));

        // Track sequence number
        var seqMatch = SEQ_PAT.matcher(payload);
        if (seqMatch.find()) {
            lastSequence = Long.parseLong(seqMatch.group(1));
        }

        switch (op) {
            case 10 -> { // Hello: start heartbeating + send identify
                var hbMatch = HEARTBEAT_PAT.matcher(payload);
                if (hbMatch.find()) {
                    startHeartbeat(Long.parseLong(hbMatch.group(1)));
                }
                sendIdentify();
            }
            case 0 -> { // Dispatch
                var typeMatch = TYPE_PAT.matcher(payload);
                if (typeMatch.find()) {
                    var eventType = typeMatch.group(1);
                    if ("READY".equals(eventType)) {
                        connected = true;
                        status = ChannelStatus.connected(channelId());
                        connectFuture.complete(null);
                    } else if ("MESSAGE_CREATE".equals(eventType)) {
                        handleMessageCreate(payload);
                    }
                }
            }
            case 11 -> { /* Heartbeat ACK */ }
            case 1 -> sendHeartbeat(); // Heartbeat request
        }
    }

    private void sendIdentify() {
        var identify = """
                {"op":2,"d":{"token":"%s","intents":33281,"properties":{"os":"java","browser":"unified-channel","device":"unified-channel"}}}"""
                .formatted(token);
        if (webSocket != null) {
            webSocket.sendText(identify, true);
        }
    }

    private void startHeartbeat(long intervalMs) {
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "discord-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::sendHeartbeat, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sendHeartbeat() {
        if (webSocket != null && connected) {
            var payload = lastSequence >= 0
                    ? "{\"op\":1,\"d\":" + lastSequence + "}"
                    : "{\"op\":1,\"d\":null}";
            webSocket.sendText(payload, true);
        }
    }

    private void handleMessageCreate(String payload) {
        var contentMatch = CONTENT_PAT.matcher(payload);
        var channelIdMatch = CHANNEL_ID_PAT.matcher(payload);
        var authorIdMatch = AUTHOR_ID_PAT.matcher(payload);

        if (!contentMatch.find() || !channelIdMatch.find() || !authorIdMatch.find()) return;

        var text = contentMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var chatId = channelIdMatch.group(1);
        var authorId = authorIdMatch.group(1);

        var msgIdMatch = MSG_ID_PAT.matcher(payload);
        var msgId = msgIdMatch.find() ? msgIdMatch.group(1) : null;
        var usernameMatch = AUTHOR_USER_PAT.matcher(payload);
        var username = usernameMatch.find() ? usernameMatch.group(1) : null;

        var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;

        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(msgId)
                .sender(new Identity(authorId, username))
                .chatId(chatId)
                .content(new MessageContent(contentType, text, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
